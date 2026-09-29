package in.brand.engage.ingest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * ingest-api receives and records; it never sends (P3-T08). Its Gradle
 * dependencies already leave channels and the orchestrator off the classpath;
 * this rule keeps it that way if someone adds them. Signals such as a push
 * click travel as events for the worker to act on.
 */
@AnalyzeClasses(packages = "in.brand.engage.ingest", importOptions = ImportOption.DoNotIncludeTests.class)
class IngestArchitectureTest {

    @ArchTest
    static final ArchRule ingest_never_sends =
            noClasses().that().resideInAPackage("in.brand.engage.ingest..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "in.brand.engage.channels..", "in.brand.engage.orchestrator..", "in.brand.engage.worker..")
                    .because("webhooks and storefront calls must never reach a provider directly (invariant 1)");
}
