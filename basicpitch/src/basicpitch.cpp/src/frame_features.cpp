// Compact frame-feature summary of the raw model output (билд #37/#38).
// The frame tensors (notes/onsets/contours probabilities per 11.6 ms frame)
// live only during transcription; this file condenses them into a small
// JSON document before they are dropped. Thresholds below are FIXED (they
// define the feature set, agreed 2026-09-07 with А.М.: "набор признаков
// фиксируется сейчас") and intentionally independent of the tunable
// V2mParams.
// Библиотека #38 (замечания А.М. по тесту #37): метаданные (трек, дата-
// время прогона, автор — непустые поля) и переносы строк в выводе;
// схема поднята до v2m-frame-features-2.
#include "basicpitch.hpp"

#include <algorithm>
#include <cmath>
#include <iomanip>
#include <sstream>
#include <vector>

namespace basic_pitch
{
namespace
{
constexpr float ACTIVE_THRESHOLD = 0.5f;      // "confidently sounding" frame
constexpr int CONTOUR_TOL_BINS = 25;          // bend search window (add_pitch_bends)
constexpr int MIDI_OFFSET = 21;               // frame index 0 -> MIDI 21 (A0)
constexpr float BINS_PER_SEMITONE = 3.0f;     // contour resolution
constexpr float CENT_PER_BIN = 100.0f / BINS_PER_SEMITONE; // 33.3 cents
// Stability = drift within one contour bin of the recording's median
// tuning (the model's contour scale has a constant ~+1 bin offset, and a
// recording may be globally detuned — median-centering removes both).
constexpr float STABLE_DEVIATION_CENTS = CENT_PER_BIN;
constexpr const char *SCHEMA = "v2m-frame-features-2";

// Quantiles of a sorted copy; q in 0..1
float quantile(std::vector<float> v, float q)
{
    if (v.empty())
    {
        return 0.0f;
    }
    std::sort(v.begin(), v.end());
    const size_t idx = static_cast<size_t>(
        std::floor(q * static_cast<float>(v.size() - 1)));
    return v[idx];
}

std::string fmt3(float x) // three decimals
{
    std::ostringstream s;
    s << std::fixed << std::setprecision(3) << x;
    return s.str();
}

std::string fmt1(float x) // one decimal
{
    std::ostringstream s;
    s << std::fixed << std::setprecision(1) << x;
    return s.str();
}

std::string fmt0(float x) // integer
{
    std::ostringstream s;
    s.precision(0);
    s << std::fixed << x;
    return s.str();
}

// JSON string literal (quotes, backslash, control characters)
std::string esc(const std::string &s)
{
    std::ostringstream o;
    for (char c : s)
    {
        switch (c)
        {
        case '"': o << "\\\""; break;
        case '\\': o << "\\\\"; break;
        case '\n': o << "\\n"; break;
        case '\r': o << "\\r"; break;
        case '\t': o << "\\t"; break;
        default:
            if (static_cast<unsigned char>(c) < 0x20)
            {
                o << "\\u" << std::hex << std::setw(4) << std::setfill('0')
                  << static_cast<int>(c) << std::dec;
            }
            else
            {
                o << c;
            }
        }
    }
    return o.str();
}
} // namespace

std::string frame_features_json(const Eigen::Tensor2dXf &notes,
                                const Eigen::Tensor2dXf &onsets,
                                const Eigen::Tensor2dXf &contours,
                                const std::string &track,
                                const std::string &author,
                                const std::string &ts)
{
    const int n_frames = static_cast<int>(notes.dimension(0));
    const int n_freqs = static_cast<int>(notes.dimension(1));      // 88
    const int n_contour_freqs = static_cast<int>(contours.dimension(1)); // 264
    // Exact frame rate (ANNOTATIONS_FPS is integer 86; real fps is sr/hop)
    const float fps =
        static_cast<float>(constants::SAMPLE_RATE) / constants::FFT_HOP;
    const float duration_s = fps > 0 ? static_cast<float>(n_frames) / fps : 0.0f;

    std::vector<float> confidences; // per-frame max note probability
    std::vector<float> drifts_cents; // per active frame, SIGNED contour drift
    std::vector<float> poly;         // per active frame, simultaneous notes
    std::vector<float> pitches;      // per active frame, argmax pitch (MIDI)
    int n_active = 0;
    int n_onsets = 0;
    int poly_max = 0;

    // Per-frame onset activity (max over pitches), needed twice per frame
    std::vector<float> onset_max(static_cast<size_t>(n_frames));
    for (int t = 0; t < n_frames; ++t)
    {
        float m = 0.0f;
        for (int i = 0; i < n_freqs; ++i)
        {
            m = std::max(m, onsets(t, i));
        }
        onset_max[static_cast<size_t>(t)] = m;
    }

    for (int t = 0; t < n_frames; ++t)
    {
        // Per-frame max probability (any note) — model confidence
        float max_p = 0.0f;
        int max_i = 0;
        for (int i = 0; i < n_freqs; ++i)
        {
            const float p = notes(t, i);
            if (p > max_p)
            {
                max_p = p;
                max_i = i;
            }
        }
        confidences.push_back(max_p);

        // Onset peaks (attack frames): local maximum above the fixed
        // threshold; the first frame counts when it already exceeds it.
        const float max_o = onset_max[static_cast<size_t>(t)];
        const float prev_o = t > 0 ? onset_max[static_cast<size_t>(t - 1)] : 0.0f;
        const float next_o =
            t + 1 < n_frames ? onset_max[static_cast<size_t>(t + 1)] : 0.0f;
        if (max_o > ACTIVE_THRESHOLD && max_o > prev_o && max_o >= next_o)
        {
            ++n_onsets;
        }

        if (max_p <= ACTIVE_THRESHOLD)
        {
            continue;
        }
        ++n_active;

        // Active frame: simultaneous notes > threshold
        int n_sim = 0;
        for (int i = 0; i < n_freqs; ++i)
        {
            if (notes(t, i) > ACTIVE_THRESHOLD)
            {
                ++n_sim;
            }
        }
        poly.push_back(static_cast<float>(n_sim));
        poly_max = std::max(poly_max, n_sim);

        // Tessitura: argmax pitch of the active frame
        const float pitch = static_cast<float>(max_i + MIDI_OFFSET);
        pitches.push_back(pitch);

        // Pitch stability: where does the contour argmax sit relative to
        // the note pitch? Searched in the same window as pitch bends.
        const int center_bin = 3 * max_i; // bin of the note pitch
        const int lo = std::max(0, center_bin - CONTOUR_TOL_BINS);
        const int hi = std::min(n_contour_freqs, center_bin + CONTOUR_TOL_BINS + 1);
        float best = -1.0f;
        int best_bin = center_bin;
        for (int j = lo; j < hi; ++j)
        {
            const float c = contours(t, j);
            if (c > best)
            {
                best = c;
                best_bin = j;
            }
        }
        const float drift_cents =
            static_cast<float>(best_bin - center_bin) * CENT_PER_BIN;
        drifts_cents.push_back(drift_cents);
    }

    // Aggregate
    float pitch_min = 0.0f, pitch_max = 0.0f, pitch_sum = 0.0f;
    if (!pitches.empty())
    {
        pitch_min = *std::min_element(pitches.begin(), pitches.end());
        pitch_max = *std::max_element(pitches.begin(), pitches.end());
        for (float p : pitches)
        {
            pitch_sum += p;
        }
    }
    float poly_sum = 0.0f;
    for (float p : poly)
    {
        poly_sum += p;
    }
    const float active_ratio =
        n_frames > 0 ? static_cast<float>(n_active) / n_frames : 0.0f;
    // Stability measures how much the pitch wanders around the recording's
    // median tuning, not around the model's raw scale
    float drift_mean = 0.0f;
    float drift_p95 = 0.0f;
    float stable_ratio = 0.0f;
    if (n_active > 0)
    {
        const float median = quantile(drifts_cents, 0.5f);
        std::vector<float> dev;
        dev.reserve(drifts_cents.size());
        double dev_sum = 0.0;
        int n_stable = 0;
        for (float d : drifts_cents)
        {
            const float a = std::fabs(d - median);
            dev.push_back(a);
            dev_sum += a;
            if (a <= STABLE_DEVIATION_CENTS)
            {
                ++n_stable;
            }
        }
        drift_mean = static_cast<float>(dev_sum / n_active);
        drift_p95 = quantile(dev, 0.95f);
        stable_ratio = static_cast<float>(n_stable) / n_active;
    }

    // Pretty-printed (билд #38, замечание «а»): each field on its own
    // line, two-space indent.
    const std::string I1 = "  ", I2 = "    ", I3 = "      ";
    std::ostringstream j;
    j << "{\n" << I1 << "\"schema\":\"" << SCHEMA << "\",\n";
    if (!track.empty() || !author.empty() || !ts.empty())
    {
        j << I1 << "\"meta\": {\n";
        bool first = true;
        if (!track.empty())
        {
            j << I2 << "\"track\": \"" << esc(track) << '"';
            first = false;
        }
        if (!ts.empty())
        {
            j << (first ? "" : ",\n") << I2 << "\"ts\": \"" << esc(ts) << '"';
            first = false;
        }
        if (!author.empty())
        {
            j << (first ? "" : ",\n") << I2 << "\"author\": \"" << esc(author) << '"';
        }
        j << "\n" << I1 << "},\n";
    }
    j << I1 << "\"frames_n\": " << n_frames << ",\n"
      << I1 << "\"duration_s\": " << fmt3(duration_s) << ",\n"
      << I1 << "\"notes\": {\n"
      << I2 << "\"conf_p50\": " << fmt3(quantile(confidences, 0.5f)) << ",\n"
      << I2 << "\"conf_p90\": " << fmt3(quantile(confidences, 0.9f)) << ",\n"
      << I2 << "\"conf_p99\": " << fmt3(quantile(confidences, 0.99f)) << ",\n"
      << I2 << "\"conf_max\": " << fmt3(quantile(confidences, 1.0f)) << ",\n"
      << I2 << "\"active_ratio\": " << fmt3(active_ratio) << ",\n"
      << I2 << "\"poly_mean\": " << fmt3(poly.empty() ? 0.0f : poly_sum / poly.size())
      << ",\n"
      << I2 << "\"poly_max\": " << poly_max << ",\n"
      << I2 << "\"pitch_min\": " << fmt0(pitch_min) << ",\n"
      << I2 << "\"pitch_max\": " << fmt0(pitch_max) << ",\n"
      << I2 << "\"pitch_mean\": "
      << fmt3(pitches.empty() ? 0.0f : pitch_sum / pitches.size()) << "\n"
      << I1 << "},\n"
      << I1 << "\"onsets\": {\n"
      << I2 << "\"n\": " << n_onsets << ",\n"
      << I2 << "\"density_per_s\": "
      << fmt3(duration_s > 0 ? n_onsets / duration_s : 0.0f) << "\n"
      << I1 << "},\n"
      << I1 << "\"contours\": {\n"
      << I2 << "\"stable_ratio\": " << fmt3(stable_ratio) << ",\n"
      << I2 << "\"drift_cents_mean\": " << fmt1(drift_mean) << ",\n"
      << I2 << "\"drift_cents_p95\": " << fmt1(drift_p95) << "\n"
      << I1 << "}\n}";
    return j.str();
}
} // namespace basic_pitch
