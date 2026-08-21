package io.github.aigoodle.connector.persistence;

import io.github.aigoodle.persistence.TenantGuardTables;
import io.github.aigoodle.persistence.TenantSqlGuardInterceptor;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Invocation;

import java.util.List;

/** @deprecated The guard is now provided by the embeddable agent-start-persistence module. */
@Deprecated(forRemoval = false)
public final class ConnectorTenantGuardInterceptor implements Interceptor {
    private final TenantSqlGuardInterceptor delegate =
            new TenantSqlGuardInterceptor(TenantGuardTables.CONNECTOR);

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        return delegate.intercept(invocation);
    }

    static void requireTenantConstraint(String sql) {
        TenantSqlGuardInterceptor.requireTenantConstraint(sql, TenantGuardTables.CONNECTOR);
    }

    static void requireTenantValues(String sql, String expectedTenant, List<?> values) {
        TenantSqlGuardInterceptor.requireTenantValues(sql, expectedTenant, values);
    }
}
