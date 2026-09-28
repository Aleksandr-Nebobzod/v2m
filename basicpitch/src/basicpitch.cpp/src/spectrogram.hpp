// Многопроходный STFT для обзора входного материала (билд #47).
// Согласовано с А.М. 2026-09-12: вместо вычитания гармоник — multi-resolution
// STFT: каждая логарифмическая полоса берётся из того прохода, где она
// разрешена по частоте (короткое окно — высокие частоты, длинное — низкие).
// Синтеза нет, результат — только для отображения ("Звук" в GUI / .pgm в CLI).
#ifndef LIBV2M_SPECTROGRAM_HPP
#define LIBV2M_SPECTROGRAM_HPP

#include <cstdint>
#include <vector>

namespace basic_pitch
{

struct SpectrogramParams
{
    int hop = 256;              // сдвиг окна в сэмплах (= кадр модели, 11.6 мс)
    int bands = 120;            // число логарифмических полос
    float f_min = 10.0f;        // нижняя граница обзора, Гц
    float f_max = 10000.0f;     // верхняя граница обзора, Гц
    float tilt_db_per_oct = 3.0f; // наклон отображения (дБ/окт от 1 кГц); 0 = без
    float floor_db = -60.0f;    // динамический диапазон шкалы вниз от верха, дБ
    bool auto_ref = true;       // true: верх шкалы = 99.5-й процентиль (иначе 0 dBFS)
    std::vector<int> windows{256, 1024, 4096}; // проходы, сэмплов
};

struct Spectrogram
{
    int n_frames = 0;                 // кадров (по времени)
    int n_bands = 0;                  // полос (по частоте)
    std::vector<float> band_hz;       // центры полос, n_bands (полоса 0 = f_min)
    std::vector<uint8_t> data;        // n_frames*n_bands; 0 = тишина, 255 = верх шкалы

    // Значение для кадра f и полосы b.
    uint8_t at(int f, int b) const { return data[static_cast<size_t>(f) * n_bands + b]; }
};

// audio — моно, sample_rate Гц (в libv2m — уже 22050).
Spectrogram compute_spectrogram(const std::vector<float> &audio, int sample_rate,
                                const SpectrogramParams &params = SpectrogramParams());

} // namespace basic_pitch

#endif // LIBV2M_SPECTROGRAM_HPP
