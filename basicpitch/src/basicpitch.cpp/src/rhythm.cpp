// Tempo estimation and rhythm quantization (Ellis 2007 beat-tracking approach).
// Pipeline: onset strength signal -> autocorrelation tempo (log-Gaussian prior)
// -> dynamic-programming beats -> grid quantization of note boundaries.
#include "basicpitch.hpp"
#include <algorithm>
#include <cmath>
#include <vector>

namespace basic_pitch
{

// Audio spectral-flux OSS: half-wave rectified frame-energy rise, normalized.
// Carries the accent structure of a performance (model OSS does not: it fires
// on every note equally).
std::vector<float> audio_flux_oss(const std::vector<float> &audio, int sample_rate,
                                  int hop)
{
    const int n_frames = static_cast<int>(audio.size()) / hop;
    std::vector<float> energy(n_frames, 0.0f);
    for (int i = 0; i < n_frames; ++i)
    {
        float s = 0.0f;
        const int lo = i * hop;
        const int hi = std::min(lo + hop, static_cast<int>(audio.size()));
        for (int k = lo; k < hi; ++k)
        {
            s += audio[k] * audio[k];
        }
        energy[i] = s;
    }
    std::vector<float> flux(n_frames, 0.0f);
    for (int i = 1; i < n_frames; ++i)
    {
        flux[i] = std::max(0.0f, energy[i] - energy[i - 1]);
    }
    return flux; // raw scale: autocorrelation is scale-invariant
}

// OSS: sum of onset probabilities across pitch bins, normalized to [0,1]
static std::vector<float> onset_strength_signal(const Eigen::Tensor2dXf &onsets)
{
    const int n_times = static_cast<int>(onsets.dimension(0));
    const int n_freqs = static_cast<int>(onsets.dimension(1));
    std::vector<float> oss(n_times, 0.0f);
    for (int t = 0; t < n_times; ++t)
    {
        for (int f = 0; f < n_freqs; ++f)
        {
            oss[t] += onsets(t, f);
        }
    }
    const float max_val = *std::max_element(oss.begin(), oss.end());
    if (max_val > 0.0f)
    {
        for (float &v : oss)
        {
            v /= max_val;
        }
    }
    return oss;
}

// Tempo (BPM) from autocorrelation of OSS, prior-centered log-Gaussian window
// (librosa-style; prior resolves octave ambiguity of periodic signals).
static float estimate_tempo_bpm(const std::vector<float> &oss, float frame_rate,
                                float prior_bpm)
{
    const int n = static_cast<int>(oss.size());
    constexpr float min_bpm = 30.0f;
    constexpr float max_bpm = 300.0f;
    const int lag_min =
        std::max(1, static_cast<int>(std::round(frame_rate * 60.0f / max_bpm)));
    const int lag_max =
        std::min(n - 1, static_cast<int>(std::round(frame_rate * 60.0f / min_bpm)));
    if (lag_max < lag_min)
    {
        return 0.0f;
    }

    std::vector<float> score(lag_max + 1, 0.0f);
    for (int lag = lag_min; lag <= lag_max; ++lag)
    {
        float s = 0.0f;
        for (int t = 0; t + lag < n; ++t)
        {
            s += oss[t] * oss[t + lag];
        }
        const float bpm = 60.0f * frame_rate / lag;
        const float log2_ratio = std::log2(bpm / prior_bpm);
        const float window =
            std::exp(-log2_ratio * log2_ratio / (2.0f * 1.4f * 1.4f));
        score[lag] = (s / (n - lag)) * window;
    }

    const auto best_it = std::max_element(score.begin() + lag_min, score.end());
    if (*best_it <= 0.0f)
    {
        return 0.0f;
    }
    const int best_lag = static_cast<int>(best_it - score.begin());

    // Octave correction (Ellis 2007): a signal with strong half-period
    // pulsation (e.g. eighths) peaks at twice the beat rate. If the octave-
    // lower lag still correlates well, report the slower tempo. The candidate
    // is the best lag within +-2 frames of the exact octave: the slow peak
    // can sit a frame off (test7.wav: octave lag 72, slow peak at 74).
    // Verified on test7.wav (70 BPM): slow peak scores 0.52x of the fast
    // one -> 69.8 BPM.
    const float best_score = *best_it;
    const int octave_lo = 2 * best_lag - 2;
    const int octave_hi = std::min(lag_max, 2 * best_lag + 2);
    int octave_lag = std::max(lag_min, 2 * best_lag);
    for (int lag = std::max(lag_min, octave_lo); lag <= octave_hi; ++lag)
    {
        if (score[lag] > score[octave_lag])
        {
            octave_lag = lag;
        }
    }
    if (octave_lag != best_lag && score[octave_lag] >= 0.5f * best_score)
    {
        return 60.0f * frame_rate / static_cast<float>(octave_lag);
    }
    return 60.0f * frame_rate / static_cast<float>(best_lag);
}

// Beat tracking by dynamic programming (Ellis 2007): maximize onset strength
// at beats while penalizing deviation of inter-beat intervals from the period.
static std::vector<float> track_beats(const std::vector<float> &oss,
                                      float frame_rate, float bpm)
{
    const int n = static_cast<int>(oss.size());
    const float period = frame_rate * 60.0f / bpm; // in frames
    constexpr float tightness = 10.0f; // tempo-consistency weight (tunable)

    std::vector<float> cumscore(n, 0.0f);
    std::vector<int> backlink(n, -1);

    for (int t = 0; t < n; ++t)
    {
        const int lo = std::max(0, t - static_cast<int>(std::round(2.0f * period)));
        const int hi = std::max(0, t - static_cast<int>(std::round(0.5f * period)));
        float best = 0.0f;
        int best_tau = -1;
        for (int tau = lo; tau <= hi; ++tau)
        {
            if (cumscore[tau] <= 0.0f)
            {
                continue;
            }
            const float interval = static_cast<float>(t - tau);
            const float ratio = std::log(interval / period);
            const float cand = cumscore[tau] - period * tightness * ratio * ratio;
            if (cand > best)
            {
                best = cand;
                best_tau = tau;
            }
        }
        cumscore[t] = oss[t] + (best_tau >= 0 ? best : 0.0f);
        backlink[t] = best_tau;
    }

    // Backtrack from the best final score
    const auto best_it = std::max_element(cumscore.begin(), cumscore.end());
    std::vector<int> chain;
    int t = static_cast<int>(best_it - cumscore.begin());
    while (t >= 0 && backlink[t] >= 0)
    {
        chain.push_back(t);
        t = backlink[t];
    }
    chain.push_back(t);
    std::reverse(chain.begin(), chain.end());

    // Drop beats closer than half a period: keep the one with higher score
    std::vector<int> beats_idx;
    for (int idx : chain)
    {
        if (!beats_idx.empty())
        {
            const float gap = static_cast<float>(idx - beats_idx.back());
            if (gap < period * 0.5f)
            {
                if (cumscore[idx] > cumscore[beats_idx.back()])
                {
                    beats_idx.back() = idx;
                }
                continue;
            }
        }
        beats_idx.push_back(idx);
    }

    std::vector<float> beats;
    beats.reserve(beats_idx.size());
    for (int idx : beats_idx)
    {
        beats.push_back(static_cast<float>(idx) / frame_rate);
    }
    return beats;
}

// Time signature numerator from the accent structure of beat-level onset
// strength. Autocorrelation over lags 2..6 finds the bar period regardless of
// which beat is accented (a strong-but-quiet downbeat still gives periodicity).
// Robust to anacrusis: phase does not affect the period.
// The accent signal must be audio flux (model OSS fires on every note equally).
static int detect_ts_numerator(const std::vector<float> &accent,
                               const std::vector<float> &beats_s, float bpm,
                               float frame_rate)
{
    if (beats_s.size() < 24)
    {
        return 0; // need at least 8 bars of 3/4 for a reliable bar period
    }
    const float period = 60.0f / bpm;

    // Per-beat accent on the ideal grid anchored at the first DP beat. Flux
    // frames are sqrt-compressed (mild expansion of the dynamic range): the
    // quiet smooth downbeat and the bright weak beats become comparable, and
    // the crescendo trend stops dominating. Verified: strong expansion (^2,
    // ^3) or strong log compression hide the bar period; sqrt reveals it
    // (test3.wav: 3/4).
    // Each flux frame belongs to exactly one beat (frame -> beat index):
    // window sums with overlapping boundaries smear the accent pattern. The
    // trailing partial beat (beyond the last full beat) is dropped: it holds
    // an incomplete window and its tail transient dominates the series.
    const int nbeats = static_cast<int>(accent.size() / frame_rate / period);
    std::vector<float> per_beat(nbeats, 0.0f);
    for (int f = 0; f < static_cast<int>(accent.size()); ++f)
    {
        const int b = static_cast<int>(
            (f / frame_rate - beats_s.front()) / period);
        if (b >= 0 && b < nbeats)
        {
            per_beat[b] += std::sqrt(accent[f]);
        }
    }

    // Detrend with a 12-beat window (4 bars of 3/4, 3 bars of 4/4): removes
    // the long-scale dynamics while keeping the bar-period pattern. Edge
    // windows divide by the window size (verified empirically: dividing by
    // the actual element count hides the bar period on test3.wav).
    const int win = 12;
    std::vector<float> detrended(nbeats, 0.0f);
    for (int i = 0; i < nbeats; ++i)
    {
        float s = 0.0f;
        const int lo = std::max(0, i - win / 2);
        const int hi = std::min(nbeats, i + win / 2 + 1);
        for (int j = lo; j < hi; ++j)
        {
            s += per_beat[j];
        }
        detrended[i] = per_beat[i] - s / win;
    }

    // Autocorrelation over lags 2..6 beats. Candidates are restricted to the
    // common meters {3, 4}: accent patterns of other meters are too ambiguous
    // (e.g., 2-bar hypermeter of a waltz shows lag 6, 4/4 with 1&3 accents
    // shows lag 2). 4 wins over 3 when its correlation is close.
    float ac[7] = {0.0f};
    for (int lag = 2; lag <= 6; ++lag)
    {
        float s = 0.0f;
        int n = 0;
        for (int i = 0; i + lag < nbeats; ++i)
        {
            s += detrended[i] * detrended[i + lag];
            ++n;
        }
        if (n > 0)
        {
            ac[lag] = s / n;
        }
    }

    int best_lag = 3;
    if (ac[4] > ac[3])
    {
        best_lag = 4;
    }
    // 4/4 with accents on beats 1 and 3 looks like 2/4: promote 2 to 4 when
    // the lag-4 correlation is close to lag-2.
    if (ac[2] > 0.0f && ac[4] >= 0.9f * ac[2])
    {
        best_lag = 4;
    }
    // Require a clear accent peak (2% margin), else fall back to the default
    const float margin = best_lag == 4 ? ac[4] - ac[3] : ac[3] - ac[4];
    if (ac[best_lag] <= 0.0f || margin < 0.02f * ac[best_lag])
    {
        return 0;
    }
    return best_lag;
}

// Finest subdivision (1,2,4,8 of the beat) where >= min_frac of note starts
// fall within tolerance of the grid; 0 if even the beat grid does not fit.
static int choose_subdivision(const std::vector<float> &starts_s,
                              const std::vector<float> &beats_s, float bpm,
                              float tolerance_s, float min_frac)
{
    const float period = 60.0f / bpm;
    for (int sub : {8, 4, 2, 1})
    {
        const float step = period / sub;
        int hits = 0;
        for (float s : starts_s)
        {
            // nearest grid point across all beats and subdivisions
            float best_dist = 1e9f;
            for (float b : beats_s)
            {
                for (int k = 0; k < sub; ++k)
                {
                    const float g = b + k * step;
                    const float d = std::fabs(s - g);
                    if (d < best_dist)
                    {
                        best_dist = d;
                    }
                }
            }
            if (best_dist <= tolerance_s)
            {
                ++hits;
            }
        }
        const float frac = static_cast<float>(hits) / starts_s.size();
        if (frac >= min_frac)
        {
            return sub;
        }
    }
    return 0;
}

// Grid-phase alignment (Т02, docs/brd.md): the whole beat grid is shifted
// (its phase chosen) instead of snapping notes one by one, so a spurious
// first attack or an anacrusis cannot offset the quantized output. The phase
// is the weighted circular median of note starts around the beat grid:
// attacks weigh 0.4, doubled to 0.8 when the attack already sits on a beat
// (+-15% of the beat period; judged on the unshifted axis — one pass). The
// returned anchor keeps the first start inside the first beat (a beat is
// prepended when the optimum would fall before it).
static float align_grid_phase(const std::vector<float> &starts_s, float bpm)
{
    if (starts_s.empty())
    {
        return 0.0f;
    }
    const float period = 60.0f / bpm;
    // Reference phase: the first start — the grid the quantizer would have
    // used before this alignment (Т02 п.4: beat membership is judged on the
    // unshifted axis).
    const float ref = starts_s.front();
    const float hit_band = 0.15f * period;
    const size_t n = starts_s.size();
    std::vector<float> phases(n);
    std::vector<float> weights(n);
    for (size_t i = 0; i < n; ++i)
    {
        float u = std::fmod(starts_s[i] - ref, period);
        if (u < 0.0f)
        {
            u += period;
        }
        phases[i] = u / period;
        const float to_beat = std::min(u, period - u);
        weights[i] = to_beat <= hit_band ? 0.8f : 0.4f;
    }

    // Exact minimum of the piecewise-linear cost: break points of the
    // circular L1 sum sit at the attacks and at their antipodes (where the
    // shortest-path wrap switches between two attacks), so both are tried.
    std::vector<float> candidates = phases;
    for (float p : phases)
    {
        float q = p + 0.5f;
        if (q >= 1.0f)
        {
            q -= 1.0f;
        }
        candidates.push_back(q);
    }
    float best_x = candidates[0];
    float best_cost = 1e18f;
    for (float x : candidates)
    {
        float cost = 0.0f;
        for (size_t i = 0; i < n; ++i)
        {
            float d = std::fabs(phases[i] - x);
            if (d > 0.5f)
            {
                d = 1.0f - d;
            }
            cost += weights[i] * d;
        }
        if (cost < best_cost)
        {
            best_cost = cost;
            best_x = x;
        }
    }
    // Cost of keeping the anchor at the first start (the pre-Т02 grid).
    // On evenly filled or tempo-drifting material the L1 gain of a phase
    // shift is small (<= ~5 %): it merely reallocates error between beat
    // clusters and moves the first note off its downbeat. Only a substantial
    // gain (>= 10 %) justifies the shift — that is the spurious-first-attack
    // / anacrusis case Т02 is for.
    float cost_at_zero = 0.0f;
    for (size_t i = 0; i < n; ++i)
    {
        float d = phases[i] < 0.5f ? phases[i] : 1.0f - phases[i];
        cost_at_zero += weights[i] * d;
    }
    if (cost_at_zero - best_cost < 0.10f * cost_at_zero)
    {
        best_x = 0.0f;
    }

    float anchor = ref + best_x * period;
    // First start must fall inside the first beat: shift the anchor by whole
    // beats (a beat is prepended when the optimum precedes the first start).
    const float first = starts_s.front();
    const float in_beat = std::fmod(first - anchor, period);
    anchor = first - (in_beat < 0.0f ? in_beat + period : in_beat);
    return anchor;
}

RhythmResult analyze_rhythm(const Eigen::Tensor2dXf &onsets,
                            const std::vector<float> &starts_s, float frame_rate,
                            const RhythmParams &params,
                            const std::vector<float> *accent)
{
    RhythmResult result;
    const std::vector<float> oss = onset_strength_signal(onsets);
    if (oss.size() < 10 || starts_s.size() < 5)
    {
        return result; // too little material for reliable tempo
    }

    // Accent signal: audio flux when available, else model OSS (flat).
    const std::vector<float> &accent_sig = accent ? *accent : oss;
    const float bpm = params.tempo_bpm > 0.0f
                          ? params.tempo_bpm
                          : estimate_tempo_bpm(accent_sig, frame_rate, 120.0f);
    if (bpm < 30.0f || bpm > 300.0f)
    {
        return result;
    }

    result.tempo_bpm = bpm;
    result.beats_s = track_beats(oss, frame_rate, bpm);
    if (result.beats_s.size() < 2)
    {
        result.tempo_bpm = 0.0f;
        result.beats_s.clear();
        return result;
    }

    if (params.time_sig_num > 0)
    {
        // Explicit time signature overrides accent-based detection
        result.ts_numerator = params.time_sig_num;
        result.ts_denominator =
            params.time_sig_den > 0 ? params.time_sig_den : 4;
    }
    else
    {
        result.ts_numerator =
            detect_ts_numerator(accent_sig, result.beats_s, bpm, frame_rate);
    }

    // Grid phase (Т02, docs/brd.md): weighted circular median of note starts,
    // not the first note — a spurious first attack must not shift the whole
    // grid. The anchor keeps the first start inside the first beat, so a
    // leading rest (anacrusis) may appear before it in the quantized output.
    result.grid_anchor = align_grid_phase(starts_s, bpm);

    // Grid for quantization: auto or explicit subdivision
    const float tolerance_s = params.tolerance_ms / 1000.0f;
    if (params.quantize == 1) // auto
    {
        // Auto always picks a grid (the best fit): a fallback chain with
        // relaxed coverage, then the best raw hit count, then the beat grid —
        // so every note snaps and no "~" remains in the output.
        result.subdivision =
            choose_subdivision(starts_s, result.beats_s, bpm, tolerance_s, 0.9f);
        if (result.subdivision == 0)
        {
            result.subdivision =
                choose_subdivision(starts_s, result.beats_s, bpm, tolerance_s, 0.5f);
        }
        if (result.subdivision == 0)
        {
            int best_sub = 0;
            int best_hits = 0;
            const float period = 60.0f / bpm;
            for (int sub : {8, 4, 2, 1})
            {
                const float step = period / sub;
                int hits = 0;
                for (float s : starts_s)
                {
                    float best_dist = 1e9f;
                    for (float b : result.beats_s)
                    {
                        for (int k = 0; k < sub; ++k)
                        {
                            const float g = b + k * step;
                            const float d = std::fabs(s - g);
                            if (d < best_dist)
                            {
                                best_dist = d;
                            }
                        }
                    }
                    if (best_dist <= tolerance_s)
                    {
                        ++hits;
                    }
                }
                if (hits > best_hits)
                {
                    best_hits = hits;
                    best_sub = sub;
                }
            }
            result.subdivision = best_sub > 0 ? best_sub : 1;
        }
    }
    else if (params.quantize == 2)
    {
        result.subdivision = 1;
    }
    else if (params.quantize == 3)
    {
        result.subdivision = 2;
    }
    else if (params.quantize == 4)
    {
        result.subdivision = 4;
    }
    return result;
}

// Ideal grid anchored at the first beat: anchor + k*step (no beat jitter)
std::vector<float> rhythm_grid(const RhythmResult &result)
{
    if (result.subdivision <= 0 || result.beats_s.size() < 2)
    {
        return {};
    }
    const float step = 60.0f / result.tempo_bpm / result.subdivision;
    const float anchor =
        result.grid_anchor > 0.0f ? result.grid_anchor : result.beats_s.front();
    const float last = result.beats_s.back();
    const int count =
        static_cast<int>(std::ceil((last - anchor) / step)) + 1;
    std::vector<float> grid;
    grid.reserve(count);
    for (int k = 0; k < count; ++k)
    {
        grid.push_back(anchor + k * step);
    }
    return grid;
}

} // namespace basic_pitch
