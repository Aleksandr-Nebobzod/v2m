#include "basicpitch.hpp"
#include "v2m.h"
#include "MultiChannelResampler.h"
#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <libnyquist/Common.h>
#include <libnyquist/Decoders.h>
#include <libnyquist/Encoders.h>
#include <map>
#include <numeric>
#include <ranges>
#include <sstream>
#include <stddef.h>
#include <tuple>
#include <vector>

using namespace nqr;
using namespace basic_pitch::constants;

static std::vector<float> load_audio_file(std::string filename, bool verbose)
{
    // load a wav file with libnyquist
    std::shared_ptr<AudioData> fileData = std::make_shared<AudioData>();
    NyquistIO loader;
    loader.Load(fileData.get(), filename);

    if (verbose)
    {
        std::cout << "Input samples: "
                  << fileData->samples.size() / fileData->channelCount
                  << std::endl;
        std::cout << "Length in seconds: " << fileData->lengthSeconds
                  << std::endl;
        std::cout << "Number of channels: " << fileData->channelCount
                  << std::endl;
    }

    if (fileData->channelCount != 2 && fileData->channelCount != 1)
    {
        std::cerr
            << "[ERROR] basicpitch.cpp only supports mono and stereo audio"
            << std::endl;
        exit(1);
    }

    // number of samples per channel
    std::size_t N = fileData->samples.size() / fileData->channelCount;

    std::vector<float> mono_audio(N);

    if (fileData->channelCount == 1)
    {
        for (std::size_t i = 0; i < N; ++i)
        {
            mono_audio[i] = fileData->samples[i];
        }
    }
    else
    {
        // Stereo case: downmix to mono
        for (std::size_t i = 0; i < N; ++i)
        {
            mono_audio[i] =
                (fileData->samples[2 * i] + fileData->samples[2 * i + 1]) /
                2.0f;
        }
    }

    // Check if resampling is needed
    if (fileData->sampleRate != SAMPLE_RATE)
    {
        if (verbose)
        {
            std::cout << "Resampling from " << fileData->sampleRate << " Hz to "
                      << SAMPLE_RATE << " Hz" << std::endl;
        }

        // Resampling using Oboe's resampler module
        aaudio::resampler::MultiChannelResampler *resampler =
            aaudio::resampler::MultiChannelResampler::make(
                1, // Mono (1 channel)
                fileData->sampleRate, SAMPLE_RATE,
                aaudio::resampler::MultiChannelResampler::Quality::Best);

        int numInputFrames =
            N; // Since the audio is mono, numInputFrames is just N
        int numOutputFrames =
            static_cast<int>(static_cast<double>(numInputFrames) * SAMPLE_RATE /
                                 fileData->sampleRate +
                             0.5);

        std::vector<float> resampledAudio(
            numOutputFrames); // Resampled mono audio

        float *inputBuffer = mono_audio.data();
        float *outputBuffer = resampledAudio.data();

        int inputFramesLeft = numInputFrames;
        int numResampledFrames = 0;

        while (inputFramesLeft > 0 && numResampledFrames < numOutputFrames)
        {
            if (resampler->isWriteNeeded())
            {
                resampler->writeNextFrame(inputBuffer);
                inputBuffer++;
                inputFramesLeft--;
            }
            else
            {
                resampler->readNextFrame(outputBuffer);
                outputBuffer++;
                numResampledFrames++;
            }
        }

        while (!resampler->isWriteNeeded() &&
               numResampledFrames < numOutputFrames)
        {
            resampler->readNextFrame(outputBuffer);
            outputBuffer++;
            numResampledFrames++;
        }

        delete resampler;

        return resampledAudio;
    }

    return mono_audio;
}

struct CliOptions
{
    basic_pitch::ModelParams params;
    bool use_melodia_trick = true;
    bool include_pitch_bends = true;
    bool verbose = false;
    basic_pitch::RhythmParams rhythm;
    basic_pitch::HarmonizeParams harmonize;
};

