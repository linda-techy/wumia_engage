// Channel adapters: the only code that talks to a provider. Must not depend on
// policy or orchestrator (CLAUDE.md §8): an adapter receives a rendered
// message and addresses and has no idea why it is sending.
plugins {
    id("io.micronaut.library")
}

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

dependencies {
    api(project(":core-domain"))
    api(project(":core-persistence"))                    // DeviceRepository, for token pruning
    implementation("org.slf4j:slf4j-api")
    // 9.11+: built against httpclient5 5.6. With 9.4.x, Micronaut's platform lifts
    // httpclient5 from 5.3 to 5.6, which decompresses responses itself, and every
    // FCM call fails "Not in GZIP format" (seen 2026-09-29).
    implementation("com.google.firebase:firebase-admin:9.11.0")

    testImplementation("io.micronaut.sql:micronaut-jdbc-hikari")
    testImplementation("io.micronaut.flyway:micronaut-flyway")
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
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

// Tests migrate engage_test themselves (see policy/build.gradle.kts).
tasks.processTestResources {
    from(rootProject.file("db/migration")) { into("db/migration") }
}

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
