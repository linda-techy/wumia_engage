package in.brand.engage.ingest.web;

import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/**
 * The countdown poster of an offer campaign (V44 {@code countdown_poster}):
 * the campaign's poster with an "OFFER ENDS IN HH:MM:SS" band, drawn when
 * the phone fetches the image, which is when it draws the notification. So the
 * notification shows the time left at the moment it appears, the closest a web
 * push gets to a native app's ticking timer. After the end it says so.
 *
 * <p>2:1 at 1024 px, what Chrome shows under a notification. The digits are
 * seven-segment shapes and the words pre-rendered PNGs, so the server needs no
 * fonts. Only campaigns with {@code countdown_poster} on, and only posters
 * uploaded through the console ({@code media}), are ever drawn.
 */
@Controller("/media/countdown")
public class CountdownImageController {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    static final int W = 1024;
    static final int H = 512;
    static final Color GOLD = new Color(0xF5, 0xC4, 0x51);
    static final Color NIGHT = new Color(0x2B, 0x0F, 0x1E);       // deep festive maroon when there is no poster
    private static final Pattern MEDIA = Pattern.compile(".*/media/([0-9a-f]{64})\\.(jpg|png)$");

    private final Db db;
    private final Clock clock;
    private final Map<String, byte[]> rendered = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > 32;
        }
    };

    @jakarta.inject.Inject
    public CountdownImageController(Db db) {
        this(db, Clock.systemUTC());
    }

    CountdownImageController(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    private record Campaign(Instant endsAt, byte[] poster) {}

    @Get("/{id}")
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<byte[]> get(@PathVariable String id) {
        UUID campaignId;
        try {
            campaignId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return HttpResponse.notFound();
        }
        var campaign = load(campaignId);
        if (campaign == null) return HttpResponse.notFound();
        var left = Duration.between(clock.instant(), campaign.endsAt());
        var key = campaignId + "/" + Math.max(-1, left.getSeconds());
        byte[] jpeg;
        synchronized (rendered) {
            jpeg = rendered.get(key);
        }
        if (jpeg == null) {
            jpeg = jpeg(draw(campaign.poster(), left));
            synchronized (rendered) {
                rendered.put(key, jpeg);
            }
        }
        return HttpResponse.ok(jpeg)
                .contentType("image/jpeg")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")        // the time left is of this moment only
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'");
    }

    private Campaign load(UUID id) {
        return db.inTx(c -> {
            Instant endsAt;
            String image;
            try (var ps = Sql.prepare(c, """
                    SELECT ends_at, vars->>'image' FROM campaigns
                     WHERE id = ? AND countdown_poster AND ends_at IS NOT NULL""", id);
                 var rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                endsAt = Sql.timestamp(rs, "ends_at").toInstant();
                image = rs.getString(2);
            }
            byte[] poster = null;
            var m = image == null ? null : MEDIA.matcher(image);
            if (m != null && m.matches()) {
                try (var ps = Sql.prepare(c, "SELECT bytes FROM media WHERE id = ?", m.group(1)); var rs = ps.executeQuery()) {
                    if (rs.next()) poster = rs.getBytes(1);
                }
            }
            return new Campaign(endsAt, poster);
        });
    }

    /* ------------------------------- drawing -------------------------------- */

    static BufferedImage draw(byte[] posterBytes, Duration left) {
        var img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        var poster = read(posterBytes);
        if (poster != null) {
            cover(g, poster);
        } else {
            g.setColor(NIGHT);
            g.fillRect(0, 0, W, H);
        }

        int bandH = 196;
        int bandY = H - bandH;
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, poster != null ? 0.66f : 1f));
        g.setColor(new Color(0x14, 0x08, 0x0E));
        g.fillRect(0, bandY, W, bandH);
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(GOLD);
        g.fillRect(0, bandY, W, 4);

        if (left.isNegative() || left.isZero()) {
            var ended = label("ended.png");
            g.drawImage(ended, (W - ended.getWidth()) / 2, bandY + (bandH - ended.getHeight()) / 2, null);
            g.dispose();
            return img;
        }

        var title = label("ends-in.png");
        g.drawImage(title, (W - title.getWidth()) / 2, bandY + 18, null);

        long s = left.getSeconds();
        long[] groups;
        String[] units;
        if (s >= 100 * 3600) {                                   // 100 h or more: days, hours, minutes
            groups = new long[] {Math.min(99, s / 86400), (s % 86400) / 3600, (s % 3600) / 60};
            units = new String[] {"days.png", "hrs.png", "min.png"};
        } else {
            groups = new long[] {s / 3600, (s % 3600) / 60, s % 60};
            units = new String[] {"hrs.png", "min.png", "sec.png"};
        }

        int dh = 84, dw = 46, gap = 12, colon = 34;              // digit height and width, gaps
        int groupW = dw * 2 + gap;
        int total = groupW * 3 + colon * 2;
        int x = (W - total) / 2;
        int y = bandY + 62;
        for (int i = 0; i < 3; i++) {
            var two = String.format("%02d", groups[i]);
            digit(g, two.charAt(0) - '0', x, y, dw, dh);
            digit(g, two.charAt(1) - '0', x + dw + gap, y, dw, dh);
            var unit = label(units[i]);
            g.drawImage(unit, x + (groupW - unit.getWidth()) / 2, y + dh + 10, null);
            x += groupW;
            if (i < 2) {
                int cx = x + colon / 2;
                g.setColor(GOLD);
                g.fillOval(cx - 5, y + dh / 3 - 5, 10, 10);
                g.fillOval(cx - 5, y + 2 * dh / 3 - 5, 10, 10);
                x += colon;
            }
        }
        g.dispose();
        return img;
    }

    /** Which of the seven segments (a b c d e f g) each digit lights. */
    static final boolean[][] SEGMENTS = {
            {true, true, true, true, true, true, false},     // 0
            {false, true, true, false, false, false, false}, // 1
            {true, true, false, true, true, false, true},    // 2
            {true, true, true, true, false, false, true},    // 3
            {false, true, true, false, false, true, true},   // 4
            {true, false, true, true, false, true, true},    // 5
            {true, false, true, true, true, true, true},     // 6
            {true, true, true, false, false, false, false},  // 7
            {true, true, true, true, true, true, true},      // 8
            {true, true, true, true, false, true, true},     // 9
    };

    static void digit(Graphics2D g, int d, int x, int y, int w, int h) {
        int t = Math.max(6, w / 5);                          // segment thickness
        int half = h / 2;
        var on = SEGMENTS[d];
        var lit = GOLD;
        var dim = new Color(GOLD.getRed(), GOLD.getGreen(), GOLD.getBlue(), 28);
        // a (top), b (top right), c (bottom right), d (bottom), e (bottom left), f (top left), g (middle)
        int[][] r = {
                {x + t / 2, y, w - t, t},
                {x + w - t, y + t / 2, t, half - t / 2},
                {x + w - t, y + half, t, half - t / 2},
                {x + t / 2, y + h - t, w - t, t},
                {x, y + half, t, half - t / 2},
                {x, y + t / 2, t, half - t / 2},
                {x + t / 2, y + half - t / 2, w - t, t},
        };
        for (int i = 0; i < 7; i++) {
            g.setColor(on[i] ? lit : dim);
            g.fill(new RoundRectangle2D.Float(r[i][0], r[i][1], r[i][2], r[i][3], t, t));
        }
    }

    /** Scale to cover 1024 x 512 and centre-crop. */
    private static void cover(Graphics2D g, BufferedImage src) {
        double scale = Math.max((double) W / src.getWidth(), (double) H / src.getHeight());
        int w = (int) Math.ceil(src.getWidth() * scale);
        int h = (int) Math.ceil(src.getHeight() * scale);
        g.drawImage(src, (W - w) / 2, (H - h) / 2, w, h, null);
    }

    private static BufferedImage read(byte[] bytes) {
        if (bytes == null) return null;
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            return null;
        }
    }

    private static final Map<String, BufferedImage> LABELS = new java.util.concurrent.ConcurrentHashMap<>();

    static BufferedImage label(String name) {
        return LABELS.computeIfAbsent(name, n -> {
            try (InputStream in = CountdownImageController.class.getResourceAsStream("/countdown/" + n)) {
                if (in == null) throw new IllegalStateException("missing /countdown/" + n);
                return ImageIO.read(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    static byte[] jpeg(BufferedImage img) {
        try {
            var out = new ByteArrayOutputStream(96 * 1024);
            ImageIO.write(img, "jpg", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
