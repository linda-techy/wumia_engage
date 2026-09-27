package in.brand.engage.policy;

import in.brand.engage.core.messaging.Category;
import in.brand.engage.core.messaging.Channel;

/**
 * A row of the {@code templates} registry (V1). Only {@code active} may send;
 * {@code paused} and {@code retired} both block as TEMPLATE_PAUSED.
 */
public record Template(String key, Channel channel, Category category, String status) {

    public boolean sendable() {
        return "active".equals(status);
    }
}
