package in.brand.engage.policy;

import in.brand.engage.core.messaging.Channel;
import in.brand.engage.persistence.Sql;
import in.brand.engage.persistence.SqlFiles;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * The halt switches, read straight from {@code config_current} on every
 * decision. Never cached: a kill switch that needs a cache expiry, or a
 * notification that might be lost, to bite is not a kill switch.
 *
 * <p>Setting a halt needs no second approver, whatever the key's risk tier;
 * stopping is always cheap to approve (P6-T02 enforces that on write).
 */
@Singleton
public class KillSwitch {

    public record Halts(boolean channel, boolean marketing, boolean journey) {}

    /** @param journeyKey null when the send belongs to no journey; its halt is then false */
    public Halts read(Connection c, Channel channel, String journeyKey) throws SQLException {
        try (var ps = Sql.prepare(c, SqlFiles.get("halts.sql"), channel.dbName(), journeyKey);
             var rs = ps.executeQuery()) {
            rs.next();
            return new Halts(rs.getBoolean("channel_halted"), rs.getBoolean("marketing_halted"),
                    journeyKey != null && rs.getBoolean("journey_halted"));
        }
    }
}
