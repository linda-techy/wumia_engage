plugins {
    id("io.micronaut.application")
}

dependencies {
    annotationProcessor("io.micronaut.serde:micronaut-serde-processor")

    implementation(project(":orchestrator"))
    implementation("io.micronaut:micronaut-management")          // /health
    implementation("io.micronaut.serde:micronaut-serde-jackson")
    implementation("io.micronaut.sql:micronaut-jdbc-hikari")

    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("ch.qos.logback:logback-classic")
    runtimeOnly("org.yaml:snakeyaml")

    // Tests migrate engage_test themselves; the worker never migrates (ingest-api owns the schema).
    testImplementation("io.micronaut.flyway:micronaut-flyway")
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql")
}

application {
    mainClass = "in.brand.engage.worker.Application"
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

tasks.processTestResources {
    from(rootProject.file("db/migration")) { into("db/migration") }
}

/* ------------------------------------------------------------------------
 * config/local.env → environment of `run` and `test` (LOCAL-SETUP.md).
 * --------------------------------------------------------------------- */

val envFile: File = rootProject.file(project.findProperty("envFile")?.toString() ?: "config/local.env")

fun readEnvFile(f: File): Map<String, String> =
    f.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
        .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }

val requiredForRun = listOf("DB_PASSWORD", "FIREBASE_SERVICE_ACCOUNT_FILE")

fun placeholder(v: String?) = v.isNullOrBlank() || v.contains("CHANGE_ME")

tasks.named<JavaExec>("run") {
    // FIREBASE_SERVICE_ACCOUNT_FILE in local.env is relative to the repo root.
    workingDir = rootProject.projectDir
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
    if (envFile.exists()) {
        val env = readEnvFile(envFile).toMutableMap()
        env["DB_NAME"] = env["TEST_DB_NAME"] ?: "engage_test"
        env["WORKER_PORT"] = "-1"
        environment(env)
    }
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
