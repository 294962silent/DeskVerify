# 本地开发与验证

## 开发环境

从仓库根目录打开 PowerShell 7。安装 Docker Desktop 并使用 Linux 容器，确保
`docker version` 和 `docker compose version` 正常。运行完整后端测试需要 JDK 21，
前端开发需要 Node.js 22。只是运行 Windows 演示包不需要本机 JDK 或 Node.js；
请使用 [Windows 发布指南](./RELEASE_WINDOWS_V0_1_1.md) 的根目录启动脚本。

## Testcontainers

项目使用 Testcontainers 1.21.4。如果在 Windows 上 Maven 无法发现已运行的
Docker Desktop Linux 引擎，可在当前 PowerShell 临时显式设置：

```powershell
$env:DOCKER_HOST = "npipe:////./pipe/dockerDesktopLinuxEngine"
```

MySQL 测试使用 UTC 会话参数：

```text
connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true
```

## Java 测试

确认 `JAVA_HOME` 指向 JDK 21，而不是系统上的其他 JDK。从仓库根目录运行：

```powershell
Push-Location EchoMindJava
.\mvnw.cmd -version
.\scripts\download-bge-small-zh-v1.5.ps1
.\mvnw.cmd test
Pop-Location
```

`mvnw.cmd` 首次运行时会下载 Maven Wrapper JAR；该文件和 `target/` 都是本地构建产物。
查看 `EchoMindJava/target/surefire-reports` 确认失败、错误和跳过计数。

## GitHub Actions

`.github/workflows/ci.yml` 在 pull request 和 `main` push 上执行：

- Java 21 完整 Maven 测试，Docker 中运行 MySQL 8.4 Testcontainers。
- 下载并校验 `bge-small-zh-v1.5` ONNX 模型，缓存命中时也会重新校验 SHA-256。
- 断言 Golden E2E 实际执行、`skipped=0` 且 `gate.passed=true`。
- Node 22 下的前端测试和 Vite 生产构建。
- 上传 Surefire、Golden JSON/Markdown 和前端 build artifact。

完整测试生成报告后，从根目录执行同一 Golden artifact 断言：

```powershell
Push-Location EchoMindJava
pwsh -NoProfile -File .\scripts\assert-golden-evaluation.ps1 `
  -ReportDirectory .\target\golden-e2e\reports `
  -SurefireDirectory .\target\surefire-reports
Pop-Location
```

## 应用运行配置

根目录 `start.ps1` 为 Compose 准备 `.env` 和演示会话，不需要手动配置以下变量。
单独启动 JVM 时需要可访问的 MySQL 与 Redis，并设置：

- `ECHOMIND_DB_URL`
- `ECHOMIND_DB_USERNAME`
- `ECHOMIND_DB_PASSWORD`
- `REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`
- `ECHOMIND_JWT_HMAC_SECRET`，Base64 编码且至少 32 解码字节
- `ECHOMIND_JWT_ISSUER`，默认 `echomind-local`
- `ECHOMIND_JWT_AUDIENCE`，默认 `echomind-api`

在当前 PowerShell 中生成本地测试 secret，不将它输出到终端：

```powershell
$env:ECHOMIND_JWT_HMAC_SECRET = [Convert]::ToBase64String(
    [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
)
```

根 Compose 的 MySQL 与 Redis 默认不会向宿主机暴露端口。单独启动 JVM 时，应使用开发者
配置的独立依赖实例，避免在本机同时运行另一个占用 `8080` 的后端。不要把真实 secret
或 token 提交到 Git。Vue 演示页将 JWT 保存在当前标签页的 `sessionStorage`。

## BGE ONNX Embedding

版本化 RAG 默认使用真实的 `BAAI/bge-small-zh-v1.5` FP32 ONNX 模型，不再使用
字符 n-gram hash 作为运行时 embedding。模型文件约 95 MB，不提交到 Git；首次运行前从
后端目录下载并校验：

```powershell
Push-Location EchoMindJava
.\scripts\download-bge-small-zh-v1.5.ps1
Pop-Location
```

