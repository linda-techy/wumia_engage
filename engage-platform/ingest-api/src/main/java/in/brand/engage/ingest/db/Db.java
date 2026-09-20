package in.brand.engage.ingest.db;

import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * Plain JDBC with explicit transactions. No ORM: the hot paths here are
 * set-based SQL (inbox claims, identity resolution, JSONB extraction) and an
 * ORM would only hide them.
 */
@Singleton
public class Db {

    @FunctionalInterface
    public interface Work<T> {
        T apply(Connection c) throws SQLException;
    }

    private final DataSource dataSource;

    public Db(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public <T> T inTx(Work<T> work) {
        try (Connection c = dataSource.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                T result = work.apply(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }

    public static final class DbException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public DbException(SQLException cause) {
            super(cause.getMessage(), cause);
        }

        /** Postgres SQLSTATE, e.g. 23505 unique_violation. */
        public String sqlState() {
            return ((SQLException) getCause()).getSQLState();
        }
    }
}
