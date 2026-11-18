# EchoMind Java 版本说明

EchoMind Java 是 Python 版 EchoMind 的 Java/Spring 技术栈重构版，目录位于：



当前版本已经覆盖智能客服主链路：对话请求、Redis 工作记忆、MySQL 版本化
Hybrid RAG、多 Agent 路由、Spring AI 模型调用、回答校验、Commerce/工单业务域、
评测、监控、Swagger 文档和 Docker 部署。

## 技术栈

| 类型 | 技术 |
|------|------|
| 语言 | Java 21 |
| Web 框架 | Spring Boot 3.5 |
| AI 框架 | Spring AI 1.1 |
| LLM Provider | Anthropic、DeepSeek |
| 文档处理 | MarkdownAwareChunker、LangChain4j DocumentSplitter |
| 记忆缓存 | Spring Data Redis |
| RAG | MySQL ngram FULLTEXT + `bge-small-zh-v1.5` ONNX + RRF + rerank |
| 持久化 | MySQL 租户业务/RAG + Redis 工作记忆 + JSON 长期记忆/用户画像 |
| 监控 | Spring Boot Actuator、Micrometer、Prometheus |
| API 文档 | Springdoc OpenAPI、Swagger UI |
| 部署 | Docker、Docker Compose、Nginx、Prometheus |
| 构建 | Maven Wrapper |

## 核心链路

```text
POST /chat
  -> MemoryManager 读取 Redis 工作记忆、会话摘要、长期记忆、用户画像
  -> 版本化 RAG 做词法/向量召回、RRF、rerank 和 citation assembly
  -> AgentOrchestrator 做意图识别和 Agent 路由
  -> General / Technical / Billing Agent 生成回复
  -> AnswerVerifier 校验回复是否可信、是否需要转人工
  -> 写入 Redis，并异步更新用户画像
```

相关实现：

- `src/main/java/com/echomind/api/EchoMindController.java`
- `src/main/java/com/echomind/memory/MemoryManager.java`
- `src/main/java/com/echomind/tool/KnowledgeToolManager.java`
- `src/main/java/com/echomind/agent/AgentOrchestrator.java`
- `src/main/java/com/echomind/agent/AnswerVerifier.java`

## Python 与 Java 版本对照

| 能力 | Python 版本 | Java 版本 | 当前状态 |
|------|-------------|-----------|----------|
| Web 框架 | FastAPI | Spring Boot MVC | 已对齐 |
| 模型调用 | Anthropic Async SDK | Spring AI ChatModel | 已对齐，调用栈不同 |
| DeepSeek | 未内置 | Spring AI DeepSeek profile | Java 增强 |
| Agent 类型 | General / Technical / Billing | General / Technical / Billing | 已对齐 |
| Agent 路由 | 意图路由 + 性能路由 + 降级 | 意图路由 + 性能路由 + 降级 | 已对齐 |
| 复合问题并行处理 | 支持 | 支持 | 已对齐 |
| 意图识别 | LLM + embedding/hash + pattern | LLM + char n-gram semantic + pattern | 基本对齐 |
| 工作记忆 | Redis | Redis | 已对齐 |
| 情景记忆 | ChromaDB `episodic` collection | JSON 持久化 + 本地向量检索 | 功能对齐，存储不同 |
| 用户画像 | ChromaDB `user_profile` collection | JSON 持久化 | 功能对齐，存储不同 |
| 知识库 | ChromaDB `knowledge_base` collection | MySQL 版本化 BGE Hybrid RAG | 功能对齐，主检索实现不同 |
| 查询改写 | LLM 改写 | LLM 改写 | 已对齐 |
| 检索重排 | LLM rerank | LLM rerank，失败回退融合分 | 已对齐 |
| 工具框架 | 通用 MCPToolManager | 专用 KnowledgeToolManager | 部分对齐 |
| 熔断/缓存/超时/fallback | 支持 | 支持 | 已对齐到知识库工具 |
| 评测 | Intent、Macro-F1、LLM Judge、baseline | Intent、Macro-F1、LLM Judge、baseline | 基本对齐 |
| 监控 | Prometheus client、Webhook、Z-score | Actuator、Micrometer、Webhook、阈值告警 | 部分对齐 |
| API 文档 | FastAPI `/docs` | Springdoc Swagger UI `/docs` | 已对齐 |
| Docker | App、Redis、ChromaDB、Prometheus、Nginx | App、Redis、ChromaDB、Prometheus、Nginx | 已对齐 |
| CLI | `api/main.py --cli` | 未实现 | Java 缺失 |

## Java 版已补齐的能力

### DeepSeek 兼容

Java 版通过 Spring profile 切换模型：

