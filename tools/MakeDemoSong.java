import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Random;

/**
 * Generates the demo track as a 48 kHz stereo 16-bit WAV.
 *
 * The song is synthesised rather than shipped so the repository carries no audio
 * anyone needs to license, and so the grid is exact by construction: every drum
 * hit lands on a sample computed from the same BPM the chart is written against,
 * which removes the usual guesswork about a track's offset.
 *
 *   javac -d out tools/MakeDemoSong.java
 *   java -cp out MakeDemoSong app/src/main/assets/songs/demo/demo.wav
 */
public final class MakeDemoSong {

    static final int SAMPLE_RATE = 48000;
    static final double BPM = 150.0;
    static final double BEAT = 60.0 / BPM;      // 0.4 s
    static final double MEASURE = BEAT * 4;     // 1.6 s
    static final int MEASURES = 20;
    static final double TAIL_SECONDS = 2.0;

    static final Random RANDOM = new Random(20260723L);

    public static void main(String[] args) throws IOException {
        String path = args.length > 0 ? args[0] : "demo.wav";

        int frames = (int) ((MEASURES * MEASURE + TAIL_SECONDS) * SAMPLE_RATE);
        double[] mix = new double[frames];

        // A minor - F - C - G, the most obliging progression there is.
        double[] roots = { 110.00, 87.31, 130.81, 98.00 };
        // A minor pentatonic, two octaves up, for the lead.
        double[] lead = { 440.00, 523.25, 587.33, 659.25, 783.99, 880.00 };

        for (int m = 0; m < MEASURES; m++) {
            double measureStart = m * MEASURE;
            double root = roots[m % roots.length];

            for (int beat = 0; beat < 4; beat++) {
                double t = measureStart + beat * BEAT;

                // Kick on 1 and 3, snare on 2 and 4: the backbone the chart follows.
                if (beat == 0 || beat == 2) kick(mix, t);
                if (beat == 1 || beat == 3) snare(mix, t);

                // Hats on every eighth, quieter on the off-beat.
                hat(mix, t, 0.30);
                hat(mix, t + BEAT / 2, 0.18);

                bass(mix, t, root, BEAT * 0.9);
            }

            // The lead enters for the middle third so the chart has somewhere to build to.
            if (m >= 6 && m < 16) {
                for (int eighth = 0; eighth < 8; eighth++) {
                    if (eighth % 2 == 1 && eighth != 3) continue;
                    double t = measureStart + eighth * (BEAT / 2);
                    double freq = lead[(m * 3 + eighth) % lead.length];
                    pluck(mix, t, freq, BEAT * 0.55, m >= 12 ? 0.22 : 0.15);
                }
            }
        }

        // A crash on the downbeat of the sections, for shape.
        crash(mix, 0.0);
        crash(mix, 6 * MEASURE);
        crash(mix, 16 * MEASURE);

        normalise(mix, 0.89);
        writeWav(path, mix);
        System.out.printf("wrote %s: %d frames, %.2f s, %.1f BPM, %d measures%n",
                path, frames, frames / (double) SAMPLE_RATE, BPM, MEASURES);
    }

    // --- instruments -----------------------------------------------------

    /** Sine whose pitch drops fast, the standard synthesised kick. */
    static void kick(double[] buf, double startSeconds) {
        int start = (int) (startSeconds * SAMPLE_RATE);
        int length = (int) (0.28 * SAMPLE_RATE);
        double phase = 0;
        for (int i = 0; i < length; i++) {
            int index = start + i;
            if (index < 0 || index >= buf.length) continue;
            double t = i / (double) SAMPLE_RATE;
            double freq = 45 + 95 * Math.exp(-t * 28);
            phase += 2 * Math.PI * freq / SAMPLE_RATE;
            buf[index] += Math.sin(phase) * Math.exp(-t * 9.0) * 0.95;
        }
    }

    static void snare(double[] buf, double startSeconds) {
        int start = (int) (startSeconds * SAMPLE_RATE);
        int length = (int) (0.20 * SAMPLE_RATE);
        double previous = 0;
        for (int i = 0; i < length; i++) {
            int index = start + i;
            if (index < 0 || index >= buf.length) continue;
            double t = i / (double) SAMPLE_RATE;
            double white = RANDOM.nextDouble() * 2 - 1;
            // Crude high pass, so the noise sits above the bass instead of on it.
            double filtered = white - previous;
            previous = white;
            double body = Math.sin(2 * Math.PI * 185 * t) * 0.35;
            buf[index] += (filtered * 0.7 + body) * Math.exp(-t * 26.0) * 0.55;
        }
    }

