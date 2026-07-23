import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Draws the launcher icon for launchers too old to take the adaptive one.
 *
 * The same mark as ic_launcher_foreground.xml -- two bars and an object between
 * them -- laid out in the same 108-unit space so the two cannot drift apart.
 * Kept as a generator rather than checked-in artwork so a change to the design
 * is one edit rather than five re-exports.
 *
 *   javac -d tools/out tools/MakeLauncherIcons.java
 *   java -cp tools/out MakeLauncherIcons app/src/main/res
 */
public final class MakeLauncherIcons {

    private static final int UNITS = 108;

    private static final Color BACKGROUND = new Color(0x05, 0x08, 0x0F);
    private static final Color FAR_BAR = new Color(0x2E, 0x9B, 0xFF);
    private static final Color NEAR_BAR = new Color(0xFF, 0x2D, 0x55);
    private static final Color RING = new Color(0xFF, 0x4D, 0x8D);
    private static final Color CORE = new Color(0xFF, 0xD9, 0xE8);

    /** Density buckets Android still asks for, and the pixel size of each. */
    private static final String[] BUCKETS = {
        "mipmap-mdpi", "mipmap-hdpi", "mipmap-xhdpi", "mipmap-xxhdpi", "mipmap-xxxhdpi",
    };
    private static final int[] SIZES = { 48, 72, 96, 144, 192 };

    public static void main(String[] args) throws Exception {
        File res = new File(args.length > 0 ? args[0] : "app/src/main/res");

        for (int i = 0; i < BUCKETS.length; i++) {
            File dir = new File(res, BUCKETS[i]);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IllegalStateException("could not create " + dir);
            }
            int size = SIZES[i];
            write(new File(dir, "ic_launcher.png"), size, false);
            write(new File(dir, "ic_launcher_round.png"), size, true);
            System.out.printf("%s: %dx%d%n", BUCKETS[i], size, size);
        }
    }

    private static void write(File target, int size, boolean round) throws Exception {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        // Legacy icons carry their own shape, and nothing masks them.
        g.setColor(BACKGROUND);
        if (round) {
            g.fill(new Ellipse2D.Float(0, 0, size, size));
        } else {
            float radius = size * 0.22f;
            g.fill(new RoundRectangle2D.Float(0, 0, size, size, radius, radius));
        }

        // The adaptive icon reserves a margin for masking that a legacy one does
        // not, so the mark is grown to fill the space it actually gets.
        float scale = size / (float) UNITS * 1.18f;
        g.translate(size / 2f, size / 2f);
        g.scale(scale, scale);
        g.translate(-UNITS / 2f, -UNITS / 2f);

        bar(g, FAR_BAR, 29f, 5f);
        bar(g, NEAR_BAR, 74f, 6f);

        Area ring = new Area(circle(54f, 54f, 15f));
        ring.subtract(new Area(circle(54f, 54f, 9f)));
        g.setColor(RING);
        g.fill(ring);

        g.setColor(CORE);
        g.fill(circle(54f, 54f, 6.5f));

        g.dispose();
        ImageIO.write(image, "png", target);
    }

    private static void bar(Graphics2D g, Color colour, float y, float thickness) {
        g.setColor(colour);
        g.setStroke(new BasicStroke(thickness, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(28, (int) y, 80, (int) y);
    }

    private static Ellipse2D.Float circle(float cx, float cy, float r) {
        return new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2);
    }
}
