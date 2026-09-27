package in.brand.engage.core.messaging;

import java.util.Locale;

/** The Postgres {@code channel} enum (V1). */
public enum Channel {
    WHATSAPP, PUSH, EMAIL, SMS, RCS;

    /** The enum label in Postgres: bind with {@code CAST(? AS channel)}. */
    public String dbName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Channel fromDb(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
