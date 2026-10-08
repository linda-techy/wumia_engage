package in.brand.engage.admin.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

/** The per-IP sign-in limit keys on this: a forged X-Real-IP must not reset it. */
class ClientIpTest {

    static InetAddress ip(String s) throws Exception {
        return InetAddress.getByName(s);
    }

    @Test void the_header_is_trusted_from_our_own_proxy() throws Exception {
        assertEquals("203.0.113.9", AuthController.clientIp(ip("127.0.0.1"), "203.0.113.9"));
        assertEquals("203.0.113.9", AuthController.clientIp(ip("172.18.0.1"), " 203.0.113.9 "), "Docker bridge");
    }

    @Test void the_header_is_ignored_from_anyone_else() throws Exception {
        assertEquals("198.51.100.7", AuthController.clientIp(ip("198.51.100.7"), "10.0.0.1"));
    }

    @Test void without_the_header_the_peer_is_the_client() throws Exception {
        assertEquals("127.0.0.1", AuthController.clientIp(ip("127.0.0.1"), null));
        assertEquals("127.0.0.1", AuthController.clientIp(ip("127.0.0.1"), " "));
    }
}
