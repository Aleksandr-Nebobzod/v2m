#include "basicpitch.hpp"
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <libremidi/libremidi.hpp>
#include <libremidi/writer.hpp>
#include <map>
#include <numeric>
#include <ranges>
#include <sstream>
#include <tuple>
#include <vector>

using namespace basic_pitch::constants;

bool basic_pitch::g_verbose = false;

void basic_pitch::set_verbose(bool verbose) { basic_pitch::g_verbose = verbose; }

static void log_verbose(const std::string &msg)
{
    if (basic_pitch::g_verbose)
        std::cout << msg << std::endl;
}

static std::vector<std::pair<int, int>>
find_peaks(const Eigen::Tensor2dXf &onsets, float onset_threshold)
{
    std::vector<std::pair<int, int>> peaks;

    // Get the dimensions of the onsets tensor
    int n_times = onsets.dimension(0); // Number of time steps (rows)
    int n_freqs = onsets.dimension(1); // Number of frequency bins (columns)

    // Loop through the tensor to find peaks
    for (int t = 1; t < n_times - 1; ++t)
    {
        for (int f = 0; f < n_freqs; ++f)
        {
            // Check if the current element is a peak and exceeds the threshold
            if (onsets(t, f) > onset_threshold &&
                onsets(t, f) > onsets(t - 1, f) &&
                onsets(t, f) > onsets(t + 1, f))
            {

                peaks.emplace_back(t, f); // Store the peak (time, frequency)
            }
        }
    }

    return peaks;
}

static std::vector<float> model_frames_to_time(int n_frames)
{
    std::vector<float> times(n_frames);

    float original_time_factor =
        static_cast<float>(FFT_HOP) / static_cast<float>(SAMPLE_RATE);
    float window_factor = 1.0f / static_cast<float>(ANNOT_N_FRAMES);
    float window_offset =
        original_time_factor * (static_cast<float>(ANNOT_N_FRAMES) -
                                (static_cast<float>(AUDIO_N_SAMPLES) /
                                 static_cast<float>(FFT_HOP))) +
        MAGIC_NUMBER;

    for (int i = 0; i < n_frames; ++i)
    {
        float frame_index = static_cast<float>(i);
        float original_time = frame_index * original_time_factor;
        float window_number = frame_index * window_factor;
        times[i] = original_time - (window_offset * window_number);
    }

    return times;
}

static void
apply_melodia_trick(Eigen::MatrixXf &remaining_energy,
                    const Eigen::MatrixXf &frames, float frame_thresh,
                    int energy_tol, int min_note_len,
                    std::vector<basic_pitch::NoteEvent> &note_events)
{

    int n_times = remaining_energy.rows();

    // Continue applying the trick as long as there is energy above the
    // threshold
    while (remaining_energy.maxCoeff() > frame_thresh)
    {
        // Find the time-frequency point with maximum remaining energy
        Eigen::Index i_mid, freq_idx;
        float max_energy = remaining_energy.maxCoeff(&i_mid, &freq_idx);

        // Zero out the max energy point
        remaining_energy(i_mid, freq_idx) = 0.0f;

        // Forward pass to find note end
        int i = i_mid + 1;
        int k = 0;
        while (i < n_times - 1 && k < energy_tol)
        {
            if (remaining_energy(i, freq_idx) < frame_thresh)
            {
                k++;
            }
            else
            {
                k = 0;
            }
            remaining_energy(i, freq_idx) = 0.0f;

            // Zero out neighboring frequencies if applicable
            if (freq_idx < MAX_FREQ_IDX)
                remaining_energy(i, freq_idx + 1) = 0.0f;
            if (freq_idx > 0)
                remaining_energy(i, freq_idx - 1) = 0.0f;

            i++;
        }
        int i_end = i - 1 - k;

        // Backward pass to find note start
        i = i_mid - 1;
        k = 0;
        while (i > 0 && k < energy_tol)
        {
            if (remaining_energy(i, freq_idx) < frame_thresh)
            {
                k++;
            }
            else
            {
                k = 0;
            }
            remaining_energy(i, freq_idx) = 0.0f;

            // Zero out neighboring frequencies if applicable
            if (freq_idx < MAX_FREQ_IDX)
                remaining_energy(i, freq_idx + 1) = 0.0f;
            if (freq_idx > 0)
                remaining_energy(i, freq_idx - 1) = 0.0f;

            i--;
        }
        int i_start = i + 1 + k;

        // Ensure the note is long enough
        if (i_end - i_start <= min_note_len)
        {
            continue; // Skip short notes
        }

        // Calculate amplitude and store the new note event
        float amplitude = 0.0f;
        for (int t = i_start; t < i_end; ++t)
        {
            amplitude += frames(t, freq_idx);
        }
        amplitude /= (i_end - i_start);

        // Store note event (start, end, MIDI pitch, amplitude)
        note_events.emplace_back(i_start, i_end, freq_idx + MIDI_OFFSET,
                                 amplitude, std::nullopt);
    }
}

