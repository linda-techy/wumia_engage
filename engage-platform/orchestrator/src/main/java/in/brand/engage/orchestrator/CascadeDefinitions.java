package in.brand.engage.orchestrator;

import jakarta.inject.Singleton;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every {@link CascadeDefinition} bean, by intent key, then the
 * {@link CascadeDefinitionSource}s (campaigns). Duplicate keys fail startup.
 */
@Singleton
public class CascadeDefinitions {

    private final Map<String, CascadeDefinition> byKey;
    private final List<CascadeDefinitionSource> sources;

    public CascadeDefinitions(List<CascadeDefinition> definitions, List<CascadeDefinitionSource> sources) {
        this.sources = List.copyOf(sources);
        var map = new HashMap<String, CascadeDefinition>();
        for (var d : definitions) {
            if (map.put(d.intentKey(), d) != null) {
                throw new IllegalStateException("two cascade definitions for intent " + d.intentKey());
            }
        }
        this.byKey = Map.copyOf(map);
    }

    public Optional<CascadeDefinition> find(String intentKey) {
        var fixed = byKey.get(intentKey);
        if (fixed != null) return Optional.of(fixed);
        for (var source : sources) {
            var found = source.find(intentKey);
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }
}
