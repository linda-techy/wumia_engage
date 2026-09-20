package in.brand.engage.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Loads SQL from src/main/resources/sql. The same files are exercised against
 * real Postgres with fixture payloads, so the query text that ships is the
 * query text that was tested.
 *
 * <p>Full-line {@code --} comments are stripped before preparing, so a comment
 * containing an apostrophe or a question mark can never be mistaken by the
 * driver for a string literal or a bind parameter.
 */
public final class SqlFiles {

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private SqlFiles() {}

    public static String get(String name) {
        return CACHE.computeIfAbsent(name, SqlFiles::load);
    }

    private static String load(String name) {
        var path = "sql/" + name;
        try (InputStream in = SqlFiles.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing SQL resource " + path);
            var text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return text.lines()
                    .filter(l -> !l.stripLeading().startsWith("--"))
                    .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
