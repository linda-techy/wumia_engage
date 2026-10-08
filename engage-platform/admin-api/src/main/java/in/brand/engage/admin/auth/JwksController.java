package in.brand.engage.admin.auth;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import java.util.Map;

/**
 * {@code /.well-known/jwks.json}: the public half of the access-token key, so
 * another service can verify a console token without a shared secret.
 */
@Controller("/.well-known")
public class JwksController {

    private final Tokens tokens;

    public JwksController(Tokens tokens) {
        this.tokens = tokens;
    }

    @PublicEndpoint("public key material only")
    @Get("/jwks.json")
    public Map<String, Object> jwks() {
        return tokens.jwks();
    }
}
