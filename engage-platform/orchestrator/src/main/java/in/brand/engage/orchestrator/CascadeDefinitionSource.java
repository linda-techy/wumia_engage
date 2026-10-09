package in.brand.engage.orchestrator;

import java.util.Optional;

/**
 * Definitions that are data, not beans: a campaign is a one-off cascade whose
 * steps come from its row ({@link CampaignCascades}). Consulted by
 * {@link CascadeDefinitions} after the code-defined intents, so a source can
 * never shadow one.
 */
public interface CascadeDefinitionSource {

    Optional<CascadeDefinition> find(String intentKey);
}
