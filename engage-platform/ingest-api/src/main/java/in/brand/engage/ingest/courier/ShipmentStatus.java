package in.brand.engage.ingest.courier;

import java.util.Locale;

/**
 * The seven shipment statuses (V9 {@code shipments.status}). Every aggregator's
 * vocabulary maps onto these in its {@link CourierAdapter}.
 *
 * <p>{@link #rank} breaks ties between events stamped with the same courier
 * time. Terminal statuses are never left: a late scan cannot un-deliver a parcel.
 */
public enum ShipmentStatus {
    CREATED(0, false),
    IN_TRANSIT(1, false),
    NDR(2, false),                 // a failed delivery attempt; a re-attempt goes out for delivery again
    OUT_FOR_DELIVERY(3, false),
    DELIVERED(4, true),
    RTO(4, true),                  // returned to origin
    CANCELLED(4, true);

    private final int rank;
    private final boolean terminal;

    ShipmentStatus(int rank, boolean terminal) {
        this.rank = rank;
        this.terminal = terminal;
    }

    public int rank() {
        return rank;
    }

    public boolean terminal() {
        return terminal;
    }

    /** The value stored in {@code shipments.status} and {@code shipment_events.status}. */
    public String db() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ShipmentStatus fromDb(String s) {
        return valueOf(s.toUpperCase(Locale.ROOT));
    }

    /** The {@code events.name} this status emits, or null when it emits none. */
    public String eventName() {
        return switch (this) {
            case OUT_FOR_DELIVERY -> "out_for_delivery";
            case DELIVERED -> "order_delivered";
            case NDR -> "delivery_failed";
            default -> null;
        };
    }
}