static CliOptions parse_cli_options(int argc, const char **argv, int &i)
{
    CliOptions opts;

    auto require_value = [&](const char *opt_name) -> const char *
    {
        if (i + 1 >= argc)
        {
            std::cerr << "Missing value for option " << opt_name << std::endl;
            exit(1);
        }
        return argv[++i];
    };

    for (; i < argc; ++i)
    {
        std::string arg = argv[i];
        auto parse_float = [&](float &out, const char *opt_name) -> void
        {
            float value = 0.0f;
            try
            {
                value = std::stof(require_value(opt_name));
            }
            catch (const std::exception &)
            {
                std::cerr << "Invalid value for option " << opt_name
                          << std::endl;
                exit(1);
            }
            if (value < 0.0f || value > 1.0f)
            {
                std::cerr << "Option " << opt_name
                          << " must be in range [0, 1]" << std::endl;
                exit(1);
            }
            out = value;
        };

        if (arg == "--onset-threshold")
            parse_float(opts.params.onset_threshold, "--onset-threshold");
        else if (arg == "--frame-threshold")
            parse_float(opts.params.frame_threshold, "--frame-threshold");
        else if (arg == "--min-note-len")
        {
            int value = 0;
            try
            {
                value = std::stoi(require_value("--min-note-len"));
            }
            catch (const std::exception &)
            {
                std::cerr << "Invalid value for option --min-note-len"
                          << std::endl;
                exit(1);
            }
            if (value < 1)
            {
                std::cerr << "Option --min-note-len must be >= 1" << std::endl;
                exit(1);
            }
            opts.params.min_note_len = value;
        }
        else if (arg == "--melodia-trick")
        {
            opts.use_melodia_trick = true;
        }
        else if (arg == "--no-melodia-trick")
        {
            opts.use_melodia_trick = false;
        }
        else if (arg == "--tempo")
        {
            float value = 0.0f;
            try
            {
                value = std::stof(require_value("--tempo"));
            }
            catch (const std::exception &)
            {
                std::cerr << "Invalid value for option --tempo" << std::endl;
                exit(1);
            }
            if (value < 0.0f || value > 300.0f)
            {
                std::cerr << "Option --tempo must be in range [0, 300] BPM"
                          << std::endl;
                exit(1);
            }
            opts.rhythm.tempo_bpm = value;
        }
        else if (arg == "--quantize")
        {
            const std::string value = require_value("--quantize");
            if (value == "off")
                opts.rhythm.quantize = 0;
            else if (value == "auto")
                opts.rhythm.quantize = 1;
            else if (value == "beat")
                opts.rhythm.quantize = 2;
            else if (value == "eighth")
                opts.rhythm.quantize = 3;
            else if (value == "sixteenth")
                opts.rhythm.quantize = 4;
            else
            {
                std::cerr << "Option --quantize must be one of: off, auto, "
                             "beat, eighth, sixteenth"
                          << std::endl;
                exit(1);
            }
        }
        else if (arg == "--tempo-tolerance")
        {
            float value = 0.0f;
            try
            {
                value = std::stof(require_value("--tempo-tolerance"));
            }
            catch (const std::exception &)
            {
                std::cerr << "Invalid value for option --tempo-tolerance"
                          << std::endl;
                exit(1);
            }
            if (value < 0.0f)
            {
                std::cerr << "Option --tempo-tolerance must be >= 0 ms"
                          << std::endl;
                exit(1);
            }
            opts.rhythm.tolerance_ms = value;
        }
        else if (arg == "--time-signature")
        {
            const std::string value = require_value("--time-signature");
            const size_t slash = value.find('/');
            const std::string num_s = value.substr(0, slash);
            const std::string den_s =
                slash == std::string::npos ? "4" : value.substr(slash + 1);
            try
            {
                opts.rhythm.time_sig_num = std::stoi(num_s);
                opts.rhythm.time_sig_den = std::stoi(den_s);
            }
            catch (const std::exception &)
            {
                std::cerr << "Invalid value for option --time-signature"
                          << std::endl;
                exit(1);
            }
            if (opts.rhythm.time_sig_num < 1 ||
                opts.rhythm.time_sig_num > 32 ||
                opts.rhythm.time_sig_den < 1 ||
                opts.rhythm.time_sig_den > 64)
            {
                std::cerr << "Option --time-signature must be N/D with "
                             "N in [1, 32], D in [1, 64] (e.g. 2/4)"
                          << std::endl;
                exit(1);
            }
        }
        else if (arg == "--velocity-compress")
        {
            float value = 0.0f;
            try
            {
                value = std::stof(require_value("--velocity-compress"));
            }
            catch (const std::exception &)
            {
                std::cerr << "Invalid value for option --velocity-compress"
                          << std::endl;
                exit(1);
            }
            if (value < 0.0f || value > 1.0f)
            {
                std::cerr << "Option --velocity-compress must be in range [0, 1]"
                          << std::endl;
                exit(1);
            }
            opts.params.velocity_compress = value;
        }
        else if (arg == "--harmonize-merge")
        {
            int value = 0;
            try
            {
                value = std::stoi(require_value("--harmonize-merge"));
            }
            catch (const std::exception &)
            {
                std::cerr << "Invalid value for option --harmonize-merge"
                          << std::endl;
                exit(1);
            }
            if (value < 1)
            {
                std::cerr << "Option --harmonize-merge must be >= 1 semitone"
                          << std::endl;
                exit(1);
            }
            opts.harmonize.merge_semitones = value;
        }
        else if (arg == "--min-bend")
        {
            int value = 0;
            try
            {
                value = std::stoi(require_value("--min-bend"));
            }
            catch (const std::exception &)
            {
                std::cerr << "Invalid value for option --min-bend" << std::endl;
                exit(1);
            }
            if (value < 1)
            {
                std::cerr << "Option --min-bend must be >= 1 bin" << std::endl;
                exit(1);
            }
            opts.harmonize.min_bend_bins = value;
        }
        else if (arg == "--mode-snap")
        {
            float value = 0.0f;
            try
            {
                value = std::stof(require_value("--mode-snap"));
            }
            catch (const std::exception &)
            {
                std::cerr << "Invalid value for option --mode-snap" << std::endl;
                exit(1);
            }
            if (value < 0.0f || value > 1.0f)
            {
                std::cerr << "Option --mode-snap must be in range [0, 1]"
                          << std::endl;
                exit(1);
            }
            opts.harmonize.mode_snap = value;
        }
        else if (arg == "--global-shift")
        {
            float value = 0.0f;
            try
            {
                value = std::stof(require_value("--global-shift"));
            }
            catch (const std::exception &)
            {
                std::cerr << "Invalid value for option --global-shift"
                          << std::endl;
                exit(1);
            }
            if (value < 0.0f || value > 1.0f)
            {
                std::cerr << "Option --global-shift must be in range [0, 1]"
                          << std::endl;
                exit(1);
            }
            opts.harmonize.global_shift = value;
        }
        else if (arg == "--verbose")
        {
            opts.verbose = true;
        }
        else if (arg == "--pitch-bends")
        {
            opts.include_pitch_bends = true;
        }
        else if (arg == "--no-pitch-bends")
        {
            opts.include_pitch_bends = false;
        }
        else if (arg == "--energy-tol")
        {
            int value = 0;
            try
            {
                value = std::stoi(require_value("--energy-tol"));
            }
            catch (const std::exception &)
            {
                std::cerr << "Invalid value for option --energy-tol"
                          << std::endl;
                exit(1);
            }
            if (value < 1)
            {
                std::cerr << "Option --energy-tol must be >= 1" << std::endl;
                exit(1);
            }
            opts.params.energy_tol = value;
        }
        else if (arg == "--program")
        {
            int value = 0;
            try
            {
                value = std::stoi(require_value("--program"));
            }
            catch (const std::exception &)
            {
                std::cerr << "Invalid value for option --program" << std::endl;
                exit(1);
            }
            if (value < 0 || value > 127)
            {
                std::cerr << "Option --program must be in range [0, 127]"
                          << std::endl;
                exit(1);
            }
            opts.params.program = value;
        }
        else
        {
            std::cerr << "Unknown option: " << arg << std::endl;
            exit(1);
        }
    }
    return opts;
}

