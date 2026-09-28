// Медианное сглаживание кадровых тензоров модели по времени (билд #52, п.5
// приёмки #51; расширено билдом #53, п.2 приёмки #52: «медианное сглаживание
// (хоть 3, хоть 15) не влияет на выходной результат»).
//
// Причина, по которой сглаживание не было видно: контур высоты используется
// движком только для питч-бендов (add_pitch_bends) и кадровых признаков, а
// при выключенных бендах («includePitchBends»: false) он на ноты не влияет
// вовсе. Активации звучания (notes) — тот тензор, из которого собираются
// ноты (границы, длина, амплитуда), поэтому сглаживание применяется и к ним.
#include "basicpitch.hpp"

#include <algorithm>
#include <vector>

namespace basic_pitch
{

// Медиана по времени в окне `window` кадров, независимо для каждого столбца
// (частотного бина); границы окна — краевое значение.
static void median_time(Eigen::Tensor2dXf &tensor, int window)
{
    const int n_frames = static_cast<int>(tensor.dimension(0));
    const int n_bins = static_cast<int>(tensor.dimension(1));
    if (window < 3 || (window % 2) == 0 || n_frames < 3 || n_bins <= 0)
    {
        return; // выключено или нет данных: без изменений
    }
    const int half = window / 2;
    const Eigen::Tensor2dXf src = tensor; // копия до фильтрации
    std::vector<float> buf(static_cast<size_t>(window));
    for (int t = 0; t < n_frames; ++t)
    {
        for (int f = 0; f < n_bins; ++f)
        {
            for (int k = 0; k < window; ++k)
            {
                // Границы окна — краевое значение (индекс поджимается)
                const int idx = std::clamp(t + k - half, 0, n_frames - 1);
                buf[static_cast<size_t>(k)] = src(idx, f);
            }
            std::nth_element(buf.begin(), buf.begin() + half, buf.end());
            tensor(t, f) = buf[static_cast<size_t>(half)];
        }
    }
}

void smooth_contours(Eigen::Tensor2dXf &contours, int window)
{
    median_time(contours, window);
}

void smooth_frames(Eigen::Tensor2dXf &frames, int window)
{
    median_time(frames, window);
}

} // namespace basic_pitch
