package in.brand.engage.admin.reports;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class LiftStatsTest {

    @Test void the_t_quantile_matches_the_tables() {
        // Student's t, 97.5%: df 3 → 3.182, 10 → 2.228, 30 → 2.042, 100 → 1.984, ∞ → 1.960
        assertEquals(3.182, LiftStats.tQuantile975(3), 0.01);
        assertEquals(2.228, LiftStats.tQuantile975(10), 0.002);
        assertEquals(2.042, LiftStats.tQuantile975(30), 0.001);
        assertEquals(1.984, LiftStats.tQuantile975(100), 0.001);
        assertEquals(1.960, LiftStats.tQuantile975(1e9), 0.001);
    }

    @Test void a_small_worked_example() {
        // treated mean 3, var 2.5 (n 5); control mean 2, var 1 (n 3)
        var r = LiftStats.welch(new double[] {1, 2, 3, 4, 5}, new double[] {1, 2, 3});
        assertEquals(1.0, r.diff(), 1e-9);
        double se = Math.sqrt(2.5 / 5 + 1.0 / 3);
        double df = Math.pow(2.5 / 5 + 1.0 / 3, 2) / (Math.pow(2.5 / 5, 2) / 4 + Math.pow(1.0 / 3, 2) / 2);
        assertEquals(df, r.df(), 1e-9);
        assertEquals(1.0 - LiftStats.tQuantile975(df) * se, r.low(), 1e-9);
        assertEquals(50.0, r.liftPct(), 1e-9);
    }

    @Test void a_seeded_20_percent_lift_lands_inside_its_interval() {
        // The P7-T03 "done when": skewed revenue (most people buy nothing), holdout a tenth of treated.
        var rnd = new Random(42);
        double[] treated = sample(rnd, 20_000, 0.06 * 1.2);
        double[] control = sample(rnd, 2_000, 0.06);
        var r = LiftStats.welch(treated, control);
        double trueDiff = 0.06 * 0.2 * 150_000;                   // 20% more buyers at ₹1,500 each
        assertTrue(r.low() <= trueDiff && trueDiff <= r.high(), r.toString());
    }

    @Test void no_difference_is_not_significant_and_tiny_groups_give_nothing() {
        var rnd = new Random(7);
        var r = LiftStats.welch(sample(rnd, 5_000, 0.05), sample(rnd, 500, 0.05));
        assertTrue(r.low() < 0 && r.high() > 0, r.toString());
        assertNull(LiftStats.welch(new double[] {1}, new double[] {1, 2}));
    }

    /** Each person buys (₹1,500 = 150,000 paise) with probability {@code p}, else nothing. */
    static double[] sample(Random rnd, int n, double p) {
        var out = new double[n];
        for (int i = 0; i < n; i++) out[i] = rnd.nextDouble() < p ? 150_000 : 0;
        return out;
    }
}
