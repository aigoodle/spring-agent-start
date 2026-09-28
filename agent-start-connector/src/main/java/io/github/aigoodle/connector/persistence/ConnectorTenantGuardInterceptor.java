package io.github.aigoodle.connector.persistence;

import io.github.aigoodle.persistence.TenantGuardTables;
import io.github.aigoodle.persistence.TenantSqlGuardInterceptor;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Invocation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** @deprecated The guard is now provided by the embeddable agent-start-persistence module. */
@Deprecated(forRemoval = false)
public final class ConnectorTenantGuardInterceptor implements Interceptor {
    /** Historical scope: this shim predates the connector/channel table split. */
    private static final Set<String> GUARDED_TABLES;
    static {
        LinkedHashSet<String> tables = new LinkedHashSet<>(TenantGuardTables.CONNECTOR);
        tables.addAll(TenantGuardTables.CHANNEL);
        GUARDED_TABLES = java.util.Collections.unmodifiableSet(tables);
    }

    private final TenantSqlGuardInterceptor delegate =
            new TenantSqlGuardInterceptor(GUARDED_TABLES);

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        return delegate.intercept(invocation);
    }

    static void requireTenantConstraint(String sql) {
        TenantSqlGuardInterceptor.requireTenantConstraint(sql, GUARDED_TABLES);
    }

    static void requireTenantValues(String sql, String expectedTenant, List<?> values) {
        TenantSqlGuardInterceptor.requireTenantValues(sql, expectedTenant, values);
    }
}
