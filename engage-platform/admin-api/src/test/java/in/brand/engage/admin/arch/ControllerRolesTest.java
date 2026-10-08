package in.brand.engage.admin.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import in.brand.engage.admin.auth.PublicEndpoint;
import in.brand.engage.admin.auth.RequiresRole;
import io.micronaut.http.annotation.Controller;
import org.junit.jupiter.api.Test;

/**
 * P6-T01: every endpoint states who may call it. A public method on a
 * controller without {@link RequiresRole} or {@link PublicEndpoint} fails the
 * build, so "forgot the role check" cannot ship.
 */
class ControllerRolesTest {

    @Test
    void every_controller_method_declares_its_role_or_why_it_is_public() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("in.brand.engage.admin");
        methods()
                .that().areDeclaredInClassesThat().areAnnotatedWith(Controller.class)
                .and().arePublic()
                .and().doNotHaveModifier(JavaModifier.STATIC)
                .and().doNotHaveModifier(JavaModifier.SYNTHETIC)
                .should().beAnnotatedWith(RequiresRole.class)
                .orShould().beAnnotatedWith(PublicEndpoint.class)
                .because("the role a request needs must be visible on the method and enforced before it runs")
                .check(classes);
    }
}
