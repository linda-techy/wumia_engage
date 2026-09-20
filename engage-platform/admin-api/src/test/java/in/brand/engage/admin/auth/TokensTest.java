package in.brand.engage.admin.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import in.brand.engage.admin.config.AdminProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TokensTest {

    Tokens tokens(Path dir) {
        return new Tokens(new AdminProperties(dir.resolve("jwt.pem").toString(), "00".repeat(32), ""));
    }

    @Test void issues_and_verifies_an_access_token(@TempDir Path dir) {
        var t = tokens(dir);
        var operator = UUID.randomUUID();
        var session = UUID.randomUUID();
        var claims = t.verify(t.issueAccess(operator, List.of("VIEWER", "ANALYST"), session, 3));
        assertEquals(operator, claims.operatorId());
        assertEquals(List.of("VIEWER", "ANALYST"), claims.roles());
        assertEquals(session, claims.sessionId());
        assertEquals(3, claims.ver());
    }

    @Test void generates_the_keypair_once_and_reuses_it(@TempDir Path dir) throws Exception {
        var jwt = tokens(dir).issueAccess(UUID.randomUUID(), List.of("VIEWER"), UUID.randomUUID(), 1);
        var pem = Files.readString(dir.resolve("jwt.pem"));
        assertTrue(pem.contains("PRIVATE KEY"), "keypair should be written on first use");
        // A second instance reading the same file must verify the first instance's token.
        assertNotNull(tokens(dir).verify(jwt));
    }

    @Test void rejects_a_token_signed_by_another_key(@TempDir Path a, @TempDir Path b) {
        var jwt = tokens(a).issueAccess(UUID.randomUUID(), List.of("VIEWER"), UUID.randomUUID(), 1);
        assertThrows(Tokens.InvalidToken.class, () -> tokens(b).verify(jwt));
    }

    @Test void rejects_a_tampered_token(@TempDir Path dir) {
        var t = tokens(dir);
        var jwt = t.issueAccess(UUID.randomUUID(), List.of("VIEWER"), UUID.randomUUID(), 1);
        var parts = jwt.split("\\.");
        var tampered = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "AA." + parts[2];
        assertThrows(Tokens.InvalidToken.class, () -> t.verify(tampered));
    }

    @Test void purpose_tokens_do_not_verify_under_another_purpose(@TempDir Path dir) {
        var t = tokens(dir);
        var jwt = t.issuePurpose(UUID.randomUUID(), "mfa", Map.of(), Duration.ofMinutes(5));
        assertThrows(Tokens.InvalidToken.class, () -> t.verifyPurpose(jwt, "set-password"));
        assertNotNull(t.verifyPurpose(jwt, "mfa"));
    }

    @Test void an_expired_token_is_rejected(@TempDir Path dir) throws Exception {
        var t = tokens(dir);
        var jwt = t.issuePurpose(UUID.randomUUID(), "mfa", Map.of(), Duration.ofMillis(1));
        Thread.sleep(50);
        assertThrows(Tokens.InvalidToken.class, () -> t.verifyPurpose(jwt, "mfa"));
    }

    @Test void rejects_a_token_signed_with_a_different_algorithm(@TempDir Path dir) throws Exception {
        var t = tokens(dir);
        var operator = UUID.randomUUID();
        // Force the keypair into existence, then re-sign equivalent claims with PS256 using
        // that same private key: RSASSAVerifier alone would happily accept this, so this
        // pins the explicit RS256 header check rather than the signature check.
        t.issueAccess(operator, List.of("VIEWER"), UUID.randomUUID(), 1);
        var body = Files.readString(dir.resolve("jwt.pem"))
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        var privateKey = (RSAPrivateKey) KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
        var claims = new JWTClaimsSet.Builder()
                .subject(operator.toString())
                .issuer("engage-admin")
                .claim("roles", List.of("VIEWER"))
                .claim("sid", UUID.randomUUID().toString())
                .claim("ver", 1)
                .expirationTime(Date.from(Instant.now().plus(Duration.ofMinutes(15))))
                .build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.PS256), claims);
        jwt.sign(new RSASSASigner(privateKey));
        assertThrows(Tokens.InvalidToken.class, () -> t.verify(jwt.serialize()));
    }
}
