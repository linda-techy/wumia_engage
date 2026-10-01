package in.brand.engage.ingest.courier;

import io.micronaut.http.HttpHeaders;
import java.time.OffsetDateTime;

/**
 * One shipping aggregator (ADR-005). Everything specific to it lives in its
 * implementation: how a webhook is authenticated, how its status vocabulary
 * maps to {@link ShipmentStatus}, and how its timestamps read. The controller,
 * the inbox and {@link Shipments} stay aggregator-neutral.
 */
public interface CourierAdapter {

    /** Short name, stored as the inbox topic and as {@code shipments.carrier} for rows it creates. */
    String name();

    /** Whether the request is really from the aggregator. Constant time. */
    boolean verify(HttpHeaders headers, byte[] body);

    /** The aggregator's status mapped onto ours; null for a status that changes nothing (manifested, pickup scheduled…). */
    ShipmentStatus status(String label, String code);

    /** The courier's event time, in the aggregator's format; null when unreadable. */
    OffsetDateTime time(String raw);
}
