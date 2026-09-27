// PolicyEngine: whether, and under which config, a message may go out.
// A library: the worker (P3-T06) and admin-api (P6 estimates) host it.
plugins {
    id("io.micronaut.library")
}

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

dependencies {
    api(project(":core-domain"))
    api(project(":core-persistence"))
    implementation("org.slf4j:slf4j-api")
    // PGConnection.getNotifications() for the config_changed LISTEN.
    implementation("org.postgresql:postgresql")

    testImplementation("io.micronaut.sql:micronaut-jdbc-hikari")
    testImplementation("io.micronaut.flyway:micronaut-flyway")
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("ch.qos.logback:logback-classic")
    testRuntimeOnly("org.yaml:snakeyaml")
}

micronaut {
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("in.brand.engage.*")
    }
}

// Tests migrate engage_test themselves rather than relying on ingest-api's
// tests having run first.
tasks.processTestResources {
    from(rootProject.file("db/migration")) { into("db/migration") }
}

/* ------------------------------------------------------------------------
 * config/local.env → environment of `test` (TEST_DB_NAME, never the main DB).
 * --------------------------------------------------------------------- */

val envFile: File = rootProject.file(project.findProperty("envFile")?.toString() ?: "config/local.env")

fun readEnvFile(f: File): Map<String, String> =
    f.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
        .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }

tasks.withType<Test>().configureEach {
    if (envFile.exists()) {
        val env = readEnvFile(envFile).toMutableMap()
        env["DB_NAME"] = env["TEST_DB_NAME"] ?: "engage_test"
        environment(env)
    }
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
