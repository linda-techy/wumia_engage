package in.brand.engage.admin.exports;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;

/**
 * RFC 4180 CSV, safe to open in a spreadsheet: a cell starting with
 * {@code = + - @} (or a tab or carriage return) is prefixed with {@code '} so
 * Excel and Sheets treat it as text, not a formula. Template variables and
 * order data come from customers; an export must not become a way to run a
 * formula on an operator's machine.
 */
final class Csv {

    private Csv() {}

    /** The result set as CSV with a header row. @return rows written (excluding the header) */
    static int write(ResultSet rs, StringBuilder out) throws SQLException {
        var md = rs.getMetaData();
        int cols = md.getColumnCount();
        for (int i = 1; i <= cols; i++) {
            if (i > 1) out.append(',');
            out.append(cell(md.getColumnLabel(i)));
        }
        out.append("\r\n");
        int rows = 0;
        while (rs.next()) {
            for (int i = 1; i <= cols; i++) {
                if (i > 1) out.append(',');
                var type = md.getColumnTypeName(i);
                Object v = "timestamptz".equals(type) ? rs.getObject(i, OffsetDateTime.class) : rs.getObject(i);
                out.append(cell(v == null ? "" : v.toString()));
            }
            out.append("\r\n");
            rows++;
        }
        return rows;
    }

    static String cell(String raw) {
        var v = raw;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
            v = '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }
}
