package in.brand.engage.worker.intents;

import in.brand.engage.orchestrator.CascadeRun;
import in.brand.engage.orchestrator.StepVars;
import jakarta.inject.Singleton;
import java.util.Map;

/**
 * The welcome series' offer push opens the auto-apply discount link instead
 * of the bestsellers page the earlier pushes open: its {@code url} is the
 * run's {@code offer_url}. Without a code there is no offer step.
 */
@Singleton
public class WelcomeOffer implements StepVars {

    public static final String NAME = "welcome_offer";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Map<String, String> provide(CascadeRun run, Map<String, String> vars) throws Unavailable {
        var link = vars.get("offer_url");
        if (link == null || link.isBlank()) throw new Unavailable("no_code");
        return Map.of("url", link);
    }
}
