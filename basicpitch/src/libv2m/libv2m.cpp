// v2m C library: thin wrapper around the basicpitch C++ pipeline.
// See v2m.h for the API. The CLI (src_cli/basicpitch.cpp) uses the same
// entry point; defaults stay byte-identical to the CLI behavior.
#include "v2m.h"
#include "audio_effects.hpp"
#include "basicpitch.hpp"
#include "spectrogram.hpp"
#include "MultiChannelResampler.h"

#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <exception>
#include <iostream>
#include <string>
#include <vector>

// Oboe-ресемплер объявляет своё пространство имён как oboe под NDK и как
// aaudio в остальных сборках (vendor/oboe-resampler/ResamplerDefinitions.h):
// алиас снимает разницу (этап 2 плана docs/260921_android_plan.md).
namespace resampler_ns = RESAMPLER_OUTER_NAMESPACE::resampler;

namespace
{

std::vector<float> resample_to_22050(const float *pcm, int n, int sample_rate)
{
    if (sample_rate == basic_pitch::constants::SAMPLE_RATE || n <= 0)
    {
        return std::vector<float>(pcm, pcm + n);
    }
    resampler_ns::MultiChannelResampler *resampler =
        resampler_ns::MultiChannelResampler::make(
            1, sample_rate, basic_pitch::constants::SAMPLE_RATE,
            resampler_ns::MultiChannelResampler::Quality::Best);
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

// Метаданные файла признаков (билд #38): трек и автор задаются перед
// прогоном (v2m_set_frames_meta; GUI — из состояния, CLI — из --author);
// пустая строка = поле в "meta" не пишется. Один прогон за раз — как
// g_last_report в v2m_jni.cpp.
static std::string g_meta_track;
static std::string g_meta_author;

// Локальное время прогона, ISO 8601
static std::string now_iso()
{
    const std::time_t t = std::time(nullptr);
    std::tm tm{};
    localtime_r(&t, &tm);
    char buf[32];
    std::strftime(buf, sizeof(buf), "%Y-%m-%dT%H:%M:%S", &tm);
    return buf;
}

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
    out->gate_db = -80.0f;
    out->low_cut_hz = 20.0f;
    out->high_cut_hz = 10000.0f;
    out->exp_comp = 0.0f;
    out->smoothing_window = 1;      // сглаживание кадров выключено (билд #52)
    out->pitch_median_window = 1;   // стабильность питча выключена (билд #54)
    return 1;
}

// Аудиообработка (билд #50): параметры v2m -> параметры ядра.
static basic_pitch::AudioEffectParams effects_from_params(const V2mParams *p)
{
    basic_pitch::AudioEffectParams e;
    e.gate_db = p->gate_db;
    e.low_cut_hz = p->low_cut_hz;
    e.high_cut_hz = p->high_cut_hz;
    e.exp_comp = p->exp_comp;
    return e;
}

// Shared transcription body. When frames_json is non-null the frame-feature
// summary is computed and returned as a malloc'ed string (see v2m.h).
static int do_transcribe(const float *pcm, int n_samples, int sample_rate,
                         const V2mParams *params, uint8_t **midi,
                         size_t *midi_len, char **err, char **frames_json)
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
        // «Обработка» (билд #50) — один раз, до модели: материал идёт в оба
        // прохода GUI уже обработанным.
        const std::string fx = basic_pitch::apply_audio_effects(
            audio, basic_pitch::constants::SAMPLE_RATE, effects_from_params(params));
        if (basic_pitch::g_verbose && !fx.empty())
        {
            std::cout << fx << std::endl;
        }
        basic_pitch::InferenceResult inference = basic_pitch::ort_inference(audio);

        // «Мелодика» (билд #52, п.5 приёмки #51; расширено билдом #53, п.2
        // приёмки #52): медианное сглаживание кадровых тензоров — контура
        // высоты (бенды/микротон) и активаций звучания (ноты). Выполняется до
        // снятия кадровых признаков, чтобы сводка описывала тот же материал,
        // что ушёл в сборку нот.
        basic_pitch::smooth_contours(inference.contours, params->smoothing_window);
        basic_pitch::smooth_frames(inference.notes, params->smoothing_window);
        if (basic_pitch::g_verbose && params->smoothing_window >= 3)
        {
            std::cout << "smoothing: " << params->smoothing_window << " frames"
                      << std::endl;
        }

