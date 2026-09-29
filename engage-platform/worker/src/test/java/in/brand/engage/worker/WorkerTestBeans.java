package in.brand.engage.worker;

import in.brand.engage.channels.ChannelAdapter;
import in.brand.engage.channels.DispatchResult;
import in.brand.engage.channels.RenderedMessage;
import in.brand.engage.channels.push.FcmAdapter;
import in.brand.engage.core.messaging.Addresses;
import in.brand.engage.core.messaging.Channel;
import in.brand.engage.orchestrator.CascadeDefinition;
import in.brand.engage.orchestrator.MessageIntent;
import in.brand.engage.orchestrator.Priority;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.core.annotation.Order;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Test consumers, a test cascade, and a push adapter that never leaves the JVM. */
@Factory
public class WorkerTestBeans {

    static final String INTENT = "test_worker_browse";

    @Singleton
    CascadeDefinition testCascade() {
        return new CascadeDefinition(INTENT, Priority.LOW_INTENT,
                List.of(CascadeDefinition.Step.push("push_browse_abandon_v1", Duration.ofHours(24))));
    }

    /** Counts how often each test_evt* event reaches a consumer. */
    @Singleton
    static class Recording implements EventConsumer {
        final Map<Long, AtomicInteger> seen = new ConcurrentHashMap<>();

        @Override public boolean accepts(String name) { return name.startsWith("test_evt"); }

        @Override public List<MessageIntent> handle(Connection c, Event e) {
            seen.computeIfAbsent(e.id(), k -> new AtomicInteger()).incrementAndGet();
            return List.of();
        }
    }

    /** Always throws: a poison event. */
    @Singleton
    static class Poison implements EventConsumer {
        final AtomicInteger attempts = new AtomicInteger();

        @Override public boolean accepts(String name) { return name.equals("test_poison"); }

        @Override public List<MessageIntent> handle(Connection c, Event e) {
            attempts.incrementAndGet();
            throw new IllegalStateException("poison");
        }
    }

    /** Starts the test intent for test_intent and test_atomic events. Runs first. */
    @Singleton
    @Order(1)
    static class StartsIntent implements EventConsumer {
        @Override public boolean accepts(String name) { return name.equals("test_intent") || name.equals("test_atomic"); }

        @Override public List<MessageIntent> handle(Connection c, Event e) {
            return List.of(new MessageIntent(e.identityId(), INTENT, "evt:" + e.id(),
                    Map.of("product", "Linen Kurta", "url", "https://w.example/p"), null));
        }
    }

    /** Fails test_atomic after StartsIntent has returned its intent. */
    @Singleton
    @Order(2)
    static class FailsAfter implements EventConsumer {
        @Override public boolean accepts(String name) { return name.equals("test_atomic"); }

        @Override public List<MessageIntent> handle(Connection c, Event e) {
            throw new IllegalStateException("second consumer fails");
        }
    }

    @Singleton
    @Replaces(FcmAdapter.class)
    public static class FakePush implements ChannelAdapter {
        final AtomicInteger sent = new AtomicInteger();
        public volatile RenderedMessage last;
        public final List<RenderedMessage> all = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override public Channel channel() { return Channel.PUSH; }

        @Override public DispatchResult send(RenderedMessage m, Addresses a) {
            sent.incrementAndGet();
            last = m;
            all.add(m);
            return new DispatchResult(null, a.push().size(), List.of());
        }
    }
}
