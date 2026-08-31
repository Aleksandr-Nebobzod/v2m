#ifndef BASIC_PITCH_HPP
#define BASIC_PITCH_HPP

#include <Eigen/Dense>
#include <cmath>
#include <complex>
#include <iostream>
#include <optional>
#include <string>
#include <unsupported/Eigen/CXX11/Tensor>
#include <vector>

namespace Eigen
{
// define some Tensors
typedef Tensor<float, 3> Tensor3dXf;
typedef Tensor<float, 3, Eigen::RowMajor> Tensor3dRowMajorXf;
typedef Tensor<float, 2> Tensor2dXf;
typedef Tensor<float, 1> Tensor1dXf;
typedef Matrix<float, Dynamic, Dynamic> MatrixXf;
} // namespace Eigen

namespace basic_pitch
{
namespace constants
{
const int SAMPLE_RATE = 22050;
const int AUDIO_SAMPLE_RATE = SAMPLE_RATE;
const int FFT_HOP = 256;
constexpr int ANNOTATIONS_FPS = SAMPLE_RATE / FFT_HOP;
const float ONSET_THRESHOLD = 0.5f;
const float FRAME_THRESHOLD = 0.3f;
const float ANNOTATIONS_BASE_FREQUENCY = 27.5f; // lowest key on a piano
const float MAGIC_NUMBER = 0.0018f;
const int CONTOURS_BINS_PER_SEMITONE = 3;
const int AUDIO_WINDOW_LENGTH = 2;
const int MIN_NOTE_LEN = 11;
const int MIDI_OFFSET = 21;
const int MAX_FREQ_IDX = 87;
const int ENERGY_TOL = 11;
constexpr float ANNOT_N_FRAMES = ANNOTATIONS_FPS * AUDIO_WINDOW_LENGTH;
constexpr float AUDIO_N_SAMPLES = SAMPLE_RATE * AUDIO_WINDOW_LENGTH - FFT_HOP;

// tempo constants
const int MIDI_TEMPO_US = 500'000; // 120 BPM
const float MIDI_TEMPO_BPM = 120.0f;
const int TIME_SIGNATURE_NUMERATOR = 4;
const int TIME_SIGNATURE_DENOMINATOR = 4;

// mode fitting (OQ-06 stage 2) constants
constexpr float MODE_TOLERANCE_SEMITONES = 0.3f; // 30 cents
constexpr float MODE_MIN_FRACTION = 0.5f;        // best key must cover >= 50%

// midi constants
const int DEFAULT_TPQN = 220; // ticks per quarter note
};                            // namespace constants

// Tunable model params, defaults match constants; overridable per call
struct ModelParams
{
    float onset_threshold = constants::ONSET_THRESHOLD;
    float frame_threshold = constants::FRAME_THRESHOLD;
    int min_note_len = constants::MIN_NOTE_LEN;
    int energy_tol = constants::ENERGY_TOL;
    int program = 4;            // default MIDI program (Electric Piano)
    float velocity_compress = 0.0f; // 0 = off, 1 = strong dynamics compression
};

// Rhythm processing params; defaults keep the original behavior
struct RhythmParams
{
    float tempo_bpm = 0.0f; // 0 = auto-detect
    int quantize = 0;       // 0 = off, 1 = auto, 2 = beat, 3 = eighth, 4 = sixteenth
    float tolerance_ms = 40.0f;
};

// Harmonization params; defaults keep the original behavior
struct HarmonizeParams
{
    int merge_semitones = 0;     // merge adjacent fragments within N semitones
    int min_bend_bins = 0;       // zero out bends smaller than N contour bins
    float global_shift = 0.0f;   // 0..1: apply median global detune correction
    float mode_snap = 0.0f;      // 0..1: fit key/mode and snap notes to scale
                                 // degrees (0 = off, 1 = exactly on degrees)
};

// Result of key/mode fitting (OQ-06 stage 2)
struct ModeFit
{
    int root_semitones = 0;      // 0..11 (C..B)
    int scale_index = 0;         // index into the scale table (harmonize.cpp)
    float fraction = 0.0f;       // notes within the 30-cent tolerance
    float avg_dist = 0.0f;       // mean circular distance to scale degrees
};

struct RhythmResult
{
    float tempo_bpm = 0.0f;      // 0 = rhythm processing not applied
    std::vector<float> beats_s;  // beat times in seconds
    int subdivision = 0;         // grid division used (0 = none)
    int ts_numerator = 0;        // detected time signature numerator (0 = none)
};

// Tempo estimation and grid selection (Ellis-style), see rhythm.cpp.
// `accent` (optional): audio flux onset signal used for time-signature
// detection; model OSS does not carry the accent structure.
RhythmResult analyze_rhythm(const Eigen::Tensor2dXf &onsets,
                            const std::vector<float> &starts_s, float frame_rate,
                            const RhythmParams &params,
                            const std::vector<float> *accent = nullptr);

// Half-wave rectified frame-energy rise, normalized; frame rate = sr/hop
std::vector<float> audio_flux_oss(const std::vector<float> &audio, int sample_rate,
                                  int hop);

// Grid points in seconds: beats expanded by the subdivision
std::vector<float> rhythm_grid(const RhythmResult &result);

struct InferenceResult
{
    Eigen::Tensor2dXf notes;
    Eigen::Tensor2dXf onsets;
    Eigen::Tensor2dXf contours;
};

InferenceResult ort_inference(const std::vector<float> &mono_audio);
InferenceResult ort_inference(const float *mono_audio, int length);

struct NoteEvent
{
    int start_idx;
    int end_idx;
    int pitch;
    float amplitude;

    std::optional<std::vector<int>> pitch_bends;

    // comparison operator for sorting
    bool operator<(const NoteEvent &other) const
    {
        return std::tie(start_idx, end_idx, pitch, amplitude, pitch_bends) <
               std::tie(other.start_idx, other.end_idx, other.pitch,
                        other.amplitude, other.pitch_bends);
    }
};

std::vector<uint8_t> convert_to_midi(const InferenceResult &inference_result,
                                     const bool use_melodia_trick = true,
                                     const bool include_pitch_bends = true,
                                     const ModelParams &params = {},
                                     const RhythmParams &rhythm = {},
                                     const std::vector<float> *mono_audio = nullptr,
                                     const HarmonizeParams &harmonize = {});

// Harmonization post-processing, see harmonize.cpp
void merge_note_fragments(std::vector<NoteEvent> &notes, int max_semitones);
void drop_small_bends(std::vector<NoteEvent> &notes, int min_bend_bins);
float estimate_global_shift(const std::vector<NoteEvent> &notes);
// Best (root, scale) covering >= 50% of note centroids within 30 cents;
// nullopt when the recording does not fit any scale (fewer than 3 notes too)
std::optional<ModeFit> fit_mode(const std::vector<NoteEvent> &notes,
                                float global_shift_bins);
// Pull note centroids toward the fitted scale degrees with the given
// strength; whole semitones go to the pitch, the remainder to the bends
void apply_mode_snap(std::vector<NoteEvent> &notes, const ModeFit &fit,
                     float strength, float global_shift_bins);
// Human-readable key description, e.g. "G natural minor"
std::string mode_fit_description(const ModeFit &fit);

// Toggle progress messages from the inference/conversion code (default: silent)
void set_verbose(bool verbose);

// Shared verbose flag, read by inference and MIDI code
extern bool g_verbose;
} // namespace basic_pitch

#endif // BASIC_PITCH_HPP
