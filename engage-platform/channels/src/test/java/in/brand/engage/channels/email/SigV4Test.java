package in.brand.engage.channels.email;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** AWS's Signature Version 4 test suite, "get-vanilla" and "post-vanilla". */
class SigV4Test {

    static final String KEY = "AKIDEXAMPLE";
    static final String SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";
    static final Instant AT = Instant.parse("2015-08-30T12:36:00Z");

    @Test void get_vanilla() {
        var s = SigV4.sign("GET", URI.create("https://example.amazonaws.com/"), Map.of(), new byte[0], AT,
                "us-east-1", "service", KEY, SECRET, null);
        assertEquals("20150830T123600Z", s.amzDate());
        assertEquals("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, "
                + "SignedHeaders=host;x-amz-date, Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31",
                s.authorization());
    }

    @Test void post_vanilla() {
        var s = SigV4.sign("POST", URI.create("https://example.amazonaws.com/"), Map.of(), new byte[0], AT,
                "us-east-1", "service", KEY, SECRET, null);
        assertTrue(s.authorization().endsWith("Signature=5da7c1a2acd57cee7505fc6676e4e544621c30862966e37dddb68e92efbe5d6b"),
                s.authorization());
    }
}
