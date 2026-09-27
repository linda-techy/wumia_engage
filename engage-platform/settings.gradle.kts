plugins {
    // Downloads a matching JDK automatically if JDK 25 is not installed locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "engage"

// Later phases add: orchestrator, channels, journeys, worker
// (see docs/implementation/README.md).
include("core-domain", "core-persistence", "policy", "ingest-api", "admin-api")
