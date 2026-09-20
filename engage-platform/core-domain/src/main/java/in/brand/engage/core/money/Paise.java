package in.brand.engage.core.money;

import java.math.BigDecimal;

/**
 * Money, always as a whole number of paise. Never double, never float
 * (CLAUDE.md invariant 6).
 *
 * <p>Two sources, two units — the reason this type exists:
 * <ul>
 *   <li>Shopify sends decimal rupee strings: {@code "1299.00"}. Use {@link #ofRupees(String)}.</li>
 *   <li>Razorpay sends integers already in paise: {@code 129900}. Use {@link #of(long)}.
 *       Dividing Razorpay amounts by 100 is the classic bug.</li>
 * </ul>
 */
public record Paise(long value) implements Comparable<Paise> {

    public static final Paise ZERO = new Paise(0);

    public Paise {
        if (value < 0) throw new IllegalArgumentException("negative amount: " + value);
    }

    public static Paise of(long paise) {
        return new Paise(paise);
    }

    /**
     * Parses a Shopify decimal rupee string. Throws if it has more than two
     * decimal places, because silently rounding money hides upstream bugs.
     */
    public static Paise ofRupees(String rupees) {
        if (rupees == null || rupees.isBlank()) throw new IllegalArgumentException("blank amount");
        return new Paise(new BigDecimal(rupees.trim()).movePointRight(2).longValueExact());
    }

    public Paise plus(Paise other) {
        return new Paise(Math.addExact(value, other.value));
    }

    public boolean withinOf(Paise other, long tolerancePaise) {
        return Math.abs(value - other.value) <= tolerancePaise;
    }

    /**
     * Indian digit grouping (lakh/crore): 17200000 paise → "1,72,000";
     * 12345678950 → "12,34,56,789.50". Whole rupees drop the decimals.
     *
     * <p>Hand-rolled on purpose. {@code java.text.NumberFormat} supports only a
     * single grouping size, so even with the en-IN locale it prints "172,000".
     * An Indian reader parses that as foreign at a glance.
     */
    public String toRupeeString() {
        var digits = Long.toString(value / 100);
        var out = new StringBuilder();
        int n = digits.length();
        for (int i = 0; i < n; i++) {
            int fromRight = n - i;                  // digits remaining including this one
            out.append(digits.charAt(i));
            // Separator after this digit if what remains is 3, 5, 7, ... digits.
            if (fromRight > 1 && fromRight - 1 >= 3 && (fromRight - 1 - 3) % 2 == 0) out.append(',');
        }
        long p = value % 100;
        return p == 0 ? out.toString() : out + "." + (p < 10 ? "0" + p : Long.toString(p));
    }

    @Override
    public int compareTo(Paise o) {
        return Long.compare(value, o.value);
    }
}
