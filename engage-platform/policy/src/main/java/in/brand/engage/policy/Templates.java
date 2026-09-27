package in.brand.engage.policy;

import in.brand.engage.core.messaging.Category;
import in.brand.engage.core.messaging.Channel;
import in.brand.engage.persistence.Sql;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Reads the {@code templates} registry. Uncached: pausing a template (by an
 * operator, or by the P4 quality sync on a RED rating) must stop the next send.
 */
@Singleton
public class Templates {

    public Optional<Template> find(Connection c, String key) throws SQLException {
        try (var ps = Sql.prepare(c, "SELECT key, channel::text, category::text, status FROM templates WHERE key = ?", key);
             var rs = ps.executeQuery()) {
            if (!rs.next()) return Optional.empty();
            return Optional.of(new Template(rs.getString(1), Channel.fromDb(rs.getString(2)),
                    Category.fromDb(rs.getString(3)), rs.getString(4)));
        }
    }
}
