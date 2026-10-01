package in.brand.engage.ingest;

import org.flywaydb.core.Flyway;

/**
 * {@code ingest-api migrate}: apply the Flyway migrations and exit, as a job
 * that runs before the services start (08-deployment-and-ops.md, Migrations).
 * Staging and production set {@code FLYWAY_ON_STARTUP=false}, so no service
 * migrates on startup and replicas never race each other to do it.
 *
 * <p>Reads the same DB_* variables as the service. Exit code 0 = schema
 * current; anything else fails the deploy before a service starts on a
 * schema it does not expect.
 */
final class Migrate {

    private Migrate() {}

    static void run() {
        var url = "jdbc:postgresql://" + env("DB_HOST", "localhost") + ":" + env("DB_PORT", "5432") + "/"
                + env("DB_NAME", "engage");
        var flyway = Flyway.configure()
                .dataSource(url, env("DB_USER", "engage_app"), env("DB_PASSWORD", ""))
                .locations("classpath:db/migration")
                .load();
        var result = flyway.migrate();
        System.out.printf("migrate: %d applied, schema now at %s%n",
                result.migrationsExecuted, flyway.info().current() == null ? "empty" : flyway.info().current().getVersion());
    }

    private static String env(String name, String fallback) {
        var v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