```env
SPRING_PROFILES_ACTIVE=deepseek
DEEPSEEK_API_KEY=your_key
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_MODEL=deepseek-chat
```

当前还做了启动容错：缺少真实 API Key 时使用 `echomind-local-placeholder` 避免 Spring AI 自动配置阶段直接失败。真实调用时如果没有配置真实 key，并且：

```env
LLM_FALLBACK_ENABLED=true
```

系统会返回本地降级回复。

Anthropic 配置：

```env
SPRING_PROFILES_ACTIVE=anthropic
ANTHROPIC_API_KEY=your_key
ANTHROPIC_BASE_URL=https://api.anthropic.com
ANTHROPIC_MODEL=claude-3-5-sonnet-20241022
```

### 记忆和知识库持久化

Python 版：

- Redis 保存工作记忆。
- ChromaDB 保存情景记忆、用户画像和知识库。

Java 版：

- Redis 保存工作记忆。
- MySQL 保存租户级知识文档、不可变版本、chunk、FULLTEXT 索引和 BGE 向量。
- `data/java/memory-store.json` 保存情景记忆和用户画像。
- `data/java/knowledge-store.json` 仅保留旧知识工具兼容路径。
- Docker 中保存到 `/app/data/java`，由 `app-data` volume 持久化。

配置项：

```env
ECHOMIND_DATA_DIR=data/java
KNOWLEDGE_STORE_PATH=data/java/knowledge-store.json
MEMORY_STORE_PATH=data/java/memory-store.json
EVAL_BASELINE_PATH=data/eval/baseline.json
```

应用重启后，导入的版本化知识文档、长期记忆和画像可以恢复。Java 没有直接写入
ChromaDB collection；`/chat` 和 `/api/v1/knowledge/*` 使用 MySQL 租户数据与
真实 `bge-small-zh-v1.5` FP32 ONNX embedding。

### Versioned Hybrid RAG

Python 版知识库主要通过 ChromaDB 做语义检索。

Java 版 `/chat` 当前检索链路：

```text
文档版本
  -> Markdown-aware chunking
  -> BGE-small-zh-v1.5 FP32 ONNX CLS embedding
  -> MySQL 不可变版本和 chunk

查询
  -> MySQL ngram FULLTEXT 词法召回
  -> BGE query embedding + 向量召回
  -> Reciprocal Rank Fusion
  -> deterministic rerank
  -> 证据阈值
  -> structured citations + degraded_modes
```

版本 checksum 去重包含 embedding model，因此相同正文可以在模型升级后重新入库。
旧模型活动版本不会参与新模型向量召回，而是明确返回
`embedding_model_mismatch`；词法召回和 citation chain 继续工作。

旧 hash 文档可通过租户管理员接口重复迁移和发布：

```text
POST /api/v1/knowledge/embedding-migrations
```

请求支持 `dry_run`、最大 500 的 `limit` 和 `after_document_id` 游标。流程逐文档锁定
文档行和当前版本、校验源/目标 ACL 与 chunk 数、复用或创建当前 BGE 版本并发布；普通
发布同样先锁文档行。成功重跑会返回
`scanned=0`；失败条目不会阻塞同页后续文档，模型不可用返回
`503 EMBEDDING_UNAVAILABLE`。

迁移实验、实际失败与修复记录：
[BGE ONNX 迁移实验记录](../docs/experiments/BGE_ONNX_MIGRATION.md)。

### 评测和监控

Java 版已经实现：

- Intent Accuracy
- Macro-F1
- per-class Precision / Recall / F1
- Golden RAG Recall@1/3/5、MRR@5、citation hit rate、ACL violation、P95
- 策略级转人工 Accuracy、Precision、Recall、误转人工和漏转人工
- LLM-as-Judge 四维评分
- baseline 保存和回归检测
- `/monitor`
- `/metrics`
- `/actuator/prometheus`
- Webhook 告警
- Agent 路由惩罚反馈

相关实现：

- `src/main/java/com/echomind/evaluation/EndToEndEvaluator.java`
- `src/main/java/com/echomind/evaluation/LLMJudge.java`
- `src/main/java/com/echomind/monitor/PerformanceMonitor.java`

### Golden RAG And Handoff

```text
POST /eval/golden-rag/jobs
GET  /eval/jobs/{jobId}/report.md
```

`rag-support-golden-v1` 固定 12 篇文档、12 个 Chunk、30 条检索案例和 20 条
转人工策略案例。检索案例按 20/10、转人工案例按 12/8 分为 DEV/HOLDOUT，执行真实
BGE ONNX 和 MySQL 混合检索。数据集为合成方法验证基线，V1 不调用外部 LLM。

