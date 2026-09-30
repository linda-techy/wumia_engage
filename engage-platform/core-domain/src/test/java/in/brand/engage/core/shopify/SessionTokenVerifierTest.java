package in.brand.engage.core.shopify;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.core.shopify.SessionTokenVerifier.InvalidToken;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Vectors were signed independently with Python's hmac (not with this code):
 * secret "test-secret-shpss-0001", aud "client-abc", dest wumika-dev,
 * nbf 1790000000, exp 1790000060.
 */
class SessionTokenVerifierTest {

    static final String SECRET = "test-secret-shpss-0001";
    static final long NBF = 1_790_000_000L;
    static final long EXP = 1_790_000_060L;

    static final String VALID =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJodHRwczovL3d1bWlrYS1kZXYubXlzaG9waWZ5LmNvbS9hZG1pbiIsImRlc3QiOiJodHRwczovL3d1bWlrYS1kZXYubXlzaG9waWZ5LmNvbSIsImF1ZCI6ImNsaWVudC1hYmMiLCJzdWIiOiJnaWQ6Ly9zaG9waWZ5L0N1c3RvbWVyLzc3ODgwMDExMjIiLCJleHAiOjE3OTAwMDAwNjAsIm5iZiI6MTc5MDAwMDAwMCwiaWF0IjoxNzkwMDAwMDAwLCJqdGkiOiI4YTFlMGM5ZS0wMDAwLTQwMDAtODAwMC0wMDAwMDAwMDAwMDEiLCJzaWQiOiJzMSJ9.yDT-s3E8XE1hw-Fef3f2CZt_2pMdhhohLzP0sh04UZk";
    static final String GUEST =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJodHRwczovL3d1bWlrYS1kZXYubXlzaG9waWZ5LmNvbS9hZG1pbiIsImRlc3QiOiJodHRwczovL3d1bWlrYS1kZXYubXlzaG9waWZ5LmNvbSIsImF1ZCI6ImNsaWVudC1hYmMiLCJleHAiOjE3OTAwMDAwNjAsIm5iZiI6MTc5MDAwMDAwMCwiaWF0IjoxNzkwMDAwMDAwLCJqdGkiOiI4YTFlMGM5ZS0wMDAwLTQwMDAtODAwMC0wMDAwMDAwMDAwMDEiLCJzaWQiOiJzMSJ9.BgcZIEumkGK4pIeyPOp_2KyzwCwJa2hFeTHpgeKXAwE";
    static final String WRONG_AUD =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJodHRwczovL3d1bWlrYS1kZXYubXlzaG9waWZ5LmNvbS9hZG1pbiIsImRlc3QiOiJodHRwczovL3d1bWlrYS1kZXYubXlzaG9waWZ5LmNvbSIsImF1ZCI6InNvbWVvbmUtZWxzZSIsInN1YiI6ImdpZDovL3Nob3BpZnkvQ3VzdG9tZXIvNzc4ODAwMTEyMiIsImV4cCI6MTc5MDAwMDA2MCwibmJmIjoxNzkwMDAwMDAwLCJpYXQiOjE3OTAwMDAwMDAsImp0aSI6IjhhMWUwYzllLTAwMDAtNDAwMC04MDAwLTAwMDAwMDAwMDAwMSIsInNpZCI6InMxIn0.1xbdsE5HcfEylwGZeag1FCNO2nrKFT78NsB6WJSq870";
    static final String WRONG_DEST =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJodHRwczovL3d1bWlrYS1kZXYubXlzaG9waWZ5LmNvbS9hZG1pbiIsImRlc3QiOiJodHRwczovL290aGVyLXNob3AubXlzaG9waWZ5LmNvbSIsImF1ZCI6ImNsaWVudC1hYmMiLCJzdWIiOiJnaWQ6Ly9zaG9waWZ5L0N1c3RvbWVyLzc3ODgwMDExMjIiLCJleHAiOjE3OTAwMDAwNjAsIm5iZiI6MTc5MDAwMDAwMCwiaWF0IjoxNzkwMDAwMDAwLCJqdGkiOiI4YTFlMGM5ZS0wMDAwLTQwMDAtODAwMC0wMDAwMDAwMDAwMDEiLCJzaWQiOiJzMSJ9.eX9sk-HzHEvoxBTPYyaZLTZpfxxZldjBWkZ863LTiiE";
    static final String ALG_NONE =
            "eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0.eyJpc3MiOiJodHRwczovL3d1bWlrYS1kZXYubXlzaG9waWZ5LmNvbS9hZG1pbiIsImRlc3QiOiJodHRwczovL3d1bWlrYS1kZXYubXlzaG9waWZ5LmNvbSIsImF1ZCI6ImNsaWVudC1hYmMiLCJzdWIiOiJnaWQ6Ly9zaG9waWZ5L0N1c3RvbWVyLzc3ODgwMDExMjIiLCJleHAiOjE3OTAwMDAwNjAsIm5iZiI6MTc5MDAwMDAwMCwiaWF0IjoxNzkwMDAwMDAwLCJqdGkiOiI4YTFlMGM5ZS0wMDAwLTQwMDAtODAwMC0wMDAwMDAwMDAwMDEiLCJzaWQiOiJzMSJ9.";
    static final String ALG_HS512 =
            "eyJhbGciOiJIUzUxMiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJodHRwczovL3d1bWlrYS1kZXYubXlzaG9waWZ5LmNvbS9hZG1pbiIsImRlc3QiOiJodHRwczovL3d1bWlrYS1kZXYubXlzaG9waWZ5LmNvbSIsImF1ZCI6ImNsaWVudC1hYmMiLCJzdWIiOiJnaWQ6Ly9zaG9waWZ5L0N1c3RvbWVyLzc3ODgwMDExMjIiLCJleHAiOjE3OTAwMDAwNjAsIm5iZiI6MTc5MDAwMDAwMCwiaWF0IjoxNzkwMDAwMDAwLCJqdGkiOiI4YTFlMGM5ZS0wMDAwLTQwMDAtODAwMC0wMDAwMDAwMDAwMDEiLCJzaWQiOiJzMSJ9.2k3BOKvNMx94YfnlLE3XK9I3IhpBbG27DJRjoqu_dCw";
    static final String WRONG_SECRET =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJodHRwczovL3d1bWlrYS1kZXYubXlzaG9waWZ5LmNvbS9hZG1pbiIsImRlc3QiOiJodHRwczovL3d1bWlrYS1kZXYubXlzaG9waWZ5LmNvbSIsImF1ZCI6ImNsaWVudC1hYmMiLCJzdWIiOiJnaWQ6Ly9zaG9waWZ5L0N1c3RvbWVyLzc3ODgwMDExMjIiLCJleHAiOjE3OTAwMDAwNjAsIm5iZiI6MTc5MDAwMDAwMCwiaWF0IjoxNzkwMDAwMDAwLCJqdGkiOiI4YTFlMGM5ZS0wMDAwLTQwMDAtODAwMC0wMDAwMDAwMDAwMDEiLCJzaWQiOiJzMSJ9.ZkoK5x7B6Tvn-idozZblLxzt7HGcBMRfDxEfl2dxkYI";
    static final String TAMPERED =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJodHRwczovL3d1bWlrYS1kZXYubXlzaG9waWZ5LmNvbS9hZG1pbiIsImRlc3QiOiJodHRwczovL3d1bWlrYS1kZXYubXlzaG9waWZ5LmNvbSIsImF1ZCI6ImNsaWVudC1hYmMiLCJzdWIiOiJnaWQ6Ly9zaG9waWZ5L0N1c3RvbWVyLzEiLCJleHAiOjE3OTAwMDAwNjAsIm5iZiI6MTc5MDAwMDAwMCwiaWF0IjoxNzkwMDAwMDAwLCJqdGkiOiI4YTFlMGM5ZS0wMDAwLTQwMDAtODAwMC0wMDAwMDAwMDAwMDEiLCJzaWQiOiJzMSJ9.yDT-s3E8XE1hw-Fef3f2CZt_2pMdhhohLzP0sh04UZk";

