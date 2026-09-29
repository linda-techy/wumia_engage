package in.brand.engage.orchestrator;

import in.brand.engage.channels.ChannelAdapter;
import in.brand.engage.channels.ChannelException;
import in.brand.engage.channels.DispatchResult;
import in.brand.engage.channels.RenderedMessage;
import in.brand.engage.channels.TokenPrune;
import in.brand.engage.channels.push.FcmAdapter;
import in.brand.engage.core.messaging.Addresses;
import in.brand.engage.core.messaging.Channel;
import io.micronaut.context.annotation.Replaces;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Stands in for FCM: records every message and answers as scripted. */
@Singleton
@Replaces(FcmAdapter.class)
public class FakePushAdapter implements ChannelAdapter {

    @FunctionalInterface
    interface Behaviour {
        DispatchResult send(RenderedMessage m, Addresses a) throws ChannelException;
    }

    static final Behaviour ACCEPT_ALL = (m, a) -> new DispatchResult(null, a.push().size(), List.of());

    final List<RenderedMessage> sent = new CopyOnWriteArrayList<>();
    private volatile Behaviour behaviour = ACCEPT_ALL;

    void reset() {
        sent.clear();
        behaviour = ACCEPT_ALL;
    }

    void answer(Behaviour b) {
        behaviour = b;
    }

    void acceptPruning(List<TokenPrune> prunes) {
        behaviour = (m, a) -> new DispatchResult(null, 1, prunes);
    }

    @Override
    public Channel channel() {
        return Channel.PUSH;
    }

    @Override
    public DispatchResult send(RenderedMessage message, Addresses addresses) throws ChannelException {
        sent.add(message);
        return behaviour.send(message, addresses);
    }
}
