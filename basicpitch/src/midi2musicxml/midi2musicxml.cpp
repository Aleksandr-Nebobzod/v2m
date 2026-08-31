// midi2musicxml: convert a standard MIDI file (SMF) to MusicXML 3.1 (partwise).
//
// Scope: music as note events. Pitch bends are dropped by design (the v2m
// pipeline treats them as vocal detune, not as notation). Notes starting at
// the same tick form a chord (<chord/>). Notes crossing a measure boundary
// are split with ties. Overlapping notes are distributed into voices
// (greedy; the same-tick notes stay in one voice as a chord). Measures are
// numbered from tick 0 (anacrusis is not detected). Key signature defaults
// to C (fifths=0).
//
// Usage: midi2musicxml <input.mid> [output.musicxml]  (default: <input>.musicxml)

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iterator>
#include <map>
#include <string>
#include <vector>

namespace
{

struct Note
{
    int start_tick = 0;
    int end_tick = 0;
    int pitch = 0;
    int voice = 0; // assigned during voice separation
};

struct Song
{
    int division = 0;        // ticks per quarter note (SMF division)
    int tempo_us = 500'000;  // microseconds per quarter note (120 BPM default)
    int ts_num = 4;          // time signature numerator
    int ts_den = 4;          // time signature denominator
    std::vector<Note> notes;
};

struct Reader
{
    const uint8_t *data;
    size_t size;
    size_t pos = 0;

