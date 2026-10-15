package in.brand.engage.orchestrator;

import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A cascade-level condition on a step (CLAUDE.md §3.2): whether trying this
 * channel makes sense for this person at all. A failing guard skips the step
 * at once, without consuming its wait. Guards are not policy: consent, caps,
 * quiet hours and budget still run on every send that passes them.
 */
public record Guard(String name, Predicate<Context> test) {

    /** What the guards look at, loaded once per step ({@link DefaultOrchestrator}). */
    /** @param whatsappReadInRun a WhatsApp message of this run has been read */
    public record Context(Priority priority, String waCapability, boolean hasLivePushDevice, boolean hasPhone,
                          Set<String> grants, long cartValuePaise, Map<String, String> vars, boolean whatsappReadInRun) {

        public Context {
            vars = vars == null ? Map.of() : Map.copyOf(vars);
        }

        public Context(Priority priority, String waCapability, boolean hasLivePushDevice, boolean hasPhone,
                       Set<String> grants, long cartValuePaise, Map<String, String> vars) {
            this(priority, waCapability, hasLivePushDevice, hasPhone, grants, cartValuePaise, vars, false);
        }

        public Context(Priority priority, String waCapability, boolean hasLivePushDevice, boolean hasPhone,
                       Set<String> grants, long cartValuePaise) {
            this(priority, waCapability, hasLivePushDevice, hasPhone, grants, cartValuePaise, Map.of(), false);
        }

        public boolean granted(String channel, String purpose) {
            return grants.contains(channel + "/" + purpose);
        }
    }

    /** ₹2,500: above it, a marketing WhatsApp to an UNKNOWN number is worth its price as discovery (CLAUDE.md §4.3). */
    public static final long DISCOVERY_CART_PAISE = 2_500_00;

    /** An active push device that can show it: iOS web never can (Safari ≠ installed PWA). */
    public static final Guard HAS_LIVE_DEVICE = new Guard("HAS_LIVE_DEVICE", Context::hasLivePushDevice);

    /** Known to be on WhatsApp; UNKNOWN only for transactional runs or valuable carts, where the attempt is the discovery. */
    public static final Guard WA_CAPABLE = new Guard("WA_CAPABLE", c -> switch (c.waCapability()) {
        case "CAPABLE" -> true;
        case "INCAPABLE" -> false;
        default -> c.priority() == Priority.TRANSACTIONAL || c.cartValuePaise() >= DISCOVERY_CART_PAISE;
    });

    /** Anything but INCAPABLE: transactional steps attempt optimistically. */
    public static final Guard WA_CAPABLE_OR_UNKNOWN = new Guard("WA_CAPABLE_OR_UNKNOWN", c -> !"INCAPABLE".equals(c.waCapability()));

    public static final Guard WA_MARKETING = new Guard("WA_MARKETING", c -> c.granted("whatsapp", "marketing"));

    public static final Guard HAS_EMAIL_MARKETING = new Guard("HAS_EMAIL_MARKETING", c -> c.granted("email", "marketing"));

    public static final Guard HAS_PHONE = new Guard("HAS_PHONE", Context::hasPhone);

    /** No WhatsApp message of this run was read: the SMS fallback after an unread WhatsApp (CLAUDE.md §4.4). */
    public static final Guard WA_UNREAD = new Guard("WA_UNREAD", c -> !c.whatsappReadInRun());

    /**
     * The run carries a non-blank variable. Lets a journey decide something
     * from config when it starts (a WhatsApp cart threshold, a discount code)
     * and have the step honour it later.
     */
    public static Guard hasVar(String name) {
        return new Guard("HAS_" + name.toUpperCase(java.util.Locale.ROOT), c -> {
            var v = c.vars().get(name);
            return v != null && !v.isBlank();
        });
    }

    /** The run does not carry this variable (e.g. {@code late}: skip a step that would arrive too late). */
    public static Guard lacksVar(String name) {
        return new Guard("LACKS_" + name.toUpperCase(java.util.Locale.ROOT), c -> {
            var v = c.vars().get(name);
            return v == null || v.isBlank();
        });
    }

    public static Guard cartValueAtLeast(long paise) {
        return new Guard("CART_VALUE_AT_LEAST_" + paise, c -> c.cartValuePaise() >= paise);
    }

    public boolean passes(Context c) {
        return test.test(c);
    }
}
