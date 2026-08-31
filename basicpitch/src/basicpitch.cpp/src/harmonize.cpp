// Harmonization post-processing of detected note events (OQ-06):
// 1. merge_note_fragments: vibrato splits a sustained note into adjacent-bin
//    fragments; merge them into one note with the dominant pitch.
// 2. drop_small_bends: small contour deviations are vocal wobble; zero them
//    so the note stays on the chromatic grid.
// 3. estimate_global_shift: median of per-note mean bends (duration-weighted):
//    a uniformly detuned recording shows a consistent offset; the median is
//    the global reference correction (applied at bend-value writing).
// 4. fit_mode: on the shifted notes, find the key (root x scale) whose scale
//    degrees cover the most note centroids (tolerance 30 cents); stage 2 of
//    the agreed harmonization algorithm (see docs/backlog.md OQ-06).
// 5. apply_mode_snap: pull each note centroid toward the nearest scale degree
//    with the user-specified strength (0..1); whole semitones go to the pitch,
//    the remainder goes to the bend values.
#include "basicpitch.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <map>
#include <optional>
#include <string>
#include <vector>

namespace basic_pitch
{
using namespace basic_pitch::constants;

void merge_note_fragments(std::vector<NoteEvent> &notes, int max_semitones)
{
    if (max_semitones <= 0)
    {
        return;
    }
    std::sort(notes.begin(), notes.end());
    std::vector<NoteEvent> merged;
    std::map<int, int> chain_duration; // pitch -> total duration in chain
    for (auto &n : notes)
    {
        if (!merged.empty())
        {
            NoteEvent &last = merged.back();
            const int gap = n.start_idx - last.end_idx;
            const bool adjacent = gap <= 2; // touching or overlapping
            if (adjacent &&
                std::abs(n.pitch - last.pitch) <= max_semitones)
            {
                const int dur_n = n.end_idx - n.start_idx;
                chain_duration[n.pitch] += dur_n;
                // dominant pitch by cumulative duration in the chain
                int best_pitch = last.pitch;
                int best_dur = -1;
                for (const auto &[pitch, dur] : chain_duration)
                {
                    if (dur > best_dur)
                    {
                        best_dur = dur;
                        best_pitch = pitch;
                    }
                }
                const int dur_last = last.end_idx - last.start_idx;
                const float w_last = static_cast<float>(dur_last);
                const float w_n = static_cast<float>(dur_n);
                last.pitch = best_pitch;
                last.end_idx = std::max(last.end_idx, n.end_idx);
                last.amplitude =
                    (last.amplitude * w_last + n.amplitude * w_n) /
                    (w_last + w_n);
                last.pitch_bends = std::nullopt; // fragment bends dropped
                continue;
            }
        }
        chain_duration.clear();
        chain_duration[n.pitch] = n.end_idx - n.start_idx;
        merged.push_back(std::move(n));
    }
    notes = std::move(merged);
}

void drop_small_bends(std::vector<NoteEvent> &notes, int min_bend_bins)
{
    if (min_bend_bins <= 0)
    {
        return;
    }
    for (auto &n : notes)
    {
        if (!n.pitch_bends)
        {
            continue;
        }
        for (int &v : *n.pitch_bends)
        {
            if (std::abs(v) < min_bend_bins)
            {
                v = 0;
            }
        }
    }
}

float estimate_global_shift(const std::vector<NoteEvent> &notes)
{
    // Per-note mean bend (contour bins) weighted by note duration
    std::vector<std::pair<float, float>> samples; // (mean_bend, weight)
    for (const auto &n : notes)
    {
        if (n.pitch_bends && !n.pitch_bends->empty())
        {
            float s = 0.0f;
            for (int v : *n.pitch_bends)
            {
                s += v;
            }
            const float mean = s / n.pitch_bends->size();
            const float weight = static_cast<float>(n.end_idx - n.start_idx);
            samples.emplace_back(mean, weight);
        }
    }
    if (samples.empty())
    {
        return 0.0f;
    }
    std::sort(samples.begin(), samples.end());
    float total = 0.0f;
    for (const auto &[mean, w] : samples)
    {
        total += w;
    }
    // Weighted median
    float acc = 0.0f;
    for (const auto &[mean, w] : samples)
    {
        acc += w;
        if (acc >= total * 0.5f)
        {
            return mean;
        }
    }
    return samples.back().first;
}

// Mode/key fitting (OQ-06 stage 2): the scale degrees are pitch-class sets;
// the root is octave-invariant, so enumerate all 12 roots x 6 scales and
// measure the fraction of note centroids within the tolerance (30 cents).
struct ScaleDef
{
    const char *name;
    std::array<int, 7> steps; // semitones from the root
};

static const std::array<ScaleDef, 6> kScales = {
    ScaleDef{"major (Ionian)", {0, 2, 4, 5, 7, 9, 11}},
    ScaleDef{"natural minor", {0, 2, 3, 5, 7, 8, 10}},
    ScaleDef{"harmonic minor", {0, 2, 3, 5, 7, 8, 11}},
    ScaleDef{"melodic minor", {0, 2, 3, 5, 7, 9, 11}},
    ScaleDef{"dorian", {0, 2, 3, 5, 7, 9, 10}},
    ScaleDef{"lydian", {0, 2, 4, 6, 7, 9, 11}},
};

static const std::array<const char *, 12> kRootNames = {
    "C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B",
};

// Per-note mean bend in contour bins, if bends are present
static std::optional<float> mean_bend(const NoteEvent &n)
{
    if (!n.pitch_bends || n.pitch_bends->empty())
    {
        return std::nullopt;
    }
    float s = 0.0f;
    for (int v : *n.pitch_bends)
    {
        s += v;
    }
    return s / n.pitch_bends->size();
}

// Signed circular distance (semitones, MIDI pitch units) from pc to step
static float step_distance(float pc, float step)
{
    float d = pc - step;
    d -= 12.0f * std::round(d / 12.0f);
    return d; // in [-6, 6)
}

std::optional<ModeFit> fit_mode(const std::vector<NoteEvent> &notes,
                                float global_shift_bins)
{
    const size_t n = notes.size();
    if (n < 3)
    {
        return std::nullopt; // too few notes for a reliable key decision
    }
    // Note centroids in semitones: pitch + (mean bend - global shift)/3
    std::vector<float> centroids;
    centroids.reserve(n);
    for (const auto &note : notes)
    {
        const float m = mean_bend(note).value_or(0.0f);
        centroids.push_back(note.pitch + (m - global_shift_bins) /
                                              CONTOURS_BINS_PER_SEMITONE);
    }

    ModeFit best;
    best.fraction = 0.0f;
    for (int root = 0; root < 12; ++root)
    {
        for (size_t s = 0; s < kScales.size(); ++s)
        {
            float total_dist = 0.0f;
            size_t inside = 0;
            for (float c : centroids)
            {
                // Pitch classes are counted from C (MIDI 24 = C1); MIDI_OFFSET
                // (21 = A0) is 3 semitones below C1
                const float pcn = std::fmod(
                    std::fmod(c - (MIDI_OFFSET + 3), 12.0f) + 12.0f, 12.0f);
                float d_min = 12.0f;
                for (int step : kScales[s].steps)
                {
                    d_min = std::min(
                        d_min,
                        std::fabs(step_distance(pcn, root + step)));
                }
                if (d_min <= MODE_TOLERANCE_SEMITONES)
                {
                    ++inside;
                }
                total_dist += d_min;
            }
            const float fraction = static_cast<float>(inside) / n;
            const float avg_dist = total_dist / n;
            if (fraction > best.fraction ||
                (fraction == best.fraction && fraction > 0.0f &&
                 avg_dist < best.avg_dist))
            {
                best = ModeFit{root, static_cast<int>(s), fraction, avg_dist};
            }
        }
    }
    if (best.fraction < MODE_MIN_FRACTION)
    {
        return std::nullopt;
    }
    return best;
}

std::string mode_fit_description(const ModeFit &fit)
{
    return std::string(kRootNames[fit.root_semitones % 12]) + " " +
           kScales[fit.scale_index].name;
}

void apply_mode_snap(std::vector<NoteEvent> &notes, const ModeFit &fit,
                     float strength, float global_shift_bins)
{
    for (auto &note : notes)
    {
        const float m = mean_bend(note).value_or(0.0f);
        const float c =
            note.pitch + (m - global_shift_bins) / CONTOURS_BINS_PER_SEMITONE;
        const float pcn = std::fmod(
            std::fmod(c - (MIDI_OFFSET + 3), 12.0f) + 12.0f, 12.0f);
        // Nearest scale degree of the fitted mode (signed circular distance)
        float d_best = 12.0f;
        for (int step : kScales[fit.scale_index].steps)
        {
            const float d =
                step_distance(pcn, fit.root_semitones + step);
            if (std::fabs(d) < std::fabs(d_best))
            {
                d_best = d;
            }
        }
        const float delta = d_best * strength; // semitones toward the degree
        const int pitch_shift = static_cast<int>(std::round(delta));
        const float bend_shift =
            (delta - pitch_shift) * CONTOURS_BINS_PER_SEMITONE;
        note.pitch = std::clamp(note.pitch + pitch_shift, 0, 127);
        if (note.pitch_bends)
        {
            for (int &v : *note.pitch_bends)
            {
                v += static_cast<int>(std::round(bend_shift));
            }
        }
    }
}

} // namespace basic_pitch
