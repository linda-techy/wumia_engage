package in.brand.engage.admin.reports;

/**
 * Treated vs holdout on per-person revenue (P7-T03): Welch's t-test, which
 * does not assume equal variances (revenue per person is heavily skewed, and
 * the holdout is a tenth the size of the treated group). Computed from the
 * per-person values, never from aggregates, so the variance is real.
 */
public final class LiftStats {

    /**
     * @param diff  mean(treated) - mean(control), paise per person
     * @param low   lower end of the 95% confidence interval of {@code diff}
     * @param high  upper end
     * @param df    Welch–Satterthwaite degrees of freedom
     */
    public record Result(int nTreated, int nControl, double meanTreated, double meanControl,
                         double diff, double low, double high, double df) {

        /** Relative lift, or NaN when the holdout earned nothing. */
        public double liftPct() {
            return meanControl == 0 ? Double.NaN : 100.0 * diff / meanControl;
        }

        /** The interval excludes zero: the difference is unlikely to be chance. */
        public boolean significant() {
            return low > 0 || high < 0;
        }
    }

    private LiftStats() {}

    /** @return null when either group has fewer than two people (no variance) */
    public static Result welch(double[] treated, double[] control) {
        int n1 = treated.length;
        int n2 = control.length;
        if (n1 < 2 || n2 < 2) return null;
        double m1 = mean(treated);
        double m2 = mean(control);
        double v1 = variance(treated, m1);
        double v2 = variance(control, m2);
        double a = v1 / n1;
        double b = v2 / n2;
        double se = Math.sqrt(a + b);
        double diff = m1 - m2;
        if (se == 0) return new Result(n1, n2, m1, m2, diff, diff, diff, n1 + n2 - 2);
        double df = (a + b) * (a + b) / ((a * a) / (n1 - 1) + (b * b) / (n2 - 1));
        double t = tQuantile975(df);
        return new Result(n1, n2, m1, m2, diff, diff - t * se, diff + t * se, df);
    }

    static double mean(double[] x) {
        double s = 0;
        for (double v : x) s += v;
        return s / x.length;
    }

    /** Sample variance (n - 1). */
    static double variance(double[] x, double mean) {
        double s = 0;
        for (double v : x) s += (v - mean) * (v - mean);
        return s / (x.length - 1);
    }

    /**
     * The 97.5% quantile of Student's t with {@code df} degrees of freedom
     * (two-sided 95%), by the Cornish–Fisher expansion around the normal
     * quantile: within 0.01 of the tables from df = 3, exact as df grows.
     */
    static double tQuantile975(double df) {
        double z = 1.959963984540054;
        double z3 = z * z * z;
        double z5 = z3 * z * z;
        double z7 = z5 * z * z;
        double z9 = z7 * z * z;
        return z
                + (z3 + z) / (4 * df)
                + (5 * z5 + 16 * z3 + 3 * z) / (96 * df * df)
                + (3 * z7 + 19 * z5 + 17 * z3 - 15 * z) / (384 * df * df * df)
                + (79 * z9 + 776 * z7 + 1482 * z5 - 1920 * z3 - 945 * z) / (92160 * df * df * df * df);
    }
}
