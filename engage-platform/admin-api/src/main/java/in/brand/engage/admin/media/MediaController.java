package in.brand.engage.admin.media;

import in.brand.engage.admin.auth.CurrentOperator;
import in.brand.engage.admin.auth.RequiresRole;
import in.brand.engage.admin.auth.Role;
import in.brand.engage.admin.web.Problems;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Post;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.serde.annotation.Serdeable;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Posters for push campaigns: the large image under the notification text
 * (Chrome and Edge on Android and Windows; iPhone and Safari show text only).
 *
 * <ul>
 * <li>{@code POST /api/media}: the image file as the body, {@code image/jpeg}
 *     or {@code image/png}, at most 1 MB. The type and size are read from the
 *     file itself, not trusted from the browser. Returns the public URL to put
 *     in the campaign's {@code image}. CAMPAIGN_EDIT.</li>
 * <li>{@code GET /api/media}: the latest 30 uploads, to reuse one. CAMPAIGN_EDIT.</li>
 * </ul>
 * Served by ingest-api at {@code PUBLIC_BASE_URL/media/<sha256>.<ext>}.
 */
@Controller("/api/media")
@ExecuteOn(TaskExecutors.BLOCKING)
public class MediaController {

    static final int MAX_BYTES = 1_048_576;
    static final int MAX_SIDE = 2048;
    static final int MIN_WIDTH = 300;

    @Serdeable
    public record Media(String id, String url, String contentType, int width, int height, int sizeBytes,
                        String name, boolean wide, OffsetDateTime createdAt) {}

    private final Db db;
    private final CurrentOperator current;
    private final String base;

    public MediaController(Db db, CurrentOperator current, @Value("${PUBLIC_BASE_URL:}") String publicBaseUrl) {
        this.db = db;
        this.current = current;
        this.base = publicBaseUrl == null ? "" : publicBaseUrl.strip().replaceAll("/+$", "");
    }

    @RequiresRole(Role.CAMPAIGN_EDIT)
    @Post(consumes = {"image/jpeg", "image/png"})
    public Media upload(@Body byte[] bytes, @Nullable @Header("X-File-Name") String fileName) {
        if (!base.startsWith("https://")) {
            throw Problems.conflict("no-public-url", "PUBLIC_BASE_URL is not set to an https address, so a poster could not be shown");
        }
        if (bytes == null || bytes.length == 0) throw Problems.badRequest("send the image file as the body");
        if (bytes.length > MAX_BYTES) throw Problems.badRequest("the poster is " + bytes.length / 1024 + " KB; at most 1024 KB");
        var type = sniff(bytes);
        if (type == null) throw Problems.badRequest("only JPEG or PNG images");
        int[] size = dimensions(bytes);
        if (size == null) throw Problems.badRequest("the image could not be read");
        if (size[0] > MAX_SIDE || size[1] > MAX_SIDE) throw Problems.badRequest("at most 2048 px on a side; 1024 × 512 is ideal");
        if (size[0] < MIN_WIDTH) throw Problems.badRequest("at least 300 px wide; 1024 × 512 is ideal");
        var id = sha256(bytes);
        var name = fileName == null ? null : fileName.replaceAll("[^A-Za-z0-9 ._()-]", "").strip();
        var operator = current.id();
        db.inTx(c -> Sql.update(c, """
                INSERT INTO media (id, content_type, bytes, width, height, size_bytes, name, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING""",
                id, type, bytes, size[0], size[1], bytes.length, name == null || name.isEmpty() ? null : name, operator));
        return list().stream().filter(m -> m.id().equals(id)).findFirst()
                .orElseThrow(() -> Problems.notFound("upload " + id + " vanished"));
    }

    @RequiresRole(Role.CAMPAIGN_EDIT)
    @Get
    public List<Media> list() {
        return db.inTx(c -> {
            var out = new ArrayList<Media>();
            try (var ps = Sql.prepare(c, """
                    SELECT id, content_type, width, height, size_bytes, name, created_at
                      FROM media ORDER BY created_at DESC LIMIT 30""");
                 var rs = ps.executeQuery()) {
                while (rs.next()) {
                    var id = rs.getString("id");
                    var type = rs.getString("content_type");
                    int w = rs.getInt("width");
                    int h = rs.getInt("height");
                    out.add(new Media(id, base + "/media/" + id + (type.equals("image/png") ? ".png" : ".jpg"), type, w, h,
                            rs.getInt("size_bytes"), rs.getString("name"), wide(w, h), Sql.timestamp(rs, "created_at")));
                }
            }
            return out;
        });
    }

    /** About 2:1, the shape Chrome crops a notification image to. */
    static boolean wide(int w, int h) {
        double r = (double) w / h;
        return r >= 1.8 && r <= 2.2;
    }

    /** The real type from the first bytes. */
    static String sniff(byte[] b) {
        if (b.length > 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff) return "image/jpeg";
        if (b.length > 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        return null;
    }

    /** Width and height from the header, without decoding the whole picture. */
    static int[] dimensions(byte[] bytes) {
        try (var in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return null;
            var reader = readers.next();
            try {
                reader.setInput(in, true, true);
                return new int[] {reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
