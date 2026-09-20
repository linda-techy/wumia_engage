package in.brand.engage.ingest.config;

import in.brand.engage.core.privacy.CustomerAllowlist;
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

    @Singleton
    RazorpaySignatureVerifier razorpaySignatureVerifier(EngageProperties.Razorpay razorpay) {
        return new RazorpaySignatureVerifier(razorpay.webhookSecret());
    }

    /** Throws at startup when CUSTOMER_ALLOWLIST_EMAILS is empty: fail closed. */
    @Singleton
    CustomerAllowlist customerAllowlist(EngageProperties.Privacy privacy) {
        return CustomerAllowlist.parse(privacy.customerAllowlistEmails());
    }
}
