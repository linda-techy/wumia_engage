package in.brand.engage.worker;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * An {@code events} row as consumers see it.
 *
 * @param props top-level props as text ({@code jsonb_each_text}); nested values arrive as JSON text
 */
public record Event(long id, UUID identityId, String name, String source, Instant occurredAt, Map<String, String> props) {

    public Event {
        props = Map.copyOf(props);
    }
}
