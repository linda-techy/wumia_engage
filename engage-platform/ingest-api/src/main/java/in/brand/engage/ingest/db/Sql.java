package in.brand.engage.ingest.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Positional parameter binding with the null handling Postgres needs. */
public final class Sql {

    private Sql() {}

    public static PreparedStatement prepare(Connection c, String sql, Object... params) throws SQLException {
        var ps = c.prepareStatement(sql);
        try {
            for (int i = 0; i < params.length; i++) bind(ps, i + 1, params[i]);
            return ps;
        } catch (SQLException | RuntimeException e) {
            ps.close();
            throw e;
        }
    }

    public static int update(Connection c, String sql, Object... params) throws SQLException {
        try (var ps = prepare(c, sql, params)) {
            return ps.executeUpdate();
        }
    }

    private static void bind(PreparedStatement ps, int i, Object v) throws SQLException {
        switch (v) {
            // Types.OTHER sends an untyped NULL, so Postgres infers the type from
            // context (a uuid column, a ?::bigint cast). Types.VARCHAR would fail
            // against uuid columns with "operator does not exist: uuid = varchar".
            case null -> ps.setNull(i, Types.OTHER);
            case String s -> ps.setString(i, s);
            case Long l -> ps.setLong(i, l);
            case Integer n -> ps.setInt(i, n);
            case Boolean b -> ps.setBoolean(i, b);
            case UUID u -> ps.setObject(i, u);
            case OffsetDateTime t -> ps.setObject(i, t);
            case String[] arr -> ps.setArray(i, ps.getConnection().createArrayOf("text", arr));
            default -> throw new IllegalArgumentException("unsupported parameter type " + v.getClass());
        }
    }

    public static OffsetDateTime timestamp(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class);
    }

    public static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }
}