// Convert MIDI pitch to frequency (Hz)
static float midi_to_hz(float pitch_midi)
{
    return 440.0f *
           std::pow(2.0f, (pitch_midi - 69.0f) / 12.0f); // A4 is 440Hz, MIDI 69
}

static float midi_pitch_to_contour_bin(float pitch_midi)
{
    // Convert MIDI pitch to frequency (Hz)
    float pitch_hz = midi_to_hz(pitch_midi);

    // Calculate the corresponding bin in the contour matrix
    return 12.0f * CONTOURS_BINS_PER_SEMITONE *
           std::log2(pitch_hz / ANNOTATIONS_BASE_FREQUENCY);
}

static void add_pitch_bends(const Eigen::Tensor2dXf &contours,
                            std::vector<basic_pitch::NoteEvent> &note_events,
                            int n_bins_tolerance = 25)
{
    int n_freqs_contours = contours.dimension(1);
    const int window_length = n_bins_tolerance * 2 + 1;

    // Create Gaussian window similar to scipy.signal.windows.gaussian
    std::vector<float> freq_gaussian(window_length);
    float sigma = 5.0f;
    for (int i = 0; i < window_length; ++i)
    {
        float x = static_cast<float>(i - n_bins_tolerance);
        freq_gaussian[i] = std::exp(-(x * x) / (2 * sigma * sigma));
    }

    for (auto &note_event : note_events)
    {
        auto &[start_idx, end_idx, pitch_midi, amplitude, pitch_bends] =
            note_event;

        float bin_float =
            midi_pitch_to_contour_bin(static_cast<float>(pitch_midi));
        int freq_idx = static_cast<int>(std::round(bin_float));

        // Ensure frequency indices are within valid bounds
        int freq_start_idx = std::max(0, freq_idx - n_bins_tolerance);
        int freq_end_idx =
            std::min(n_freqs_contours, freq_idx + n_bins_tolerance + 1);

        // Adjust Gaussian window bounds to handle boundary conditions
        int gaussian_start = std::max(0, n_bins_tolerance - freq_idx);
        int gaussian_end =
            window_length -
            std::max(0, freq_idx - (n_freqs_contours - n_bins_tolerance - 1));

        // Shift factor for calculating relative bends
        int pb_shift =
            n_bins_tolerance - std::max(0, n_bins_tolerance - freq_idx);

        // Initialize pitch_bends only if needed
        pitch_bends
            .emplace(); // Initializes the std::optional with an empty vector
        pitch_bends->resize(end_idx -
                            start_idx); // Resize the vector within the optional

        // Apply Gaussian window and extract submatrix, performing element-wise
        // multiplication
        for (int t = start_idx; t < end_idx; ++t)
        {
            float max_val = -std::numeric_limits<float>::infinity();
            int max_idx = 0;

            // Loop within the Gaussian window limits: from `gaussian_start` to
            // `gaussian_end`
            for (int f = freq_start_idx, g = gaussian_start;
                 f < freq_end_idx && g < gaussian_end; ++f, ++g)
            {
                // Apply Gaussian window to each frequency bin in the current
                // frame
                float weighted_value = contours(t, f) * freq_gaussian[g];
                if (weighted_value > max_val)
                {
                    max_val = weighted_value;
                    max_idx = f; // Store the actual frequency bin index
                }
            }

            // Normalize the max index relative to the Gaussian window center
            (*pitch_bends)[t - start_idx] =
                (max_idx - freq_start_idx) - pb_shift;
        }
    }
}

