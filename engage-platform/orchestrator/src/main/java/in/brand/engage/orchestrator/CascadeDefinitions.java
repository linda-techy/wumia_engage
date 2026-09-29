package in.brand.engage.orchestrator;

import jakarta.inject.Singleton;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Every {@link CascadeDefinition} bean, by intent key. Duplicate keys fail startup. */
@Singleton
public class CascadeDefinitions {

    private final Map<String, CascadeDefinition> byKey;

    public CascadeDefinitions(List<CascadeDefinition> definitions) {
        var map = new HashMap<String, CascadeDefinition>();
        for (var d : definitions) {
            if (map.put(d.intentKey(), d) != null) {
                throw new IllegalStateException("two cascade definitions for intent " + d.intentKey());
            }
        }
        this.byKey = Map.copyOf(map);
    }

    public Optional<CascadeDefinition> find(String intentKey) {
        return Optional.ofNullable(byKey.get(intentKey));
    }
}
