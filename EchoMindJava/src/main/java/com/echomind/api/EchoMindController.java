package com.echomind.api;

import com.echomind.agent.AgentOrchestrator;
import com.echomind.agent.AgentRequest;
import com.echomind.agent.AgentType;
import com.echomind.agent.AnswerVerifier;
import com.echomind.agent.OrchestratorResult;
import com.echomind.api.dto.BatchDocInput;
import com.echomind.api.dto.ChatCitationResponse;
import com.echomind.api.dto.ChatRequest;
import com.echomind.api.dto.ChatResponse;
import com.echomind.api.dto.EvalRunRequest;
import com.echomind.evaluation.EndToEndEvaluator;
import com.echomind.evaluation.EvaluationJobService;
import com.echomind.intent.IntentCategory;
import com.echomind.intent.IntentRecognizer;
import com.echomind.intent.IntentResult;
import com.echomind.knowledge.KnowledgeBaseService;
import com.echomind.knowledge.SearchResult;
import com.echomind.memory.MemoryContext;
import com.echomind.memory.MemoryManager;
import com.echomind.memory.MessageRole;
import com.echomind.monitor.PerformanceMonitor;
import com.echomind.saas.api.error.ResourceNotFoundException;
import com.echomind.saas.architecture.LegacySaaSBridge;
import com.echomind.saas.evaluation.api.GoldenRagEvaluationRequest;
import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.identity.domain.TenantScopedActorKey;
import com.echomind.saas.rag.application.KnowledgeRetrieval;
import com.echomind.saas.rag.domain.KnowledgeCitation;
import com.echomind.saas.rag.domain.KnowledgeRetrievalResult;
import com.echomind.saas.rag.domain.RetrievalHit;
import com.echomind.skill.SkillManager;
import com.echomind.tool.KnowledgeToolManager;
import com.echomind.tool.ToolResult;
import com.echomind.trace.ToolCallTrace;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
@Tag(name = "EchoMind API", description = "智能客服对话、知识库、监控和评测接口")
@LegacySaaSBridge("legacy HTTP controller binds authenticated SaaS actor scope into existing services")
public class EchoMindController {

    private final AgentOrchestrator orchestrator;
    private final IntentRecognizer intentRecognizer;
    private final MemoryManager memoryManager;
    private final KnowledgeToolManager knowledgeToolManager;
    private final KnowledgeRetrieval knowledgeRetrieval;
    private final KnowledgeBaseService knowledgeBaseService;
    private final AnswerVerifier answerVerifier;
    private final PerformanceMonitor performanceMonitor;
    private final EndToEndEvaluator evaluator;
    private final EvaluationJobService evaluationJobService;
    private final SkillManager skillManager;
    private final ObjectMapper objectMapper;
    private final PrometheusMeterRegistry prometheusMeterRegistry;

    public EchoMindController(
            AgentOrchestrator orchestrator,
            IntentRecognizer intentRecognizer,
            MemoryManager memoryManager,
            KnowledgeToolManager knowledgeToolManager,
            KnowledgeRetrieval knowledgeRetrieval,
            KnowledgeBaseService knowledgeBaseService,
            AnswerVerifier answerVerifier,
            PerformanceMonitor performanceMonitor,
            EndToEndEvaluator evaluator,
            EvaluationJobService evaluationJobService,
            SkillManager skillManager,
            ObjectMapper objectMapper,
            PrometheusMeterRegistry prometheusMeterRegistry
    ) {
        this.orchestrator = orchestrator;
        this.intentRecognizer = intentRecognizer;
        this.memoryManager = memoryManager;
        this.knowledgeToolManager = knowledgeToolManager;
        this.knowledgeRetrieval = knowledgeRetrieval;
        this.knowledgeBaseService = knowledgeBaseService;
        this.answerVerifier = answerVerifier;
        this.performanceMonitor = performanceMonitor;
        this.evaluator = evaluator;
        this.evaluationJobService = evaluationJobService;
        this.skillManager = skillManager;
        this.objectMapper = objectMapper;
        this.prometheusMeterRegistry = prometheusMeterRegistry;
    }