// Function to drop pitch bends from overlapping notes
static void
drop_overlapping_pitch_bends(std::vector<basic_pitch::NoteEvent> &note_events)
{
    if (note_events.size() < 2)
        return; // nothing to compare (empty or single note)

    // Sort by start time
    std::sort(note_events.begin(), note_events.end());

    for (size_t i = 0; i < note_events.size() - 1; ++i)
    {
        for (size_t j = i + 1; j < note_events.size(); ++j)
        {
            // Check if start time of note j is after end time of note i
            if ((note_events[j].start_idx) >= (note_events[i].end_idx))
            {
                break; // No overlap
            }

            // Remove pitch bends from overlapping notes
            note_events[i].pitch_bends = std::nullopt;
            note_events[j].pitch_bends = std::nullopt;
        }
    }
}

// Main function to convert frames and onsets to note events
static std::vector<basic_pitch::NoteEvent>
output_to_notes_polyphonic(const basic_pitch::InferenceResult &inference_result,
                           const bool use_melodia_trick,
                           const bool include_pitch_bends,
                           const basic_pitch::ModelParams &params)
{

    int n_times_onsets = inference_result.onsets.dimension(0);

    Eigen::Tensor2dXf frames = inference_result.notes;

    Eigen::Tensor2dXf remaining_energy =
        frames; // Clone frames as we will modify this in-place
    std::vector<basic_pitch::NoteEvent> note_events;

    // Find peaks in the onsets
    auto peaks = find_peaks(inference_result.onsets, params.onset_threshold);

    // reverse sort the peaks by onset value
    // std::sort(filtered_peaks.begin(), filtered_peaks.end(),
    // std::greater<>());
    std::reverse(peaks.begin(), peaks.end());

    // Process peaks to generate note events
    for (const auto &[note_start_idx, freq_idx] : peaks)
    {
        int i = note_start_idx + 1;
        int k = 0;

        // Find the point where the note energy drops below the threshold
        while (i < n_times_onsets - 1 && k < params.energy_tol)
        {
            if (remaining_energy(i, freq_idx) < params.frame_threshold)
            {
                k++;
            }
            else
            {
                k = 0;
            }
            i++;
        }
        i -= k; // Adjust index

        if (i - note_start_idx <= params.min_note_len)
            continue; // Skip short notes

        // Clear energy in the current frequency band
        for (int t = note_start_idx; t < i; ++t)
        {
            remaining_energy(t, freq_idx) = 0.0f;
            if (freq_idx > 0)
                remaining_energy(t, freq_idx - 1) = 0.0f;
            if (freq_idx < MAX_FREQ_IDX)
                remaining_energy(t, freq_idx + 1) = 0.0f;
        }

        // Calculate amplitude and store note event
        float amplitude = 0.0f;
        for (int t = note_start_idx; t < i; ++t)
        {
            amplitude += frames(t, freq_idx);
        }
        amplitude /= (i - note_start_idx);

        note_events.emplace_back(note_start_idx, i, freq_idx + MIDI_OFFSET,
                                 amplitude, std::nullopt);
    }

    if (use_melodia_trick)
    {
        // get matrixxf of remaining_energy, frames to apply melodia trick
        Eigen::MatrixXf remaining_energy_mat = Eigen::Map<Eigen::MatrixXf>(
            remaining_energy.data(), remaining_energy.dimension(0),
            remaining_energy.dimension(1));
        Eigen::MatrixXf frames_mat = Eigen::Map<Eigen::MatrixXf>(
            frames.data(), frames.dimension(0), frames.dimension(1));
        apply_melodia_trick(remaining_energy_mat, frames_mat,
                            params.frame_threshold, params.energy_tol,
                            params.min_note_len, note_events);
    }

    if (include_pitch_bends)
    {
        add_pitch_bends(inference_result.contours, note_events);
    }
    return note_events;
}

