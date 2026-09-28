// JNI bridge: Kotlin (com.v2m.app.V2mEngine) -> v2m C library.
// Compiled into libv2m.so only (not into the CLI static link).
// Params are passed as a fixed 23-element double array, in the order of
// V2mParams (libv2m/v2m.h) field by field: index 15 = verbose, 16/17 =
// time signature, 18..21 = the audio effects of билд #50, 22 = the contour
// median smoothing of билд #52.
#include <jni.h>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <functional>
#include <string>
#include <vector>
#include "v2m.h"
#include "v2m_posix.h" // pipe/dup/dup2/open/close/read: POSIX или MSVC (Windows)
// Захват звука через ALSA — только Linux (билд #43). На Android запись идёт
// через AudioRecord (этап 2 плана docs/260921_android_plan.md); в Windows-
// сборке нативного захвата нет (ALSA отсутствует, WASAPI не реализован —
// решение А.М. 2026-09-24), поэтому символы NativeCapture там не компилируются
// (тот же признак в CMakeLists: v2m_capture.cpp вне сборки).
#if !defined(__ANDROID__) && !defined(_WIN32)
#include <alsa/asoundlib.h>
#include "v2m_capture.h"
#endif

namespace
{
std::string g_last_report;
#if !defined(__ANDROID__) && !defined(_WIN32)
// Text of the last NativeCapture open failure (билд #43); read by Kotlin
// via nativeCaptureLastError(0). Kept even after the handle is freed.
std::string g_last_capture_error;
#endif
// Frame-features summary JSON of the last nativeTranscribeFrames call
// (билд #37); empty string when not requested or on failure.
std::string g_last_frames_json;

// Run fn while capturing stdout (the pipeline's verbose report) and silencing
// stderr (ONNX "Schema error" noise). Restores the process streams afterwards.
std::string capture_verbose(std::function<void()> fn)
{
    int fd[2];
    if (v2m_pipe(fd) != 0)
    {
        fn();
        return {};
    }
    const int saved_out = v2m_dup(STDOUT_FILENO);
    const int saved_err = v2m_dup(STDERR_FILENO);
    const int devnull = v2m_open(V2M_DEVNULL, O_WRONLY);
    v2m_dup2(fd[1], STDOUT_FILENO);
    v2m_dup2(devnull, STDERR_FILENO);
    v2m_close(fd[1]);
    fn();
    fflush(stdout);
    v2m_dup2(saved_out, STDOUT_FILENO);
    v2m_dup2(saved_err, STDERR_FILENO);
    v2m_close(saved_out);
    v2m_close(saved_err);
    v2m_close(devnull);
    std::string out;
    char buf[4096];
    v2m_ssize n;
    while ((n = v2m_read(fd[0], buf, sizeof(buf))) > 0)
    {
        out.append(buf, n);
    }
    v2m_close(fd[0]);
    return out;
}

// Keep only the informative lines for the GUI report
std::string filter_report(const std::string &raw)
{
    std::string out;
    size_t pos = 0;
    while (pos <= raw.size())
    {
        const size_t eol = raw.find('\n', pos);
        const std::string line = raw.substr(pos, eol == std::string::npos ? std::string::npos : eol - pos);
        const bool useful = line.find("tempo:") != std::string::npos ||
                            line.find("mode fit:") != std::string::npos ||
                            line.find("global shift:") != std::string::npos ||
                            line.find("audio fx:") != std::string::npos || // «Обработка» (билд #50)
                            line.find("smoothing:") != std::string::npos || // «Мелодика» (билд #52)
                            line.find("pitch median:") != std::string::npos; // «Мелодика» (билд #54)
        if (useful)
        {
            out += line + "\n";
        }
        if (eol == std::string::npos)
        {
            break;
        }
        pos = eol + 1;
    }
    return out;
}
} // namespace

