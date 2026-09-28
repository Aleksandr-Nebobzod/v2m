// Аудиообработка входа (билд #50). Порядок канала: срезы НЧ/ВЧ → gate →
// компрессор-экспандер. Сигнал обрабатывается один раз, до модели — см.
// audio_effects.hpp.
#include "audio_effects.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>

namespace basic_pitch
{
namespace
{

constexpr double kPiD = 3.14159265358979323846;
// «Выключено» — крайние положения ручек; согласовано с v2m.h и GUI.
constexpr float kGateOffDb = -80.0f;
constexpr float kLowCutOffHz = 20.0f;
constexpr float kHighCutOffHz = 10000.0f;
// 4-й порядок = две секции Баттерворта (24 дБ/окт).
constexpr double kQ1 = 0.54119610;
constexpr double kQ2 = 1.30656296;
constexpr float kSilence = 1e-9f;
// Билд #52 (п.1 приёмки #51): предел подъёма тихого материала у
// компрессора — выравнивание «тихие → средние» без вытаскивания шума
// между нотами.
constexpr float kBoostMaxDb = 24.0f;
// Потолок выходного отсчёта компрессора, дБ: подъём тихого материала не
// должен уводить сигнал за шкалу PCM (см. apply_exp_comp).
constexpr float kOutCeilingDb = -1.0f;

float db_of(float lin) { return 20.0f * std::log10(std::max(lin, kSilence)); }

/** Коэффициент однополюсного сглаживания для времени t с на частоте fs. */
float one_pole(float seconds, int fs)
{
    return 1.0f - std::exp(-1.0f / (seconds * static_cast<float>(fs)));
}

std::string fmt(const char *format, double v)
{
    char buf[32];
    std::snprintf(buf, sizeof(buf), format, v);
    return buf;
}

std::string hz_text(float hz)
{
    return hz >= 1000.0f ? fmt("%.1f kHz", hz / 1000.0) : fmt("%.0f Hz", hz);
}

/** Биквад RBJ (cookbook), transposed direct form II. */
struct Biquad
{
    double b0 = 1.0, b1 = 0.0, b2 = 0.0, a1 = 0.0, a2 = 0.0;
    double z1 = 0.0, z2 = 0.0;

    float process(float x)
    {
        const double y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return static_cast<float>(y);
    }

    static Biquad low_pass(double fs, double f0, double q)
    {
        const double w = 2.0 * kPiD * f0 / fs;
        const double cw = std::cos(w), alpha = std::sin(w) / (2.0 * q);
        const double a0 = 1.0 + alpha;
        Biquad b;
        b.b0 = (1.0 - cw) / 2.0 / a0;
        b.b1 = (1.0 - cw) / a0;
        b.b2 = b.b0;
        b.a1 = -2.0 * cw / a0;
        b.a2 = (1.0 - alpha) / a0;
        return b;
    }

