package in.brand.engage.admin.web;

import in.brand.engage.persistence.Sql;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Report rows as maps with camelCase keys, for read-only views whose columns
 * are the API (the dashboard). Dates become ISO strings, timestamps
 * {@link OffsetDateTime}. Not for anything that needs a typed contract.
 */
public final class Rows {

    private Rows() {}

    public static List<Map<String, Object>> list(Connection c, String sql, Object... params) throws SQLException {
        var out = new ArrayList<Map<String, Object>>();
        try (var ps = Sql.prepare(c, sql, params); var rs = ps.executeQuery()) {
            var md = rs.getMetaData();
            while (rs.next()) {
                var row = new LinkedHashMap<String, Object>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    Object v = switch (md.getColumnType(i)) {
                        case Types.DATE -> rs.getObject(i) == null ? null : rs.getDate(i).toLocalDate().toString();
                        case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> rs.getObject(i, OffsetDateTime.class);
                        default -> rs.getObject(i);
                    };
                    row.put(camel(md.getColumnLabel(i)), v);
                }
                out.add(row);
            }
        }
        return out;
    }

    static String camel(String snake) {
        var sb = new StringBuilder(snake.length());
        boolean up = false;
        for (char ch : snake.toCharArray()) {
            if (ch == '_') {
                up = sb.length() > 0;
            } else {
                sb.append(up ? Character.toUpperCase(ch) : ch);
                up = false;
            }
        }
        return sb.toString();
    }
}
