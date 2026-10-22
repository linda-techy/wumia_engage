package in.brand.engage.channels;

import java.util.List;
import java.util.Objects;

/**
 * A WhatsApp template as Meta knows it: the approved name and language, and
 * the parameters in placeholder order ({{1}}, {{2}}, ...). Rendering fills
 * this in the orchestrator; the adapter only turns it into Graph API JSON.
 *
 * @param urlButtonSuffixes the dynamic part of each URL button, by button index
 *                          (an empty list when the template has none)
 * @param quickReplyPayloads the payload of each quick-reply button, by button index: what a
 *                          tap sends back (a template has either URL buttons or quick replies)
 * @param headerImage       the https link of the header photo, for a template approved with an
 *                          IMAGE header; null for a template without a header
 * @param couponCode        the code of a copy-code button; null without one
 * @param couponIndex       that button's position among the template's buttons (it is the last)
 */
public record WaTemplateCall(String name, String language, List<String> bodyParams, List<String> urlButtonSuffixes,
                             List<String> quickReplyPayloads, String headerImage, String couponCode, int couponIndex) {

    public WaTemplateCall(String name, String language, List<String> bodyParams, List<String> urlButtonSuffixes,
                          List<String> quickReplyPayloads, String headerImage) {
        this(name, language, bodyParams, urlButtonSuffixes, quickReplyPayloads, headerImage, null, -1);
    }

    public WaTemplateCall {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(language, "language");
        bodyParams = List.copyOf(bodyParams);
        urlButtonSuffixes = List.copyOf(urlButtonSuffixes);
        quickReplyPayloads = List.copyOf(quickReplyPayloads);
    }

    public WaTemplateCall(String name, String language, List<String> bodyParams, List<String> urlButtonSuffixes,
                          List<String> quickReplyPayloads) {
        this(name, language, bodyParams, urlButtonSuffixes, quickReplyPayloads, null);
    }

    public WaTemplateCall(String name, String language, List<String> bodyParams, List<String> urlButtonSuffixes) {
        this(name, language, bodyParams, urlButtonSuffixes, List.of(), null);
    }
}
