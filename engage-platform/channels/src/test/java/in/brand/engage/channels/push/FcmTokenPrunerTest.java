package in.brand.engage.channels.push;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.core.messaging.Addresses;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** A send with mixed FCM results, then the prunes applied to real devices rows. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class FcmTokenPrunerTest {

    @Inject FcmTokenPruner pruner;
    @Inject Db db;

    @Test void dead_tokens_are_deactivated_with_their_reason_and_live_ones_are_untouched() throws Exception {
        var identity = UUID.randomUUID();
        db.inTx(c -> Sql.update(c, "INSERT INTO identities (id) VALUES (?)", identity));
        var reasons = List.of("ok", "UNREGISTERED", "SENDER_ID_MISMATCH", "INVALID_ARGUMENT", "PAYLOAD", "UNAVAILABLE");
        var targets = new ArrayList<Addresses.PushTarget>();
        for (var r : reasons) {
            var token = r + "-" + UUID.randomUUID();
            targets.add(new Addresses.PushTarget(device(identity, token), token));
        }
        var fcm = new FakeFcm(token -> switch (token.substring(0, token.indexOf('-'))) {
            case "ok" -> FcmClient.TokenResult.ok();
            case "INVALID_ARGUMENT" -> FcmClient.TokenResult.error("INVALID_ARGUMENT", FcmAdapterTest.INVALID_TOKEN_MSG);
            case "PAYLOAD" -> FcmClient.TokenResult.error("INVALID_ARGUMENT", "Invalid JSON payload received.");
            default -> FcmClient.TokenResult.error(token.substring(0, token.indexOf('-')), "x");
        });

        var result = new FcmAdapter(fcm).send(FcmAdapterTest.message("b"), new Addresses(targets, null, null));
        db.inTx(c -> {
            pruner.apply(c, result.prunes());
            return null;
        });

        assertEquals(List.of("active", "unregistered", "sender_mismatch", "invalid_token", "active", "active"),
                targets.stream().map(t -> state(t.deviceId())).toList());
    }

    private long device(UUID identity, String token) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, """
                    INSERT INTO devices (identity_id, fcm_token, platform, origin, permission_source, consent_copy_ver)
                    VALUES (?, ?, 'WEB', 'https://test.example', 'add_to_cart', 'push_v1') RETURNING id""",
                    identity, token);
                 var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    private String state(long deviceId) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c,
                    "SELECT CASE WHEN active THEN 'active' ELSE deactivated_reason END FROM devices WHERE id = ?", deviceId);
                 var rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        });
    }
}
