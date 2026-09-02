// v2m C library: thin wrapper around the basicpitch C++ pipeline.
// See v2m.h for the API. The CLI (src_cli/basicpitch.cpp) uses the same
// entry point; defaults stay byte-identical to the CLI behavior.
#include "v2m.h"
#include "basicpitch.hpp"
#include "MultiChannelResampler.h"

#include <cstdlib>
#include <cstring>
#include <exception>
#include <string>
#include <vector>

namespace
{

std::vector<float> resample_to_22050(const float *pcm, int n, int sample_rate)
{
    if (sample_rate == basic_pitch::constants::SAMPLE_RATE || n <= 0)
    {
        return std::vector<float>(pcm, pcm + n);
    }
    aaudio::resampler::MultiChannelResampler *resampler =
        aaudio::resampler::MultiChannelResampler::make(
            1, sample_rate, basic_pitch::constants::SAMPLE_RATE,
            aaudio::resampler::MultiChannelResampler::Quality::Best);
    const int n_out = static_cast<int>(
        static_cast<double>(n) * basic_pitch::constants::SAMPLE_RATE /
            sample_rate +
        0.5);
    std::vector<float> out(n_out);
    const float *in = pcm;
    float *o = out.data();
    int in_left = n;
    int out_left = n_out;
    while (in_left > 0 && out_left > 0)
    {
        if (resampler->isWriteNeeded())
        {
            resampler->writeNextFrame(in);
            ++in;
            --in_left;
        }
        else
        {
            resampler->readNextFrame(o);
            ++o;
            --out_left;
        }
    }
    while (out_left > 0)
    {
        resampler->readNextFrame(o);
        ++o;
        --out_left;
    }
    delete resampler;
    return out;
}

} // namespace

extern "C"
{

int v2m_params_default(V2mParams *out)
{
    if (!out)
    {
        return 0;
    }
    out->onset_threshold = basic_pitch::constants::ONSET_THRESHOLD;
    out->frame_threshold = basic_pitch::constants::FRAME_THRESHOLD;
    out->min_note_len = basic_pitch::constants::MIN_NOTE_LEN;
    out->energy_tol = basic_pitch::constants::ENERGY_TOL;
    out->program = 4;
    out->velocity_compress = 0.0f;
    out->use_melodia_trick = 1;
    out->include_pitch_bends = 1;
    out->tempo_bpm = 0.0f;
    out->quantize = 0;
    out->tolerance_ms = 40.0f;
    out->time_sig_num = 0;
    out->time_sig_den = 0;
    out->harmonize_merge = 0;
    out->min_bend_bins = 0;
    out->global_shift = 0.0f;
    out->mode_snap = 0.0f;
    out->verbose = 0;
    return 1;
}

int v2m_transcribe(const float *pcm, int n_samples, int sample_rate,
                   const V2mParams *params, uint8_t **midi, size_t *midi_len,
                   char **err)
{
    if (!pcm || n_samples <= 0 || !params || !midi || !midi_len)
    {
        if (err)
        {
            *err = strdup("invalid arguments");
        }
        return 0;
    }
    try
    {
        basic_pitch::set_verbose(params->verbose != 0);
        std::vector<float> audio = resample_to_22050(pcm, n_samples, sample_rate);
        basic_pitch::InferenceResult inference = basic_pitch::ort_inference(audio);

        basic_pitch::ModelParams mp;
        mp.onset_threshold = params->onset_threshold;
        mp.frame_threshold = params->frame_threshold;
        mp.min_note_len = params->min_note_len;
        mp.energy_tol = params->energy_tol;
        mp.program = params->program;
        mp.velocity_compress = params->velocity_compress;

        basic_pitch::RhythmParams rp;
        rp.tempo_bpm = params->tempo_bpm;
        rp.quantize = params->quantize;
        rp.tolerance_ms = params->tolerance_ms;
        rp.time_sig_num = params->time_sig_num;
        rp.time_sig_den = params->time_sig_den;

        basic_pitch::HarmonizeParams hp;
        hp.merge_semitones = params->harmonize_merge;
        hp.min_bend_bins = params->min_bend_bins;
        hp.global_shift = params->global_shift;
        hp.mode_snap = params->mode_snap;

        std::vector<uint8_t> bytes = basic_pitch::convert_to_midi(
            inference, params->use_melodia_trick != 0,
            params->include_pitch_bends != 0, mp, rp, &audio, hp);

        uint8_t *buf = static_cast<uint8_t *>(std::malloc(bytes.size()));
        if (!buf)
        {
            if (err)
            {
                *err = strdup("out of memory");
            }
            return 0;
        }
        std::memcpy(buf, bytes.data(), bytes.size());
        *midi = buf;
        *midi_len = bytes.size();
        return 1;
    }
    catch (const std::exception &e)
    {
        if (err)
        {
            *err = strdup(e.what());
        }
        return 0;
    }
}

void v2m_free(void *p)
{
    std::free(p);
}

} // extern "C"
