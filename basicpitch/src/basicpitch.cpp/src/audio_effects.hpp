// Аудиообработка входа (билд #50): шумоподавитель (gate), срезы НЧ/ВЧ
// (high/low-cut) и компрессор-экспандер. Место в пайплайне — после ресемпла,
// до модели: сигнал обрабатывается один раз за прогон, поэтому его видят оба
// прохода GUI («Кванты» и «Тоны»). Ручки и семантика «выключено» — V2mParams
// (libv2m/v2m.h), раздел «Обработка» в docs/brd.md (Р16).
#ifndef LIBV2M_AUDIO_EFFECTS_HPP
#define LIBV2M_AUDIO_EFFECTS_HPP

#include <string>
#include <vector>

namespace basic_pitch
{

/** Параметры «Обработки». Крайние значения = эффект выключен. */
struct AudioEffectParams
{
    float gate_db = -80.0f;       // порог шумоподавителя, дБ; -80 = выкл
    float low_cut_hz = 20.0f;     // срез НЧ (high-pass), Гц; <= 20 = выкл
    float high_cut_hz = 10000.0f; // срез ВЧ (low-pass), Гц; >= 10000 = выкл
    float exp_comp = 0.0f;        // -100..+100 %: <0 компрессор, >0 экспандер
};

/** Применяет эффекты к моно PCM на месте. sample_rate — частота буфера
 *  (в пайплайне — 22050 Гц). Возвращает строку отчёта для журнала
 *  («audio fx: ...»); пустая строка — всё выключено, сигнал не тронут. */
std::string apply_audio_effects(std::vector<float> &x, int sample_rate,
                                const AudioEffectParams &p);

} // namespace basic_pitch

#endif
