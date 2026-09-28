package in.brand.engage.channels;

import java.util.List;

/**
 * A send the provider accepted.
 *
 * @param providerId the provider's message id; null for FCM multicast, which has none
 * @param delivered  recipients the provider accepted (push: tokens). Always ≥ 1:
 *                   zero accepted is thrown as a {@link ChannelException}, not returned.
 * @param prunes     dead tokens discovered during the send
 */
public record DispatchResult(String providerId, int delivered, List<TokenPrune> prunes) {

    public DispatchResult {
        if (delivered < 1) throw new IllegalArgumentException("a dispatch with nothing accepted is a failure");
        prunes = List.copyOf(prunes);
    }
}