    bool u8(uint8_t &v)
    {
        if (pos >= size) return false;
        v = data[pos++];
        return true;
    }
    bool bytes(void *dst, size_t n)
    {
        if (pos + n > size) return false;
        std::memcpy(dst, data + pos, n);
        pos += n;
        return true;
    }
    bool vlq(uint32_t &v)
    {
        v = 0;
        for (int i = 0; i < 4; ++i)
        {
            uint8_t b;
            if (!u8(b)) return false;
            v = (v << 7) | (b & 0x7f);
            if (!(b & 0x80)) return true;
        }
        return false;
    }
};

void parse_track(Reader &r, uint32_t len, Song &song)
{
    const size_t end = r.pos + len;
    uint32_t tick = 0;
    uint8_t running = 0;
    std::map<std::pair<int, int>, int> active; // (channel, pitch) -> start tick

    while (r.pos < end)
    {
        uint32_t delta = 0;
        if (!r.vlq(delta)) break;
        tick += delta;

        uint8_t st = 0;
        if (!r.u8(st)) break;
        if (!(st & 0x80))
        {
            if (running == 0) break; // malformed running status
            --r.pos;                 // the byte is data of the running status
            st = running;
        }
        else
        {
            running = st;
        }

        const int type = st & 0xf0;
        const int chan = st & 0x0f;
        uint8_t b1 = 0, b2 = 0;

        if (type == 0x80 || type == 0x90 || type == 0xa0 || type == 0xb0 || type == 0xe0)
        {
            if (!r.u8(b1) || !r.u8(b2)) break;
            if (type == 0x80 || (type == 0x90 && b2 == 0))
            {
                auto it = active.find({chan, b1});
                if (it != active.end())
                {
                    song.notes.push_back(Note{it->second, static_cast<int>(tick), b1, 0});
                    active.erase(it);
                }
            }
            else if (type == 0x90)
            {
                active[{chan, b1}] = static_cast<int>(tick);
            }
            // 0xa0, 0xb0, 0xe0 (pitch bend) — ignored
        }
        else if (type == 0xc0 || type == 0xd0)
        {
            if (!r.u8(b1)) break; // program change / pressure — ignored
        }
        else if (st == 0xff) // meta event
        {
            if (!r.u8(b1)) break;
            uint32_t mlen = 0;
            if (!r.vlq(mlen)) break;
            const size_t meta_end = r.pos + mlen;
            if (b1 == 0x51 && mlen >= 3)
            {
                song.tempo_us = (r.data[r.pos] << 16) | (r.data[r.pos + 1] << 8) | r.data[r.pos + 2];
            }
            else if (b1 == 0x58 && mlen >= 2)
            {
                song.ts_num = r.data[r.pos];
                const int dd = r.data[r.pos + 1];
                if (dd >= 0 && dd <= 6) song.ts_den = 1 << dd;
            }
            r.pos = meta_end;
            if (b1 == 0x2f) break; // end of track
        }
        // 0xf0/0xf7 (sysex) and 0xf1..0xf6: skipped via their length field
        // (sysex uses vlq length; f1..f6 carry no data — nothing to skip)
    }
}

bool parse_smf(const std::vector<uint8_t> &bytes, Song &song)
{
    if (bytes.size() < 14 || std::memcmp(bytes.data(), "MThd", 4) != 0) return false;
    const uint32_t hlen = (uint32_t(bytes[4]) << 24) | (uint32_t(bytes[5]) << 16) |
                          (uint32_t(bytes[6]) << 8) | bytes[7];
    if (hlen < 6) return false;
    const uint16_t ntrks = (uint16_t(bytes[10]) << 8) | bytes[11];
    const uint16_t division = (uint16_t(bytes[12]) << 8) | bytes[13];
    if (division & 0x8000)
    {
        std::fprintf(stderr, "midi2musicxml: SMPTE division not supported\n");
        return false;
    }
    if (division <= 0)
    {
        std::fprintf(stderr, "midi2musicxml: invalid division\n");
        return false;
    }
    song.division = division;

    Reader r{bytes.data(), bytes.size(), 8 + hlen};
    for (int t = 0; t < ntrks; ++t)
    {
        char id[4];
        uint8_t lenb[4];
        if (!r.bytes(id, 4) || !r.bytes(lenb, 4)) break;
        const uint32_t len = (uint32_t(lenb[0]) << 24) | (uint32_t(lenb[1]) << 16) |
                             (uint32_t(lenb[2]) << 8) | lenb[3];
        if (std::memcmp(id, "MTrk", 4) == 0)
        {
            parse_track(r, len, song);
        }
        else
        {
            r.pos += len;
        }
    }

    std::sort(song.notes.begin(), song.notes.end(), [](const Note &a, const Note &b)
              { return a.start_tick < b.start_tick; });
    return true;
}

// Greedy voice assignment: a note joins the first voice free at its start;
// notes with the same start tick stay together in one voice (chord).
void assign_voices(std::vector<Note> &notes)
{
    std::vector<int> voice_end;
    for (size_t i = 0; i < notes.size(); ++i)
    {
        // same-tick note goes to the voice of the previous note at this tick
        int v = -1;
        for (size_t j = i; j-- > 0;)
        {
            if (notes[j].start_tick == notes[i].start_tick)
            {
                v = notes[j].voice;
                break;
            }
        }
        if (v < 0)
        {
            for (size_t w = 0; w < voice_end.size(); ++w)
            {
                if (voice_end[w] <= notes[i].start_tick)
                {
                    v = static_cast<int>(w);
                    break;
                }
            }
        }
        if (v < 0)
        {
            v = static_cast<int>(voice_end.size());
            voice_end.push_back(0);
        }
        notes[i].voice = v;
        voice_end[v] = std::max(voice_end[v], notes[i].end_tick);
    }
}

// ---- MusicXML generation ----

const int pc_to_step[12] = {0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6}; // C,C#,D,Eb,E,F,F#,G,Ab,A,Bb,B
const int pc_to_alter[12] = {0, 1, 0, -1, 0, 0, 1, 0, -1, 0, -1, 0};

// MusicXML note type for a duration in quarters; sets dotted for
// augmented values (half., quarter., eighth., 16th., 32nd.). Empty if
// non-standard.
std::string note_type(double quarters, bool &dotted)
{
    struct T { double q; const char *n; bool dotted; };
    static const T types[] = {
        {4.0, "whole", false}, {2.0, "half", false}, {1.0, "quarter", false},
        {0.5, "eighth", false}, {0.25, "16th", false}, {0.125, "32nd", false},
        {0.0625, "64th", false},
        {3.0, "half", true}, {1.5, "quarter", true}, {0.75, "eighth", true},
        {0.375, "16th", true}, {0.1875, "32nd", true},
    };
    for (const T &t : types)
    {
        if (std::fabs(quarters - t.q) < 1e-6)
        {
            dotted = t.dotted;
            return t.n;
        }
    }
    dotted = false;
    return "";
}

// Quantize a note duration (ticks in the 3520-division grid) to the nearest
// standard type, so that <duration> and <type> agree (MuseScore3 rejects
// inconsistent notes). Resolution is a 64th (55 ticks ~ 8 ms); rests keep
// their raw duration without <type> (accepted by MuseScore).
std::pair<int, bool> quantize_duration(int ticks)
{
    // standard lengths in ticks at division 3520
    struct V { int t; double q; };
    static const V vals[] = {
        {55, 0.015625},   // 64th
        {110, 0.03125},   // 32nd
        {165, 0.046875},  // 32nd.
        {220, 0.0625},    // 16th
        {330, 0.09375},   // 16th.
        {440, 0.125},     // eighth
        {660, 0.1875},    // eighth.
        {880, 0.25},      // quarter
        {1320, 0.375},    // quarter.
        {1760, 0.5},      // half
        {2640, 0.75},     // half.
        {3520, 1.0},      // whole
        {5280, 1.5},      // whole.
        {7040, 2.0},      // breve
        {10560, 3.0},     // breve.
        {14080, 4.0},     // long
    };
    const double quarters = static_cast<double>(ticks) / 3520.0;
    int best_t = ticks;
    double best_d = 1e9;
    for (const V &v : vals)
    {
        const double d = std::fabs(quarters - v.q);
        if (d < best_d)
        {
            best_d = d;
            best_t = v.t;
        }
    }
    bool dotted = false;
    const std::string n = note_type(static_cast<double>(best_t) / 3520.0, dotted);
    if (n.empty())
    {
        return {ticks, false}; // no standard match — keep raw (no <type>)
    }
    return {best_t, dotted};
}

// One <note> element (note or rest). Ties are written AFTER <duration>:
// MusicXML 2.0 order (duration, tie?, ...) which MuseScore3's parser expects;
// it also satisfies 3.1 (the second tie slot).
void write_note(std::string &out, const Note &n, int divisions, bool is_rest,
                bool chord, bool tie_start, bool tie_stop)
{
    out += "      <note>\n";
    if (chord) out += "        <chord/>\n";
    if (is_rest)
    {
        out += "        <rest/>\n";
    }
    else
    {
        const int pc = n.pitch % 12;
        static const char *step_names[] = {"C", "D", "E", "F", "G", "A", "B"};
        out += "        <pitch><step>" + std::string(step_names[pc_to_step[pc]]) + "</step>";
        if (pc_to_alter[pc] != 0)
            out += "<alter>" + std::to_string(pc_to_alter[pc]) + "</alter>";
        out += "<octave>" + std::to_string(n.pitch / 12 - 1) + "</octave></pitch>\n";
    }
    out += "        <duration>" + std::to_string(n.end_tick - n.start_tick) + "</duration>\n";
    if (tie_start) out += "        <tie type=\"start\"/>\n";
    if (tie_stop) out += "        <tie type=\"stop\"/>\n";
    if (!chord) out += "        <voice>" + std::to_string(n.voice + 1) + "</voice>\n";
    if (!is_rest)
    {
        bool dotted = false;
        const std::string t = note_type(double(n.end_tick - n.start_tick) / divisions, dotted);
        if (!t.empty())
        {
            out += "        <type>" + t + "</type>\n";
            if (dotted) out += "        <dot/>\n";
        }
    }
    out += "      </note>\n";
}

} // namespace

