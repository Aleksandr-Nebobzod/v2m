// JNI bridge: Kotlin (com.v2m.app.V2mEngine) -> v2m C library.
// Compiled into libv2m.so only (not into the CLI static link).
// Params are passed as a fixed 18-element double array (see V2mParams order
// in v2m.h: 17 knobs + verbose).
#include <jni.h>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <functional>
#include <string>
#include <unistd.h>
#include <vector>
#include "v2m.h"

namespace
{
std::string g_last_report;

// Run fn while capturing stdout (the pipeline's verbose report) and silencing
// stderr (ONNX "Schema error" noise). Restores the process streams afterwards.
std::string capture_verbose(std::function<void()> fn)
{
    int fd[2];
    if (pipe(fd) != 0)
    {
        fn();
        return {};
    }
    const int saved_out = dup(STDOUT_FILENO);
    const int saved_err = dup(STDERR_FILENO);
    const int devnull = open("/dev/null", O_WRONLY);
    dup2(fd[1], STDOUT_FILENO);
    dup2(devnull, STDERR_FILENO);
    close(fd[1]);
    fn();
    fflush(stdout);
    dup2(saved_out, STDOUT_FILENO);
    dup2(saved_err, STDERR_FILENO);
    close(saved_out);
    close(saved_err);
    close(devnull);
    std::string out;
    char buf[4096];
    ssize_t n;
    while ((n = read(fd[0], buf, sizeof(buf))) > 0)
    {
        out.append(buf, n);
    }
    close(fd[0]);
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
                            line.find("global shift:") != std::string::npos;
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
    const jdouble d[18] = {
        p.onset_threshold, p.frame_threshold,  p.min_note_len,
        p.energy_tol,      p.program,          p.velocity_compress,
        p.use_melodia_trick, p.include_pitch_bends,
        p.tempo_bpm,       p.quantize,         p.tolerance_ms,
        p.harmonize_merge, p.min_bend_bins,    p.global_shift,
        p.mode_snap,       p.verbose,
        p.time_sig_num,    p.time_sig_den,
    };
    jdoubleArray out = env->NewDoubleArray(18);
    env->SetDoubleArrayRegion(out, 0, 18, d);
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

extern "C" JNIEXPORT jboolean JNICALL
Java_com_v2m_app_V2mEngine_nativeMidiToMusicXml(JNIEnv *env, jobject,
                                                jbyteArray midi,
                                                jstring path,
                                                jint clef)
{
    const jsize len = env->GetArrayLength(midi);
    jbyte *mb = env->GetByteArrayElements(midi, nullptr);
    const char *p = env->GetStringUTFChars(path, nullptr);
    char *err = nullptr;
    const int ok = v2m_midi_to_musicxml(
        reinterpret_cast<const uint8_t *>(mb), static_cast<size_t>(len), p,
        clef, &err);
    env->ReleaseStringUTFChars(path, p);
    env->ReleaseByteArrayElements(midi, mb, JNI_ABORT);
    if (!ok)
    {
        report_error(env, err);
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}