    static SessionTokenVerifier at(long epochSecond) {
        return new SessionTokenVerifier(SECRET, "client-abc", "wumika-dev.myshopify.com",
                Clock.fixed(Instant.ofEpochSecond(epochSecond), ZoneOffset.UTC));
    }

    static String reason(Runnable r) {
        return assertThrows(InvalidToken.class, r::run).getMessage();
    }

    @Test void a_valid_token_names_the_shop_and_the_customer() {
        var claims = at(NBF + 30).verify(VALID);
        assertEquals("wumika-dev.myshopify.com", claims.shop());
        assertEquals("gid://shopify/Customer/7788001122", claims.sub());
        assertEquals(Instant.ofEpochSecond(EXP), claims.expiresAt());
    }

    @Test void a_guest_checkout_has_no_customer() {
        assertNull(at(NBF + 30).verify(GUEST).sub());
    }

    @Test void expiry_and_not_before_allow_ten_seconds_of_clock_skew() {
        assertDoesNotThrow(() -> at(EXP + 10).verify(VALID));
        assertEquals("expired", reason(() -> at(EXP + 11).verify(VALID)));
        assertDoesNotThrow(() -> at(NBF - 10).verify(VALID));
        assertEquals("not yet valid", reason(() -> at(NBF - 11).verify(VALID)));
    }

    @Test void another_app_or_another_shop_is_refused() {
        assertEquals("wrong aud", reason(() -> at(NBF + 30).verify(WRONG_AUD)));
        assertEquals("wrong dest", reason(() -> at(NBF + 30).verify(WRONG_DEST)));
    }

    @Test void only_hs256_is_accepted_so_alg_none_cannot_skip_the_signature() {
        assertEquals("alg must be HS256", reason(() -> at(NBF + 30).verify(ALG_NONE)));
        assertEquals("alg must be HS256", reason(() -> at(NBF + 30).verify(ALG_HS512)));
    }

    @Test void a_wrong_key_or_an_edited_payload_fails_the_signature() {
        assertEquals("bad signature", reason(() -> at(NBF + 30).verify(WRONG_SECRET)));
        assertEquals("bad signature", reason(() -> at(NBF + 30).verify(TAMPERED)));
    }

    @Test void malformed_tokens_are_refused_without_exceptions_leaking() {
        assertEquals("missing", reason(() -> at(NBF).verify(null)));
        assertEquals("not a JWT", reason(() -> at(NBF).verify("abc.def")));
        assertEquals("not base64url", reason(() -> at(NBF).verify("a*b.c.d")));
        assertEquals("header is not valid JSON", reason(() -> at(NBF).verify("bm90anNvbg.e30.")));
    }
}
