package in.brand.engage.channels.email;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * AWS Signature Version 4 for one request (P7-T01): the SES v2 API is called
 * with the JDK's HTTP client, like the WhatsApp and MSG91 adapters, rather
 * than the AWS SDK and its HTTP stack. Checked against AWS's published
 * "get-vanilla" test vector (Sigv4Test).
 */
final class SigV4 {

    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    /** The headers to add to the request: {@code x-amz-date}, optional {@code x-amz-security-token}, {@code authorization}. */
    record Signed(String amzDate, String authorization) {}

    private SigV4() {}

    /**
     * @param headers the request's other headers to sign (names any case); {@code host}
     *                and {@code x-amz-date} are added here
     */
    static Signed sign(String method, URI uri, Map<String, String> headers, byte[] payload, Instant now,
                       String region, String service, String accessKey, String secretKey, String sessionToken) {
        var amzDate = AMZ_DATE.format(now);
        var date = DATE.format(now);
        var canonicalHeaders = new TreeMap<String, String>();
        headers.forEach((k, v) -> canonicalHeaders.put(k.toLowerCase(Locale.ROOT), v.strip().replaceAll("\\s+", " ")));
        canonicalHeaders.put("host", uri.getPort() > 0 ? uri.getHost() + ":" + uri.getPort() : uri.getHost());
        canonicalHeaders.put("x-amz-date", amzDate);
        if (sessionToken != null && !sessionToken.isBlank()) canonicalHeaders.put("x-amz-security-token", sessionToken);

        var headerBlock = new StringBuilder();
        canonicalHeaders.forEach((k, v) -> headerBlock.append(k).append(':').append(v).append('\n'));
        var signedHeaders = String.join(";", canonicalHeaders.keySet());
        var path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        var query = uri.getRawQuery() == null ? "" : uri.getRawQuery();
        var canonicalRequest = method + "\n" + path + "\n" + query + "\n" + headerBlock + "\n" + signedHeaders + "\n"
                + hex(sha256(payload));

        var scope = date + "/" + region + "/" + service + "/aws4_request";
        var stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                + hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        var key = hmac(hmac(hmac(hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date), region), service), "aws4_request");
        var signature = hex(hmac(key, stringToSign));
        return new Signed(amzDate, "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature);
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }
}
