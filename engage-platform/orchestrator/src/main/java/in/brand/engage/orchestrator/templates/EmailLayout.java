package in.brand.engage.orchestrator.templates;

import java.util.ArrayList;
import java.util.List;

/**
 * The one branded email layout (P7-T02), in code rather than MJML: a single
 * 600 px table with inline styles, which renders the same in Gmail, Outlook
 * and Apple Mail, and needs no Node toolchain in the Java build. Templates
 * supply the copy (subject, paragraphs, one button); the layout supplies the
 * WUMIKA header, the footer and the unsubscribe link every marketing email
 * must carry. Every value is HTML-escaped.
 */
public final class EmailLayout {

    /** Gmail clips a message above ~102 KB; the lint keeps every template far below it. */
    public static final int MAX_HTML_BYTES = 100 * 1024;

    private static final String INK = "#2b2226";
    private static final String MUTED = "#7a6e72";
    private static final String ACCENT = "#7a2e3a";
    private static final String GROUND = "#f6f1ee";

    private EmailLayout() {}

    public record Rendered(String html, String text) {}

    /**
     * @param body      the copy; paragraphs are separated by a blank line
     * @param button    the call to action's label, or null for none
     * @param buttonUrl where it goes (UTMs already applied)
     */
    public static Rendered render(String subject, String body, String button, String buttonUrl, String unsubscribeUrl) {
        var paragraphs = paragraphs(body);
        var html = new StringBuilder(4096);
        html.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
            .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            .append("<meta name=\"color-scheme\" content=\"light\"><title>").append(esc(subject)).append("</title></head>")
            .append("<body style=\"margin:0;padding:0;background:").append(GROUND).append(";\">")
            // Preheader: the line inboxes show after the subject.
            .append("<div style=\"display:none;max-height:0;overflow:hidden;opacity:0;\">")
            .append(esc(paragraphs.isEmpty() ? subject : paragraphs.getFirst())).append("</div>")
            .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:")
            .append(GROUND).append(";\"><tr><td align=\"center\" style=\"padding:24px 12px;\">")
            .append("<table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" ")
            .append("style=\"width:100%;max-width:600px;background:#ffffff;border-radius:6px;\">")
            .append("<tr><td style=\"padding:28px 32px 8px;font-family:Georgia,'Times New Roman',serif;font-size:22px;")
            .append("letter-spacing:4px;color:").append(ACCENT).append(";\">WUMIKA</td></tr>")
            .append("<tr><td style=\"padding:8px 32px 24px;font-family:Arial,Helvetica,sans-serif;font-size:16px;line-height:1.6;color:")
            .append(INK).append(";\">");
        for (var p : paragraphs) {
            html.append("<p style=\"margin:0 0 16px;\">").append(esc(p).replace("\n", "<br>")).append("</p>");
        }
        if (button != null && buttonUrl != null) {
            html.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:8px 0 8px;\"><tr>")
                .append("<td style=\"background:").append(ACCENT).append(";border-radius:4px;\">")
                .append("<a href=\"").append(esc(buttonUrl)).append("\" style=\"display:inline-block;padding:12px 24px;")
                .append("font-family:Arial,Helvetica,sans-serif;font-size:16px;color:#ffffff;text-decoration:none;\">")
                .append(esc(button)).append("</a></td></tr></table>");
        }
        html.append("</td></tr>")
            .append("<tr><td style=\"padding:16px 32px 28px;border-top:1px solid #eee4e0;font-family:Arial,Helvetica,sans-serif;")
            .append("font-size:12px;line-height:1.5;color:").append(MUTED).append(";\">")
            .append("You are receiving this because you agreed to hear from WUMIKA. ")
            .append("<a href=\"").append(esc(unsubscribeUrl)).append("\" style=\"color:").append(MUTED).append(";\">Unsubscribe</a>")
            .append("</td></tr></table></td></tr></table></body></html>");

        var text = new StringBuilder();
        text.append(String.join("\n\n", paragraphs));
        if (button != null && buttonUrl != null) text.append("\n\n").append(button).append(": ").append(buttonUrl);
        text.append("\n\n--\nYou are receiving this because you agreed to hear from WUMIKA.\nUnsubscribe: ").append(unsubscribeUrl);
        return new Rendered(html.toString(), text.toString());
    }

    static List<String> paragraphs(String body) {
        var out = new ArrayList<String>();
        for (var p : body.strip().split("\\n\\s*\\n")) {
            if (!p.isBlank()) out.add(p.strip());
        }
        return out;
    }

    static String esc(String s) {
        var out = new StringBuilder(s.length() + 16);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
