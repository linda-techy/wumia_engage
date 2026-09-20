package in.brand.engage.core.razorpay;

import static in.brand.engage.core.razorpay.FailureKind.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FailureKindTest {

    @Test void explicit_customer_cancel_is_customer_cancelled() {
        assertEquals(CUSTOMER_CANCELLED, classify("customer", "payment_cancelled"));
    }

    @Test void customer_side_timeout_is_technical() {
        // UPI collect request that never surfaced: offer another rail.
        assertEquals(TECHNICAL, classify("customer", "payment_timed_out"));
    }

    @Test void bank_failures_are_technical() {
        assertEquals(TECHNICAL, classify("bank", "payment_failed"));
        assertEquals(TECHNICAL, classify("gateway", "server_error"));
    }

    @Test void unknown_or_missing_fields_default_to_technical() {
        assertEquals(TECHNICAL, classify(null, null));
        assertEquals(TECHNICAL, classify("", "something_new"));
    }
}