static V2mParams params_from_doubles(JNIEnv *env, jdoubleArray arr)
{
    V2mParams p;
    v2m_params_default(&p);
    const jsize n = env->GetArrayLength(arr);
    if (n >= 16)
    {
        jdouble *d = env->GetDoubleArrayElements(arr, nullptr);
        p.onset_threshold = static_cast<float>(d[0]);
        p.frame_threshold = static_cast<float>(d[1]);
        p.min_note_len = static_cast<int>(d[2]);
        p.energy_tol = static_cast<int>(d[3]);
        p.program = static_cast<int>(d[4]);
        p.velocity_compress = static_cast<float>(d[5]);
        p.use_melodia_trick = static_cast<int>(d[6]);
        p.include_pitch_bends = static_cast<int>(d[7]);
        p.tempo_bpm = static_cast<float>(d[8]);
        p.quantize = static_cast<int>(d[9]);
        p.tolerance_ms = static_cast<float>(d[10]);
        p.harmonize_merge = static_cast<int>(d[11]);
        p.min_bend_bins = static_cast<int>(d[12]);
        p.global_shift = static_cast<float>(d[13]);
        p.mode_snap = static_cast<float>(d[14]);
        if (n >= 18)
        {
            p.time_sig_num = static_cast<int>(d[16]);
            p.time_sig_den = static_cast<int>(d[17]);
        }
        if (n >= 22) // «Обработка» (билд #50)
        {
            p.gate_db = static_cast<float>(d[18]);
            p.low_cut_hz = static_cast<float>(d[19]);
            p.high_cut_hz = static_cast<float>(d[20]);
            p.exp_comp = static_cast<float>(d[21]);
        }
        if (n >= 23) // «Мелодика»: сглаживание кадров (билд #52)
        {
            p.smoothing_window = static_cast<int>(d[22]);
        }
        if (n >= 24) // «Мелодика»: стабильность питча (билд #54)
        {
            p.pitch_median_window = static_cast<int>(d[23]);
        }
        p.verbose = static_cast<int>(d[15]);
        env->ReleaseDoubleArrayElements(arr, d, JNI_ABORT);
    }
    return p;
}

