package in.brand.engage.worker;

import io.micronaut.runtime.Micronaut;

/**
 * The worker: event dispatch into intents, the cascade tick, the lost-send
 * sweeper. The only runtime that sends messages; ingest-api and admin-api never do.
 */
public final class Application {

    private Application() {}

    public static void main(String[] args) {
        if (WaCommands.handles(args)) {            // one-off WhatsApp commands: wa-check, wa-sync, wa-submit
            WaCommands.run(args);
            return;
        }
        if (ShopifyCheck.handles(args)) {          // one-off, read-only: the Admin token and the order number format
            ShopifyCheck.run(args);
            return;
        }
        if (ProductImport.handles(args)) {         // one-off: products and photos from the storefront catalogue
            ProductImport.run(args);
            return;
        }
        if (HeldRerun.handles(args)) {             // one-off: recovery journeys a holdout stopped, started again
            HeldRerun.run(args);
            return;
        }
        Micronaut.run(Application.class, args);
    }
}
