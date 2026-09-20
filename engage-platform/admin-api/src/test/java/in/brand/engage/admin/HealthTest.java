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

    @Test void health_reports_up_and_reaches_the_database() {
        var body = client.toBlocking().retrieve(HttpRequest.GET("/health"));
        assertTrue(body.contains("\"status\":\"UP\""), body);
        assertTrue(body.contains("jdbc:postgresql"), body);
    }
}
