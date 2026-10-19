package in.brand.engage.worker;

import in.brand.engage.orchestrator.templates.WaManagementClient;
import io.micronaut.context.ApplicationContext;
import io.micronaut.json.JsonMapper;
import java.util.Map;

/**
 * One-off WhatsApp commands, run as {@code worker <command>} (the worker's
 * jobs stay off). They talk to Meta with WA_BUSINESS_ACCOUNT_ID and
 * WA_ACCESS_TOKEN from the environment and never print the token.
 *
 * <ul>
 * <li>{@code wa-check}: read-only. The account's phone numbers and every
 *     template Meta holds, with status, category and quality.</li>
 * <li>{@code wa-sync}: mirror Meta's verdicts into this database now.</li>
 * <li>{@code wa-submit}: print what would be submitted for review (every
 *     WhatsApp template locale Meta does not have yet). {@code wa-submit --yes}
 *     submits them. Submitting changes the Meta account: run it only when the
 *     owner has said so.</li>
 * </ul>
 */
final class WaCommands {

    private WaCommands() {}

    static boolean handles(String[] args) {
        return args.length > 0 && args[0].startsWith("wa-");
    }

    static void run(String[] args) {
        try (var ctx = ApplicationContext.builder()
                .properties(Map.of("engage.worker.jobs-enabled", "false", "micronaut.server.port", "-1"))
                .start()) {
            if (!ctx.containsBean(WaManagementClient.class)) {
                System.out.println("WA_BUSINESS_ACCOUNT_ID is not set: nothing to talk to.");
                return;
            }
            var meta = ctx.getBean(WaManagementClient.class);
            var templates = ctx.getBean(WhatsAppTemplates.class);
            var json = ctx.getBean(JsonMapper.class);
            switch (args[0]) {
                case "wa-check" -> {
                    System.out.println("WhatsApp Business Account " + meta.businessAccountId());
                    for (var n : meta.phoneNumbers()) {
                        System.out.printf("  number %s  %s  \"%s\"  quality %s  tier %s%n",
                                n.id(), n.displayNumber(), n.verifiedName(), n.quality(), n.tier());
                    }
                    for (var t : meta.templates()) {
                        System.out.printf("  template %-28s %-6s %-9s %-14s quality %s%s%n", t.name(), t.language(), t.status(),
                                t.category(), t.quality(), t.rejectedReason() == null || "NONE".equals(t.rejectedReason())
                                        ? "" : "  rejected: " + t.rejectedReason());
                    }
                }
                case "wa-sync" -> System.out.println(templates.sync());
                case "wa-submit" -> {
                    var submissions = templates.submissions();
                    boolean yes = args.length > 1 && args[1].equals("--yes");
                    System.out.println(submissions.size() + " template locale(s) not on Meta yet"
                            + (yes ? "; submitting" : "; dry run, add --yes to submit"));
                    for (var s : submissions) {
                        System.out.println(new String(json.writeValueAsBytes(templates.preview(s)), java.nio.charset.StandardCharsets.UTF_8));
                        if (s.samplePhoto() != null) System.out.println("  sample photo: " + s.samplePhoto());
                        if (yes) System.out.println("  -> " + templates.submit(s));
                    }
                }
                default -> System.out.println("unknown command " + args[0] + ": wa-check | wa-sync | wa-submit [--yes]");
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
