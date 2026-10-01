package in.brand.engage.ingest.courier;

import in.brand.engage.ingest.config.EngageProperties;
import io.micronaut.http.HttpHeaders;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;

/**
 * Shiprocket tracking webhooks (ADR-005; apidocs.shiprocket.in, "Webhooks").
 *
 * <ul>
 * <li><b>Auth:</b> the security token set in Shiprocket (Settings → API →
 *     Webhooks) arrives as {@code x-api-key}. There is no signature, so the
 *     token is required here (COURIER_WEBHOOK_TOKEN; blank refuses all).</li>
 * <li><b>URL:</b> must not contain "shiprocket", "kartrocket", "sr" or "kr",
 *     so it is {@code /webhooks/courier}. Shiprocket wants 200 back.</li>
 * <li><b>Status:</b> {@code shipment_status} (label) and {@code shipment_status_id}.
 *     Ids confirmed by a public integration: 6 shipped, 7 delivered, 9 RTO
 *     initiated, 10 RTO delivered, 42 picked up. The rest map by label, the
 *     more stable of the two; tighten with Shiprocket's "Test Webhook".</li>
 * <li><b>Time:</b> {@code current_timestamp} as "dd MM yyyy HH:mm:ss", IST.</li>
 * </ul>
 */
@Singleton
public class ShiprocketAdapter implements CourierAdapter {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter SHIPROCKET = DateTimeFormatter.ofPattern("dd MM yyyy HH:mm:ss");
    private static final DateTimeFormatter ISO_LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Map<String, ShipmentStatus> BY_ID = Map.of(
            "6", ShipmentStatus.IN_TRANSIT,        // SHIPPED
            "7", ShipmentStatus.DELIVERED,
            "9", ShipmentStatus.RTO,               // RTO INITIATED
            "10", ShipmentStatus.RTO,              // RTO DELIVERED
            "42", ShipmentStatus.IN_TRANSIT);      // PICKED UP

    private final byte[] token;

    public ShiprocketAdapter(EngageProperties.Courier courier) {
        var t = courier.webhookToken();
        this.token = t == null || t.isBlank() ? null : t.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String name() {
        return "shiprocket";
    }

    @Override
    public boolean verify(HttpHeaders headers, byte[] body) {
        var presented = headers.get("x-api-key");
        return token != null && presented != null
                && MessageDigest.isEqual(token, presented.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public ShipmentStatus status(String label, String code) {
        var l = label == null ? "" : label.strip().toUpperCase(Locale.ROOT).replace('_', ' ');
        // Label first: the ids are not published and differ between Shiprocket's fields.
        if (l.contains("RTO") || l.contains("RETURN")) return ShipmentStatus.RTO;
        if (l.contains("UNDELIVERED") || l.contains("NDR") || l.contains("DELIVERY ATTEMPT")
                || l.contains("FAILED DELIVERY")) return ShipmentStatus.NDR;
        if (l.contains("OUT FOR DELIVERY")) return ShipmentStatus.OUT_FOR_DELIVERY;
        if (l.equals("DELIVERED")) return ShipmentStatus.DELIVERED;
        if (l.contains("CANCEL")) return ShipmentStatus.CANCELLED;
        if (l.contains("LOST") || l.contains("DAMAGED") || l.contains("DESTROYED")) return null;   // a human's call
        if (l.contains("IN TRANSIT") || l.equals("SHIPPED") || l.contains("PICKED UP")
                || l.contains("REACHED") || l.contains("DELAYED")) return ShipmentStatus.IN_TRANSIT;
        return code == null ? null : BY_ID.get(code.strip());
    }

    @Override
    public OffsetDateTime time(String raw) {
        if (raw == null || raw.isBlank()) return null;
        var s = raw.strip();
        for (var f : new DateTimeFormatter[] {SHIPROCKET, ISO_LOCAL}) {
            try {
                return LocalDateTime.parse(s, f).atZone(IST).toOffsetDateTime();
            } catch (DateTimeParseException ignored) {
                // try the next format
            }
        }
        try {
            return OffsetDateTime.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
