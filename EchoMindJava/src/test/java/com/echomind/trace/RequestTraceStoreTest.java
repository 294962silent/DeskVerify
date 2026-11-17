package com.echomind.trace;

import com.echomind.saas.identity.domain.ActorRole;
import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.identity.domain.UserId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestTraceStoreTest {

    private final RequestTraceStore store = new RequestTraceStore();

    @Test
    void recordingRequiresAnActorScope() {
        assertThatThrownBy(() -> store.record(trace("request-1", "user-a"), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("actor scope");
    }

    @Test
    void usersReadOnlyTheirOwnTraceAndAdminsReadTheirTenant() {
        store.record(trace("request-a", "user-a"), actor("tenant-a", "user-a", ActorRole.CUSTOMER));
        store.record(trace("request-b", "user-b"), actor("tenant-a", "user-b", ActorRole.SUPPORT_AGENT));
        store.record(trace("request-c", "user-c"), actor("tenant-b", "user-c", ActorRole.CUSTOMER));

        assertThat(store.find("request-a", actor("tenant-a", "user-a", ActorRole.CUSTOMER))).isPresent();
        assertThat(store.find("request-b", actor("tenant-a", "user-a", ActorRole.CUSTOMER))).isEmpty();
        assertThat(store.find("request-a", actor("tenant-b", "user-a", ActorRole.CUSTOMER))).isEmpty();
        assertThat(store.find("request-b", actor("tenant-a", "admin-a", ActorRole.TENANT_ADMIN))).isPresent();
        assertThat(store.find("request-c", actor("tenant-a", "admin-a", ActorRole.TENANT_ADMIN))).isEmpty();
    }

    @Test
    void recentTracesUseTheSameTenantAndActorFilters() {
        store.record(trace("request-a", "user-a"), actor("tenant-a", "user-a", ActorRole.CUSTOMER));
        store.record(trace("request-b", "user-b"), actor("tenant-a", "user-b", ActorRole.SUPPORT_AGENT));
        store.record(trace("request-c", "user-c"), actor("tenant-b", "user-c", ActorRole.CUSTOMER));

        assertThat(store.recent(20, actor("tenant-a", "user-a", ActorRole.CUSTOMER)))
                .extracting(RequestToolTrace::requestId)
                .containsExactly("request-a");
        assertThat(store.recent(20, actor("tenant-a", "admin-a", ActorRole.TENANT_ADMIN)))
                .extracting(RequestToolTrace::requestId)
                .containsExactly("request-b", "request-a");
        assertThat(store.recent(20, actor("tenant-b", "user-c", ActorRole.CUSTOMER)))
                .extracting(RequestToolTrace::requestId)
                .containsExactly("request-c");
    }

    private static RequestToolTrace trace(String requestId, String userId) {
        return new RequestToolTrace(
                requestId,
                "2026-11-18T00:00:00Z",
                "chat",
                "tenant-qualified:" + userId,
                "conversation-1",
                "greeting",
                "smalltalk",
                "general",
                "general",
                List.of(),
                List.of(),
                List.of(),
                false,
                false,
                1);
    }

    private static ActorScope actor(String tenantId, String userId, ActorRole role) {
        return new ActorScope(
                new UserId(userId),
                new TenantId(tenantId),
                Set.of(role),
                Set.of(),
                "request-id");
    }
}