// Core conversion: SMF bytes -> MusicXML document string
bool convert_smf_to_string(const std::vector<uint8_t> &bytes,
                           const std::string &title, std::string &out,
                           std::string &err)
{
    Song song;
    if (!parse_smf(bytes, song) || song.notes.empty())
    {
        err = "not a valid SMF or no notes";
        return false;
    }
    assign_voices(song.notes);

    // Scale to a 3520-division grid (64th-note resolution, 55 ticks) so note
    // lengths can be quantized to standard types with ~8 ms error.
    constexpr int GRID_SCALE = 16;
    for (Note &n : song.notes)
    {
        n.start_tick *= GRID_SCALE;
        n.end_tick *= GRID_SCALE;
    }
    const int divisions = song.division * GRID_SCALE;
    const int L = song.ts_num * divisions; // ticks per measure
    const int tempo_bpm = 60'000'000 / song.tempo_us;
    const int last_tick = song.notes.back().end_tick;
    const int n_measures = (last_tick + L - 1) / L;

    out.clear();
    out += "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";
    out += "<!DOCTYPE score-partwise PUBLIC \"-//Recordare//DTD MusicXML 3.1 Partwise//EN\" "
           "\"http://www.musicxml.org/dtds/partwise.dtd\">\n";
    out += "<score-partwise version=\"3.1\">\n";
    out += "  <work><work-title>" + title + "</work-title></work>\n";
    out += "  <part-list>\n";
    out += "    <score-part id=\"P1\">\n      <part-name>Music</part-name>\n";
    out += "      <score-instrument id=\"P1-I1\"><instrument-name>Piano</instrument-name></score-instrument>\n";
    out += "      <midi-instrument id=\"P1-I1\"><midi-channel>1</midi-channel><midi-program>1</midi-program></midi-instrument>\n";
    out += "    </score-part>\n  </part-list>\n";
    out += "  <part id=\"P1\">\n";

    // Per voice, the notes that cross measure m: they start before m+1's edge
    // and end after m's edge. The events of a voice inside the measure are
    // laid out on a timeline; rests fill the gaps; measure-crossing segments
    // carry ties.
    int n_voices = 0;
    for (const Note &n : song.notes)
        n_voices = std::max(n_voices, n.voice + 1);
    std::vector<std::vector<size_t>> voice_notes(n_voices);
    for (size_t i = 0; i < song.notes.size(); ++i)
        voice_notes[song.notes[i].voice].push_back(i);

    for (int m = 0; m < n_measures; ++m)
    {
        const int m_start = m * L;
        const int m_end = m_start + L;
        out += "    <measure number=\"" + std::to_string(m + 1) + "\">\n";
        if (m == 0)
        {
            out += "      <attributes>\n";
            out += "        <divisions>" + std::to_string(divisions) + "</divisions>\n";
            out += "        <key><fifths>0</fifths></key>\n";
            out += "        <time><beats>" + std::to_string(song.ts_num) +
                   "</beats><beat-type>" + std::to_string(song.ts_den) + "</beat-type></time>\n";
            out += "        <clef><sign>G</sign><line>2</line></clef>\n";
            out += "      </attributes>\n";
            out += "      <direction placement=\"above\">\n"
                   "        <direction-type><metronome><beat-unit>quarter</beat-unit>"
                   "<per-minute>" + std::to_string(tempo_bpm) + "</per-minute></metronome></direction-type>\n"
                   "        <sound tempo=\"" + std::to_string(tempo_bpm) + "\"/>\n"
                   "      </direction>\n";
        }

        // A voice may have several segments crossing measure m (e.g. a
        // sustained note that started in m-1 and one starting in m).
        std::vector<size_t> order;
        for (size_t v = 0; v < voice_notes.size(); ++v)
        {
            if (voice_notes[v].empty()) continue;
            for (size_t idx : voice_notes[v])
            {
                const Note &n = song.notes[idx];
                if (n.start_tick < m_end && n.end_tick > m_start)
                    order.push_back(idx);
            }
        }
        std::sort(order.begin(), order.end(), [&](size_t a, size_t b)
                  {
                      const Note &na = song.notes[a];
                      const Note &nb = song.notes[b];
                      if (na.start_tick != nb.start_tick) return na.start_tick < nb.start_tick;
                      if (na.voice != nb.voice) return na.voice < nb.voice;
                      return na.pitch < nb.pitch;
                  });

        // Group order into per-voice timelines
        std::vector<std::vector<size_t>> per_voice;
        for (size_t idx : order)
        {
            const int v = song.notes[idx].voice;
            while (static_cast<int>(per_voice.size()) <= v) per_voice.emplace_back();
            per_voice[v].push_back(idx);
        }

        bool wrote_anything = false;
        for (size_t v = 0; v < per_voice.size(); ++v)
        {
            if (per_voice[v].empty()) continue;
            int cursor = m_start;
            const Note *prev = nullptr; // for chord detection
            for (size_t j = 0; j < per_voice[v].size(); ++j)
            {
                const size_t idx = per_voice[v][j];
                const Note &n = song.notes[idx];
                const int s = std::max(n.start_tick, m_start);
                const int e = std::min(n.end_tick, m_end);
                if (s >= e) continue;

                const bool chord = prev && prev->start_tick == n.start_tick;
                if (!chord && s > cursor)
                {
                    // rest filling the gap
                    Note rest{m_start, s, 0, static_cast<int>(v)};
                    rest.start_tick = cursor;
                    write_note(out, rest, divisions, true, false, false, false);
                }
                // Quantize the note length to a standard type so that
                // <duration> and <type> agree (MuseScore3 rejects raw lengths);
                // never extend beyond the raw end or the next note of the line
                int note_end = e;
                if (!chord)
                {
                    // never extend past the next note of the line or the
                    // measure edge (quantized length may overshoot the raw one)
                    const int limit = (j + 1 < per_voice[v].size())
                                          ? std::min(song.notes[per_voice[v][j + 1]].start_tick,
                                                     m_end) - s
                                          : m_end - s;
                    const auto [q, qd] = quantize_duration(e - s);
                    note_end = s + std::min(q, std::max(limit, 1));
                }
                else
                {
                    const auto [q, qd] = quantize_duration(e - s);
                    note_end = s + q;
                }
                Note seg{s, note_end, n.pitch, static_cast<int>(v)};
                seg.start_tick = s;
                seg.end_tick = note_end;
                write_note(out, seg, divisions, false, chord,
                           n.start_tick < m_start, n.end_tick > m_end);
                if (!chord) cursor = note_end; // chord notes run parallel, do not move the line
                prev = &n;
                wrote_anything = true;
            }
            if (cursor < m_end)
            {
                Note rest{m_start, m_end, 0, static_cast<int>(v)};
                rest.start_tick = cursor;
                write_note(out, rest, divisions, true, false, false, false);
            }
            // return to the measure start before the next voice
            bool next_voice = false;
            for (size_t w = v + 1; w < per_voice.size() && !next_voice; ++w)
                next_voice = !per_voice[w].empty();
            if (next_voice)
            {
                out += "      <backup><duration>" + std::to_string(L) +
                       "</duration></backup>\n";
            }
        }
        if (!wrote_anything)
        {
            Note rest{m_start, m_end, 0, 0};
            rest.start_tick = m_start;
            write_note(out, rest, divisions, true, false, false, false);
        }
        out += "    </measure>\n";
    }
    out += "  </part>\n";
    out += "</score-partwise>\n";
    return true;
}

