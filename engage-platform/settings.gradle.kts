plugins {
    // Downloads a matching JDK automatically if JDK 25 is not installed locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "engage"

// Phase 1 modules. Later phases add: policy, orchestrator, channels, journeys,
// admin-api, worker (see docs/technical/00-overview.md).
include("core-domain", "core-persistence", "ingest-api")
