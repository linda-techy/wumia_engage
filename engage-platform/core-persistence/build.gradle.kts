// JDBC helpers shared by ingest-api and admin-api. Micronaut only for @Singleton.
//
// Db carries @Singleton, so this module needs Micronaut's own Gradle plugin
// (not just a manually-added annotationProcessor) so the inject annotation
// processor gets the compiler args it needs to generate bean definitions.
// Micronaut resolves beans from compile-time-generated definitions, not
// runtime classpath scanning, so a bean in a library module is invisible to
// ingest-api's context otherwise.
plugins {
    id("io.micronaut.library")
}

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

dependencies {
    api("jakarta.inject:jakarta.inject-api:2.0.1")
    api("jakarta.transaction:jakarta.transaction-api:2.0.1")
    // javax.sql.DataSource ships with the JDK; javax.sql:javax.sql-api:1.0 does not
    // resolve on Maven Central, so no compileOnly dependency is needed for it.
}
