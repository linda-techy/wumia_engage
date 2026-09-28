package in.brand.engage.channels.push;

import static org.junit.jupiter.api.Assertions.assertEquals;

import in.brand.engage.channels.DispatchResult;
import in.brand.engage.channels.RenderedMessage;
import in.brand.engage.core.messaging.Addresses;
import in.brand.engage.core.messaging.Channel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The P3-T04 manual check: one real push to one dev device, through the real
 * Firebase client. Skipped unless FCM_SMOKE_TOKEN is set, so it never runs in
 * CI or by accident:
 *
 * <pre>FCM_SMOKE_TOKEN=&lt;a dev device token&gt; ./gradlew :channels:test --tests '*FcmSmokeTest'</pre>
 *
 * <p>{@link #main} runs the same send without JUnit, for a machine whose HTTPS
 * is intercepted (antivirus TLS scanning breaks the Firebase SDK): copy the
 * test runtime classpath to a host that is not, and run it there.
 */
@EnabledIfEnvironmentVariable(named = "FCM_SMOKE_TOKEN", matches = ".+")
class FcmSmokeTest {

    @Test void one_real_push_reaches_the_dev_device() throws Exception {
        var result = send(System.getenv("FCM_SMOKE_TOKEN"), System.getenv("FIREBASE_SERVICE_ACCOUNT_FILE"));
        assertEquals(1, result.delivered(), "FCM did not accept the token: " + result.prunes());
    }

    public static void main(String[] args) throws Exception {
        var result = send(System.getenv("FCM_SMOKE_TOKEN"), System.getenv("FIREBASE_SERVICE_ACCOUNT_FILE"));
        System.out.println("FCM accepted " + result.delivered() + " token(s)");
    }

    static DispatchResult send(String token, String file) throws Exception {
        // local.env holds a repo-relative path; tests run in the module directory.
        if (file != null && !Path.of(file).isAbsolute() && !Files.exists(Path.of(file))) file = "../" + file;
        var adapter = new FcmAdapter(new FirebaseFcmClient(
                com.google.firebase.messaging.FirebaseMessaging.getInstance(FcmClientFactory.app(file))));
        return adapter.send(new RenderedMessage(Channel.PUSH, "push_smoke_test", 0, "smoke_test",
                        "Engage test push", "P3-T04 FCM adapter check. You can ignore this.",
                        "https://wumika-dev.myshopify.com/", null, "smoke_test", Duration.ofMinutes(10), false),
                new Addresses(List.of(new Addresses.PushTarget(0, token)), null, null));
    }
}
