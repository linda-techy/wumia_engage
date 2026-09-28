package in.brand.engage.channels;

import in.brand.engage.core.messaging.Addresses;
import in.brand.engage.core.messaging.Channel;

/**
 * The only interface that touches a provider. Only {@code MessageRouter} may
 * hold one (CLAUDE.md invariant 1, enforced by ArchUnit in P3-T08).
 *
 * <p>An adapter never writes to the database and never runs inside a
 * transaction: the router calls it between its two transactions (P3-T05) and
 * applies what the result asks for (token prunes) afterwards.
 */
public interface ChannelAdapter {

    Channel channel();

    /**
     * @throws ChannelException.Permanent this message can never be delivered as it is
     * @throws ChannelException.Transient it may succeed later; nothing was accepted
     */
    DispatchResult send(RenderedMessage message, Addresses addresses) throws ChannelException;
}
