package in.brand.engage.ingest.config;

import in.brand.engage.core.crypto.TrackRef;
import in.brand.engage.core.crypto.UnsubscribeToken;
import in.brand.engage.core.privacy.CustomerAllowlist;
import in.brand.engage.core.meta.MetaSignatureVerifier;
import in.brand.engage.core.razorpay.RazorpaySignatureVerifier;
import in.brand.engage.core.shopify.AppProxyVerifier;
import in.brand.engage.core.shopify.ShopifyWebhookVerifier;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;

/** Exposes the framework-free verifiers and policies from core-domain as beans. */
@Factory
public class VerifierFactory {

    @Singleton
    ShopifyWebhookVerifier shopifyWebhookVerifier(EngageProperties.Shopify shopify) {
        return new ShopifyWebhookVerifier(shopify.apiSecret());
    }

    @Singleton
    AppProxyVerifier appProxyVerifier(EngageProperties.Shopify shopify) {
        return new AppProxyVerifier(shopify.apiSecret(), shopify.shopDomain());
    }

    /** Tracking links in order updates (P4-T08), keyed from the same app secret as the worker's. */
    @Singleton
    TrackRef trackRef(EngageProperties.Shopify shopify) {
        return TrackRef.fromAppSecret(shopify.apiSecret());
    }

    /** The unsubscribe links in marketing email (P7-T01), signed by the worker with the same app secret. */
    @Singleton
    UnsubscribeToken unsubscribeToken(EngageProperties.Shopify shopify) {
        return UnsubscribeToken.fromAppSecret(shopify.apiSecret());
    }

    @Singleton
    RazorpaySignatureVerifier razorpaySignatureVerifier(EngageProperties.Razorpay razorpay) {
        return new RazorpaySignatureVerifier(razorpay.webhookSecret());
    }

    /** Not required at startup: until WA_APP_SECRET is set, every Meta webhook is refused. */
    @Singleton
    MetaSignatureVerifier metaSignatureVerifier(EngageProperties.Meta meta) {
        return new MetaSignatureVerifier(meta.appSecret());
    }

    /** Throws at startup when CUSTOMER_ALLOWLIST_EMAILS is empty: fail closed. */
    @Singleton
    CustomerAllowlist customerAllowlist(EngageProperties.Privacy privacy) {
        return CustomerAllowlist.parse(privacy.customerAllowlistEmails());
    }
}
