package in.brand.engage.admin.config;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.admin.web.Problems;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigValuesTest {

    static final Map<String, Object> WA_CAP = Map.of("type", "integer", "minimum", 0, "maximum", 2);

    static void bad(Runnable r) {
        assertThrows(Problems.ApiException.class, r::run);
    }

    @Test void meta_per_user_limit_is_a_validation_not_a_note() {
        ConfigValues.validate("INT", WA_CAP, 2);
        bad(() -> ConfigValues.validate("INT", WA_CAP, 3));
        bad(() -> ConfigValues.validate("INT", WA_CAP, -1));
        bad(() -> ConfigValues.validate("INT", WA_CAP, 1.5));
        bad(() -> ConfigValues.validate("INT", WA_CAP, "1"));
        ConfigValues.validate("INT", WA_CAP, new BigDecimal("2.0"));
    }

    @Test void decimals_booleans_and_strings_are_typed() {
        var pct = Map.<String, Object>of("type", "number", "minimum", 0, "maximum", 50);
        ConfigValues.validate("DECIMAL", pct, 5.5);
        bad(() -> ConfigValues.validate("DECIMAL", pct, 50.01));
        ConfigValues.validate("BOOL", null, false);
        bad(() -> ConfigValues.validate("BOOL", null, "false"));
        bad(() -> ConfigValues.validate("STRING", Map.of("maxLength", 3), "abcd"));
        bad(() -> ConfigValues.validate("INT", null, null));
    }

    @Test void enums_must_list_their_options() {
        var schema = Map.<String, Object>of("enum", List.of("low", "high"));
        ConfigValues.validate("ENUM", schema, "low");
        bad(() -> ConfigValues.validate("ENUM", schema, "medium"));
        assertThrows(IllegalStateException.class, () -> ConfigValues.validate("ENUM", null, "low"));
    }

    @Test void quiet_hours_are_two_distinct_24h_times() {
        ConfigValues.validate("TIME_RANGE", null, Map.of("from", "21:00", "to", "09:00"));
        bad(() -> ConfigValues.validate("TIME_RANGE", null, Map.of("from", "9pm", "to", "09:00")));
        bad(() -> ConfigValues.validate("TIME_RANGE", null, Map.of("from", "24:00", "to", "09:00")));
        bad(() -> ConfigValues.validate("TIME_RANGE", null, Map.of("from", "09:00", "to", "09:00")));
        bad(() -> ConfigValues.validate("TIME_RANGE", null, Map.of("from", "21:00", "to", "09:00", "tz", "UTC")));
    }

    @Test void an_unknown_schema_keyword_fails_closed() {
        assertThrows(IllegalStateException.class,
                () -> ConfigValues.validate("INT", Map.of("multipleOf", 5), 10));
    }

    @Test void selectors_follow_the_scope() {
        assertEquals("*", ConfigValues.selector("GLOBAL", null));
        bad(() -> ConfigValues.selector("GLOBAL", "whatsapp"));
        assertEquals("whatsapp", ConfigValues.selector("CHANNEL", "whatsapp"));
        bad(() -> ConfigValues.selector("CHANNEL", "pigeon"));
        assertEquals("cart_abandon", ConfigValues.selector("JOURNEY", "cart_abandon"));
        bad(() -> ConfigValues.selector("JOURNEY", "Cart Abandon"));
    }
}
