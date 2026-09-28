package in.brand.engage.channels.push;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.env.Environment;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The real FCM client, from the service-account JSON named by
 * {@code FIREBASE_SERVICE_ACCOUNT_FILE}. A missing or unreadable file fails
 * the application that needs push (the worker), never ingest-api, which does
 * not depend on this module. Tests replace the client with a fake.
 */
@Factory
public class FcmClientFactory {

    private static final String APP_NAME = "engage";

    @Singleton
    @Requires(notEnv = Environment.TEST)
    FcmClient fcmClient(@Value("${FIREBASE_SERVICE_ACCOUNT_FILE:}") String file) {
        return new FirebaseFcmClient(FirebaseMessaging.getInstance(app(file)));
    }

    static FirebaseApp app(String file) {
        if (file == null || file.isBlank()) {
            throw new IllegalStateException("FIREBASE_SERVICE_ACCOUNT_FILE is not set: push cannot send. "
                    + "Point it at the Firebase service-account JSON (LOCAL-SETUP.md).");
        }
        var path = Path.of(file).toAbsolutePath();
        if (!Files.isReadable(path)) {
            throw new IllegalStateException("FIREBASE_SERVICE_ACCOUNT_FILE=" + file + " is not a readable file ("
                    + path + "). A relative path resolves against the working directory.");
        }
        for (var existing : FirebaseApp.getApps()) {
            if (existing.getName().equals(APP_NAME)) return existing;
        }
        try (var in = Files.newInputStream(path)) {
            var options = FirebaseOptions.builder().setCredentials(GoogleCredentials.fromStream(in)).build();
            return FirebaseApp.initializeApp(options, APP_NAME);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read Firebase credentials from " + path, e);
        }
    }
}
