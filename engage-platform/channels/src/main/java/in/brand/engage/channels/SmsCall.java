package in.brand.engage.channels;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A DLT SMS as the operator knows it (CLAUDE.md §5): the registered template
 * id, the registered text with its {@code {#var#}} slots, and one value per
 * slot. Rendering fills this in the orchestrator; the adapter re-checks it
 * before any HTTP call, because content that deviates from the registered
 * template is dropped by the operator with a success response.
 *
 * @param dltTemplateId      the 19-digit DLT content template id
 * @param providerTemplateId MSG91's own id for the template it holds for the DLT one
 * @param dltBody            the registered text, {@code {#var#}} for each slot
 * @param names              the variable name behind each slot, in slot order (MSG91 takes them by name)
 * @param values             one value per slot, in slot order
 */
public record SmsCall(String dltTemplateId, String providerTemplateId, String dltBody,
                      List<String> names, List<String> values) {

    /** The DLT slot marker. */
    public static final String SLOT = "{#var#}";
    /** The usual DLT limit on one variable's value. */
    public static final int VALUE_MAX = 30;
    /** A DLT content template id: 19 digits. */
    public static final Pattern DLT_ID = Pattern.compile("\\d{19}");

    public SmsCall {
        Objects.requireNonNull(dltTemplateId, "dltTemplateId");
        Objects.requireNonNull(providerTemplateId, "providerTemplateId");
        Objects.requireNonNull(dltBody, "dltBody");
        names = List.copyOf(names);
        values = List.copyOf(values);
    }

    /** {@code {#var#}} slots in the registered text. */
    public int slots() {
        int n = 0;
        for (int i = dltBody.indexOf(SLOT); i >= 0; i = dltBody.indexOf(SLOT, i + SLOT.length())) n++;
        return n;
    }

    /** Why the operator would drop this message, or null if it matches its registration. */
    public String mismatch() {
        if (!DLT_ID.matcher(dltTemplateId).matches()) return "dlt_template_id '" + dltTemplateId + "' is not a registered id";
        if (providerTemplateId.isBlank() || providerTemplateId.equalsIgnoreCase("PENDING")) return "no MSG91 template id";
        if (names.size() != values.size()) return names.size() + " names for " + values.size() + " values";
        if (values.size() != slots()) return values.size() + " values for " + slots() + " registered slots";
        for (int i = 0; i < values.size(); i++) {
            var v = values.get(i);
            if (v == null || v.isBlank()) return "variable '" + names.get(i) + "' is empty";
            if (v.codePointCount(0, v.length()) > VALUE_MAX) return "variable '" + names.get(i) + "' is over " + VALUE_MAX + " characters";
        }
        return null;
    }
}
