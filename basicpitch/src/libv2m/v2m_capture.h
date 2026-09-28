// Minimal ALSA capture for the v2m recorder (билд #43, replaces javax.sound).
// A separate header on purpose: v2m.h is the transcription contract shared
// with the CLI; capture is a desktop-only subsystem (Android will have its
// own native backend). S16_LE mono at the model rate (22050 Hz).
#pragma once

typedef struct v2m_capture v2m_capture;

// Open a capture device ("default" = Pulse default source, like tools/rec).
// The device must provide exactly rate x channels x S16_LE — otherwise the
// open fails (no resampling here; the Kotlin side guarantees model params).
// Returns NULL on failure; *err is malloc'ed (free with v2m_free).
v2m_capture *v2m_capture_open(const char *device, unsigned rate,
                              unsigned channels, unsigned period_frames,
                              char **err);

// Read up to max_frames S16_LE frames into buf. Returns:
//  > 0 — frames read;
//    0 — stop flag set OR poll timeout (caller checks its own exit
//        conditions; no separate is-stopped query needed);
//  < 0 — fatal error (text in v2m_capture_last_error). xruns recover inside.
int v2m_capture_read(v2m_capture *h, short *buf, unsigned max_frames);

// Set the stop flag only — safe from any thread (no ALSA calls cross-thread).
void v2m_capture_stop(v2m_capture *h);

const char *v2m_capture_last_error(const v2m_capture *h);

// Drop + close + free. Call from the thread that finished reading.
void v2m_capture_close(v2m_capture *h);