    static Biquad high_pass(double fs, double f0, double q)
    {
        const double w = 2.0 * kPiD * f0 / fs;
        const double cw = std::cos(w), alpha = std::sin(w) / (2.0 * q);
        const double a0 = 1.0 + alpha;
        Biquad b;
        b.b0 = (1.0 + cw) / 2.0 / a0;
        b.b1 = -(1.0 + cw) / a0;
        b.b2 = b.b0;
        b.a1 = -2.0 * cw / a0;
        b.a2 = (1.0 - alpha) / a0;
        return b;
    }
};

/** Срезы НЧ (high-pass) и ВЧ (low-pass), по 24 дБ/окт. */
void apply_cuts(std::vector<float> &x, int fs, float low_hz, float high_hz)
{
    const bool do_low = low_hz > kLowCutOffHz;
    const bool do_high = high_hz < kHighCutOffHz;
    if (!do_low && !do_high)
    {
        return;
    }
    // Выше 0.45·fs биквад теряет смысл (варпинг) — срез поджимаем.
    const double f_max = 0.45 * fs;
    Biquad hp1, hp2, lp1, lp2;
    if (do_low)
    {
        const double f0 = std::clamp(static_cast<double>(low_hz), 10.0, f_max);
        hp1 = Biquad::high_pass(fs, f0, kQ1);
        hp2 = Biquad::high_pass(fs, f0, kQ2);
    }
    if (do_high)
    {
        const double f0 = std::clamp(static_cast<double>(high_hz), 10.0, f_max);
        lp1 = Biquad::low_pass(fs, f0, kQ1);
        lp2 = Biquad::low_pass(fs, f0, kQ2);
    }
    for (float &v : x)
    {
        float y = v;
        if (do_low)
        {
            y = hp2.process(hp1.process(y));
        }
        if (do_high)
        {
            y = lp2.process(lp1.process(y));
        }
        v = y;
    }
}

/** Шумоподавитель: ниже порога сигнал глушится. Огибающая — пиковая (атака
 *  мгновенная, спад 50 мс), усиление — сглаженное (открытие 2 мс, закрытие
 *  30 мс), перегиб ±1.5 дБ — чтобы не «дребезжал» на пороге. */
void apply_gate(std::vector<float> &x, int fs, float thr_db)
{
    const float env_rel = std::exp(-1.0f / (0.050f * static_cast<float>(fs)));
    const float open_a = one_pole(0.002f, fs);
    const float close_a = one_pole(0.030f, fs);
    constexpr float knee = 3.0f;
    float env = 0.0f, gain = 0.0f;
    for (float &v : x)
    {
        const float a = std::fabs(v);
        env = a > env ? a : env * env_rel;
        const float target = std::clamp((db_of(env) - thr_db) / knee + 0.5f, 0.0f, 1.0f);
        gain += (target - gain) * (target > gain ? open_a : close_a);
        v *= gain;
    }
}

/** Компрессор-экспандер: величина = модуль параметра (0..1), знак задаёт
 *  направление. Порог — на 12 дБ ниже опорного уровня (95-й процентиль
 *  огибающей), т.е. кривая работает с верхними 12 дБ материала; колено
 *  ±3 дБ; отношение 1..7 (билд #51, приёмка #50 п.1: «удвоить силу
 *  эффектов» — рост отношения на единицу параметра удвоен, 3 → 6, так
 *  что прежнему максимуму отвечает середина шкалы). Компрессор возвращает
 *  опорный уровень усилением (makeup), экспандер — нет. Возвращает false,
 *  если материал тише -80 дБ (обрабатывать нечего).
 *
 *  Билд #52 (п.1 приёмки #51: «тихие стали средними и громкие стали
 *  средними»): у компрессора материал НИЖЕ порога теперь поднимается к
 *  порогу с силой параметра (было: не сжимался и получал только makeup —
 *  разброс громкости почти сохранялся), подъём ограничен kBoostMaxDb;
 *  выше порога кривая прежняя. Экспандер не изменён. */
bool apply_exp_comp(std::vector<float> &x, int fs, float pct)
{
    const float amount = std::clamp(std::fabs(pct) / 100.0f, 0.0f, 1.0f);
    const bool compress = pct < 0.0f;
    const float ratio = 1.0f + 6.0f * amount;
    const float slope = 1.0f - 1.0f / ratio;
    const float atk = one_pole(0.010f, fs); // детектор: атака 10 мс
    const float rel = one_pole(0.150f, fs); // спад 150 мс

    // Проход 1: гистограмма уровней (−100..0 дБ, шаг 0.25 дБ).
    constexpr int kBins = 400;
    int hist[kBins] = {0};
    double rms2 = 0.0;
    for (float v : x)
    {
        const double p = static_cast<double>(v) * v;
        rms2 += (p - rms2) * (p > rms2 ? atk : rel);
        const int bin = static_cast<int>((db_of(static_cast<float>(std::sqrt(rms2))) + 100.0f) * 4.0f);
        ++hist[std::clamp(bin, 0, kBins - 1)];
    }
    const int tail = std::max(1, static_cast<int>(x.size()) / 20); // верхние 5 %
    int acc = 0, ref_bin = 0;
    for (int i = kBins - 1; i >= 0; --i)
    {
        acc += hist[i];
        if (acc >= tail)
        {
            ref_bin = i;
            break;
        }
    }
    const float ref_db = static_cast<float>(ref_bin) * 0.25f - 100.0f;
    if (ref_db < -80.0f)
    {
        return false; // тишина
    }
    const float thr_db = ref_db - 12.0f;
    const float knee = 3.0f;

    // Проход 2: усиление по кривой; у компрессора — возврат опорного уровня.
    const float makeup_db = compress ? (ref_db - thr_db) * slope : 0.0f;
    const float makeup = std::exp(makeup_db * 0.11512925f); // 10^(x/20)
    rms2 = 0.0;
    for (float &v : x)
    {
        const double p = static_cast<double>(v) * v;
        rms2 += (p - rms2) * (p > rms2 ? atk : rel);
        const float over = db_of(static_cast<float>(std::sqrt(rms2))) - thr_db;
        float g;
        if (compress)
        {
            // Выравнивание (билд #52): ниже порога материал поднимается к
            // порогу с силой amount (ограничение kBoostMaxDb — шум между
            // нотами не выходит вперёд), выше — сжатие с отношением.
            const float g_hi = -knee * slope;
            const float g_lo = knee * amount;
            if (over >= knee)
            {
                g = -over * slope;
            }
            else if (over <= -knee)
            {
                g = -over * amount;
            }
            else // колено: линейная склейка ветвей сжатия и подъёма
            {
                g = g_lo + (g_hi - g_lo) * (over + knee) / (2.0f * knee);
            }
            g = std::min(g, kBoostMaxDb);
            // Потолок выхода (билд #52, п.1 приёмки #51): детектор (атака
            // 10 мс) отстаёт от атаки ноты, поэтому подъём тихого материала
            // выводил мгновенные отсчёты за шкалу — замер: пик до 10, до
            // 5.6 % отсчётов за ±1. Ограничение снижает только подъём.
            g = std::min(g, kOutCeilingDb - db_of(std::fabs(v)) - makeup_db);
        }
        else if (over >= knee)
        {
            g = 0.0f;
        }
        else if (over <= -knee)
        {
            g = over * (ratio - 1.0f);
        }
        else // колено: квадратичная склейка ветвей экспандера
        {
            const float t = knee - over;
            g = -(ratio - 1.0f) * t * t / (4.0f * knee);
        }
        g = std::max(g, -48.0f);
        v *= std::exp(g * 0.11512925f) * makeup;
    }
    return true;
}

} // namespace

std::string apply_audio_effects(std::vector<float> &x, int sample_rate,
                                const AudioEffectParams &p)
{
    if (x.empty() || sample_rate <= 0)
    {
        return {};
    }
    std::string report;
    const auto add = [&report](const std::string &s)
    {
        report += report.empty() ? s : ", " + s;
    };

    if (p.low_cut_hz > kLowCutOffHz || p.high_cut_hz < kHighCutOffHz)
    {
        apply_cuts(x, sample_rate, p.low_cut_hz, p.high_cut_hz);
        if (p.low_cut_hz > kLowCutOffHz)
        {
            add("low-cut " + hz_text(p.low_cut_hz));
        }
        if (p.high_cut_hz < kHighCutOffHz)
        {
            add("high-cut " + hz_text(p.high_cut_hz));
        }
    }
    if (p.gate_db > kGateOffDb)
    {
        apply_gate(x, sample_rate, p.gate_db);
        add("gate " + fmt("%.0f dB", p.gate_db));
    }
    if (std::fabs(p.exp_comp) >= 1.0f)
    {
        const bool compress = p.exp_comp < 0.0f;
        if (apply_exp_comp(x, sample_rate, p.exp_comp))
        {
            add(std::string(compress ? "comp " : "exp ") +
                fmt("%.0f%%", std::fabs(static_cast<double>(p.exp_comp))));
        }
    }
    return report.empty() ? std::string() : "audio fx: " + report;
}

} // namespace basic_pitch