    @GetMapping("/health")
    @Operation(summary = "健康检查", description = "返回服务状态和 Agent 路由统计。")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "agents", orchestrator.stats());
    }

    @PostMapping("/chat")
    @Operation(summary = "智能客服对话", description = "执行完整对话链路：记忆读取、知识库检索、意图识别、多 Agent 路由、回答校验和记忆写入。")
    public ChatResponse chat(
            @Valid @RequestBody ChatRequest request,
            @AuthenticationPrincipal ActorScope actorScope) {
        String actorKey = new TenantScopedActorKey(
                actorScope.tenantId(), actorScope.userId()).value();
        String conversationId = request.conversationId() == null || request.conversationId().isBlank()
                ? UUID.randomUUID().toString()
                : request.conversationId();
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        MemoryContext memoryContext = memoryManager.getContext(actorKey, conversationId, request.message());
        String memoryText = memoryContext.toPromptText(objectMapper);
        List<Map<String, String>> history = memoryContext.recentMessages().stream()
                .skip(Math.max(0, memoryContext.recentMessages().size() - 5))
                .map(m -> Map.of("role", m.role().name().toLowerCase(), "content", m.content()))
                .toList();
        IntentResult intentResult = intentRecognizer.recognize(request.message(), history);
        boolean useKnowledge = shouldUseKnowledge(intentResult.intent());
        ToolCallTrace knowledgeTrace = null;
        KnowledgeRetrievalResult knowledge = useKnowledge
                ? retrieveKnowledge(request.message(), actorScope)
                : new KnowledgeRetrievalResult(
                        request.message(), List.of(), List.of(), List.of(), 0);
        if (useKnowledge) {
            boolean failed = knowledge.degradedModes().contains("retrieval_failed");
            knowledgeTrace = new ToolCallTrace(
                    "versioned_knowledge_search",
                    !failed,
                    !knowledge.degradedModes().isEmpty(),
                    false,
                    true,
                    knowledge.latencyMs(),
                    String.join(",", knowledge.degradedModes())
            );
        }
        String knowledgeText = buildKnowledgeContext(knowledge);
        String fullContext = join(memoryText, knowledgeText);
        OrchestratorResult result = orchestrator.run(
                AgentRequest.of(request.message(), actorKey, conversationId, fullContext, history, intentResult, requestId),
                knowledgeTrace == null ? List.of() : List.of(knowledgeTrace),
                actorScope
        );
        AnswerVerifier.VerificationResult verification = answerVerifier.verify(request.message(), result.response(), fullContext);
        boolean escalated = result.escalated() || verification.needEscalation();
        memoryManager.addMessage(actorKey, conversationId, MessageRole.USER, request.message());
        memoryManager.addMessage(actorKey, conversationId, MessageRole.ASSISTANT, result.response());
        memoryManager.updateProfile(actorKey, conversationId);
        return new ChatResponse(
                conversationId,
                result.requestId(),
                result.response(),
                result.intent() == null ? "other" : result.intent().name().toLowerCase(),
                intentResult.intentGroup(),
                result.agentType().name().toLowerCase(),
                agentNames(result.agentTypes()),
                result.primaryAgent() == null ? null : result.primaryAgent().name().toLowerCase(Locale.ROOT),
                agentNames(result.supportingAgents()),
                result.routingReason(),
                result.routingConfidence(),
                escalated,
                result.latencyMs(),
                !knowledge.hits().isEmpty(),
                verification.pass(),
                verification.grounded(),
                intentResult.entities(),
                round(intentResult.confidence(), 4),
                intentResult.sourceScores(),
                knowledge.citations().stream()
                        .map(this::chatCitation)
                        .toList(),
                knowledge.degradedModes()
        );
    }

    @PostMapping("/search")
    @Operation(summary = "知识库检索", description = "对用户查询做查询改写、并行召回和 LLM rerank。")
    public Map<String, Object> search(
            @Parameter(description = "检索关键词或用户问题", example = "退款多久能到账") @RequestParam String query,
            @Parameter(description = "返回结果数量", example = "5") @RequestParam(defaultValue = "5") int topK) {
        ToolResult<List<SearchResult>> result = knowledgeToolManager.searchWithRewrite(query, topK);
        return Map.of("query", query, "results", result.data(), "reranked", result.reranked());
    }

    @PostMapping("/knowledge/add")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @Operation(summary = "批量添加知识文档", description = "将文档切片后写入 Java 版持久化知识库。")
    public Map<String, Object> addKnowledge(@Valid @RequestBody BatchDocInput input) {
        List<Map<String, String>> docs = input.documents().stream()
                .map(d -> Map.of("title", d.title(), "content", d.content()))
                .toList();
        int added = knowledgeBaseService.addDocuments(docs);
        return Map.of("added_chunks", added, "total_chunks", knowledgeBaseService.docCount());
    }

    @PostMapping(value = "/knowledge/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @Operation(summary = "上传知识库文件", description = "支持上传 .txt、.md、.json 文件，最大 10MB。JSON 文件格式为数组：[{\"title\":\"...\",\"content\":\"...\"}]。")
    public Map<String, Object> uploadKnowledge(@Parameter(description = "知识库文件") @RequestParam("file") MultipartFile file) throws Exception {
        if (file.getSize() > 10L * 1024 * 1024) {
            throw new IllegalArgumentException("文件大小超过 10MB 限制");
        }
        String filename = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
        String text = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
        List<Map<String, String>> docs;
        if (filename.endsWith(".json")) {
            docs = parseJsonDocs(text);
        } else {
            String title = filename.contains(".") ? filename.substring(0, filename.lastIndexOf('.')) : filename;
            docs = List.of(Map.of("title", title, "content", text));
        }
        int added = knowledgeBaseService.addDocuments(docs);
        return Map.of("message", "文件 " + filename + " 导入成功", "added_chunks", added, "total_chunks", knowledgeBaseService.docCount());
    }

    @GetMapping("/knowledge/stats")
    @Operation(summary = "知识库统计", description = "返回当前知识库片段数量。")
    public Map<String, Object> knowledgeStats() {
        return Map.of("total_chunks", knowledgeBaseService.docCount());
    }

    @GetMapping("/monitor")
    @Operation(summary = "监控摘要", description = "返回 Agent 指标、工具统计、告警和优化建议。")
    public Map<String, Object> monitor() {
        return performanceMonitor.summary();
    }

    @GetMapping({"/trace/{requestId}", "/trace/tool/{requestId}"})
    @Operation(summary = "查看单次请求的工具轨迹", description = "返回指定 requestId 对应的工具调用详情。")
    public Map<String, Object> toolTrace(
            @Parameter(description = "请求 ID") @org.springframework.web.bind.annotation.PathVariable String requestId,
            @AuthenticationPrincipal ActorScope actorScope) {
        return orchestrator.getToolTrace(requestId, actorScope)
                .<Map<String, Object>>map(trace -> Map.of("found", true, "trace", trace))
                .orElseThrow(ResourceNotFoundException::new);
    }

    @GetMapping({"/trace/recent", "/trace/tools"})
    @Operation(summary = "查看最近工具轨迹", description = "返回最近 N 次请求的工具调用详情。")
    public Map<String, Object> recentToolTraces(
            @RequestParam(defaultValue = "20") int limit,
            @AuthenticationPrincipal ActorScope actorScope) {
        return Map.of("items", orchestrator.getRecentToolTraces(limit, actorScope));
    }

    @GetMapping("/skills")
    @Operation(summary = "Skills 摘要", description = "查看当前已加载的动态 Skills，便于确认热加载结果和排查解析错误。")
    public Map<String, Object> skills() {
        return skillManager.summary();
    }

    @PostMapping("/skills/reload")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @Operation(summary = "重新加载 Skills", description = "运行时重新扫描 Skill 目录，不需要重启服务。")
    public Map<String, Object> reloadSkills() {
        skillManager.reload();
        return skillManager.summary();
    }

    @GetMapping(value = "/metrics", produces = "text/plain; version=0.0.4; charset=utf-8")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @Operation(summary = "Prometheus 指标", description = "返回 Prometheus 文本格式指标，兼容 Python 版 /metrics 路径。")
    public ResponseEntity<String> metrics() {
        return ResponseEntity.ok(prometheusMeterRegistry.scrape());
    }

    @PostMapping(value = "/eval/jobs", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @Operation(summary = "提交异步评测任务", description = "立即返回任务 ID；使用 GET /eval/jobs/{jobId} 查询进度和最终报告。")
    public ResponseEntity<com.echomind.saas.evaluation.domain.EvaluationJob> submitEvalJob(
            @RequestBody(required = false) EvalRunRequest request,
            @AuthenticationPrincipal ActorScope actorScope) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(evaluationJobService.submit(request, actorScope));
    }

    @GetMapping("/eval/jobs/{jobId}")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @Operation(summary = "查询评测任务", description = "返回评测任务的状态、进度、失败原因或最终报告。")
    public ResponseEntity<com.echomind.saas.evaluation.domain.EvaluationJob> getEvalJob(
            @org.springframework.web.bind.annotation.PathVariable String jobId,
            @AuthenticationPrincipal ActorScope actorScope) {
        return ResponseEntity.ok(evaluationJobService.get(jobId, actorScope));
    }

    @GetMapping("/eval/jobs")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @Operation(summary = "查询最近评测任务", description = "读取持久化的最近评测任务，刷新页面后可恢复结果。")
    public Map<String, Object> listEvalJobs(
            @RequestParam(defaultValue = "10") int limit,
            @AuthenticationPrincipal ActorScope actorScope) {
        return Map.of("items", evaluationJobService.list(actorScope, limit));
    }

    @PostMapping(
            value = "/eval/golden-rag/jobs",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @Operation(
            summary = "提交 Golden RAG 与转人工评测",
            description = "使用版本化合成数据集执行真实 BGE/MySQL 检索和确定性转人工策略评测。")
    public ResponseEntity<com.echomind.saas.evaluation.domain.EvaluationJob>
            submitGoldenRagJob(
                    @Valid @RequestBody GoldenRagEvaluationRequest request,
                    @AuthenticationPrincipal ActorScope actorScope) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(evaluationJobService.submitGoldenRag(
                        request.datasetVersion(), actorScope));
    }

    @GetMapping(
            value = "/eval/jobs/{jobId}/report.md",
            produces = "text/markdown; charset=utf-8")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @Operation(summary = "下载 Golden RAG Markdown 评测报告")
    public ResponseEntity<String> goldenRagMarkdownReport(
            @org.springframework.web.bind.annotation.PathVariable String jobId,
            @AuthenticationPrincipal ActorScope actorScope) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "text/markdown; charset=utf-8"))
                .body(evaluationJobService.markdownReport(jobId, actorScope));
    }

    @PostMapping(value = "/eval/run", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @Operation(summary = "同步运行评测（兼容接口）", description = "兼容旧调用方；前端应使用异步 /eval/jobs 接口。", deprecated = true)
    public Map<String, Object> eval(
            @RequestBody(required = false) EvalRunRequest request,
            @AuthenticationPrincipal ActorScope actorScope) {
        return evaluator.run(request, new TenantScopedActorKey(
                actorScope.tenantId(), actorScope.userId()));
    }

    private boolean shouldUseKnowledge(IntentCategory intent) {
        if (intent == null) {
            return false;
        }
        return switch (intent) {
            case QUERY,
                    COMPLAINT,
                    REQUEST,
                    TECHNICAL,
                    BILLING,
                    ACCOUNT,
                    ORDER_STATUS,
                    LOGISTICS,
                    REFUND,
                    INVOICE,
                    PAYMENT_ISSUE,
                    ACCOUNT_SECURITY,
                    TECHNICAL_LOGIN,
                    TECHNICAL_CRASH -> true;
            case GREETING, ESCALATION, HUMAN_HANDOFF, FEEDBACK, OTHER -> false;
        };
    }

    private KnowledgeRetrievalResult retrieveKnowledge(
            String query, ActorScope actorScope) {
        try {
            return knowledgeRetrieval.search(query, 4, actorScope);
        } catch (RuntimeException exception) {
            return new KnowledgeRetrievalResult(
                    query,
                    List.of(),
                    List.of(),
                    List.of("retrieval_failed"),
                    0);
        }
    }

    private ChatCitationResponse chatCitation(KnowledgeCitation citation) {
        return new ChatCitationResponse(
                citation.citationId(),
                citation.chunkId(),
                citation.documentId(),
                citation.documentTitle(),
                citation.versionNumber(),
                citation.pageNumber(),
                citation.sectionTitle(),
                citation.headingPath(),
                citation.excerpt(),
                citation.charStart(),
                citation.charEnd(),
                citation.score());
    }

    private String buildKnowledgeContext(KnowledgeRetrievalResult result) {
        if (result == null || result.hits().isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        parts.add("[版本化知识库检索结果]");
        for (int index = 0; index < result.hits().size(); index++) {
            RetrievalHit hit = result.hits().get(index);
            KnowledgeCitation citation = index < result.citations().size()
                    ? result.citations().get(index)
                    : null;
            String citationId = citation == null
                    ? "C" + (index + 1)
                    : citation.citationId();
            parts.add("[" + citationId + "] 文档: "
                    + hit.chunk().documentTitle()
                    + " v" + hit.chunk().versionNumber()
                    + "\n   章节: "
                    + (hit.chunk().headingPath() == null
                            ? "未标注"
                            : hit.chunk().headingPath())
                    + "\n   字符范围: " + hit.chunk().charStart()
                    + "-" + hit.chunk().charEnd()
                    + "\n   内容: " + hit.chunk().content());
        }
        if (!result.degradedModes().isEmpty()) {
            parts.add("[检索降级: " + String.join(",", result.degradedModes()) + "]");
        }
        parts.add("请优先依据以上知识库内容回答。引用具体事实时使用对应的 [citation-id]；"
                + "如果检索降级或证据不足，请明确说明不确定性。");
        return String.join("\n", parts);
    }

    private String join(String left, String right) {
        if (left == null || left.isBlank()) {
            return right == null ? "" : right;
        }
        if (right == null || right.isBlank()) {
            return left;
        }
        return left + "\n\n" + right;
    }

    private List<String> agentNames(List<AgentType> agentTypes) {
        if (agentTypes == null) {
            return List.of();
        }
        return agentTypes.stream()
                .map(agentType -> agentType.name().toLowerCase(Locale.ROOT))
                .toList();
    }

    private double round(double value, int digits) {
        double factor = Math.pow(10, digits);
        return Math.round(value * factor) / factor;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> parseJsonDocs(String text) throws Exception {
        List<?> values = objectMapper.readValue(text, List.class);
        List<Map<String, String>> docs = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof Map<?, ?> map) {
                Map<String, String> doc = new LinkedHashMap<>();
                Object title = map.containsKey("title") ? map.get("title") : "未命名文档";
                Object content = map.containsKey("content") ? map.get("content") : "";
                doc.put("title", String.valueOf(title));
                doc.put("content", String.valueOf(content));
                docs.add(doc);
            }
        }
        return docs;
    }
}
