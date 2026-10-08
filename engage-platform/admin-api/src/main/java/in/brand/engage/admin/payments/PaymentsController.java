package in.brand.engage.admin.payments;

import in.brand.engage.admin.auth.CurrentOperator;
import in.brand.engage.admin.auth.RequiresRole;
import in.brand.engage.admin.auth.Role;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import in.brand.engage.persistence.SqlFiles;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.serde.annotation.Serdeable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code GET /api/payments/failures}: the Razorpay failure match report (last
 * 30 IST days) and the 100 most recent attempts. Money stays paise; the UI
 * formats it. No contact details. Requires VIEWER.
 */
@Controller("/api/payments")
@ExecuteOn(TaskExecutors.BLOCKING)
public class PaymentsController {

    @Serdeable
    public record ReportRow(LocalDate istDay, String matchMethod, long failures, long withoutMobile,
                            BigDecimal avgWebhookLagSeconds) {}

    @Serdeable
    public record Attempt(String gatewayPaymentId, String status, long amountPaise, String method, String errorSource,
                          String errorReason, String matchMethod, boolean joinedToCheckout, OffsetDateTime receivedAt) {}

    @Serdeable
    public record Failures(List<ReportRow> report, List<Attempt> recent) {}

    private final Db db;
    private final CurrentOperator current;

    public PaymentsController(Db db, CurrentOperator current) {
        this.db = db;
        this.current = current;
    }

    @RequiresRole(Role.VIEWER)
    @Get("/failures")
    public Failures failures() {
        return db.inTx(c -> {
            var report = new ArrayList<ReportRow>();
            try (var ps = Sql.prepare(c, SqlFiles.get("payment_failure_report.sql")); var rs = ps.executeQuery()) {
                while (rs.next()) {
                    report.add(new ReportRow(rs.getObject("ist_day", LocalDate.class), rs.getString("match_method"),
                            rs.getLong("failures"), rs.getLong("without_mobile"), rs.getBigDecimal("avg_webhook_lag_s")));
                }
            }
            var recent = new ArrayList<Attempt>();
            try (var ps = Sql.prepare(c, SqlFiles.get("payment_attempts_recent.sql")); var rs = ps.executeQuery()) {
                while (rs.next()) {
                    recent.add(new Attempt(rs.getString("gateway_payment_id"), rs.getString("status"),
                            rs.getLong("amount_paise"), rs.getString("method"), rs.getString("error_source"),
                            rs.getString("error_reason"), rs.getString("match_method"),
                            rs.getBoolean("joined_to_checkout"), Sql.timestamp(rs, "received_at")));
                }
            }
            return new Failures(report, recent);
        });
    }
}
