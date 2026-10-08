package in.brand.engage.admin.auth;

import io.micronaut.aop.Around;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The role a controller method needs, or any one of several roles: 401 when
 * not signed in, 403 without it ({@link RoleInterceptor}). Every controller method in admin-api
 * carries this or {@link PublicEndpoint}; an ArchUnit rule fails the build
 * otherwise, so a forgotten check cannot ship.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Around
public @interface RequiresRole {
    /** Any one of these suffices: {@code @RequiresRole({CAMPAIGN_SEND, CONFIG_ADMIN})}. */
    Role[] value();
}
