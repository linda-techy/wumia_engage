package in.brand.engage.admin.auth;

import io.micronaut.aop.InterceptorBean;
import io.micronaut.aop.MethodInterceptor;
import io.micronaut.aop.MethodInvocationContext;
import jakarta.inject.Singleton;

/** Enforces {@link RequiresRole} before the controller method runs. */
@Singleton
@InterceptorBean(RequiresRole.class)
public class RoleInterceptor implements MethodInterceptor<Object, Object> {

    private final CurrentOperator current;

    public RoleInterceptor(CurrentOperator current) {
        this.current = current;
    }

    @Override
    public Object intercept(MethodInvocationContext<Object, Object> context) {
        var roles = context.enumValues(RequiresRole.class, Role.class);
        if (roles.length == 0) throw new IllegalStateException("@RequiresRole without a role on " + context);
        current.requireAny(java.util.Arrays.stream(roles).map(Role::name).toList());
        return context.proceed();
    }
}