#ifndef V2M_NO_MAIN
int main(int argc, char **argv)
{
    if (argc < 2 || argc > 3)
    {
        std::fprintf(stderr, "Usage: %s <input.mid> [output.musicxml]\n", argv[0]);
        return 1;
    }
    const std::string in_path = argv[1];
    const std::string out_path = argc == 3 ? argv[2] : in_path + ".musicxml";

    std::ifstream in(in_path, std::ios::binary);
    if (!in)
    {
        std::fprintf(stderr, "midi2musicxml: cannot open %s\n", in_path.c_str());
        return 1;
    }
    std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(in)), {});
    std::string out, err;
    if (!convert_smf_to_string(bytes, in_path.substr(in_path.find_last_of('/') + 1), out, err))
    {
        std::fprintf(stderr, "midi2musicxml: %s: %s\n", in_path.c_str(), err.c_str());
        return 1;
    }
    std::ofstream ofs(out_path, std::ios::binary);
    if (!ofs)
    {
        std::fprintf(stderr, "midi2musicxml: cannot write %s\n", out_path.c_str());
        return 1;
    }
    ofs << out;
    std::printf("midi2musicxml: %s -> %s (%zu bytes)\n", in_path.c_str(),
                out_path.c_str(), out.size());
    return 0;
}
#endif // V2M_NO_MAIN

// C ABI entry point for the v2m library (see src/libv2m.cpp / v2m.h)
extern "C" int v2m_midi_to_musicxml(const uint8_t *midi, size_t len,
                                    const char *out_path, char **err_out)
{
    std::vector<uint8_t> bytes(midi, midi + len);
    std::string out, err;
    if (!convert_smf_to_string(bytes, "Music", out, err))
    {
        if (err_out)
            *err_out = strdup(err.c_str());
        return 0;
    }
    std::ofstream ofs(out_path, std::ios::binary);
    if (!ofs)
    {
        if (err_out)
            *err_out = strdup("cannot write output file");
        return 0;
    }
    ofs << out;
    return 1;
}
