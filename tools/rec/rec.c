// rec — минимальная консольная запись с микрофона, один режим.
// По аналогии с arecord, но без опций: ALSA "default" (= PulseAudio default
// source), 22050 Гц / 16 бит / моно — те же параметры, что у записи в GUI v2m.
// Ничего в системе не меняет. Остановка — Ctrl-C (SIGINT).
// Выход: rec_cli_<YYYYMMDD_HHMMSS>.wav в текущем каталоге + уровни в stdout.

#include <alsa/asoundlib.h>
#include <math.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define RATE 22050
#define BLOCK_FRAMES 2048 /* 4096 байт — как блок read() в GUI v2m */

static volatile sig_atomic_t g_stop = 0;
static void on_int(int s) { g_stop = 1; (void)s; }

static void write_le32(FILE *f, unsigned v) {
    fputc(v & 0xFF, f); fputc((v >> 8) & 0xFF, f);
    fputc((v >> 16) & 0xFF, f); fputc((v >> 24) & 0xFF, f);
}
static void write_le16(FILE *f, unsigned v) {
    fputc(v & 0xFF, f); fputc((v >> 8) & 0xFF, f);
}

int main(void) {
    /* имя файла: rec_cli_<ts>.wav */
    char path[256];
    time_t now = time(NULL);
    struct tm *tm = localtime(&now);
    snprintf(path, sizeof path, "rec_cli_%04d%02d%02d_%02d%02d%02d.wav",
             tm->tm_year + 1900, tm->tm_mon + 1, tm->tm_mday,
             tm->tm_hour, tm->tm_min, tm->tm_sec);

    snd_pcm_t *pcm = NULL;
    int err = snd_pcm_open(&pcm, "default", SND_PCM_STREAM_CAPTURE, 0);
    if (err < 0) {
        fprintf(stderr, "rec: open 'default' failed: %s\n", snd_strerror(err));
        return 1;
    }
    snd_pcm_hw_params_t *hw;
    snd_pcm_hw_params_alloca(&hw);
    snd_pcm_hw_params_any(pcm, hw);
    snd_pcm_hw_params_set_access(pcm, hw, SND_PCM_ACCESS_RW_INTERLEAVED);
    snd_pcm_hw_params_set_format(pcm, hw, SND_PCM_FORMAT_S16_LE);
    snd_pcm_hw_params_set_channels(pcm, hw, 1);
    unsigned rate = RATE;
    snd_pcm_hw_params_set_rate_near(pcm, hw, &rate, NULL);
    snd_pcm_uframes_t period = BLOCK_FRAMES;
    snd_pcm_hw_params_set_period_size_near(pcm, hw, &period, NULL);
    snd_pcm_hw_params_set_buffer_size_near(pcm, hw, &period);
    err = snd_pcm_hw_params(pcm, hw);
    if (err < 0) {
        fprintf(stderr, "rec: hw params failed: %s\n", snd_strerror(err));
        return 1;
    }
    snd_pcm_uframes_t psize = 0, bsize = 0;
    snd_pcm_hw_params_get_period_size(hw, &psize, NULL);
    snd_pcm_hw_params_get_buffer_size(hw, &bsize);
    printf("rec: open ok: %u Hz, 1 ch, 16 bit (period %lu fr, buffer %lu fr) -> %s  (Ctrl-C = stop)\n",
           rate, (unsigned long)psize, (unsigned long)bsize, path);

    FILE *f = fopen(path, "wb");
    if (!f) { perror("rec: create wav"); return 1; }
    /* заголовок с нулями, финализируется при закрытии */
    unsigned char hdr[44] = {0};
    fwrite(hdr, 1, 44, f);

    /* INT/TERM -> аккуратный стоп. Без SA_RESTART, чтобы блокирующий
       readi прерывался; маску снимаем на случай фонового запуска
       (родитель может блокировать SIGINT). */
    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.sa_handler = on_int;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = 0;
    sigaction(SIGINT, &sa, NULL);
    sigaction(SIGTERM, &sa, NULL);
    sigset_t unblk;
    sigemptyset(&unblk);
    sigaddset(&unblk, SIGINT);
    sigaddset(&unblk, SIGTERM);
    sigprocmask(SIG_UNBLOCK, &unblk, NULL);

    snd_pcm_start(pcm);

    short *buf = malloc(BLOCK_FRAMES * sizeof(short));
    long long total = 0;          /* всего фреймов */
    double sum2 = 0;              /* сумма квадратов, посекундно */
    long peak = 0;                /* максимум |sample|, посекундно */
    long clips = 0;               /* клипы, посекундно */
    double all_sum2 = 0;          /* сумма квадратов, за всю запись */
    long all_peak = 0, all_clips = 0;
    int xruns = 0;
    double t0 = 0;                /* последняя напечатанная секунда */

    while (!g_stop) {
        long n = snd_pcm_readi(pcm, buf, BLOCK_FRAMES);
        if (n < 0) {
            if (n == -EPIPE) { /* xrun: пропуск, продолжаем; защита от зацикливания */
                if (xruns == 0) fprintf(stderr, "rec: xrun — пропуск данных\n");
                if (++xruns > 20) {
                    fprintf(stderr, "rec: xrun не восстанавливается, стоп\n");
                    break;
                }
                snd_pcm_prepare(pcm);
                continue;
            }
            if (n == -EINTR && g_stop) break; /* сигнал останова прервал read */
            fprintf(stderr, "rec: read error: %s\n", snd_strerror((int)n));
            break;
        }
        fwrite(buf, sizeof(short), (size_t)n, f);
        for (long i = 0; i < n; i++) {
            int s = buf[i];
            sum2 += (double)s * s;
            all_sum2 += (double)s * s;
            long a = s < 0 ? -(long)s : (long)s;
            if (a > peak) peak = a;
            if (a > all_peak) all_peak = a;
            if (a >= 32767) { clips++; all_clips++; }
        }
        total += n;
        double now_s = (double)total / rate;
        if (now_s - t0 >= 1.0) {
            long sec_fr = (long)((now_s - t0) * rate);
            double rms = sqrt(sum2 / (sec_fr > 0 ? sec_fr : 1));
            printf("[%5.1fs] rms %7.1f dBFS | peak %7.1f | clip %5.2f%%\n",
                   now_s, 20 * log10(rms / 32768.0),
                   20 * log10((double)peak / 32768.0),
                   100.0 * clips / total);
            fflush(stdout);
            t0 = now_s;
            sum2 = 0; peak = 0; clips = 0;
        }
    }

    /* финализация WAV-заголовка */
    fseek(f, 0, SEEK_SET);
    fwrite("RIFF", 1, 4, f);
    write_le32(f, (unsigned)(36 + total * 2));
    fwrite("WAVEfmt ", 1, 8, f);
    write_le32(f, 16); write_le16(f, 1); write_le16(f, 1);
    write_le32(f, rate); write_le32(f, rate * 2);
    write_le16(f, 2); write_le16(f, 16);
    fwrite("data", 1, 4, f);
    write_le32(f, (unsigned)(total * 2));
    fclose(f);

    snd_pcm_drop(pcm); /* на capture drain ждал бы «конца» потока — drop мгновенный */
    snd_pcm_close(pcm);

    printf("rec: done: %s — %.1f s (%lld fr), rms %.1f dBFS, peak %.1f dBFS, clip %.3f%%, xruns %d\n",
           path, (double)total / rate, total,
           20 * log10(sqrt(all_sum2 / (total > 0 ? total : 1)) / 32768.0),
           20 * log10((double)all_peak / 32768.0),
           100.0 * all_clips / total, xruns);
    free(buf);
    return 0;
}