Golden release gate 的默认阻塞条件：

- ACL violation 为 `0`。
- DEV/HOLDOUT 的 Recall@5、MRR@5、citation hit 相对 baseline 下降不超过
  `0.02`。
- 检索 degraded rate 为 `0`，避免 BGE 不可用时静默通过。
- 转人工 Recall 不低于 `0.95`，false escalation 和 missed handoff 不高于
  `0.10`。
- ALL P95 超过 `2000 ms` 时产生 advisory warning；V1 不据此阻塞。

门槛可通过 `GOLDEN_EVAL_*` 环境变量调整。阻塞检查失败时异步任务返回稳定错误码
`GOLDEN_EVALUATION_GATE_FAILED`，同时保留报告。CI 通过
`scripts/assert-golden-evaluation.ps1` 检查 `gate.passed=true`，并确认
`GoldenEvaluationEndToEndTest` 实际执行且 skipped 为 `0`。

### Swagger / OpenAPI

Python 版 FastAPI 默认提供 `/docs`。

Java 版现在通过 Springdoc OpenAPI 提供同名入口：

- Swagger UI：`http://localhost:8080/docs`
- Nginx 代理：`http://localhost:8081/docs`
- OpenAPI JSON：`http://localhost:8080/v3/api-docs`

相关实现：

- `pom.xml`
- `src/main/resources/application.yml`
- `src/main/java/com/echomind/config/OpenApiConfig.java`
- `src/main/java/com/echomind/api/SwaggerDocsController.java`
- `src/main/java/com/echomind/api/EchoMindController.java`

`/docs` 页面会从 jsdelivr CDN 加载 Swagger UI 静态资源；如果部署在不能访问外网的环境，需要把 Swagger UI 静态资源放到项目本地。

## 当前仍然不同的地方

### 通用 MCPToolManager 未完整迁移

Python 版 `MCPToolManager` 可以注册任意 Tool，并统一处理：

- register / unregister
- JSON Schema 参数校验
- timeout
- circuit breaker
- TTL cache
- fallback
- rerank
- 工具统计

Java 版当前是专用 `KnowledgeToolManager`，只服务 `knowledge_search`，但已经具备查询改写、并行召回、缓存、超时、熔断、fallback、rerank 和统计。

要完全对齐，可以继续新增：

- `ToolDefinition`
- `ToolRegistry`
- `ToolExecutor`
- JSON Schema validator

### ChromaDB 不是 Java 版主检索源

Java 版保留 ChromaDB 容器，并引入了 Spring AI Chroma VectorStore starter，但当前 profile 中排除了 Chroma VectorStore 自动配置，主检索仍然走本地 Hybrid RAG。

原因：

- 版本化 RAG 使用独立、可版本化的 ONNX embedding，不依赖 Chat Provider 自带
  EmbeddingModel。
- 当前实现优先保证 DeepSeek / Anthropic profile 都能与相同检索链路运行。

后续可增强为：

```text
ChromaDB VectorStore semantic search
  + BM25 keyword recall
  + RRF / weighted fusion
  + LLM rerank
```

### CLI 模式未迁移

Python 版支持：

```bash
python api/main.py --cli
```

Java 版当前没有 CLI。如果需要对齐，可以使用 Spring Shell 或 `CommandLineRunner` 实现。

### 监控算法仍有差异

Python 版监控包含 Z-score 异常检测。

Java 版当前是：

- 成功率阈值告警
- 平均延迟阈值告警
- Webhook
- Micrometer 指标
- 路由惩罚反馈

可继续补：

- Z-score 异常检测
- 告警 resolved 状态
- 请求 latency histogram
- tool success rate gauge

## 主要接口

