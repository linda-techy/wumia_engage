package in.brand.engage.channels;

/**
 * A push token the provider says is dead, to be deactivated by the router
 * after the send ({@code FcmTokenPruner}).
 *
 * @param reason a {@code devices.deactivated_reason}: unregistered | sender_mismatch | invalid_token
 */
public record TokenPrune(long deviceId, String reason) {}
