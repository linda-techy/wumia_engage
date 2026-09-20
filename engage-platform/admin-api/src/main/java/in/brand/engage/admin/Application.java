package in.brand.engage.admin;

import io.micronaut.runtime.Micronaut;

/** Admin console API. Operators and customer data; never a webhook endpoint. */
public final class Application {

    private Application() {}

    public static void main(String[] args) {
        Micronaut.run(Application.class, args);
    }
}
