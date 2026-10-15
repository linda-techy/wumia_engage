package in.brand.engage.core.crypto;

import static org.junit.jupiter.api.Assertions.*;

import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class TrackRefTest {

    final TrackRef refs = TrackRef.fromAppSecret("test-app-secret");

    @Test void a_ref_round_trips_and_fits_a_dlt_variable() {
        for (long id : new long[] {1, 1042, 9_876_543_210L, Long.MAX_VALUE}) {
            var ref = refs.of(id);
            assertEquals(OptionalLong.of(id), refs.parse(ref), ref);
            assertTrue(ref.length() <= 30, ref + " is over the 30-character DLT limit");
            assertTrue(ref.matches("[0-9a-z]+-[A-Za-z0-9_-]{11}"), ref);
        }
    }

    @Test void a_walked_or_tampered_ref_is_refused() {
        var ref = refs.of(1042);
        var sig = ref.substring(ref.indexOf('-'));
        assertTrue(refs.parse(Long.toString(1043, 36) + sig).isEmpty(), "the next id with this signature");
        assertTrue(refs.parse(ref.substring(0, ref.length() - 1) + (ref.endsWith("A") ? "B" : "A")).isEmpty());
        assertTrue(refs.parse(TrackRef.fromAppSecret("another-secret").of(1042)).isEmpty());
        for (var bad : new String[] {null, "", "-", "1042", "ZZ-abc", "x".repeat(40)}) assertTrue(refs.parse(bad).isEmpty(), bad);
    }

    @Test void no_secret_no_refs() {
        assertThrows(IllegalStateException.class, () -> TrackRef.fromAppSecret(""));
        assertThrows(IllegalStateException.class, () -> TrackRef.fromAppSecret("CHANGE_ME"));
    }
}