        if (frames_json)
        {
            std::string fj = basic_pitch::frame_features_json(
                inference.notes, inference.onsets, inference.contours,
                g_meta_track, g_meta_author, now_iso());
            *frames_json = static_cast<char *>(std::malloc(fj.size() + 1));
            if (!*frames_json)
            {
                if (err)
                {
                    *err = strdup("out of memory");
                }
                return 0;
            }
            std::memcpy(*frames_json, fj.c_str(), fj.size() + 1);
        }

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
        hp.pitch_median_window = params->pitch_median_window;

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

int v2m_transcribe(const float *pcm, int n_samples, int sample_rate,
                   const V2mParams *params, uint8_t **midi, size_t *midi_len,
                   char **err)
{
    return do_transcribe(pcm, n_samples, sample_rate, params, midi, midi_len,
                         err, nullptr);
}

int v2m_transcribe_frames(const float *pcm, int n_samples, int sample_rate,
                          const V2mParams *params, uint8_t **midi,
                          size_t *midi_len, char **frames_json, char **err)
{
    return do_transcribe(pcm, n_samples, sample_rate, params, midi, midi_len,
                         err, frames_json);
}

int v2m_process_audio(const float *pcm, int n_samples, int sample_rate,
                      const V2mParams *params, float **out, int *out_n,
                      char **err)
{
    if (!pcm || n_samples <= 0 || sample_rate <= 0 || !out || !out_n)
    {
        if (err)
        {
            *err = strdup("invalid arguments");
        }
        return 0;
    }
    try
    {
        std::vector<float> audio = resample_to_22050(pcm, n_samples, sample_rate);
        if (params)
        {
            basic_pitch::apply_audio_effects(
                audio, basic_pitch::constants::SAMPLE_RATE,
                effects_from_params(params));
        }
        float *buf = static_cast<float *>(std::malloc(sizeof(float) * audio.size()));
        if (!buf)
        {
            if (err)
            {
                *err = strdup("out of memory");
            }
            return 0;
        }
        std::memcpy(buf, audio.data(), sizeof(float) * audio.size());
        *out = buf;
        *out_n = static_cast<int>(audio.size());
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

int v2m_spectro_params_default(V2mSpectroParams *out)
{
    if (!out)
    {
        return 0;
    }
    const basic_pitch::SpectrogramParams defaults;
    out->hop = defaults.hop;
    out->bands = defaults.bands;
    out->f_min = defaults.f_min;
    out->f_max = defaults.f_max;
    out->tilt_db_per_oct = defaults.tilt_db_per_oct;
    out->floor_db = defaults.floor_db;
    out->auto_ref = defaults.auto_ref ? 1 : 0;
    return 1;
}

int v2m_spectrogram(const float *pcm, int n_samples, int sample_rate,
                    const V2mSpectroParams *params, uint8_t **data,
                    int *out_frames, int *out_bands, char **err)
{
    if (!pcm || n_samples <= 0 || sample_rate <= 0 || !data || !out_frames ||
        !out_bands)
    {
        if (err)
        {
            *err = strdup("invalid arguments");
        }
        return 0;
    }
    try
    {
        std::vector<float> audio = resample_to_22050(pcm, n_samples, sample_rate);
        basic_pitch::SpectrogramParams sp;
        if (params)
        {
            if (params->hop > 0)
            {
                sp.hop = params->hop;
            }
            if (params->bands > 0)
            {
                sp.bands = params->bands;
            }
            if (params->f_min > 0.0f)
            {
                sp.f_min = params->f_min;
            }
            if (params->f_max > 0.0f)
            {
                sp.f_max = std::max(params->f_max, sp.f_min * 1.01f);
            }
            sp.tilt_db_per_oct = params->tilt_db_per_oct;
            if (params->floor_db < 0.0f)
            {
                sp.floor_db = params->floor_db;
            }
            sp.auto_ref = params->auto_ref != 0;
        }

        const basic_pitch::Spectrogram spec =
            basic_pitch::compute_spectrogram(audio, basic_pitch::constants::SAMPLE_RATE, sp);

        const size_t bytes = spec.data.size();
        uint8_t *buf = static_cast<uint8_t *>(std::malloc(bytes ? bytes : 1));
        if (!buf)
        {
            if (err)
            {
                *err = strdup("out of memory");
            }
            return 0;
        }
        if (bytes)
        {
            std::memcpy(buf, spec.data.data(), bytes);
        }
        *data = buf;
        *out_frames = spec.n_frames;
        *out_bands = spec.n_bands;
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

void v2m_set_frames_meta(const char *track, const char *author)
{
    g_meta_track = track ? track : "";
    g_meta_author = author ? author : "";
}

} // extern "C"
