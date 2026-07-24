#!/usr/bin/env python3
"""
Auto-charter for Rebound.

Decodes an audio file with ffmpeg, finds its tempo and note onsets by pure-Python
signal analysis (no numpy/librosa), and writes a `.rbc` chart plus a packaged
`.reb`. The chart is generated from the acoustics of the file, not copied from
anywhere.

    python autochart.py <audio> <out.reb> [--title T] [--artist A] [--diff NAME]
"""
import sys, os, math, struct, subprocess, zipfile, argparse, array

SR = 22050          # analysis sample rate (mono)
HOP = 256           # frame hop in samples
WIN = 512           # frame window in samples
SUBDIV = 4          # grid = 1/16 note (4 per beat)


def decode_pcm(path):
    """ffmpeg -> mono 16-bit PCM samples as a float list in [-1, 1]."""
    cmd = ["ffmpeg", "-v", "error", "-i", path,
           "-ac", "1", "-ar", str(SR), "-f", "s16le", "-"]
    raw = subprocess.run(cmd, stdout=subprocess.PIPE, check=True).stdout
    ints = array.array("h")
    ints.frombytes(raw)
    return ints


def onset_novelty(samples):
    """Half-wave-rectified log-energy flux per frame -> (novelty, fps)."""
    n = len(samples)
    # Prefix sum of squared samples, so a frame's energy is O(1).
    energy = [0.0] * (n + 1)
    for i in range(n):
        s = samples[i] / 32768.0
        energy[i + 1] = energy[i] + s * s

    frames = max(1, (n - WIN) // HOP)
    nov = [0.0] * frames
    prev = 0.0
    eps = 1e-9
    for f in range(frames):
        a = f * HOP
        e = (energy[a + WIN] - energy[a]) / WIN
        cur = math.log(e + eps)
        d = cur - prev
        nov[f] = d if d > 0 else 0.0
        prev = cur
    fps = SR / HOP
    return nov, fps


def estimate_bpm(nov, fps):
    """Autocorrelation of the novelty, folded into a musical range."""
    lo_bpm, hi_bpm = 60.0, 200.0
    lag_min = int(fps * 60.0 / hi_bpm)
    lag_max = int(fps * 60.0 / lo_bpm)
    best_lag, best_score = lag_min, -1.0
    for lag in range(lag_min, lag_max + 1):
        s = 0.0
        for i in range(len(nov) - lag):
            s += nov[i] * nov[i + lag]
        s /= (len(nov) - lag)  # normalise so long lags are not favoured
        if s > best_score:
            best_score, best_lag = s, lag
    bpm = 60.0 * fps / best_lag
    # Fold to a comfortable range so half/double-time reads sensibly.
    while bpm < 90:
        bpm *= 2
    while bpm > 180:
        bpm /= 2
    return bpm


def estimate_offset(nov, fps, bpm):
    """Beat phase that lines up best with the novelty -> offset in ms."""
    period = fps * 60.0 / bpm
    best_phase, best_score = 0.0, -1.0
    steps = 48
    for k in range(steps):
        phase = period * k / steps
        s, i = 0.0, phase
        while i < len(nov):
            s += nov[int(i)]
            i += period
        if s > best_score:
            best_score, best_phase = s, phase
    return best_phase * HOP / SR * 1000.0


def pick_onsets(nov, fps):
    """Local maxima above an adaptive threshold -> onset times in ms."""
    win = int(fps * 0.15)  # ~150 ms neighbourhood
    onsets = []
    for f in range(1, len(nov) - 1):
        v = nov[f]
        if v <= 0 or v < nov[f - 1] or v < nov[f + 1]:
            continue
        a, b = max(0, f - win), min(len(nov), f + win)
        local = nov[a:b]
        mean = sum(local) / len(local)
        var = sum((x - mean) ** 2 for x in local) / len(local)
        thresh = mean + 1.3 * math.sqrt(var)
        if v >= thresh:
            onsets.append((f * HOP / SR * 1000.0, v))
    return onsets


def build_grid(onsets, bpm, offset_ms, duration_ms):
    """Snap onsets to the 1/16 grid; return a set of grid indices with strength."""
    step = 60000.0 / bpm / SUBDIV
    strat = {}
    for t, v in onsets:
        n = round((t - offset_ms) / step)
        if n < 0:
            continue
        if abs((offset_ms + n * step) - t) > step * 0.5:
            continue
        strat[n] = max(strat.get(n, 0.0), v)
    last = int((duration_ms - offset_ms) / step)
    return strat, step, last


COLUMNS = 16
# The columns the editor's SLOT lanes own, so generated notes land in real lanes
# and stay editable rather than piling into a fallback lane.
SLOTS = [0, 4, 7, 11]


def render_rbc(strat, step, last, bpm, offset_ms, meta):
    rows_per_measure = 4 * SUBDIV
    measures = last // rows_per_measure + 1
    grid = [["0"] * COLUMNS for _ in range(measures * rows_per_measure)]

    order = sorted(strat.keys())
    # Strong downbeats with a clear gap after become golds; the parser finds them
    # something to fly to on its own.
    strong = sorted(strat.items(), key=lambda kv: -kv[1])
    golds = set()
    for n, _ in strong:
        if n % (SUBDIV * 4) != 0:        # a bar downbeat only
            continue
        nxt = next((m for m in order if m > n), None)
        if nxt is None or (nxt - n) * step >= 700:  # room for the rally
            golds.add(n)
        if len(golds) >= max(2, len(order) // 24):
            break

    col = 0
    for n in order:
        if n >= len(grid):
            continue
        c = SLOTS[col % len(SLOTS)]
        col += 1
        grid[n][c] = "2" if n in golds else "1"

    out = []
    out.append(f"title={meta['title']}")
    out.append(f"artist={meta['artist']}")
    out.append(f"audio={meta['audio']}")
    out.append(f"bpm={bpm_str(bpm)}")
    out.append(f"offset={round(offset_ms)}")
    out.append(f"columns={COLUMNS}")
    out.append(f"level={meta['level']}")
    out.append(f"difficulty={meta['difficulty']}")
    out.append(f"end={round(meta['duration_ms'])}")
    out.append("--")
    for m in range(measures):
        for r in range(rows_per_measure):
            out.append("".join(grid[m * rows_per_measure + r]))
        out.append("--")
    return "\n".join(out) + "\n"


def bpm_str(bpm):
    r = round(bpm, 2)
    return str(int(r)) if abs(r - round(r)) < 1e-6 else str(r)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("audio")
    ap.add_argument("out")
    ap.add_argument("--title", default=None)
    ap.add_argument("--artist", default="Unknown")
    ap.add_argument("--diff", default="NORMAL")
    ap.add_argument("--bpm", type=float, default=None,
                    help="override the detected tempo (e.g. if it locked onto half-time)")
    ap.add_argument("--max-density", type=float, default=3.0,
                    help="cap on notes per second; the strongest onsets are kept")
    args = ap.parse_args()

    samples = decode_pcm(args.audio)
    duration_ms = len(samples) / SR * 1000.0
    nov, fps = onset_novelty(samples)
    bpm = args.bpm if args.bpm else estimate_bpm(nov, fps)
    offset_ms = estimate_offset(nov, fps, bpm)
    onsets = pick_onsets(nov, fps)

    # Thin to the strongest onsets so the chart is playable, not a wall of notes.
    cap = int(args.max_density * duration_ms / 1000.0)
    if len(onsets) > cap:
        onsets = sorted(onsets, key=lambda o: -o[1])[:cap]

    strat, step, last = build_grid(onsets, bpm, offset_ms, duration_ms)

    title = args.title or os.path.splitext(os.path.basename(args.audio))[0]
    audio_entry = "audio" + os.path.splitext(args.audio)[1].lower()
    density = len(strat) / (duration_ms / 1000.0)
    level = max(1, min(12, round(density * 1.1)))
    meta = dict(title=title, artist=args.artist, audio=audio_entry,
                difficulty=args.diff, level=level, duration_ms=duration_ms)

    rbc = render_rbc(strat, step, last, bpm, offset_ms, meta)
    chart_entry = "chart.rbc"

    manifest = (f"format=1\ntitle={title}\nartist={args.artist}\n"
                f"audio={audio_entry}\nchart={chart_entry}\n")

    with zipfile.ZipFile(args.out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("rebound.manifest", manifest)
        z.writestr(chart_entry, rbc)
        with open(args.audio, "rb") as f:
            z.writestr(audio_entry, f.read())

    print(f"bpm={bpm_str(bpm)} offset={round(offset_ms)}ms "
          f"onsets={len(strat)} density={density:.1f}/s level={level} "
          f"duration={duration_ms/1000:.1f}s")
    print(f"wrote {args.out}")


if __name__ == "__main__":
    main()