脚本从 ModelScope 的 BAAI 转换 FP32 ONNX 包下载文件：

```text
https://modelscope.cn/models/lyf219/bge-small-zh-v1.5-onnx
```

| 文件 | SHA-256 |
| --- | --- |
| `model.onnx` | `e256a606233996478f0eb6c99c9814e3797043d4a5563b4646b10929546196e5` |
| `tokenizer.json` | `48cea5d44424912a6fd1ea647bf4fe50b55ab8b1e5879c3275f80e339e8fae26` |
| `vocab.txt` | `45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c` |

模型配置为 512 维 hidden size、FP32、最大序列 512。Provider 使用 BERT CLS
pooling 和 L2 normalization；文档保持原文编码，查询增加 BGE 中文检索前缀：

```text
为这个句子生成表示以用于检索相关文章：
```

默认配置：

```text
RAG_EMBEDDING_MODEL=bge-small-zh-v1.5-onnx-fp32
RAG_EMBEDDING_MODEL_PATH=models/bge-small-zh-v1.5/model.onnx
RAG_TOKENIZER_PATH=models/bge-small-zh-v1.5/tokenizer.json
RAG_EMBEDDING_MAX_LENGTH=512
RAG_ONNX_INTRA_OP_THREADS=4
```

模型缺失或推理失败时，检索继续执行词法召回，并返回
`embedding_unavailable` 和 `vector_unavailable`；不会回退到 hash 向量。

根目录启动脚本会在构建前调用下载脚本。若手动运行 `docker compose build`，
必须先完成模型下载；模型不随 Git 或 Windows ZIP 分发，但会从本地复制到 Java 镜像。
Actuator `UP` 只证明应用就绪。要验证 ONNX，运行确实计算向量的导入或 Golden 评测，
并检查后端 `Loaded BGE ONNX embedding model` 日志和实际检索报告。

## Commerce Demo Profile

`demo` profile 需要与实际的模型 profile 组合启动。`anthropic,demo` 会在启动时执行常规 Flyway 迁移，再从 demo 目录加载确定性的 Commerce 演示数据迁移：

```text
classpath:db/migration
classpath:db/demo
```

根目录 Compose 默认使用 `anthropic,demo`。独立 JVM 开发时，在配置好独立的
MySQL、Redis 和 JWT 签名密钥后，从仓库根目录运行：

```powershell
Push-Location EchoMindJava
$env:SPRING_PROFILES_ACTIVE = "anthropic,demo"
.\mvnw.cmd spring-boot:run
Pop-Location
```

也可以使用 `deepseek,demo`；必须保留第二个 profile 作为 `demo`，否则不会加载 V100 演示数据。

Demo 数据包含三个租户，每个租户有两个 customer account，并配有活跃订阅、套餐与 entitlement、订单、已支付/逾期发票及失败支付尝试。两个 customer ID 在所有租户中重复使用，用于验证读取始终按租户隔离。

| Tenant | Demo user (`sub`) | Customer IDs |
| --- | --- | --- |
| `acme` | `demo-acme-user` | `customer-001`, `customer-002` |
| `globex` | `demo-globex-user` | `customer-001`, `customer-002` |
| `initech` | `demo-initech-user` | `customer-001`, `customer-002` |

