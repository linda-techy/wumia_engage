// The one door (MessageOrchestrator, MessageRouter: P3-T05) and the message
// templates it renders. A library: the worker (P3-T06) hosts it.
plugins {
    id("io.micronaut.library")
}

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

dependencies {
    api(project(":policy"))
    implementation("org.slf4j:slf4j-api")
    implementation("org.yaml:snakeyaml")                  // templates/*.yaml

    testImplementation("io.micronaut.sql:micronaut-jdbc-hikari")
    testImplementation("io.micronaut.flyway:micronaut-flyway")
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
    testRuntimeOnly("ch.qos.logback:logback-classic")
}

micronaut {
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("in.brand.engage.*")
    }
}

/* ------------------------------------------------------------------------
 * Templates are authored at the repo root (templates/) and shipped on the
 * classpath with an index, because a jar cannot be listed like a directory.
 * --------------------------------------------------------------------- */

val templatesDir: File = rootProject.file("templates")

val templateIndex = tasks.register("templateIndex") {
    val out = layout.buildDirectory.dir("generated/template-index")
    inputs.dir(templatesDir)
    outputs.dir(out)
    doLast {
        val index = out.get().file("templates/index.txt").asFile
        index.parentFile.mkdirs()
        index.writeText(templatesDir.walkTopDown()
            .filter { it.isFile && it.extension == "yaml" }
            .map { it.relativeTo(templatesDir).invariantSeparatorsPath }
            .sorted()
            .joinToString("\n", postfix = "\n"))
    }
}

sourceSets.main { resources.srcDir(templateIndex) }

tasks.processResources {
    from(templatesDir) { into("templates") }
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
