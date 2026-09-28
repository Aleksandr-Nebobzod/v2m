// Многопроходный STFT для обзора входного материала (билд #47).
// Согласовано с А.М. 2026-09-12: без вычитания гармоник и без ISTFT —
// полоса обслуживается тем проходом, у которого разрешение по частоте
// не грубее ширины полосы (короткое окно — ВЧ, длинное — НЧ).
#include "spectrogram.hpp"

#include <unsupported/Eigen/FFT>

#include <algorithm>
#include <cmath>
#include <complex>
#include <vector>

namespace basic_pitch
{
namespace
{
constexpr float kPi = 3.14159265358979323846f;
constexpr float kFftEps = 1e-9f;

// Границы и геометрические центры полос (лог. шкала от f_min до f_max).
void band_edges(const SpectrogramParams &params, std::vector<float> &edges,
                std::vector<float> &centers)
{
    const int n = std::max(1, params.bands);
    const float f_min = std::max(1.0f, params.f_min);
    const float f_max = std::max(f_min * 1.01f, params.f_max);
    const float ratio = f_max / f_min;

    edges.resize(static_cast<size_t>(n) + 1);
    centers.resize(static_cast<size_t>(n));
    for (int i = 0; i <= n; ++i)
    {
        edges[i] = f_min * std::pow(ratio, static_cast<float>(i) / static_cast<float>(n));
    }
    for (int i = 0; i < n; ++i)
    {
        centers[i] = std::sqrt(edges[i] * edges[i + 1]);
    }
}

// Максимум магнитуды в полосе [f_lo, f_hi). Сетка бинов равномерна
// (df = sr / window), поэтому диапазон бинов считается напрямую; если полоса
// уже одного бина (низ при коротком окне) — берётся ближайший бин.
float band_peak(const std::vector<std::complex<float>> &spec, int n_bins, int window,
                int sample_rate, float f_lo, float f_hi)
{
    const float bins_per_hz = static_cast<float>(window) / static_cast<float>(sample_rate);
    int k_lo = static_cast<int>(std::ceil(f_lo * bins_per_hz));
    int k_hi = static_cast<int>(std::floor(f_hi * bins_per_hz));
    k_lo = std::max(0, std::min(k_lo, n_bins - 1));
    k_hi = std::max(0, std::min(k_hi, n_bins - 1));
    if (k_hi < k_lo)
    {
        const float f_c = 0.5f * (f_lo + f_hi);
        k_lo = k_hi = std::max(0, std::min(n_bins - 1, static_cast<int>(std::lround(f_c * bins_per_hz))));
    }

    float peak = 0.0f;
    for (int k = k_lo; k <= k_hi; ++k)
    {
        peak = std::max(peak, std::abs(spec[static_cast<size_t>(k)]));
    }
    return peak;
}

// 99.5-й процентиль по грубой гистограмме (робастный верх шкалы).
float percentile_995(const std::vector<float> &values)
{
    float lo = values.front();
    float hi = values.front();
    for (float v : values)
    {
        lo = std::min(lo, v);
        hi = std::max(hi, v);
    }
    if (hi - lo < 1e-3f)
    {
        return hi;
    }

    constexpr int kBins = 1024;
    std::vector<int> hist(kBins, 0);
    const float scale = static_cast<float>(kBins - 1) / (hi - lo);
    for (float v : values)
    {
        int idx = static_cast<int>((v - lo) * scale);
        idx = std::max(0, std::min(kBins - 1, idx));
        ++hist[static_cast<size_t>(idx)];
    }

    const long long target = static_cast<long long>(values.size()) * 995 / 1000;
    long long acc = 0;
    for (int i = 0; i < kBins; ++i)
    {
        acc += hist[static_cast<size_t>(i)];
        if (acc >= target)
        {
            return lo + static_cast<float>(i) / scale;
        }
    }
    return hi;
}

} // namespace

Spectrogram compute_spectrogram(const std::vector<float> &audio, int sample_rate,
                                const SpectrogramParams &params)
{
    Spectrogram out;
    const int hop = params.hop > 0 ? params.hop : 256;
    const int n_samples = static_cast<int>(audio.size());

    std::vector<float> edges;
    band_edges(params, edges, out.band_hz);
    out.n_bands = static_cast<int>(out.band_hz.size());
    // Кадров — вверх (билд #56, OQ-25): целочисленное деление теряло хвост
    // короче hop (< 11.6 мс), и картинка была короче материала. Недостающие
    // сэмплы последнего кадра берутся нулями (см. чтение сэмплов ниже:
    // индекс за концом аудио → 0.0f).
    out.n_frames = (n_samples + hop - 1) / hop;
    out.data.assign(static_cast<size_t>(out.n_frames) * static_cast<size_t>(out.n_bands), 0);
    if (out.n_frames <= 0 || sample_rate <= 0)
    {
        out.n_frames = std::max(0, out.n_frames);
        return out;
    }

    // Проходы: окна не короче hop, по возрастанию, без повторов.
    std::vector<int> windows;
    for (int w : params.windows)
    {
        if (w >= hop)
        {
            windows.push_back(w);
        }
    }
    if (windows.empty())
    {
        windows.push_back(std::max(hop, 1024));
    }
    std::sort(windows.begin(), windows.end());
    windows.erase(std::unique(windows.begin(), windows.end()), windows.end());

    // Полоса обслуживается самым коротким окном, у которого df не грубее ширины
    // полосы; если такого нет — самым длинным (низ: полосы уже разрешения).
    std::vector<int> band_pass(out.n_bands, static_cast<int>(windows.size()) - 1);
    for (int b = 0; b < out.n_bands; ++b)
    {
        const float width = edges[static_cast<size_t>(b) + 1] - edges[static_cast<size_t>(b)];
        for (size_t w = 0; w < windows.size(); ++w)
        {
            if (static_cast<float>(sample_rate) / static_cast<float>(windows[w]) <= width)
            {
                band_pass[static_cast<size_t>(b)] = static_cast<int>(w);
                break;
            }
        }
    }

    std::vector<float> db(static_cast<size_t>(out.n_frames) * static_cast<size_t>(out.n_bands), -400.0f);
    std::vector<std::complex<float>> spec;
    std::vector<float> frame_in;
    Eigen::FFT<float> fft;

    for (size_t w = 0; w < windows.size(); ++w)
    {
        bool used = false;
        for (int b = 0; b < out.n_bands && !used; ++b)
        {
            used = band_pass[static_cast<size_t>(b)] == static_cast<int>(w);
        }
        if (!used)
        {
            continue;
        }

        const int window = windows[w];
        const int n_bins = window / 2; // бины 0..W/2-1 (без бина Найквиста)
        frame_in.resize(static_cast<size_t>(window));

        std::vector<float> hann(static_cast<size_t>(window));
        for (int i = 0; i < window; ++i)
        {
            hann[static_cast<size_t>(i)] =
                0.5f - 0.5f * std::cos(2.0f * kPi * static_cast<float>(i) / static_cast<float>(window - 1));
        }

        // Опорный уровень dBFS: синус амплитуды A даёт |X| = A*W/4 (окно Ханна).
        const float ref = static_cast<float>(window) / 4.0f;

        for (int f = 0; f < out.n_frames; ++f)
        {
            const long long start = static_cast<long long>(f) * hop - window / 2;
            for (int i = 0; i < window; ++i)
            {
                const long long idx = start + i;
                const float s = (idx >= 0 && idx < static_cast<long long>(n_samples))
                                    ? audio[static_cast<size_t>(idx)]
                                    : 0.0f;
                frame_in[static_cast<size_t>(i)] = s * hann[static_cast<size_t>(i)];
            }
            fft.fwd(spec, frame_in);

            for (int b = 0; b < out.n_bands; ++b)
            {
                if (band_pass[static_cast<size_t>(b)] != static_cast<int>(w))
                {
                    continue;
                }
                const float mag = band_peak(spec, n_bins, window, sample_rate,
                                            edges[static_cast<size_t>(b)],
                                            edges[static_cast<size_t>(b) + 1]);
                db[static_cast<size_t>(f) * out.n_bands + static_cast<size_t>(b)] =
                    20.0f * std::log10(mag / ref + kFftEps);
            }
        }
    }

    // Наклон отображения (дБ/окт относительно 1 кГц): вес картинки,
    // на аудио и на модель не влияет.
    for (int b = 0; b < out.n_bands; ++b)
    {
        const float tilt = params.tilt_db_per_oct * std::log2(out.band_hz[static_cast<size_t>(b)] / 1000.0f);
        for (int f = 0; f < out.n_frames; ++f)
        {
            db[static_cast<size_t>(f) * out.n_bands + static_cast<size_t>(b)] += tilt;
        }
    }

    // Верх шкалы: 99.5-й процентиль (адаптация к уровню записи; тихие записи
    // остаются читаемыми) либо абсолютный 0 dBFS.
    const float ref_db = params.auto_ref ? percentile_995(db) : 0.0f;
    const float floor_db = params.floor_db < 0.0f ? params.floor_db : -60.0f;
    const float span = -floor_db;
    for (size_t i = 0; i < out.data.size(); ++i)
    {
        const float v = std::clamp((db[i] - (ref_db + floor_db)) / span, 0.0f, 1.0f);
        out.data[i] = static_cast<uint8_t>(std::lround(v * 255.0f));
    }
    return out;
}

} // namespace basic_pitch
