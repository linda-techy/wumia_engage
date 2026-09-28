package in.brand.engage.core.messaging;

import java.util.List;

/**
 * Where an allowed message may go. The policy engine fills it and a channel
 * adapter consumes it; it lives here so {@code channels} never imports
 * {@code policy}.
 *
 * <p>{@code push} holds only targets that can receive it: active, not iOS web,
 * and not stale unless the intent allows it.
 */
public record Addresses(List<PushTarget> push, String phone, String email) {

    public Addresses {
        push = List.copyOf(push);
    }

    public record PushTarget(long deviceId, String token) {}
}