int main(int argc, const char **argv)
{
    if (argc < 3)
    {
        std::cerr << "Usage: " << argv[0]
                  << " <wav file> <out dir> [options]" << std::endl;
        std::cerr << "Options:" << std::endl;
        std::cerr << "  --onset-threshold <0..1>   onset detection threshold "
                     "(default: 0.5)"
                  << std::endl;
        std::cerr << "  --frame-threshold <0..1>   frame presence threshold "
                     "(default: 0.3)"
                  << std::endl;
        std::cerr << "  --min-note-len <n>         minimum note length in "
                     "frames (default: 11)"
                  << std::endl;
        std::cerr << "  --energy-tol <n>           frames of energy loss "
                     "tolerated at note edges (default: 11)"
                  << std::endl;
        std::cerr << "  --program <0..127>         MIDI instrument program "
                     "(default: 4)"
                  << std::endl;
        std::cerr << "  --melodia-trick            enable melodia post-processing "
                     "(default: on)"
                  << std::endl;
        std::cerr << "  --no-melodia-trick         disable melodia post-processing"
                  << std::endl;
        std::cerr << "  --pitch-bends              include pitch bend events "
                     "(default: on)"
                  << std::endl;
        std::cerr << "  --no-pitch-bends           drop pitch bend events"
                  << std::endl;
        std::cerr << "  --tempo <BPM>             manual tempo (0 = auto-detect, "
                     "default: 0)"
                  << std::endl;
        std::cerr << "  --quantize <mode>         rhythm quantization: off, auto, "
                     "beat, eighth, sixteenth (default: off)"
                  << std::endl;
        std::cerr << "  --tempo-tolerance <ms>    snap tolerance for quantization "
                     "(default: 40)"
                  << std::endl;
        std::cerr << "  --time-signature <N/D>    manual time signature, e.g. "
                     "2/4 (default: auto-detect)"
                  << std::endl;
        std::cerr << "  --velocity-compress <0..1> dynamics compression of note "
                     "velocity (default: 0)"
                  << std::endl;
        std::cerr << "  --harmonize-merge <N>     merge adjacent fragments within "
                     "N semitones (default: 0)"
                  << std::endl;
        std::cerr << "  --min-bend <n>            zero out bends smaller than n "
                     "contour bins (default: 0)"
                  << std::endl;
        std::cerr << "  --global-shift <0..1>     apply median global detune "
                     "correction (default: 0)"
                  << std::endl;
        std::cerr << "  --mode-snap <0..1>        fit key/mode and snap notes "
                     "to scale degrees (default: 0)"
                  << std::endl;
        std::cerr << "  --verbose                  show progress messages "
                     "(default: silent)"
                  << std::endl;
        exit(1);
    }

    // load audio passed as argument
    std::string wav_file = argv[1];

    // output dir passed as argument
    std::string out_dir = argv[2];

    // optional tunable params
    int argi = 3;
    CliOptions opts = parse_cli_options(argc, argv, argi);

    if (opts.verbose)
    {
        std::cout << "basicpitch.cpp Main driver program" << std::endl;
    }

    // Check if the output directory exists, and create it if not
    std::filesystem::path output_dir_path(out_dir);
    if (!std::filesystem::exists(output_dir_path))
    {
        if (opts.verbose)
        {
            std::cout << "Directory does not exist: " << out_dir
                      << ". Creating it." << std::endl;
        }
        if (!std::filesystem::create_directories(output_dir_path))
        {
            std::cerr << "Error: Unable to create directory: " << out_dir
                      << std::endl;
            return 1;
        }
    }
    else if (!std::filesystem::is_directory(output_dir_path))
    {
        std::cerr << "Error: " << out_dir << " exists but is not a directory!"
                  << std::endl;
        return 1;
    }

    if (opts.verbose)
    {
        std::cout << "Predicting MIDI for: " << wav_file << std::endl;
    }

    std::vector<float> audio = load_audio_file(wav_file, opts.verbose);

    // Transcribe via the v2m C library (the CLI is a thin wrapper)
    V2mParams vp;
    v2m_params_default(&vp);
    vp.onset_threshold = opts.params.onset_threshold;
    vp.frame_threshold = opts.params.frame_threshold;
    vp.min_note_len = opts.params.min_note_len;
    vp.energy_tol = opts.params.energy_tol;
    vp.program = opts.params.program;
    vp.velocity_compress = opts.params.velocity_compress;
    vp.use_melodia_trick = opts.use_melodia_trick ? 1 : 0;
    vp.include_pitch_bends = opts.include_pitch_bends ? 1 : 0;
    vp.tempo_bpm = opts.rhythm.tempo_bpm;
    vp.quantize = opts.rhythm.quantize;
    vp.tolerance_ms = opts.rhythm.tolerance_ms;
    vp.time_sig_num = opts.rhythm.time_sig_num;
    vp.time_sig_den = opts.rhythm.time_sig_den;
    vp.harmonize_merge = opts.harmonize.merge_semitones;
    vp.min_bend_bins = opts.harmonize.min_bend_bins;
    vp.global_shift = opts.harmonize.global_shift;
    vp.mode_snap = opts.harmonize.mode_snap;
    vp.verbose = opts.verbose ? 1 : 0;

    uint8_t *midi = nullptr;
    size_t midi_len = 0;
    char *err = nullptr;
    if (!v2m_transcribe(audio.data(), static_cast<int>(audio.size()),
                        SAMPLE_RATE, &vp, &midi, &midi_len, &err))
    {
        std::cerr << "[ERROR] " << (err ? err : "transcription failed")
                  << std::endl;
        v2m_free(err);
        return 1;
    }
    std::vector<uint8_t> midiBytes(midi, midi + midi_len);
    v2m_free(midi);

    if (opts.verbose)
    {
        std::cout << "MIDI data size: " << midiBytes.size() << std::endl;
    }

    // write the midiBytes to a file 'output.mid' in the output directory
    // we dont need to use libremidi itself since the bytes are already correct
    // just write the bytes to a file

    // Generate MIDI output file name with .mid extension
    std::filesystem::path midi_file =
        output_dir_path / std::filesystem::path(wav_file).filename();
    midi_file.replace_extension(".mid");

    std::ofstream midi_stream(midi_file, std::ios::binary);
    midi_stream.write(reinterpret_cast<const char *>(midiBytes.data()),
                      midiBytes.size());

    if (opts.verbose)
    {
        std::cout << "Wrote MIDI file to: " << midi_file << std::endl;
    }

    return 0;
}
