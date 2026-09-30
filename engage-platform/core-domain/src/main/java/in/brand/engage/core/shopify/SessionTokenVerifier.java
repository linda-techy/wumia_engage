package in.brand.engage.core.shopify;

import in.brand.engage.core.crypto.Hmacs;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Verifies a Shopify session token (checkout and customer account UI
 * extensions): an HS256 JWT signed with the app's client secret (phase-2 §6.2).
 *
 * <p>Pure Java on purpose. One algorithm needs no JWT library, and without one
 * the algorithm-confusion class of bugs cannot happen: the header must say
 * {@code HS256} exactly, so {@code none}, {@code RS256} and friends are refused
 * before any key is used.
 *
 * <p>Checks, in order: three base64url parts; {@code alg == "HS256"}; the
 * signature (constant time); {@code exp} and {@code nbf} with 10 s leeway;
 * {@code aud} is this app's client id; {@code dest}'s host is this shop.
 */
public final class SessionTokenVerifier {

    public static final Duration LEEWAY = Duration.ofSeconds(10);

    /** What the controller may rely on after verification. {@code sub} is null for a guest checkout. */
    public record Claims(String shop, String sub, Instant expiresAt) {}

    public static final class InvalidToken extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public InvalidToken(String reason) {
            super(reason);
        }
    }

    private final String apiSecret;
    private final String clientId;
    private final String shopDomain;
    private final Clock clock;

    public SessionTokenVerifier(String apiSecret, String clientId, String shopDomain, Clock clock) {
        if (apiSecret == null || apiSecret.isBlank()) throw new IllegalArgumentException("Shopify API secret not set");
        if (clientId == null || clientId.isBlank()) throw new IllegalArgumentException("Shopify client id not set");
        if (shopDomain == null || shopDomain.isBlank()) throw new IllegalArgumentException("Shopify shop domain not set");
        this.apiSecret = apiSecret;
        this.clientId = clientId;
        this.shopDomain = shopDomain;
        this.clock = clock;
    }

    /** @throws InvalidToken with a reason safe to log (never the token itself) */
    public Claims verify(String token) {
        if (token == null) throw new InvalidToken("missing");
        var parts = token.strip().split("\\.", -1);
        if (parts.length != 3) throw new InvalidToken("not a JWT");

        var header = object(decode(parts[0]), "header");
        if (!"HS256".equals(header.get("alg"))) throw new InvalidToken("alg must be HS256");

        var expected = Hmacs.sha256(apiSecret, (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        if (!MessageDigest.isEqual(expected, decode(parts[2]))) throw new InvalidToken("bad signature");

        var claims = object(decode(parts[1]), "payload");
        var now = clock.instant();
        var exp = seconds(claims, "exp");
        var nbf = seconds(claims, "nbf");
        if (now.isAfter(exp.plus(LEEWAY))) throw new InvalidToken("expired");
        if (now.isBefore(nbf.minus(LEEWAY))) throw new InvalidToken("not yet valid");

        var aud = claims.get("aud");
        boolean audOk = aud instanceof String s ? s.equals(clientId)
                : aud instanceof List<?> l && l.contains(clientId);
        if (!audOk) throw new InvalidToken("wrong aud");

        var shop = host(claims.get("dest"));
        if (shop == null || !shop.equalsIgnoreCase(shopDomain)) throw new InvalidToken("wrong dest");

        return new Claims(shop.toLowerCase(), claims.get("sub") instanceof String s ? s : null, exp);
    }

    private static byte[] decode(String part) {
        try {
            return Base64.getUrlDecoder().decode(part);
        } catch (IllegalArgumentException e) {
            throw new InvalidToken("not base64url");
        }
    }

    private static Instant seconds(Map<String, Object> claims, String name) {
        if (!(claims.get(name) instanceof BigDecimal n)) throw new InvalidToken(name + " missing");
        return Instant.ofEpochSecond(n.longValue());
    }

    /** "https://shop.myshopify.com" or a bare host → the host. */
    private static String host(Object dest) {
        if (!(dest instanceof String s) || s.isBlank()) return null;
        try {
            return s.contains("://") ? URI.create(s).getHost() : s.strip();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(byte[] json, String what) {
        try {
            var p = new Json(new String(json, StandardCharsets.UTF_8));
            var v = p.value();
            p.end();
            if (!(v instanceof Map)) throw new InvalidToken(what + " is not a JSON object");
            return (Map<String, Object>) v;
        } catch (IllegalStateException e) {
            throw new InvalidToken(what + " is not valid JSON");
        }
    }

    /** Minimal strict JSON reader: objects, arrays, strings, numbers, booleans, null. */
    private static final class Json {
        private final String s;
        private int i;

        Json(String s) {
            this.s = s;
        }

        Object value() {
            ws();
            if (i >= s.length()) throw new IllegalStateException("eof");
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> obj();
                case '[' -> arr();
                case '"' -> str();
                case 't' -> lit("true", Boolean.TRUE);
                case 'f' -> lit("false", Boolean.FALSE);
                case 'n' -> lit("null", null);
                default -> num();
            };
        }

        void end() {
            ws();
            if (i != s.length()) throw new IllegalStateException("trailing data");
        }

        private Map<String, Object> obj() {
            var m = new LinkedHashMap<String, Object>();
            i++;
            ws();
            if (peek() == '}') { i++; return m; }
            while (true) {
                ws();
                if (peek() != '"') throw new IllegalStateException("key");
                var k = str();
                ws();
                expect(':');
                if (m.put(k, value()) != null) throw new IllegalStateException("duplicate key");
                ws();
                if (peek() == ',') { i++; continue; }
                expect('}');
                return m;
            }
        }

        private List<Object> arr() {
            var l = new ArrayList<Object>();
            i++;
            ws();
            if (peek() == ']') { i++; return l; }
            while (true) {
                l.add(value());
                ws();
                if (peek() == ',') { i++; continue; }
                expect(']');
                return l;
            }
        }

        private String str() {
            var b = new StringBuilder();
            i++;
            while (true) {
                if (i >= s.length()) throw new IllegalStateException("unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c < 0x20) throw new IllegalStateException("control character");
                if (c != '\\') { b.append(c); continue; }
                if (i >= s.length()) throw new IllegalStateException("escape");
                char e = s.charAt(i++);
                switch (e) {
                    case '"', '\\', '/' -> b.append(e);
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r');
                    case 't' -> b.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) throw new IllegalStateException("escape");
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> throw new IllegalStateException("escape");
                }
            }
        }

        private BigDecimal num() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (start == i) throw new IllegalStateException("unexpected character");
            try {
                return new BigDecimal(s.substring(start, i));
            } catch (NumberFormatException e) {
                throw new IllegalStateException("number");
            }
        }

        private Object lit(String word, Object v) {
            if (!s.startsWith(word, i)) throw new IllegalStateException("literal");
            i += word.length();
            return v;
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private char peek() {
            if (i >= s.length()) throw new IllegalStateException("eof");
            return s.charAt(i);
        }

        private void expect(char c) {
            if (peek() != c) throw new IllegalStateException("expected " + c);
            i++;
        }
    }
}