    static void hat(double[] buf, double startSeconds, double gain) {
        int start = (int) (startSeconds * SAMPLE_RATE);
        int length = (int) (0.05 * SAMPLE_RATE);
        double previous = 0;
        for (int i = 0; i < length; i++) {
            int index = start + i;
            if (index < 0 || index >= buf.length) continue;
            double t = i / (double) SAMPLE_RATE;
            double white = RANDOM.nextDouble() * 2 - 1;
            double filtered = white - previous;
            previous = white;
            buf[index] += filtered * Math.exp(-t * 150.0) * gain;
        }
    }

    static void crash(double[] buf, double startSeconds) {
        int start = (int) (startSeconds * SAMPLE_RATE);
        int length = (int) (1.2 * SAMPLE_RATE);
        double previous = 0;
        for (int i = 0; i < length; i++) {
            int index = start + i;
            if (index < 0 || index >= buf.length) continue;
            double t = i / (double) SAMPLE_RATE;
            double white = RANDOM.nextDouble() * 2 - 1;
            double filtered = white - previous;
            previous = white;
            buf[index] += filtered * Math.exp(-t * 3.2) * 0.22;
        }
    }

    /** Detuned saw pair, low-passed by a one-pole filter. */
    static void bass(double[] buf, double startSeconds, double freq, double durationSeconds) {
        int start = (int) (startSeconds * SAMPLE_RATE);
        int length = (int) (durationSeconds * SAMPLE_RATE);
        double state = 0;
        for (int i = 0; i < length; i++) {
            int index = start + i;
            if (index < 0 || index >= buf.length) continue;
            double t = i / (double) SAMPLE_RATE;
            double saw = saw(freq, t) * 0.6 + saw(freq * 1.005, t) * 0.4;
            state += (saw - state) * 0.10;
            double envelope = Math.min(1.0, t * 220) * Math.exp(-t * 3.4);
            buf[index] += state * envelope * 0.38;
        }
    }

    /** Triangle-ish lead with a quick attack and a long-ish tail. */
    static void pluck(double[] buf, double startSeconds, double freq,
                      double durationSeconds, double gain) {
        int start = (int) (startSeconds * SAMPLE_RATE);
        int length = (int) (durationSeconds * SAMPLE_RATE);
        for (int i = 0; i < length; i++) {
            int index = start + i;
            if (index < 0 || index >= buf.length) continue;
            double t = i / (double) SAMPLE_RATE;
            double tone = Math.sin(2 * Math.PI * freq * t)
                    + 0.32 * Math.sin(2 * Math.PI * freq * 2 * t)
                    + 0.14 * Math.sin(2 * Math.PI * freq * 3 * t);
            double envelope = Math.min(1.0, t * 400) * Math.exp(-t * 6.0);
            buf[index] += tone * envelope * gain;
        }
    }

    static double saw(double freq, double t) {
        double phase = (t * freq) % 1.0;
        return phase * 2 - 1;
    }

    // --- output ----------------------------------------------------------

    static void normalise(double[] buf, double peak) {
        double max = 0;
        for (double v : buf) max = Math.max(max, Math.abs(v));
        if (max <= 0) return;
        double scale = peak / max;
        for (int i = 0; i < buf.length; i++) buf[i] *= scale;
    }

    static void writeWav(String path, double[] mono) throws IOException {
        int frames = mono.length;
        int channels = 2;
        int bitsPerSample = 16;
        int byteRate = SAMPLE_RATE * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        int dataSize = frames * blockAlign;

        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + dataSize);
        writeAscii(out, "RIFF");
        writeInt(out, 36 + dataSize);
        writeAscii(out, "WAVE");
        writeAscii(out, "fmt ");
        writeInt(out, 16);
        writeShort(out, 1); // PCM
        writeShort(out, channels);
        writeInt(out, SAMPLE_RATE);
        writeInt(out, byteRate);
        writeShort(out, blockAlign);
        writeShort(out, bitsPerSample);
        writeAscii(out, "data");
        writeInt(out, dataSize);

        for (double v : mono) {
            int sample = (int) Math.round(Math.max(-1.0, Math.min(1.0, v)) * 32767);
            writeShort(out, sample); // left
            writeShort(out, sample); // right
        }

        try (FileOutputStream file = new FileOutputStream(path)) {
            out.writeTo(file);
        }
    }

    static void writeAscii(ByteArrayOutputStream out, String s) {
        for (int i = 0; i < s.length(); i++) out.write(s.charAt(i) & 0xFF);
    }

    static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    static void writeShort(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }
}
