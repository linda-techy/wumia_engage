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
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")
    implementation("com.nimbusds:nimbus-jose-jwt:9.47")

    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("ch.qos.logback:logback-classic")
    runtimeOnly("org.yaml:snakeyaml")

    testImplementation("io.micronaut:micronaut-http-client")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.1")   // ControllerRolesTest
    // Tests migrate engage_test themselves, like the other modules: without it they
    // depended on another module's tests having run first (seen with V33, 2026-10-15).
    testImplementation("io.micronaut.flyway:micronaut-flyway")
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql")
    implementation(project(":policy"))                                 // campaign dry runs (P6-T06)
}

application {
    mainClass = "in.brand.engage.admin.Application"
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

val requiredForRun = listOf("DB_PASSWORD", "ADMIN_MFA_KEY")

fun placeholder(v: String?) = v.isNullOrBlank() || v.contains("CHANGE_ME")

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
        // Tests never reach a real provider, whatever local.env holds: no WhatsApp,
        // template sync or SMS adapter is created (fakes stand in where needed).
        listOf("WA_PHONE_NUMBER_ID", "WA_ACCESS_TOKEN", "WA_BUSINESS_ACCOUNT_ID", "MSG91_AUTH_KEY", "EMAIL_FROM", "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN").forEach { env[it] = "" }
        // Deterministic test keys: never the operator's real ones.
        // 64 hex = 32 bytes: SecretBox.keyFromHex accepts nothing shorter.
        env["ADMIN_MFA_KEY"] = "0123456789abcdef".repeat(4)
        env["ADMIN_JWT_KEY_FILE"] = layout.buildDirectory.file("test-admin-jwt.pem").get().asFile.absolutePath
        env["ADMIN_BOOTSTRAP_EMAIL"] = ""
        // -1 binds to a random free port: tests must not fight the running
        // admin-api service on 8083 for the fixed port.
        env["ADMIN_API_PORT"] = "-1"
        environment(env)
    }
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

// Tests migrate engage_test themselves (see policy/build.gradle.kts).
tasks.processTestResources {
    from(rootProject.file("db/migration")) { into("db/migration") }
}
