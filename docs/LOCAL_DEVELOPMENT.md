# 本地开发与验证

## 当前环境

- Docker Desktop: `D:\Docker\Docker`
- Docker WSL 数据目录: `D:\Docker\data\wsl`
- Docker CLI: `D:\Docker\Docker\resources\bin\docker.exe`
- Docker service: `com.docker.service`，启动类型 Automatic
- Windows: Windows 11，WSL 2.6.3，`VirtualMachinePlatform` 已启用
- Java 构建目标: Java 21

Docker Desktop 和 CLI 路径已写入持久用户/系统 PATH。已有终端或 Codex 进程需要重启后才能直接使用 `docker` 命令；当前会话可显式设置：

```powershell
$env:Path = "D:\Docker\Docker\resources\bin;$env:Path"
$env:DOCKER_HOST = "npipe:////./pipe/dockerDesktopLinuxEngine"
docker version
```

## Testcontainers

项目使用 Testcontainers 1.21.4。Docker Engine 29 需要显式使用 Docker Desktop Linux pipe：

```powershell
$env:DOCKER_HOST = "npipe:////./pipe/dockerDesktopLinuxEngine"
```

MySQL 测试使用 UTC 会话参数：

```text
connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true
```

## Java 测试

项目要求 JDK 21。设置 `JAVA_HOME` 后，从后端目录运行：

```powershell
cd E:\EchoMind\echomind-saas-agent\EchoMindJava
$env:JAVA_HOME = "<jdk-21-home>"
$env:Path = "$env:JAVA_HOME\bin;D:\Docker\Docker\resources\bin;$env:Path"
$env:DOCKER_HOST = "npipe:////./pipe/dockerDesktopLinuxEngine"
.\mvnw.cmd test
```

当前工作树验证结果：

- Backend: `246/246`
- Frontend: `17/17`
- Vite production build: passed
- Live MySQL / Testcontainers skips: `0`
- Flyway V1-V11 and demo V100: passed on MySQL 8.4
- Docker image: `echomind-java:bge-verified` built successfully
- Container BGE smoke: provider loaded, 512-dimensional vector persisted, and
  search returned a citation without degraded modes

### Docker Hub Proxy

本机的 Docker Hub 直连会被异常 DNS 结果和连接超时阻断，而 Clash Verge 的
`127.0.0.1:7897` 代理可用。Docker Desktop 当前配置为手动代理：

```text
ProxyHTTPMode=manual
OverrideProxyHTTP=http://127.0.0.1:7897
OverrideProxyHTTPS=http://127.0.0.1:7897
OverrideProxyExclude=localhost,127.0.0.1,::1,hubproxy.docker.internal
```

修改前的设置备份在：

```text
C:\Users\MLL\AppData\Roaming\Docker\settings-store.before-dockerhub-proxyfix.json
```

`docker info` 仍显示 Docker Desktop 内部代理 `http.docker.internal:3128`，这是正常
架构；`%LOCALAPPDATA%\Docker\log\host\httpproxy.log` 会显示实际使用
`app settings http_proxy=http://127.0.0.1:7897`。如果 Clash Verge 更换端口，需要
同步更新 Docker Desktop 的代理设置。

`mvnw.cmd` 在首次运行时会下载 Maven Wrapper JAR；该文件是未跟踪构建产物。

## 应用运行配置

后端需要以下环境：

- `ECHOMIND_DB_URL`
- `ECHOMIND_DB_USERNAME`
- `ECHOMIND_DB_PASSWORD`
- `ECHOMIND_JWT_HMAC_SECRET`，Base64 编码且至少 32 解码字节
- `ECHOMIND_JWT_ISSUER`，默认 `echomind-local`
- `ECHOMIND_JWT_AUDIENCE`，默认 `echomind-api`

生成测试会话 JWT secret：

```powershell
$bytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
[Convert]::ToBase64String($bytes)
```

不要把真实 secret 或 token 提交到 Git。Vue 演示页从 `sessionStorage` 的 `echomind_access_token` 读取 JWT，并在缺少 token 时阻止 Java 请求。

## BGE ONNX Embedding

版本化 RAG 默认使用真实的 `BAAI/bge-small-zh-v1.5` FP32 ONNX 模型，不再使用
字符 n-gram hash 作为运行时 embedding。模型文件约 95 MB，不提交到 Git；首次运行前从
后端目录下载并校验：

```powershell
cd E:\EchoMind\echomind-saas-agent\EchoMindJava
.\scripts\download-bge-small-zh-v1.5.ps1
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

Docker 构建会在本地 `EchoMindJava/models/bge-small-zh-v1.5` 查找模型并复制到镜像。
因此 `docker compose build` 前必须先运行下载脚本；模型目录被 Git 忽略，但不会被子目录
的 `.dockerignore` 排除。`echomind-java:bge-verified` 已验证镜像内模型 SHA-256
与本地一致，并在真实容器中完成入库、发布和检索。

模型替换过程中实际遇到的 bug、根因、修复和未覆盖边界见
[BGE ONNX 迁移实验记录](./experiments/BGE_ONNX_MIGRATION.md)。

## Commerce Demo Profile

`demo` profile 需要与实际的模型 profile 组合启动。`anthropic,demo` 会在启动时执行常规 Flyway 迁移，再从 demo 目录加载确定性的 Commerce 演示数据迁移：

```text
classpath:db/migration
classpath:db/demo
```

设置数据库环境变量和上面的 JWT HMAC secret 后，从后端目录启动：

```powershell
cd E:\EchoMind\echomind-saas-agent\EchoMindJava
$env:SPRING_PROFILES_ACTIVE = "anthropic,demo"
.\mvnw.cmd spring-boot:run
```

也可以使用 `deepseek,demo`；必须保留第二个 profile 作为 `demo`，否则不会加载 V100 演示数据。

Demo 数据包含三个租户，每个租户有两个 customer account，并配有活跃订阅、套餐与 entitlement、订单、已支付/逾期发票及失败支付尝试。两个 customer ID 在所有租户中重复使用，用于验证读取始终按租户隔离。

| Tenant | Demo user (`sub`) | Customer IDs |
| --- | --- | --- |
| `acme` | `demo-acme-user` | `customer-001`, `customer-002` |
| `globex` | `demo-globex-user` | `customer-001`, `customer-002` |
| `initech` | `demo-initech-user` | `customer-001`, `customer-002` |

从外部身份系统取得演示 JWT。JWT 必须由 `ECHOMIND_JWT_HMAC_SECRET` 对应的 secret 签名，并包含有效的 `iss`、`aud`、`sub`、`tenant_id`；例如 Acme 用户使用 `sub=demo-acme-user` 和 `tenant_id=acme`。可选 `jti` 会作为 request ID。不要把 token 写入仓库。

```powershell
$env:DEMO_JWT = Read-Host -Prompt "Bearer token"
curl.exe -H "Authorization: Bearer $env:DEMO_JWT" `
  http://localhost:8080/api/v1/commerce/customers/customer-001
```

更换 `tenant_id` 后，同一个 `customer-001` 路径会返回对应租户的数据；未映射给当前 actor 的 customer ID 返回 `404`，避免泄露其他租户或客户是否存在。

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
cd E:\EchoMind\echomind-saas-agent\EchoMindFrontend
npm test
npm run build
```

## 当前分支

- Branch: `codex/echomind-saas-mvp`
- Last committed baseline: `f8c32cd`
- BGE ONNX migration changes are currently uncommitted in the working tree.
- No remote push was performed.
