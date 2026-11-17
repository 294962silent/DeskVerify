package com.echomind.saas.identity.infrastructure;

import com.echomind.saas.identity.application.TenantMembershipRepository;
import com.echomind.saas.identity.domain.ActorRole;
import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.identity.domain.UserId;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTenantMembershipRepository
        implements TenantMembershipRepository {

    private final JdbcTemplate jdbc;

    public JdbcTenantMembershipRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<ActorRole> findRoles(TenantId tenantId, UserId userId) {
        return Set.copyOf(jdbc.queryForList("""
                select rb.role_name
                from role_binding rb
                join tenant_membership tm
                  on tm.tenant_id = rb.tenant_id and tm.user_id = rb.user_id
                join tenant t on t.id = tm.tenant_id
                join app_user u on u.id = tm.user_id
                where rb.tenant_id = ? and rb.user_id = ?
                  and tm.status = 'ACTIVE'
                  and t.status = 'ACTIVE'
                  and u.status = 'ACTIVE'
                """, String.class, tenantId.value(), userId.value())
                .stream()
                .map(ActorRole::valueOf)
                .toList());
    }

    @Override
    public Set<String> findCustomerIds(TenantId tenantId, UserId userId) {
        return Set.copyOf(jdbc.queryForList("""
                select cm.customer_id
                from customer_membership cm
                join tenant_membership tm
                  on tm.tenant_id = cm.tenant_id and tm.user_id = cm.user_id
                join tenant t on t.id = tm.tenant_id
                join app_user u on u.id = tm.user_id
                where cm.tenant_id = ? and cm.user_id = ?
                  and tm.status = 'ACTIVE'
                  and t.status = 'ACTIVE'
                  and u.status = 'ACTIVE'
                """, String.class, tenantId.value(), userId.value()));
    }
}
