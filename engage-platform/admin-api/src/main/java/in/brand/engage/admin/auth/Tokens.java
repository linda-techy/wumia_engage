package in.brand.engage.admin.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import in.brand.engage.admin.config.AdminProperties;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Access tokens: RS256, 15 minutes, verified without touching Postgres.
 * The keypair lives in one PEM file so a restart does not log everyone out.
 */
@Singleton
public class Tokens {

    public static final Duration ACCESS_TTL = Duration.ofMinutes(15);
    private static final String ISSUER = "engage-admin";

    public record Claims(UUID operatorId, List<String> roles, UUID sessionId, int ver) {}

    public static final class InvalidToken extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public InvalidToken(String message) { super(message); }
    }

    private final RSAPrivateKey privateKey;
    private final RSAPublicKey publicKey;

    public Tokens(AdminProperties properties) {
        var keyPair = loadOrCreate(Path.of(properties.jwtKeyFile()));
        this.privateKey = (RSAPrivateKey) keyPair.getPrivate();
        this.publicKey = (RSAPublicKey) keyPair.getPublic();
    }

    public String issueAccess(UUID operatorId, List<String> roles, UUID sessionId, int ver) {
        return sign(new JWTClaimsSet.Builder()
                .subject(operatorId.toString())
                .issuer(ISSUER)
                .claim("roles", roles)
                .claim("sid", sessionId.toString())
                .claim("ver", ver)
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plus(ACCESS_TTL)))
                .build());
    }

    public Claims verify(String jwt) {
        var claims = parse(jwt);
        try {
            if (claims.getStringListClaim("roles") == null || claims.getStringClaim("sid") == null) {
                throw new InvalidToken("not an access token");
            }
            return new Claims(
                    UUID.fromString(claims.getSubject()),
                    List.copyOf(claims.getStringListClaim("roles")),
                    UUID.fromString(claims.getStringClaim("sid")),
                    claims.getIntegerClaim("ver"));
        } catch (java.text.ParseException | IllegalArgumentException | NullPointerException e) {
            throw new InvalidToken("malformed access token");
        }
    }

    /** Short-lived single-purpose token: the MFA step, a set-password link. */
    public String issuePurpose(UUID subject, String purpose, Map<String, String> extra, Duration ttl) {
        var builder = new JWTClaimsSet.Builder()
                .subject(subject.toString())
                .issuer(ISSUER)
                .claim("purpose", purpose)
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plus(ttl)));
        extra.forEach(builder::claim);
        return sign(builder.build());
    }

    public Map<String, Object> verifyPurpose(String jwt, String purpose) {
        var claims = parse(jwt);
        if (!purpose.equals(claims.getClaim("purpose"))) throw new InvalidToken("wrong purpose");
        return claims.getClaims();
    }

    private String sign(JWTClaimsSet claims) {
        try {
            var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(new RSASSASigner(privateKey));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("signing failed", e);
        }
    }

    private JWTClaimsSet parse(String jwt) {
        try {
            var parsed = SignedJWT.parse(jwt);
            // RSASSAVerifier alone accepts the whole RSASSA family (RS*, PS*); pin to the
            // one algorithm this class ever signs with, per spec.
            if (!JWSAlgorithm.RS256.equals(parsed.getHeader().getAlgorithm())) throw new InvalidToken("wrong algorithm");
            if (!parsed.verify(new RSASSAVerifier(publicKey))) throw new InvalidToken("bad signature");
            var claims = parsed.getJWTClaimsSet();
            var expiry = claims.getExpirationTime();
            if (expiry == null || expiry.toInstant().isBefore(Instant.now())) throw new InvalidToken("expired");
            if (!ISSUER.equals(claims.getIssuer())) throw new InvalidToken("wrong issuer");
            return claims;
        } catch (java.text.ParseException | JOSEException e) {
            throw new InvalidToken("unparseable token");
        }
    }

    private static KeyPair loadOrCreate(Path pem) {
        try {
            if (Files.exists(pem)) return readPem(pem);
            if (pem.getParent() != null) Files.createDirectories(pem.getParent());
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            var encoded = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pair.getPrivate().getEncoded());
            Files.writeString(pem, "-----BEGIN PRIVATE KEY-----\n" + encoded + "\n-----END PRIVATE KEY-----\n");
            // Best-effort lock-down for shared POSIX hosts: owner read/write only. Windows'
            // default filesystem has no POSIX permission view, so this throws there and is
            // caught — the file keeps whatever ACL its parent directory hands it, same as
            // before this change; it does not attempt an equivalent Windows ACL restriction.
            try {
                Files.setPosixFilePermissions(pem, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // No POSIX view (e.g. Windows) — nothing to tighten.
            }
            return pair;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("cannot create RSA keypair", e);
        }
    }

    private static KeyPair readPem(Path pem) throws IOException, java.security.GeneralSecurityException {
        var body = Files.readString(pem)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        var factory = KeyFactory.getInstance("RSA");
        var priv = (RSAPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
        // The PEM holds only the PKCS#8 private key, so the public key is rebuilt from it.
        // A CRT-form key (what every JCE provider produces, including this class's own
        // generator) carries the real public exponent — read it instead of assuming F4, so a
        // PEM regenerated by other tooling with a non-standard exponent still verifies after
        // a restart instead of failing silently. 65537 is only a fallback for the rare
        // provider that hands back a plain (non-CRT) RSAPrivateKey, which has no exponent to read.
        var exponent = priv instanceof RSAPrivateCrtKey crt
                ? crt.getPublicExponent()
                : java.math.BigInteger.valueOf(65537);
        var pub = (RSAPublicKey) factory.generatePublic(new java.security.spec.RSAPublicKeySpec(
                priv.getModulus(), exponent));
        return new KeyPair(pub, priv);
    }
}