默认端口：`8080`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/health` | 健康检查 |
| POST | `/chat` | 主对话接口 |
| POST | `/search` | 知识库检索 |
| POST | `/knowledge/add` | 批量添加知识文档 |
| POST | `/knowledge/upload` | 上传 `.txt` / `.md` / `.json` 文件 |
| GET | `/knowledge/stats` | 知识库统计 |
| GET | `/monitor` | 监控摘要 |
| GET | `/metrics` | Prometheus 指标，兼容 Python 版路径 |
| GET | `/actuator/prometheus` | Spring Actuator Prometheus 指标 |
| POST | `/eval/run` | 运行评测 |
| GET | `/docs` | Swagger UI，可在线调用接口 |
| GET | `/v3/api-docs` | OpenAPI JSON |

Java 版配置了 Jackson `SNAKE_CASE`，响应字段会输出为：

```json
{
  "conversation_id": "...",
  "response": "...",
  "intent": "...",
  "agent_type": "...",
  "escalated": false,
  "latency_ms": 123,
  "knowledge_used": true,
  "verified": true,
  "grounded": true
}
```

和 Python 版主要差异：

- Python 使用 `conv_id`。
- Java 使用 `conversation_id`。
- Java 额外返回 `verified` 和 `grounded`。

推荐请求字段：

```json
{
  "message": "我想申请退款",
  "user_id": "u1001",
  "conversation_id": "optional-conversation-id"
}
```

## 本地运行

### macOS / Linux

准备环境：

- JDK 21 或更高版本
- Docker Desktop 或 Docker Engine
- PowerShell 7（用于模型下载脚本）

先在 `EchoMindJava` 目录准备 BGE ONNX 模型：

```powershell
pwsh -File scripts/download-bge-small-zh-v1.5.ps1
```

启动依赖：

```bash
docker compose up -d redis chromadb
```

DeepSeek 启动：

```bash
export SPRING_PROFILES_ACTIVE=deepseek
export DEEPSEEK_API_KEY=your_key
./mvnw spring-boot:run
```

Anthropic 启动：

```bash
export SPRING_PROFILES_ACTIVE=anthropic
export ANTHROPIC_API_KEY=your_key
./mvnw spring-boot:run
```

健康检查：

```bash
curl http://localhost:8080/health
```

Swagger：

```text
http://localhost:8080/docs
```

### Windows PowerShell

先下载并校验模型：

```powershell
.\scripts\download-bge-small-zh-v1.5.ps1
```

默认配置会读取：

```text
models/bge-small-zh-v1.5/model.onnx
models/bge-small-zh-v1.5/tokenizer.json
```

模型约 95 MB，被 `.gitignore` 忽略。缺失时会进入 lexical-only 降级，并返回
`embedding_unavailable` / `vector_unavailable`。

启动依赖：

```powershell
docker compose up -d redis chromadb
```

DeepSeek 启动：

```powershell
$env:SPRING_PROFILES_ACTIVE="deepseek"
$env:DEEPSEEK_API_KEY="your_key"
.\mvnw.cmd spring-boot:run
```

Anthropic 启动：

```powershell
$env:SPRING_PROFILES_ACTIVE="anthropic"
$env:ANTHROPIC_API_KEY="your_key"
.\mvnw.cmd spring-boot:run
```

健康检查：

```powershell
curl http://localhost:8080/health
```

Swagger：

```text
http://localhost:8080/docs
```

## Docker 部署

复制配置：

```bash
cp .env.example .env
```

Windows PowerShell：

```powershell
Copy-Item .env.example .env
```

编辑 `.env`，选择模型 profile 并填写对应 API Key。

Docker 构建会把本地 BGE 模型复制进镜像；构建前必须执行：

```powershell
.\scripts\download-bge-small-zh-v1.5.ps1
```

启动：

```bash
docker compose up -d --build
```

查看状态：

```bash
docker compose ps
```

查看日志：

```bash
docker compose logs -f echomind-java
```

停止：

```bash
docker compose down
```

Compose 服务和端口：

| 服务 | 容器名 | 地址 |
|------|--------|------|
| Java App | `echomind-java-app` | `http://localhost:8080` |
| Nginx | `echomind-java-nginx` | `http://localhost:8081` |
| Prometheus | `echomind-java-prometheus` | `http://localhost:9091` |
| ChromaDB | `echomind-java-chromadb` | `http://localhost:8002` |
| Redis | `echomind-java-redis` | `localhost:6380` |

常用验证：

```bash
curl http://localhost:8080/health
curl http://localhost:8081/health
curl http://localhost:8080/metrics
```

Swagger：

```text
http://localhost:8080/docs
http://localhost:8081/docs
```

## API 示例

### 对话

```bash
curl -X POST http://localhost:8080/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "我想申请退款，订单号是 #12345",
    "user_id": "u1001"
  }'
```

也可以打开 Swagger UI，通过页面直接调用：

```text
http://localhost:8080/docs
```

### 检索

```bash
curl -X POST "http://localhost:8080/search?query=退款多久能到账&topK=3"
```

### 添加知识库

```bash
curl -X POST http://localhost:8080/knowledge/add \
  -H "Content-Type: application/json" \
  -d '{
    "documents": [
      {
        "title": "退款补充政策",
        "content": "大促期间退款审核时间可能延长到 3-5 个工作日。"
      }
    ]
  }'
```

### 上传知识库文件

```bash
curl -X POST http://localhost:8080/knowledge/upload \
  -F "file=@docs.md"
```

### 运行评测

```bash
curl -X POST http://localhost:8080/eval/run \
  -H "Content-Type: application/json" \
  -d '{}'
```
