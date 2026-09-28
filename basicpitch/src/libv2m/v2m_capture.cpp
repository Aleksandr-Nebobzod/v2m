// ALSA capture backend for the v2m recorder (билд #43).
// Nonblocking PCM + poll(150 ms) + an atomic stop flag: the stopping thread
// never touches ALSA, so there is no cross-thread race by construction; a
// stalled pulse source cannot hang the read forever (the poll times out).
#include "v2m_capture.h"

#include <alsa/asoundlib.h>

#include <atomic>
#include <cstdlib>
#include <cstring>
#include <poll.h>
#include <string>

struct v2m_capture
{
    snd_pcm_t *pcm;
    std::atomic_int stop_flag{0};
    std::string last_error;
    int xruns;
};

static constexpr int POLL_TIMEOUT_MS = 150; // stop latency <= 150 ms
static constexpr int MAX_CONSECUTIVE_XRUNS = 20;

// Configure the PCM to exactly rate x channels x S16_LE; period and buffer
// sized from period_frames. Returns 0 on success, negative ALSA code
// otherwise with the human-readable reason in why.
static int configure_params(snd_pcm_t *pcm, unsigned rate, unsigned channels,
                            unsigned period_frames, std::string &why)
{
    snd_pcm_hw_params_t *hw;
    snd_pcm_hw_params_alloca(&hw);
    int rc = snd_pcm_hw_params_any(pcm, hw);
    if (rc < 0)
    {
        why = snd_strerror(rc);
        return rc;
    }
    rc = snd_pcm_hw_params_set_access(pcm, hw, SND_PCM_ACCESS_RW_INTERLEAVED);
    if (rc < 0)
    {
        why = snd_strerror(rc);
        return rc;
    }
    rc = snd_pcm_hw_params_set_format(pcm, hw, SND_PCM_FORMAT_S16_LE);
    if (rc < 0)
    {
        why = snd_strerror(rc);
        return rc;
    }
    rc = snd_pcm_hw_params_set_channels(pcm, hw, channels);
    if (rc < 0)
    {
        why = snd_strerror(rc);
        return rc;
    }
    unsigned actual_rate = rate;
    rc = snd_pcm_hw_params_set_rate_near(pcm, hw, &actual_rate, nullptr);
    if (rc < 0)
    {
        why = snd_strerror(rc);
        return rc;
    }
    snd_pcm_uframes_t period = period_frames;
    snd_pcm_hw_params_set_period_size_near(pcm, hw, &period, nullptr);
    snd_pcm_hw_params_set_buffer_size_near(pcm, hw, &period);
    rc = snd_pcm_hw_params(pcm, hw);
    if (rc < 0)
    {
        why = snd_strerror(rc);
        return rc;
    }
    // The device must provide exactly the model parameters — otherwise the
    // open fails loudly instead of silently recording at a wrong rate.
    snd_pcm_format_t fmt;
    unsigned got_rate = 0, got_ch = 0;
    snd_pcm_hw_params_get_format(hw, &fmt);
    snd_pcm_hw_params_get_rate(hw, &got_rate, nullptr);
    snd_pcm_hw_params_get_channels(hw, &got_ch);
    if (fmt != SND_PCM_FORMAT_S16_LE || got_rate != rate || got_ch != channels)
    {
        why = "устройство дало " + std::to_string(got_rate) + " Гц/" +
              std::to_string(got_ch) + " к. вместо " + std::to_string(rate) +
              " Гц/" + std::to_string(channels) + " к.";
        return -EINVAL;
    }
    return 0;
}

v2m_capture *v2m_capture_open(const char *device, unsigned rate,
                              unsigned channels, unsigned period_frames,
                              char **err)
{
    if (err)
    {
        *err = nullptr;
    }
    const char *dev = device ? device : "default";
    snd_pcm_t *pcm = nullptr;
    int rc = snd_pcm_open(&pcm, dev, SND_PCM_STREAM_CAPTURE, SND_PCM_NONBLOCK);
    if (rc < 0)
    {
        if (err)
        {
            *err = strdup(("не удалось открыть '" + std::string(dev) + "': " +
                           snd_strerror(rc)).c_str());
        }
        return nullptr;
    }
    std::string why;
    rc = configure_params(pcm, rate, channels, period_frames, why);
    if (rc < 0)
    {
        if (err)
        {
            *err = strdup(("настройка '" + std::string(dev) + "': " + why).c_str());
        }
        snd_pcm_close(pcm);
        return nullptr;
    }
    rc = snd_pcm_start(pcm);
    if (rc < 0)
    {
        if (err)
        {
            *err = strdup(("старт '" + std::string(dev) + "': " +
                           snd_strerror(rc)).c_str());
        }
        snd_pcm_close(pcm);
        return nullptr;
    }
    auto *h = new (std::nothrow) v2m_capture;
    if (!h)
    {
        snd_pcm_close(pcm);
        return nullptr;
    }
    h->pcm = pcm;
    h->xruns = 0;
    return h;
}

int v2m_capture_read(v2m_capture *h, short *buf, unsigned max_frames)
{
    if (!h)
    {
        return -EINVAL;
    }
    if (h->stop_flag.load(std::memory_order_acquire))
    {
        return 0;
    }
    int rc = snd_pcm_poll_descriptors_count(h->pcm);
    if (rc <= 0)
    {
        return rc;
    }
    const int nfds = rc;
    pollfd *pfds = static_cast<pollfd *>(alloca(sizeof(pollfd) * nfds));
    snd_pcm_poll_descriptors(h->pcm, pfds, nfds);
    rc = poll(pfds, static_cast<nfds_t>(nfds), POLL_TIMEOUT_MS);
    if (rc <= 0)
    {
        return 0; // timeout or EINTR — caller checks its own flags
    }
    unsigned short revents = 0;
    rc = snd_pcm_poll_descriptors_revents(h->pcm, pfds, nfds, &revents);
    if (rc < 0)
    {
        h->last_error = "poll revents: " + std::string(snd_strerror(rc));
        return rc;
    }
    if (!(revents & (POLLIN | POLLERR | POLLHUP | POLLNVAL)))
    {
        return 0; // spurious wake — poll again next call
    }
    rc = snd_pcm_readi(h->pcm, buf, max_frames);
    if (rc >= 0)
    {
        return rc;
    }
    if (rc == -EAGAIN || rc == -EINTR)
    {
        return 0; // not ready this round — poll again next call
    }
    if (rc == -EPIPE)
    {
        // xrun: recover, but a chronically failing stream is fatal (as rec.c)
        if (++h->xruns > MAX_CONSECUTIVE_XRUNS)
        {
            h->last_error = "xrun не восстанавливается";
            return rc;
        }
        snd_pcm_prepare(h->pcm);
        snd_pcm_start(h->pcm);
        return 0;
    }
    h->last_error = snd_strerror(rc);
    return rc;
}

void v2m_capture_stop(v2m_capture *h)
{
    if (h)
    {
        h->stop_flag.store(1, std::memory_order_release);
    }
}

const char *v2m_capture_last_error(const v2m_capture *h)
{
    return h ? h->last_error.c_str() : "no capture handle";
}

void v2m_capture_close(v2m_capture *h)
{
    if (!h)
    {
        return;
    }
    snd_pcm_drop(h->pcm); // instant stop; drain would wait forever on capture
    snd_pcm_close(h->pcm);
    delete h;
}
