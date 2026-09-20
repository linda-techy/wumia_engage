plugins {
    // Declared once here, applied in the modules that need it.
    id("io.micronaut.application") version "5.0.2" apply false
}

subprojects {
    repositories {
        mavenCentral()
    }
    group = "in.brand.engage"
    version = "0.1.0"
}
