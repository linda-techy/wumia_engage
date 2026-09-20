plugins {
    id("io.micronaut.application")
}

dependencies {
    annotationProcessor("io.micronaut:micronaut-http-validation")
    annotationProcessor("io.micronaut.serde:micronaut-serde-processor")

    implementation(project(":core-domain"))
    implementation(project(":core-persistence"))
    implementation("io.micronaut:micronaut-management")          // /health
    implementation("io.micronaut.serde:micronaut-serde-jackson")
    implementation("io.micronaut.sql:micronaut-jdbc-hikari")
    implementation("io.micronaut.flyway:micronaut-flyway")

    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("ch.qos.logback:logback-classic")
    runtimeOnly("org.yaml:snakeyaml")

    testImplementation("io.micronaut:micronaut-http-client")
}

application {
    mainClass = "in.brand.engage.ingest.Application"
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

micronaut {
    runtime("netty")
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("in.brand.engage.*")
    }
}

// Flyway migrations live at the repo root (db/migration) so every service
// shares one schema history. Package them onto the classpath here.
tasks.processResources {
    from(rootProject.file("db/migration")) { into("db/migration") }
}

/* ------------------------------------------------------------------------
 * config/local.env → environment of `run` and `test`.
 * One file holds every local credential (see LOCAL-SETUP.md).
 * --------------------------------------------------------------------- */

// -PenvFile=config/devstore.env runs a second, fully separate instance
// (own database, port and shop) against the Shopify development store.
val envFile: File = rootProject.file(project.findProperty("envFile")?.toString() ?: "config/local.env")

fun readEnvFile(f: File): Map<String, String> =
    f.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
        .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }

val requiredForRun = listOf("DB_PASSWORD", "SHOPIFY_SHOP_DOMAIN", "SHOPIFY_API_SECRET", "RAZORPAY_WEBHOOK_SECRET",
                           "CUSTOMER_ALLOWLIST_EMAILS")

fun placeholder(v: String?) = v.isNullOrBlank() || v.contains("CHANGE_ME") || v == "your-store.myshopify.com"

tasks.named<JavaExec>("run") {
    doFirst {
        if (!envFile.exists()) {
            throw GradleException(
                "${envFile.relativeTo(rootProject.projectDir)} not found.\n" +
                "  Create it:  cp config/local.env.example config/local.env\n" +
                "  Then edit it (LOCAL-SETUP.md, section 3).")
        }
        val env = readEnvFile(envFile)
        val missing = requiredForRun.filter { placeholder(env[it]) }
        if (missing.isNotEmpty()) {
            throw GradleException("Edit ${envFile.relativeTo(rootProject.projectDir)} and set: ${missing.joinToString(", ")}")
        }
        environment(env)
    }
}

tasks.withType<Test>().configureEach {
    // Integration tests run against TEST_DB_NAME (default engage_test), never
    // the main database. They are skipped when config/local.env is absent.
    if (envFile.exists()) {
        val env = readEnvFile(envFile).toMutableMap()
        env["DB_NAME"] = env["TEST_DB_NAME"] ?: "engage_test"
        env["FLYWAY_ON_STARTUP"] = "true"
        // Tests use synthetic fixtures (priya.k@example.com), never the real allowlist.
        env["CUSTOMER_ALLOWLIST_EMAILS"] = "priya.k@example.com"
        environment(env)
    }
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
