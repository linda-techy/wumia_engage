plugins {
    // Declared once here, applied in the modules that need it.
    id("io.micronaut.application") version "5.0.2" apply false
    // For library modules (no main class) that still carry @Singleton beans,
    // e.g. core-persistence: Micronaut needs its own compiler args to
    // generate bean definitions, which plain annotationProcessor wiring does
    // not set up correctly.
    id("io.micronaut.library") version "5.0.2" apply false
}

subprojects {
    repositories {
        mavenCentral()
    }
    group = "in.brand.engage"
    version = "0.1.0"
}