static uint32_t time_to_ticks(float time_seconds, int tempo_us,
                              int tpqn = DEFAULT_TPQN)
{
    return static_cast<uint32_t>(
        std::round((time_seconds * tpqn * 1'000'000) / tempo_us));
}

static libremidi::writer
note_events_to_midi(const std::vector<basic_pitch::NoteEvent> &note_events,
                    int n_times_onsets, int program,
                    const basic_pitch::RhythmResult &rhythm_result,
                    float global_shift_bins, float velocity_compress)
{
    const bool rhythm_on = rhythm_result.tempo_bpm > 0.0f;
    const int tpqn = rhythm_on ? 480 : DEFAULT_TPQN;
    const int tempo_us =
        rhythm_on ? static_cast<int>(std::round(60.0e6 / rhythm_result.tempo_bpm))
                  : MIDI_TEMPO_US;

    libremidi::writer midi_writer;
    midi_writer.ticksPerQuarterNote = tpqn;

    // Track with tempo and time signature
    const int ts_num =
        rhythm_on && rhythm_result.ts_numerator > 0
            ? rhythm_result.ts_numerator
            : TIME_SIGNATURE_NUMERATOR;
    libremidi::midi_track meta_track;
    meta_track.emplace_back(0, 0, libremidi::meta_events::tempo(tempo_us));
    meta_track.emplace_back(
        0, 0,
        libremidi::meta_events::time_signature(
            ts_num, rhythm_on && rhythm_result.ts_denominator > 0
                        ? rhythm_result.ts_denominator
                        : TIME_SIGNATURE_DENOMINATOR));
    midi_writer.tracks.push_back(meta_track);

    // Calculate frame times for each note onset
    std::vector<float> frame_times = model_frames_to_time(n_times_onsets);

    // Rhythm grid for snapping note boundaries (empty when rhythm is off).
    // The grid starts at the first note, and tick time 0 is its first point —
    // so the first note opens the file (no leading rest in the output).
    const std::vector<float> grid =
        rhythm_result.subdivision > 0 ? basic_pitch::rhythm_grid(rhythm_result)
                                      : std::vector<float>{};
    const float grid_origin = grid.empty() ? 0.0f : grid.front();
    auto snap_to_grid = [&grid](float t) -> float
    {
        if (grid.empty())
        {
            return t;
        }
        float best = grid.front();
        float best_dist = std::fabs(t - best);
        for (float g : grid)
        {
            const float d = std::fabs(t - g);
            if (d < best_dist)
            {
                best_dist = d;
                best = g;
            }
        }
        return best;
    };

    // Create a vector to hold all events with their absolute tick times
    struct MidiEvent
    {
        uint32_t tick; // Absolute tick time
        libremidi::message message;
    };

    std::vector<MidiEvent> midi_events;

    log_verbose("Before iterating over note events");

    // Iterate over note events
    for (const auto &[start_idx, end_idx, pitch, amplitude, pitch_bend_opt] :
         note_events)
    {
        float start_time = snap_to_grid(frame_times[start_idx]);
        float end_time = snap_to_grid(frame_times[end_idx]);
        start_time -= grid_origin; // ticks count from the first grid point
        end_time -= grid_origin;
        uint32_t start_tick = time_to_ticks(start_time, tempo_us, tpqn);
        uint32_t end_tick = time_to_ticks(end_time, tempo_us, tpqn);
        int velocity = static_cast<int>(amplitude * 127);
        if (velocity_compress > 0.0f)
        {
            // Dynamics compression: v' = 127*(v/127)^(1/k), k in [1..3]
            const float k = 1.0f + 2.0f * velocity_compress;
            const float v =
                std::clamp(static_cast<float>(velocity), 0.0f, 127.0f) / 127.0f;
            velocity = static_cast<int>(
                std::round(std::pow(v, 1.0f / k) * 127.0f));
            velocity = std::clamp(velocity, 1, 127);
        }

        // Add `Note_on` event at start_tick
        midi_events.push_back({start_tick, libremidi::channel_events::note_on(
                                               0, pitch, velocity)});

        // Process pitch bends directly without allocating a sub-vector
        if (pitch_bend_opt.has_value())
        {
            const std::vector<int> &pitch_bend = pitch_bend_opt.value();
            int num_bends = pitch_bend.size();

            if (num_bends > 1)
            {
                float time_increment =
                    (end_time - start_time) / (num_bends - 1);

                for (int i = 0; i < num_bends; ++i)
                {
                    float bend_time = start_time + i * time_increment;
                    uint32_t bend_tick =
                        time_to_ticks(bend_time, tempo_us, tpqn);

                    // Ensure bend_tick does not exceed end_tick
                    bend_tick = std::min(bend_tick, end_tick);

                    int bend_value;
                    if (global_shift_bins != 0.0f)
                    {
                        bend_value = static_cast<int>(std::round(
                            (pitch_bend[i] - global_shift_bins) *
                            (4096.0f / CONTOURS_BINS_PER_SEMITONE))) +
                                     8192;
                    }
                    else
                    {
                        bend_value =
                            pitch_bend[i] *
                                (4096 / CONTOURS_BINS_PER_SEMITONE) +
                            8192;
                    }
                    bend_value = std::clamp(bend_value, 0, 16383);

                    // Add pitch bend event at bend_tick
                    midi_events.push_back(
                        {bend_tick,
                         libremidi::channel_events::pitch_bend(0, bend_value)});
                }
            }
            else
            {
                // Single pitch bend case
                uint32_t bend_tick = start_tick;
                int bend_value;
                if (global_shift_bins != 0.0f)
                {
                    bend_value = static_cast<int>(std::round(
                                    (pitch_bend[0] - global_shift_bins) *
                                    (4096.0f / CONTOURS_BINS_PER_SEMITONE))) +
                                8192;
                }
                else
                {
                    bend_value =
                        pitch_bend[0] *
                            (4096 / CONTOURS_BINS_PER_SEMITONE) +
                        8192;
                }
                bend_value = std::clamp(bend_value, 0, 16383);

                // Add pitch bend event at start_tick
                midi_events.push_back(
                    {bend_tick,
                     libremidi::channel_events::pitch_bend(0, bend_value)});
            }
        }

        // Add `Note_off` event at end_tick
        midi_events.push_back(
            {end_tick, libremidi::channel_events::note_off(0, pitch, 0)});
    }

    log_verbose("After iterating over note events");

    // Sort all events by their absolute tick times
    std::sort(midi_events.begin(), midi_events.end(),
              [](const MidiEvent &a, const MidiEvent &b)
              {
                  if (a.tick != b.tick)
                  {
                      return a.tick < b.tick; // Primary sorting by tick
                  }
                  else
                  {
                      // Secondary sorting by message type
                      return a.message.get_message_type() <
                             b.message.get_message_type();
                  }
              });

    log_verbose("Now creating instrument track");

    // Now, compute delta times and add events to the instrument track
    libremidi::midi_track instrument_track;
    instrument_track.emplace_back(0, 0,
                                  libremidi::channel_events::program_change(
                                      0, program)); // Set instrument program

    uint32_t last_tick = 0;
    for (const auto &event : midi_events)
    {
        uint32_t delta_ticks = event.tick - last_tick;
        instrument_track.emplace_back(delta_ticks, 0, event.message);
        last_tick = event.tick;
    }

    midi_writer.tracks.push_back(instrument_track);
    return midi_writer;
}

