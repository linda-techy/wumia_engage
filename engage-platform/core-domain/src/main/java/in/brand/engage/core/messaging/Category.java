package in.brand.engage.core.messaging;

import java.util.Locale;

/** The Postgres {@code msg_category} enum (V1). */
public enum Category {
    MARKETING, UTILITY, AUTHENTICATION, SERVICE;

    /** The enum label in Postgres: bind with {@code CAST(? AS msg_category)}. */
    public String dbName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Category fromDb(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
