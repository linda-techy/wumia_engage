package in.brand.engage.orchestrator;

import in.brand.engage.core.crypto.BeaconSignature;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

/**
 * The push click-beacon signer, keyed from the Shopify app secret. The worker
 * will not start without it: every push it sends must carry a signature that
 * ingest-api can check.
 */
@Factory
class Beacons {

    @Singleton
    BeaconSignature beaconSignature(@Value("${SHOPIFY_API_SECRET:}") String appSecret) {
        return BeaconSignature.fromAppSecret(appSecret);
    }
}
