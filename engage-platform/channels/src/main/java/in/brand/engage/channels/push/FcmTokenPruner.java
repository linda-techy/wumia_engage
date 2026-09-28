package in.brand.engage.channels.push;

import in.brand.engage.channels.TokenPrune;
import in.brand.engage.persistence.DeviceRepository;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Deactivates the tokens a send found dead. The router calls it in the
 * transaction that records the send's outcome (P3-T05 step 3), so a token
 * FCM reports as UNREGISTERED is gone in the same send cycle.
 */
@Singleton
public class FcmTokenPruner {

    private final DeviceRepository devices;

    public FcmTokenPruner(DeviceRepository devices) {
        this.devices = devices;
    }

    public void apply(Connection c, List<TokenPrune> prunes) throws SQLException {
        for (var p : prunes) devices.deactivate(c, p.deviceId(), p.reason());
    }
}
