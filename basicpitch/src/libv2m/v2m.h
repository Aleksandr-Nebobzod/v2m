/* v2m: C ABI of the transcription pipeline (audio -> MIDI bytes -> MusicXML).
 * Plain C interface so that Kotlin (JNI) and other bindings can call it.
 * All knobs are in V2mParams; defaults match the CLI (see README.md).
 */
#ifndef V2M_H
#define V2M_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct
{
    float onset_threshold;    /* 0..1, default 0.5  */
    float frame_threshold;    /* 0..1, default 0.3  */
    int min_note_len;         /* frames, default 11 */
    int energy_tol;           /* frames, default 11 */
    int program;              /* MIDI program 0..127, default 4 */
    float velocity_compress;  /* 0..1, default 0 (off) */
    int use_melodia_trick;    /* 0/1, default 1 */
    int include_pitch_bends;  /* 0/1, default 1 */
    float tempo_bpm;          /* 0 = auto, default 0 */
    int quantize;             /* 0 off, 1 auto, 2 beat, 3 eighth, 4 sixteenth */
    float tolerance_ms;       /* default 40 */
    int time_sig_num;         /* 0 = auto, default 0 */
    int time_sig_den;         /* 0 = auto, default 0 */
    int harmonize_merge;      /* semitones, 0 = off */
    int min_bend_bins;        /* contour bins, 0 = off */
    float global_shift;       /* 0..1, 0 = off */
    float mode_snap;          /* 0..1, 0 = off */
    int verbose;              /* 0/1, default 0: progress messages to stdout */
    /* «Обработка» (билд #50): аудиоэффекты применяются к сигналу один раз за
     * прогон, после ресемпла и до модели. Крайние значения = выключено. */
    float gate_db;            /* -80..0, порог шумоподавителя; -80 = off */
    float low_cut_hz;         /* срез НЧ (high-pass), Гц; <= 20 = off */
    float high_cut_hz;        /* срез ВЧ (low-pass), Гц; >= 10000 = off */
    float exp_comp;           /* -100..+100 %: <0 компрессор, >0 экспандер */
    /* «Мелодика» (билд #52, п.5 приёмки #51): медианное сглаживание кадров
     * модели — нечётное окно в кадрах (1 кадр ≈ 11.6 мс), 3..15;
     * 1 = выключено (дефолт). Фильтрует контур высоты (бенды/микротон,
     * центроиды, гармонизация) и активации звучания (границы и длительность
     * нот), см. contour_smoothing.cpp. */
    int smoothing_window;
    /* «Стабильность питча» (билд #54, Р19): медианный пост-фильтр списка нот —
     * удаляет короткие (< 174 мс) ноты, выбивающиеся из медианы высот соседей;
     * 1 = выключено (дефолт), нечётные 3..7 — ширина окна в нотах
     * (harmonize.cpp:stabilize_note_pitches). */
    int pitch_median_window;
} V2mParams;

/* Fill out with CLI defaults. Returns 1. */
int v2m_params_default(V2mParams *out);

/* Transcribe mono PCM audio (any sample rate; resampled internally to
 * 22050 Hz) to standard MIDI file bytes.
 * On success: returns 1, *midi is a malloc'ed buffer (release with v2m_free).
 * On failure: returns 0, *err is a malloc'ed message (release with v2m_free).
 * The buffer is writable and may be null-terminated for convenience.
 */
int v2m_transcribe(const float *pcm, int n_samples, int sample_rate,
                   const V2mParams *params, uint8_t **midi, size_t *midi_len,
                   char **err);

/* Same as v2m_transcribe, and additionally returns the frame-features
 * summary JSON (compact statistics of the per-frame note/onset/contour
 * model output — see frame_features.cpp) in *frames_json, a malloc'ed
 * string (release with v2m_free). On failure *frames_json stays untouched.
 */
int v2m_transcribe_frames(const float *pcm, int n_samples, int sample_rate,
                          const V2mParams *params, uint8_t **midi,
                          size_t *midi_len, char **frames_json, char **err);

/* Аудиообработка «Обработки» (билд #50) без транскрипции: тот же ресемпл в
 * 22050 Гц и те же эффекты, что применяет v2m_transcribe. Нужна GUI, чтобы
 * получить обработанный материал для спектрограммы ровно таким же, каким его
 * видит модель (вкладка «Спектр»).
 * On success: returns 1, *out is a malloc'ed float buffer at 22050 Hz
 * (release with v2m_free), *out_n its length in samples.
 * On failure: returns 0, *err is a malloc'ed message (release with v2m_free).
 */
int v2m_process_audio(const float *pcm, int n_samples, int sample_rate,
                      const V2mParams *params, float **out, int *out_n,
                      char **err);

/* Spectrogram overview of the input material (билд #47): multi-resolution
 * STFT over the raw audio, log-spaced bands, no synthesis. Display only —
 * it neither affects nor reflects the transcription pipeline.
 */
typedef struct
{
    int hop;                 /* samples, 0 = 256 (model frame, 11.6 ms) */
    int bands;               /* 0 = 120 */
    float f_min;             /* Hz, 0 = 10 */
    float f_max;             /* Hz, 0 = 10000 */
    float tilt_db_per_oct;   /* display tilt from 1 kHz; 0 = none */
    float floor_db;          /* dynamic range below the top, 0 = -60 */
    int auto_ref;            /* 1: top of scale = 99.5th percentile (default),
                              * 0: absolute dBFS */
} V2mSpectroParams;

/* Fill out with defaults (120 bands 10 Hz..10 kHz, tilt 3 dB/oct, adaptive
 * 60 dB scale). Returns 1. */
int v2m_spectro_params_default(V2mSpectroParams *out);

/* Compute the spectrogram of mono PCM audio (any sample rate; resampled
 * internally to 22050 Hz).
 * On success: returns 1, *data is a malloc'ed n_frames*n_bands matrix
 * (release with v2m_free), index = frame*n_bands + band, band 0 = f_min
 * (lowest frequency), 0 = silence, 255 = top of the scale.
 * On failure: returns 0, *err is a malloc'ed message (release with v2m_free).
 */
int v2m_spectrogram(const float *pcm, int n_samples, int sample_rate,
                    const V2mSpectroParams *params, uint8_t **data,
                    int *out_frames, int *out_bands, char **err);

/* Convert MIDI file bytes to a MusicXML 3.1 file at out_path.
 * clef: 0 = G (treble), 1 = F (bass). fifths: the <key><fifths> value
 * (-7..7, 0 = no key signature). anacrusis_eighths: затакт — first measure
 * is a partial one of N eighth notes (0 = none); the time signature is
 * declared in the first full measure. Pitch bends are dropped by design
 * (see midi2musicxml). Returns 1 on success, 0 on failure (*err malloc'ed,
 * release with v2m_free). */
int v2m_midi_to_musicxml(const uint8_t *midi, size_t len, const char *out_path,
                         int clef, int fifths, int anacrusis_eighths,
                         char **err);

/* Release buffers returned by the functions above. */
void v2m_free(void *p);

/* Frame-feature metadata (билд #38) for the next v2m_transcribe_frames
 * call: the "track" name (e.g. the input file without extension) and the
 * "author" (empty strings = the field is omitted from "meta"). Copies both
 * strings; a timestamp of the run itself is added by the engine. */
void v2m_set_frames_meta(const char *track, const char *author);

#ifdef __cplusplus
}
#endif

#endif /* V2M_H */
