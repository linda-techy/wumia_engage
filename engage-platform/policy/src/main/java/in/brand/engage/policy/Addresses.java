package in.brand.engage.policy;

import java.util.List;

/**
 * Where an allowed message may go. {@code push} holds only targets that can
 * receive it: active, not iOS web, and not stale unless the intent allows it.
 */
public record Addresses(List<PushTarget> push, String phone, String email) {

    public record PushTarget(long deviceId, String token) {}
}
