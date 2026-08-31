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
    int harmonize_merge;      /* semitones, 0 = off */
    int min_bend_bins;        /* contour bins, 0 = off */
    float global_shift;       /* 0..1, 0 = off */
    float mode_snap;          /* 0..1, 0 = off */
    int verbose;              /* 0/1, default 0: progress messages to stdout */
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

/* Convert MIDI file bytes to a MusicXML 3.1 file at out_path.
 * Pitch bends are dropped by design (see midi2musicxml). Returns 1 on success,
 * 0 on failure (*err malloc'ed, release with v2m_free). */
int v2m_midi_to_musicxml(const uint8_t *midi, size_t len, const char *out_path,
                         char **err);

/* Release buffers returned by the functions above. */
void v2m_free(void *p);

#ifdef __cplusplus
}
#endif

#endif /* V2M_H */
