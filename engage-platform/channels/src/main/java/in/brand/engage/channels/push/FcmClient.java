package in.brand.engage.channels.push;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The seam between {@link FcmAdapter} and the Firebase Admin SDK. The SDK's
 * result types cannot be constructed outside it, so tests fake this instead;
 * {@link FirebaseFcmClient} is the one real implementation.
 */
public interface FcmClient {

    /**
     * One {@code sendEachForMulticast} call. At most {@link #MAX_TOKENS} tokens.
     *
     * @return one result per token, in token order
     * @throws FcmCallException the call as a whole failed (auth, network, quota)
     */
    List<TokenResult> send(Batch batch) throws FcmCallException;

    int MAX_TOKENS = 500;

    /**
     * @param topic Web Push {@code Topic}: a newer message with the same topic
     *              replaces an undelivered older one at the push service
     */
    record Batch(List<String> tokens, Map<String, String> data, Duration ttl, boolean highUrgency, String topic) {}

    /**
     * @param errorCode the SDK's {@code MessagingErrorCode} name (UNREGISTERED,
     *                  SENDER_ID_MISMATCH, INVALID_ARGUMENT, QUOTA_EXCEEDED,
     *                  UNAVAILABLE, INTERNAL, THIRD_PARTY_AUTH_ERROR), or null on success
     */
    record TokenResult(boolean success, String errorCode, String message) {

        public static TokenResult ok() {
            return new TokenResult(true, null, null);
        }

        public static TokenResult error(String code, String message) {
            return new TokenResult(false, code, message);
        }
    }

    final class FcmCallException extends Exception {
        private static final long serialVersionUID = 1L;

        public FcmCallException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