std::vector<uint8_t> basic_pitch::convert_to_midi(
    const basic_pitch::InferenceResult &inference_result,
    const bool use_melodia_trick,  // defaults to true
    const bool include_pitch_bends, // defaults to true
    const basic_pitch::ModelParams &params,
    const basic_pitch::RhythmParams &rhythm,
    const std::vector<float> *mono_audio,
    const basic_pitch::HarmonizeParams &harmonize)
{
    // Process the unwrapped notes and onsets to detect note events

    log_verbose("output_to_notes_polyphonic");

    std::vector<basic_pitch::NoteEvent> note_events =
        output_to_notes_polyphonic(inference_result, use_melodia_trick,
                                   include_pitch_bends, params);

    // Harmonization (OQ-06): merge vibrato fragments, drop small bends,
    // estimate the global detune reference
    if (harmonize.merge_semitones > 0)
    {
        basic_pitch::merge_note_fragments(note_events,
                                          harmonize.merge_semitones);
    }
    if (harmonize.min_bend_bins > 0)
    {
        basic_pitch::drop_small_bends(note_events, harmonize.min_bend_bins);
    }
    float global_shift_bins = 0.0f;
    if (harmonize.global_shift > 0.0f)
    {
        global_shift_bins =
            basic_pitch::estimate_global_shift(note_events) *
            harmonize.global_shift;
        log_verbose("global shift: " + std::to_string(global_shift_bins) +
                    " contour bins");
    }

    // OQ-06 stage 2: key/mode fitting on the shifted notes (reported always,
    // so the GUI can show the key); the snap to scale degrees is optional
    // (strength 0..1)
    { // fit always (for the report), snap when enabled
        auto fit = basic_pitch::fit_mode(note_events, global_shift_bins);
        if (fit)
        {
            if (harmonize.mode_snap > 0.0f)
            {
                basic_pitch::apply_mode_snap(note_events, *fit,
                                             harmonize.mode_snap,
                                             global_shift_bins);
            }
            log_verbose("mode fit: " +
                        basic_pitch::mode_fit_description(*fit) + ", " +
                        std::to_string(static_cast<int>(std::round(
                            fit->fraction * 100))) +
                        "% of notes within 30 cents, snap strength " +
                        std::to_string(harmonize.mode_snap));
        }
        else
        {
            log_verbose("mode fit: no scale covers 50% of notes - stage-1 "
                        "shift only");
        }
    }

    if (include_pitch_bends)
    {
        // Drop pitch bends from overlapping notes
        drop_overlapping_pitch_bends(note_events);
    }

    int n_times_notes = inference_result.notes.dimension(0);

    // Rhythm processing: tempo detection + grid quantization (optional)
    basic_pitch::RhythmResult rhythm_result;
    if (rhythm.tempo_bpm > 0.0f || rhythm.quantize != 0)
    {
        const float frame_rate = static_cast<float>(SAMPLE_RATE) / FFT_HOP;
        const std::vector<float> all_frame_times =
            model_frames_to_time(n_times_notes);
        std::vector<float> starts_s;
        starts_s.reserve(note_events.size());
        for (const auto &note : note_events)
        {
            starts_s.push_back(all_frame_times[note.start_idx]);
        }
        // Audio flux carries the accent structure (model OSS is flat per beat)
        const std::vector<float> flux =
            mono_audio ? basic_pitch::audio_flux_oss(
                             *mono_audio, SAMPLE_RATE, FFT_HOP)
                       : std::vector<float>{};
        rhythm_result = basic_pitch::analyze_rhythm(
            inference_result.onsets, starts_s, frame_rate, rhythm,
            flux.empty() ? nullptr : &flux);
        if (rhythm_result.tempo_bpm > 0.0f)
        {
            const int ts_num =
                rhythm_result.ts_numerator > 0
                    ? rhythm_result.ts_numerator
                    : TIME_SIGNATURE_NUMERATOR;
            log_verbose("tempo: " + std::to_string(rhythm_result.tempo_bpm) +
                        " BPM, time signature " + std::to_string(ts_num) + "/" +
                        std::to_string(rhythm_result.ts_denominator > 0
                                          ? rhythm_result.ts_denominator
                                          : TIME_SIGNATURE_DENOMINATOR) +
                        ", subdivision " +
                        std::to_string(rhythm_result.subdivision));
        }
    }

    log_verbose("note_events_to_midi");

    // Convert the detected note events to a MIDI writer object
    libremidi::writer midi_writer = note_events_to_midi(
        note_events, n_times_notes, params.program, rhythm_result,
        global_shift_bins, params.velocity_compress);

    log_verbose("done!");

    // Use ostringstream to write MIDI data to a byte stream
    std::ostringstream output(std::ios::binary);
    midi_writer.write(output);

    // Convert the byte stream to a string
    std::string midi_string = output.str();

    // Create a vector to hold the MIDI data
    std::vector<uint8_t> midi_data(midi_string.begin(), midi_string.end());

    // Return the MIDI data as bytes
    return midi_data;
}