static void report_error(JNIEnv *env, char *err)
{
    if (err)
    {
        std::fprintf(stderr, "v2m: %s\n", err);
        v2m_free(err);
    }
    else
    {
        std::fprintf(stderr, "v2m: unknown error\n");
    }
    env->ExceptionClear();
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_v2m_app_V2mEngine_nativeParamsDefault(JNIEnv *env, jobject)
{
    V2mParams p;
    v2m_params_default(&p);
    // clang (NDK) rejects implicit narrowing int -> double in an initializer
    // list, so every field is widened explicitly (этап 2 плана Android).
    const auto asDouble = [](auto v) { return static_cast<jdouble>(v); };
    const jdouble d[24] = {
        asDouble(p.onset_threshold),   asDouble(p.frame_threshold),  asDouble(p.min_note_len),
        asDouble(p.energy_tol),        asDouble(p.program),          asDouble(p.velocity_compress),
        asDouble(p.use_melodia_trick), asDouble(p.include_pitch_bends),
        asDouble(p.tempo_bpm),         asDouble(p.quantize),         asDouble(p.tolerance_ms),
        asDouble(p.harmonize_merge),   asDouble(p.min_bend_bins),    asDouble(p.global_shift),
        asDouble(p.mode_snap),         asDouble(p.verbose),
        asDouble(p.time_sig_num),      asDouble(p.time_sig_den),
        asDouble(p.gate_db),           asDouble(p.low_cut_hz),       asDouble(p.high_cut_hz), asDouble(p.exp_comp),
        asDouble(p.smoothing_window),  asDouble(p.pitch_median_window),
    };
    jdoubleArray out = env->NewDoubleArray(24);
    env->SetDoubleArrayRegion(out, 0, 24, d);
    return out;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_v2m_app_V2mEngine_nativeTranscribe(JNIEnv *env, jobject,
                                            jfloatArray pcm, jint sr,
                                            jdoubleArray params)
{
    const jsize n = env->GetArrayLength(pcm);
    jfloat *pf = env->GetFloatArrayElements(pcm, nullptr);
    V2mParams p = params_from_doubles(env, params);
    p.verbose = 1; // capture the pipeline report for the GUI

    uint8_t *midi = nullptr;
    size_t len = 0;
    char *err = nullptr;
    int ok = 0;
    g_last_report = filter_report(capture_verbose([&]()
                                                  {
                                                      ok = v2m_transcribe(
                                                          pf, static_cast<int>(n),
                                                          sr, &p, &midi, &len,
                                                          &err);
                                                  }));
    env->ReleaseFloatArrayElements(pcm, pf, JNI_ABORT);
    if (!ok)
    {
        report_error(env, err);
        return nullptr;
    }
    jbyteArray out = env->NewByteArray(static_cast<jsize>(len));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(len),
                            reinterpret_cast<const jbyte *>(midi));
    v2m_free(midi);
    return out;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_v2m_app_V2mEngine_nativeLastReport(JNIEnv *env, jobject)
{
    return env->NewStringUTF(g_last_report.c_str());
}

// Transcription that also captures the frame-features summary JSON into
// g_last_frames_json (read back via nativeLastFramesJson). A separate
// symbol (not a changed nativeTranscribe): an outdated libv2m.so then
// fails loudly with UnsatisfiedLinkError instead of silently misreading
// the arguments.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_v2m_app_V2mEngine_nativeTranscribeFrames(JNIEnv *env, jobject,
                                                  jfloatArray pcm, jint sr,
                                                  jdoubleArray params)
{
    const jsize n = env->GetArrayLength(pcm);
    jfloat *pf = env->GetFloatArrayElements(pcm, nullptr);
    V2mParams p = params_from_doubles(env, params);
    p.verbose = 1; // capture the pipeline report for the GUI

    uint8_t *midi = nullptr;
    size_t len = 0;
    char *err = nullptr;
    char *frames = nullptr;
    int ok = 0;
    g_last_frames_json.clear();
    g_last_report = filter_report(capture_verbose([&]()
                                                  {
                                                      ok = v2m_transcribe_frames(
                                                          pf, static_cast<int>(n),
                                                          sr, &p, &midi, &len,
                                                          &frames, &err);
                                                  }));
    env->ReleaseFloatArrayElements(pcm, pf, JNI_ABORT);
    if (frames)
    {
        g_last_frames_json = frames;
        v2m_free(frames);
    }
    if (!ok)
    {
        report_error(env, err);
        return nullptr;
    }
    jbyteArray out = env->NewByteArray(static_cast<jsize>(len));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(len),
                            reinterpret_cast<const jbyte *>(midi));
    v2m_free(midi);
    return out;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_v2m_app_V2mEngine_nativeLastFramesJson(JNIEnv *env, jobject)
{
    return env->NewStringUTF(g_last_frames_json.c_str());
}

// Metadata (track name, author) for the "meta" of the next frame-features
// summary (билд #38); null = empty. A separate symbol on purpose: with an
// outdated libv2m.so the call fails loudly with UnsatisfiedLinkError
// instead of silently dropping the metadata.
extern "C" JNIEXPORT void JNICALL
Java_com_v2m_app_V2mEngine_nativeSetFramesMeta(JNIEnv *env, jobject,
                                               jstring track, jstring author)
{
    const char *t = track ? env->GetStringUTFChars(track, nullptr) : nullptr;
    const char *a = author ? env->GetStringUTFChars(author, nullptr) : nullptr;
    v2m_set_frames_meta(t, a);
    if (t)
    {
        env->ReleaseStringUTFChars(track, t);
    }
    if (a)
    {
        env->ReleaseStringUTFChars(author, a);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_v2m_app_V2mEngine_nativeMidiToMusicXml(JNIEnv *env, jobject,
                                                jbyteArray midi,
                                                jstring path,
                                                jint clef,
                                                jint fifths,
                                                jint anacrusis)
{
    const jsize len = env->GetArrayLength(midi);
    jbyte *mb = env->GetByteArrayElements(midi, nullptr);
    const char *p = env->GetStringUTFChars(path, nullptr);
    char *err = nullptr;
    const int ok = v2m_midi_to_musicxml(
        reinterpret_cast<const uint8_t *>(mb), static_cast<size_t>(len), p,
        clef, fifths, anacrusis, &err);
    env->ReleaseStringUTFChars(path, p);
    env->ReleaseByteArrayElements(midi, mb, JNI_ABORT);
    if (!ok)
    {
        report_error(env, err);
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

// «Обработка» без транскрипции (билд #50): тот же материал в 22050 Гц, какой
// получает модель, — GUI считает по нему обработанную спектрограмму. Отдельный
// символ: устаревшая libv2m.so падает явно (UnsatisfiedLinkError), а не
// молча возвращает сырьё.
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_v2m_app_V2mEngine_nativeProcessAudio(JNIEnv *env, jobject,
                                              jfloatArray pcm, jint sr,
                                              jdoubleArray params)
{
    const jsize n = env->GetArrayLength(pcm);
    jfloat *pf = env->GetFloatArrayElements(pcm, nullptr);
    V2mParams p = params_from_doubles(env, params);

    float *out = nullptr;
    int out_n = 0;
    char *err = nullptr;
    const int ok = v2m_process_audio(pf, static_cast<int>(n), sr, &p, &out,
                                     &out_n, &err);
    env->ReleaseFloatArrayElements(pcm, pf, JNI_ABORT);
    if (!ok)
    {
        report_error(env, err);
        return nullptr;
    }
    jfloatArray arr = env->NewFloatArray(static_cast<jsize>(out_n));
    if (!arr)
    {
        v2m_free(out);
        return nullptr;
    }
    env->SetFloatArrayRegion(arr, 0, static_cast<jsize>(out_n), out);
    v2m_free(out);
    return arr;
}

// Spectrogram overview of the input material (билд #47, GUI tab "Звук").
// Display only: it neither affects nor reflects the transcription pipeline.
// Layout of the returned array: "VSP1" + int32 frames + int32 bands
// (big-endian) + the frames*bands uint8 matrix (index = frame*bands + band,
// band 0 = f_min, 0 = silence, 255 = top of the scale). A separate symbol
// on purpose: an outdated libv2m.so fails loudly with UnsatisfiedLinkError
// instead of silently misreading the arguments.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_v2m_app_V2mEngine_nativeSpectrogram(JNIEnv *env, jobject,
                                             jfloatArray pcm, jint sr)
{
    const jsize n = env->GetArrayLength(pcm);
    jfloat *pf = env->GetFloatArrayElements(pcm, nullptr);

    uint8_t *data = nullptr;
    int frames = 0;
    int bands = 0;
    char *err = nullptr;
    const int ok = v2m_spectrogram(pf, static_cast<int>(n), sr, nullptr, &data,
                                   &frames, &bands, &err);
    env->ReleaseFloatArrayElements(pcm, pf, JNI_ABORT);
    if (!ok)
    {
        report_error(env, err);
        return nullptr;
    }

    std::vector<uint8_t> head(12, 0);
    std::memcpy(head.data(), "VSP1", 4);
    const int32_t meta[2] = {frames, bands};
    for (int i = 0; i < 2; ++i)
    {
        const uint32_t v = static_cast<uint32_t>(meta[i]);
        head[static_cast<size_t>(4 + i * 4)] = static_cast<uint8_t>(v >> 24);
        head[static_cast<size_t>(5 + i * 4)] = static_cast<uint8_t>(v >> 16);
        head[static_cast<size_t>(6 + i * 4)] = static_cast<uint8_t>(v >> 8);
        head[static_cast<size_t>(7 + i * 4)] = static_cast<uint8_t>(v);
    }

    jbyteArray out =
        env->NewByteArray(static_cast<jsize>(12) + static_cast<jsize>(frames) * bands);
    if (!out)
    {
        v2m_free(data);
        return nullptr;
    }
    env->SetByteArrayRegion(out, 0, 12,
                            reinterpret_cast<const jbyte *>(head.data()));
    env->SetByteArrayRegion(out, 12, static_cast<jsize>(frames) * bands,
                            reinterpret_cast<const jbyte *>(data));
    v2m_free(data);
    return out;
}

#if !defined(__ANDROID__) && !defined(_WIN32)
// --- NativeCapture: ALSA recorder (билд #43, javax.sound removed) ---
// One symbol per method on purpose: with an outdated libv2m.so the call
// fails loudly with UnsatisfiedLinkError instead of behaving wrongly.
// Handle is the opaque v2m_capture pointer as jlong (0 = no capture).

extern "C" JNIEXPORT jlong JNICALL
Java_com_v2m_app_NativeCapture_nativeCaptureOpen(JNIEnv *env, jobject,
                                                 jstring device, jint rate,
                                                 jint channels)
{
    const char *dev =
        device ? env->GetStringUTFChars(device, nullptr) : nullptr;
    char *err = nullptr;
    v2m_capture *h = v2m_capture_open(dev, static_cast<unsigned>(rate),
                                      static_cast<unsigned>(channels), 2048,
                                      &err);
    if (dev)
    {
        env->ReleaseStringUTFChars(device, dev);
    }
    if (!h)
    {
        g_last_capture_error = err ? err : "open failed";
        std::fprintf(stderr, "v2m capture: %s\n", g_last_capture_error.c_str());
        if (err)
        {
            v2m_free(err);
        }
        return 0;
    }
    return reinterpret_cast<jlong>(h);
}

// Read into buf up to maxFrames; returns frames read (>0), 0 (stop flag or
// poll timeout) or a negative error (text via nativeCaptureLastError).
extern "C" JNIEXPORT jint JNICALL
Java_com_v2m_app_NativeCapture_nativeCaptureRead(JNIEnv *env, jobject,
                                                 jlong handle,
                                                 jshortArray buf,
                                                 jint maxFrames)
{
    auto *h = reinterpret_cast<v2m_capture *>(handle);
    if (!h)
    {
        return -EINVAL;
    }
    const jsize cap = env->GetArrayLength(buf);
    const jsize want = maxFrames < 0 ? 0 : maxFrames;
    const jsize n = want < cap ? want : cap;
    if (n == 0)
    {
        return 0;
    }
    std::vector<short> tmp(static_cast<size_t>(n));
    const int got = v2m_capture_read(h, tmp.data(), static_cast<unsigned>(n));
    if (got > 0)
    {
        env->SetShortArrayRegion(buf, 0, got, tmp.data());
    }
    return got;
}

extern "C" JNIEXPORT void JNICALL
Java_com_v2m_app_NativeCapture_nativeCaptureStop(JNIEnv *, jobject,
                                                 jlong handle)
{
    v2m_capture_stop(reinterpret_cast<v2m_capture *>(handle));
}

extern "C" JNIEXPORT void JNICALL
Java_com_v2m_app_NativeCapture_nativeCaptureClose(JNIEnv *, jobject,
                                                  jlong handle)
{
    v2m_capture_close(reinterpret_cast<v2m_capture *>(handle));
}

// Last error of the handle, or of the last failed open when handle == 0.
extern "C" JNIEXPORT jstring JNICALL
Java_com_v2m_app_NativeCapture_nativeCaptureLastError(JNIEnv *env, jobject,
                                                      jlong handle)
{
    auto *h = reinterpret_cast<v2m_capture *>(handle);
    const char *s =
        h ? v2m_capture_last_error(h) : g_last_capture_error.c_str();
    return env->NewStringUTF(s);
}

// Prove the JNI symbols and the asound link through the public ABI, without
// touching a real device: the probe name cannot exist, so open fails fast.
extern "C" JNIEXPORT jstring JNICALL
Java_com_v2m_app_NativeCapture_nativeCaptureSelfTest(JNIEnv *env, jobject)
{
    char *err = nullptr;
    v2m_capture *h =
        v2m_capture_open("v2m_selftest_no_such_device", 22050, 1, 2048, &err);
    if (h)
    {
        v2m_capture_close(h); // unexpected: the probe device exists
        return env->NewStringUTF("alsa-ok (probe open unexpectedly succeeded)");
    }
    if (err)
    {
        v2m_free(err); // expected open failure — ALSA is functional
    }
    const std::string ok = "alsa-ok: " + std::string(SND_LIB_VERSION_STR);
    return env->NewStringUTF(ok.c_str());
}
#endif // !__ANDROID__ && !_WIN32
