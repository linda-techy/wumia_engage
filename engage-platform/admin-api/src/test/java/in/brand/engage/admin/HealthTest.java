package in.brand.engage.admin;

import static org.junit.jupiter.api.Assertions.*;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class HealthTest {

    @Inject @Client("/") HttpClient client;

    @Test void health_reports_up_without_disclosing_the_database() {
        var body = client.toBlocking().retrieve(HttpRequest.GET("/health"));
        // UP still proves the database is reachable: Micronaut's aggregate
        // health goes DOWN (503) when the jdbc indicator fails.
        assertTrue(body.contains("\"status\":\"UP\""), body);
        assertFalse(body.contains("jdbc:postgresql"), body);
        assertFalse(body.contains(System.getenv("DB_NAME")), body);
    }
}
