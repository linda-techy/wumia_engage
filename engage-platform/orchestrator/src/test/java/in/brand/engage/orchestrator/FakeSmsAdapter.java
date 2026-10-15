package in.brand.engage.orchestrator;

import in.brand.engage.channels.ChannelAdapter;
import in.brand.engage.channels.DispatchResult;
import in.brand.engage.channels.RenderedMessage;
import in.brand.engage.core.messaging.Addresses;
import in.brand.engage.core.messaging.Channel;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Stands in for MSG91 (which needs MSG91_AUTH_KEY): records every message and accepts it. */
@Singleton
public class FakeSmsAdapter implements ChannelAdapter {

    final List<RenderedMessage> sent = new CopyOnWriteArrayList<>();

    @Override
    public Channel channel() {
        return Channel.SMS;
    }

    @Override
    public DispatchResult send(RenderedMessage message, Addresses addresses) {
        sent.add(message);
        return new DispatchResult("req-" + message.sendId(), 1, List.of());
    }
}
