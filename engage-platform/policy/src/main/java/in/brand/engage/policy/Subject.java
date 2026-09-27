package in.brand.engage.policy;

import in.brand.engage.core.messaging.Channel;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One person as the policy engine sees them, loaded once per decision.
 *
 * @param grants        {@code channel:purpose} pairs whose latest statement is granted
 * @param suppressions  active suppressions: channel → first reason
 * @param capability    channel → UNKNOWN | CAPABLE | INCAPABLE (no row = UNKNOWN)
 */
public record Subject(UUID identityId, Set<String> grants, Map<Channel, String> suppressions,
                      List<Device> devices, Map<Channel, String> capability,
                      Instant waBackoffUntil, Instant waWindowUntil, String locale,
                      String phone, String email) {

    public record Device(long id, String token, String platform, boolean stale) {}

    public boolean hasGrant(Channel channel, String purpose) {
        return grants.contains(channel.dbName() + ":" + purpose);
    }

    public boolean hasAnyGrant(Channel channel) {
        return hasGrant(channel, "transactional") || hasGrant(channel, "marketing");
    }

    /** iOS web tokens never count: Safari push without a Home Screen install does not arrive. */
    public List<Addresses.PushTarget> pushTargets(boolean allowStale) {
        return devices.stream()
                .filter(d -> !"IOS_WEB".equals(d.platform()))
                .filter(d -> allowStale || !d.stale())
                .map(d -> new Addresses.PushTarget(d.id(), d.token()))
                .toList();
    }

    public boolean inServiceWindow(Instant now) {
        return waWindowUntil != null && waWindowUntil.isAfter(now);
    }

    public boolean waBackedOff(Instant now) {
        return waBackoffUntil != null && waBackoffUntil.isAfter(now);
    }

    public String capabilityOf(Channel channel) {
        return capability.getOrDefault(channel, "UNKNOWN");
    }
}
