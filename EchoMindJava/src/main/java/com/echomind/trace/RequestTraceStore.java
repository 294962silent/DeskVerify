package com.echomind.trace;

import com.echomind.saas.architecture.LegacySaaSBridge;
import com.echomind.saas.identity.domain.ActorRole;
import com.echomind.saas.identity.domain.ActorScope;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
@LegacySaaSBridge("legacy in-memory trace store adapts authenticated SaaS actor scope")
public class RequestTraceStore {

    private static final int MAX_SIZE = 200;

    private final Deque<RequestToolTrace> traces = new ArrayDeque<>();

    public synchronized void record(RequestToolTrace trace, ActorScope actorScope) {
        Objects.requireNonNull(actorScope, "actor scope is required to record a trace");
        if (trace == null || trace.requestId() == null || trace.requestId().isBlank()) {
            return;
        }
        RequestToolTrace normalized = new RequestToolTrace(
                trace.requestId(),
                trace.timestamp() == null || trace.timestamp().isBlank()
                        ? Instant.now().toString()
                        : trace.timestamp(),
                trace.endpoint(),
                trace.userId(),
                trace.conversationId(),
                trace.intent(),
                trace.intentGroup(),
                trace.agentType(),
                trace.primaryAgent(),
                trace.supportingAgents(),
                trace.toolsUsed(),
                trace.toolCalls(),
                trace.knowledgeUsed(),
                trace.escalated(),
                trace.latencyMs(),
                actorScope.tenantId().value(),
                actorScope.userId().value());
        traces.addLast(normalized);
        while (traces.size() > MAX_SIZE) {
            traces.removeFirst();
        }
    }

    public synchronized Optional<RequestToolTrace> find(String requestId, ActorScope actorScope) {
        Objects.requireNonNull(actorScope, "actor scope is required to read a trace");
        Iterator<RequestToolTrace> iterator = traces.descendingIterator();
        while (iterator.hasNext()) {
            RequestToolTrace trace = iterator.next();
            if (Objects.equals(requestId, trace.requestId()) && canRead(trace, actorScope)) {
                return Optional.of(trace);
            }
        }
        return Optional.empty();
    }

    public synchronized List<RequestToolTrace> recent(int limit, ActorScope actorScope) {
        Objects.requireNonNull(actorScope, "actor scope is required to read traces");
        if (limit <= 0 || traces.isEmpty()) {
            return List.of();
        }
        List<RequestToolTrace> items = new ArrayList<>(Math.min(limit, traces.size()));
        Iterator<RequestToolTrace> iterator = traces.descendingIterator();
        while (iterator.hasNext() && items.size() < limit) {
            RequestToolTrace trace = iterator.next();
            if (canRead(trace, actorScope)) {
                items.add(trace);
            }
        }
        return List.copyOf(items);
    }

    public synchronized void clear() {
        traces.clear();
    }

    private boolean canRead(RequestToolTrace trace, ActorScope actorScope) {
        if (trace == null || !Objects.equals(trace.tenantId(), actorScope.tenantId().value())) {
            return false;
        }
        return actorScope.hasRole(ActorRole.TENANT_ADMIN)
                || Objects.equals(trace.actorUserId(), actorScope.userId().value());
    }
}