根目录脚本生成 Acme 演示 JWT，可按 [Windows 发布指南](./RELEASE_WINDOWS_V0_1_1.md#access)
验证客户接口。独立应用的 JWT 必须由应用配置的 HMAC 密钥签名，包含有效的
`iss`、`aud`、`sub`、`tenant_id` 和 `exp`。每个租户要用相应已授权的会话；
不能靠修改 JWT claim 绕过成员关系。对当前 actor 未授权的 customer ID 返回 `404`，
避免透露其他租户或客户是否存在。

## Ticket Workflow

Java API 提供以下工单端点：

```text
POST /api/v1/tickets
GET  /api/v1/tickets/{ticketId}
GET  /api/v1/tickets
POST /api/v1/tickets/{ticketId}/claim
POST /api/v1/tickets/{ticketId}/resolve
POST /api/v1/tickets/{ticketId}/close
POST /api/v1/tickets/{ticketId}/reopen
GET  /api/v1/tickets/{ticketId}/events
```

创建接口必须携带 `Idempotency-Key`。首次创建返回 `201 Created`，相同 key 和相同请求的已完成重放返回 `200 OK`。未知或跨租户工单返回统一 `404`；状态或版本冲突返回 `409 TICKET_STATE_CONFLICT`。

Vue 的“工单”页只从 JWT 会话和宿主提供的不可编辑上下文获取客户范围。Demo 默认使用：

```text
VITE_ECHOMIND_CUSTOMER_ID=customer-001
```

如果身份系统在 JWT 中提供 `customer_id` / `customer_ids`，前端会优先使用该 claim 作为展示上下文；后端仍只信任数据库中的真实 membership。`roles` claim 只用于显示支持操作，实际权限始终由 Java 后端校验。可用 `VITE_ECHOMIND_ROLES=SUPPORT_AGENT,TENANT_ADMIN` 为本地演示提供前端角色提示，但不能替代服务端授权。

完整工单链路包括创建、认领、解决、关闭和重开；每次状态变化都写不可变 `ticket_event` 和 transactional outbox。Ticket E2E 测试使用真实 Spring context、JWT 和 MySQL 8.4。

## Versioned RAG

V10 增加租户级知识文档、不可变版本和 chunk 表。Java API：

```text
POST /api/v1/knowledge-documents
POST /api/v1/knowledge-documents/{documentId}/versions
POST /api/v1/knowledge-documents/{documentId}/publish
GET  /api/v1/knowledge-documents/{documentId}/versions
POST /api/v1/knowledge/embedding-migrations
POST /api/v1/knowledge/search
GET  /api/v1/knowledge/stats
```

只有 `TENANT_ADMIN` 可以创建版本和发布。新版本先在 `DRAFT` 状态完成切片和索引，发布时才原子切换 `current_version`，旧版本随后变为 `RETIRED`。相同文档的相同标准化 checksum 不会重复创建版本。

检索先按 JWT actor 的租户和 customer membership 过滤，再并行执行：

1. MySQL ngram FULLTEXT 词法召回。
2. 本地 `bge-small-zh-v1.5` FP32 ONNX 512 维向量召回。
3. Reciprocal Rank Fusion。
4. 可替换 `Reranker` port 的确定性 cross-feature rerank。
5. 证据阈值与 citation assembly。

V11 将版本 checksum 去重键扩展为
`tenant + document + checksum + embedding_model`，因此相同正文可以因 embedding
模型变化重新入库为新一代版本。检索只对当前模型版本的向量打分；仍为旧模型的活动文档
会返回 `embedding_model_mismatch`，词法召回继续可用。若没有任何当前模型版本可参与
向量召回，还会同时返回 `vector_unavailable`。旧文档应重新提交版本并发布，不能把
混合模型状态当成完整语义检索。

旧 hash 文档使用可重复执行的 embedding 迁移接口处理。先预览：

```json
POST /api/v1/knowledge/embedding-migrations
{
  "dry_run": true,
  "limit": 100
}
```

执行：

```json
POST /api/v1/knowledge/embedding-migrations
{
  "dry_run": false,
  "limit": 100
}
```

接口只扫描当前租户 `PUBLISHED` 且 `embedding_model` 不是当前模型的活动版本。每个
文档在自己的事务中锁定 `knowledge_document` 行，读取旧版本和唯一 ACL，复用或创建
当前 BGE 内容版本，校验 chunk 数和 ACL 后发布，旧版本原子退休。某个文档失败不会阻塞
同页其他文档。普通发布也使用同一个文档行锁，因此迁移与人工发布并发时按同一顺序
串行化；MySQL 回归测试会验证发布必须等待文档锁释放。

重跑是幂等的：已迁移文档不再出现在 stale 查询中，成功重跑返回
`scanned=0`、`remaining=0`。需要分页时传入上一页返回的
`next_after_document_id`：

```json
{
  "dry_run": false,
  "limit": 100,
  "after_document_id": "doc-previous-page-last-id"
}
```

单条结果状态为 `PLANNED`、`MIGRATED`、`SKIPPED` 或 `FAILED`。`limit` 最大 500；
BGE 模型不可用时接口返回 `503 EMBEDDING_UNAVAILABLE`。源版本存在混合 ACL、chunk
数不一致，或目标版本 ACL 与源版本不同时，迁移会失败而不是发布错误可见性。

### Golden RAG and Handoff

版本化合成数据集位于：

```text
EchoMindJava/src/main/resources/eval/golden/rag-support-golden-v1/
```

用已绑定 `TENANT_ADMIN` 的会话提交异步评测；普通 Acme 演示会话没有该权限。
评测将固定文档导入专用评测租户，建议使用测试实例：

```text
POST /eval/golden-rag/jobs
```

```json
{
  "dataset_version": "rag-support-golden-v1"
}
```

数据固定 12 篇文档、12 个 Chunk、30 条检索案例和 20 条转人工案例。检索切分为
DEV 20 / HOLDOUT 10，转人工切分为 DEV 12 / HOLDOUT 8。服务使用
`tenant-golden-v1` 导入固定文档，执行真实 MySQL + BGE 检索，并输出
Recall@1/3/5、MRR@5、引用命中率、ACL 违规、P95 延迟和策略级转人工指标。

轮询状态和读取 Markdown 报告：

```text
GET /eval/jobs/{jobId}
GET /eval/jobs/{jobId}/report.md
```

该数据集是 `synthetic`、非人工审核、非生产代表性样本，V1 外部 LLM token 为 0。

Release gate 使用同目录下的 `baseline.json`。默认最大相对回退为 `0.02`；
ACL violation 和 degraded rate 必须为 `0`；转人工 Recall 最低 `0.95`，
误转人工率和漏转人工率最高 `0.10`。ALL P95 超过 `2000 ms` 只产生 advisory
warning，避免把网络和 Runner 抖动误判成质量回退。配置入口为
`echomind.eval.golden-gate`，对应 `GOLDEN_EVAL_*` 环境变量。

阻塞检查失败时，异步任务写入完整报告后标记为 `failed` / `gate_failed`，错误码为
`GOLDEN_EVALUATION_GATE_FAILED`；报告下载接口仍然可用。CI 额外检查 Golden E2E
Surefire 结果，`skipped>0` 视为失败，不能把缺少模型当成通过。

词法或向量任一路失败时，响应会返回 `degraded_modes`，不会把降级结果伪装成全链路成功。
现有降级值包括：

- `lexical_unavailable`
- `embedding_unavailable`
- `embedding_model_mismatch`
- `vector_unavailable`
- `retrieval_failed`

Citation 包含 chunk ID、document ID、标题、版本、section/heading path、字符范围、摘录和分数。检索与发布 E2E 使用真实 Spring context、JWT 和 MySQL 8.4。

`POST /chat` 已切换到同一版本化 RAG 链。聊天响应新增：

- `citations`：与回答上下文对应的结构化引用。
- `retrieval_degraded_modes`：保留检索服务的完整降级列表，包括
  `lexical_unavailable`、`embedding_unavailable`、`embedding_model_mismatch`、
  `vector_unavailable` 或 `retrieval_failed`。

引用 ID 会注入 Agent context，前端在回答下方展示引用标题、版本、章节和摘录。单路检索降级时回答继续生成但明确标记；检索服务整体异常时使用 `retrieval_failed`，不会把空证据伪装成正常检索。

## Frontend

```powershell
Push-Location EchoMindFrontend
npm ci
npm test -- --run
npm run build
Pop-Location
```

使用 Vite 开发服务器的配置见 [前端 README](../EchoMindFrontend/README.md#local-development)。

## 文档范围

仓库内历史实验记录和设计计划用于说明当时的实验或决策，不是当前安装步骤；
独立 Windows ZIP 只包含当前运行与开发文档。
