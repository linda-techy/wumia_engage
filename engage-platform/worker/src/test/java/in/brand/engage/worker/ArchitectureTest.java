package in.brand.engage.worker;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import in.brand.engage.channels.ChannelAdapter;
import in.brand.engage.orchestrator.MessageRouter;
import in.brand.engage.orchestrator.SendRepository;

/**
 * The one-door rule, enforced by the build (phase-3 §6). The worker depends on
 * every sending module, so its classpath holds all the classes these rules
 * judge. Test classes are excluded: fakes implement ChannelAdapter on purpose.
 *
 * <p>ArchUnit reads bytecode. An unused import leaves no trace in it; a
 * reference (a field, a call, a type) does, and that is what these rules catch.
 */
@AnalyzeClasses(packages = "in.brand.engage", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule only_the_router_reaches_channels =
            noClasses().that().resideOutsideOfPackages("..orchestrator..", "..channels..")
                    .should().dependOnClassesThat().resideInAPackage("..channels..")
                    .because("every send must pass through policy (CLAUDE.md invariant 1)");

    @ArchTest
    static final ArchRule only_the_router_holds_channel_adapters =
            noClasses().that().resideOutsideOfPackage("..channels..")
                    .and().doNotHaveFullyQualifiedName(MessageRouter.class.getName())
                    // Micronaut's generated bean definitions ($MessageRouter$Definition) wire the adapters in.
                    .and().haveNameNotMatching(".*\\.\\$[^.]*")
                    .should().dependOnClassesThat().areAssignableTo(ChannelAdapter.class)
                    .because("only MessageRouter may call a provider (P3-T05)");

    @ArchTest
    static final ArchRule channels_do_not_know_policy =
            noClasses().that().resideInAPackage("..channels..")
                    .should().dependOnClassesThat().resideInAnyPackage("..policy..", "..orchestrator..", "..worker..")
                    .because("an adapter has no idea why it is sending (CLAUDE.md §8)");

    @ArchTest
    static final ArchRule policy_sits_below_the_orchestrator =
            noClasses().that().resideInAPackage("..policy..")
                    .should().dependOnClassesThat().resideInAnyPackage("..orchestrator..", "..channels..", "..worker..")
                    .because("dependencies point inward: policy is used by the orchestrator, never the reverse");

    @ArchTest
    static final ArchRule intents_never_send =
            noClasses().that().resideInAPackage("..worker.intents..")
                    .should().dependOnClassesThat().areAssignableTo(MessageRouter.class)
                    .orShould().dependOnClassesThat().areAssignableTo(SendRepository.class)
                    .because("an intent says what it wants; the orchestrator decides and sends (invariant 2)");
}
