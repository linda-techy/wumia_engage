package in.brand.engage.worker;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** P2-T08: the nightly dormant sweep and the device_health view (V11), against Postgres. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class TokenLifecycleTest {

    @Inject TokenLifecycle lifecycle;
    @Inject PushTokenGauge gauge;
    @Inject io.micrometer.core.instrument.MeterRegistry registry;
    @Inject TestClock clock;
    @Inject Db db;

    @BeforeEach void reset() {
        clock.setIst("2026-09-28T02:30:00");
        db.inTx(c -> {
            try (var st = c.createStatement(); var rs = st.executeQuery("select current_database()")) {
                rs.next();
                assertTrue(rs.getString(1).endsWith("_test"), "refusing to touch " + rs.getString(1));
            }
            return null;
        });
    }

    @Test void fresh_stays_active_45_days_is_stale_but_active_200_days_is_deactivated() {
        var now = clock.instant();
        long fresh = device(now, null);
        long stale = device(now.minus(Duration.ofDays(45)), null);
        long dormant = device(now.minus(Duration.ofDays(200)), null);

        assertTrue(lifecycle.deactivateDormant() >= 1);

        assertEquals("active", health(fresh));
        assertEquals("stale|t", health(stale) + "|" + active(stale), "stale is a view: the token stays active");
        assertEquals("inactive|dormant", health(dormant) + "|" + reason(dormant));
    }

    @Test void a_click_in_the_last_180_days_keeps_an_unrefreshed_device() {
        var now = clock.instant();
        long clicked = device(now.minus(Duration.ofDays(200)), now.minus(Duration.ofDays(10)));
        long clickedLongAgo = device(now.minus(Duration.ofDays(200)), now.minus(Duration.ofDays(190)));

        lifecycle.deactivateDormant();

        assertEquals("t", active(clicked));
        assertEquals("f", active(clickedLongAgo));
    }

    @Test void a_second_pass_changes_nothing_more() {
        device(clock.instant().minus(Duration.ofDays(365)), null);
        lifecycle.deactivateDormant();

        assertEquals(0, lifecycle.deactivateDormant());
    }

    @Test void the_gauge_counts_devices_by_state_and_browser() {
        double staleBefore = tokens("stale", "chrome");
        device(clock.instant().minus(Duration.ofDays(45)), null);
        device(clock.instant().minus(Duration.ofDays(45)), null);

        gauge.refresh();

        assertEquals(staleBefore + 2, tokens("stale", "chrome"));
    }

    @Test void the_prometheus_endpoint_fails_closed_without_a_token() {
        var res = new MetricsAccessFilter("").check(io.micronaut.http.HttpRequest.GET("/prometheus"));
        assertEquals(io.micronaut.http.HttpStatus.NOT_FOUND, res.status());
    }

    private double tokens(String state, String browser) {
        gauge.refresh();
        var g = registry.find("engage.push.tokens").tags("state", state, "browser", browser).gauge();
        return g == null ? 0 : g.value();
    }

    /* -------------------------------- fixtures -------------------------------- */

    private long device(Instant refreshed, Instant clicked) {
        var id = UUID.randomUUID();
        return db.inTx(c -> {
            Sql.update(c, "INSERT INTO identities (id) VALUES (?)", id);
            try (var ps = Sql.prepare(c, """
                    INSERT INTO devices (identity_id, fcm_token, platform, browser, origin, permission_source,
                                         consent_copy_ver, last_refreshed_at, last_clicked_at)
                    VALUES (?, ?, 'WEB', 'chrome', 'https://test.example', 'notify_me', 'push_v1', ?, ?)
                    RETURNING id""",
                    id, "tok-" + UUID.randomUUID(), refreshed.atOffset(ZoneOffset.UTC),
                    clicked == null ? null : clicked.atOffset(ZoneOffset.UTC));
                 var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    private String health(long deviceId) {
        return one("SELECT state FROM device_health WHERE id = ?", deviceId);
    }

    private String active(long deviceId) {
        return one("SELECT CASE WHEN active THEN 't' ELSE 'f' END FROM devices WHERE id = ?", deviceId);
    }

    private String reason(long deviceId) {
        return one("SELECT deactivated_reason FROM devices WHERE id = ?", deviceId);
    }

    private String one(String sql, Object... params) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, sql, params); var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
    }
}
