package in.brand.engage.worker;

import io.micronaut.runtime.Micronaut;

/**
 * The worker: event dispatch into intents, the cascade tick, the lost-send
 * sweeper. The only runtime that sends messages; ingest-api and admin-api never do.
 */
public final class Application {

    private Application() {}

    public static void main(String[] args) {
        Micronaut.run(Application.class, args);
    }
}
