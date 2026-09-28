package in.brand.engage.channels.push;

import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.WebpushConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link FcmClient} over the Firebase Admin SDK, HTTP v1 API
 * ({@code sendEachForMulticast}; the legacy batch endpoint is retired).
 *
 * <p>Data-only on every platform: a {@code notification} block would hand
 * rendering to the OS and lose control of title, icon and click-through.
 */
public class FirebaseFcmClient implements FcmClient {

    private final FirebaseMessaging messaging;

    public FirebaseFcmClient(FirebaseMessaging messaging) {
        this.messaging = messaging;
    }

    @Override
    public List<TokenResult> send(Batch batch) throws FcmCallException {
        if (batch.tokens().size() > MAX_TOKENS) {
            throw new IllegalArgumentException(batch.tokens().size() + " tokens > " + MAX_TOKENS);
        }
        try {
            var responses = messaging.sendEachForMulticast(build(batch)).getResponses();
            var out = new ArrayList<TokenResult>(responses.size());
            for (var r : responses) {
                if (r.isSuccessful()) {
                    out.add(TokenResult.ok());
                } else {
                    var e = r.getException();
                    var code = e.getMessagingErrorCode() == null ? "UNKNOWN" : e.getMessagingErrorCode().name();
                    out.add(TokenResult.error(code, e.getMessage()));
                }
            }
            return out;
        } catch (FirebaseMessagingException e) {
            throw new FcmCallException(e.getMessage(), e);
        }
    }

    static MulticastMessage build(Batch batch) {
        var webpush = WebpushConfig.builder()
                .putHeader("TTL", Long.toString(batch.ttl().toSeconds()))
                .putHeader("Urgency", batch.highUrgency() ? "high" : "normal");
        if (batch.topic() != null) webpush.putHeader("Topic", batch.topic());
        return MulticastMessage.builder()
                .putAllData(batch.data())
                .setWebpushConfig(webpush.build())
                .setAndroidConfig(AndroidConfig.builder()            // for a future native app
                        .setTtl(batch.ttl().toMillis())
                        .setPriority(batch.highUrgency() ? AndroidConfig.Priority.HIGH : AndroidConfig.Priority.NORMAL)
                        .build())
                .addAllTokens(batch.tokens())
                .build();
    }
}
