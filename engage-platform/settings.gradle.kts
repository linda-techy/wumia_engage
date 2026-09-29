plugins {
    // Downloads a matching JDK automatically if JDK 25 is not installed locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "engage"

// Later phases add: journeys (see docs/implementation/README.md).
include("core-domain", "core-persistence", "policy", "channels", "orchestrator", "ingest-api", "admin-api", "worker")
