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

当前 Foundation 验证结果：

- Backend: `107/107`
- Frontend: `6/6`
- Vite production build: passed
- Live MySQL / Testcontainers skips: `0`

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
POST /api/v1/knowledge/search
GET  /api/v1/knowledge/stats
```

只有 `TENANT_ADMIN` 可以创建版本和发布。新版本先在 `DRAFT` 状态完成切片和索引，发布时才原子切换 `current_version`，旧版本随后变为 `RETIRED`。相同文档的相同标准化 checksum 不会重复创建版本。

检索先按 JWT actor 的租户和 customer membership 过滤，再并行执行：

1. MySQL ngram FULLTEXT 词法召回。
2. 本地 384 维字符 n-gram 哈希向量召回。
3. Reciprocal Rank Fusion。
4. 可替换 `Reranker` port 的确定性 cross-feature rerank。
5. 证据阈值与 citation assembly。

当前默认 embedding 是确定性的本地 fallback，不是 `bge-small-zh-v1.5` 真实模型；接口已通过 `EmbeddingProvider` 隔离，后续可替换 ONNX adapter。词法或向量任一路失败时，响应会返回 `degraded_modes`，不会把降级结果伪装成全链路成功。

Citation 包含 chunk ID、document ID、标题、版本、section/heading path、字符范围、摘录和分数。检索与发布 E2E 使用真实 Spring context、JWT 和 MySQL 8.4。

`POST /chat` 已切换到同一版本化 RAG 链。聊天响应新增：

- `citations`：与回答上下文对应的结构化引用。
- `retrieval_degraded_modes`：`lexical_unavailable`、`vector_unavailable` 或 `retrieval_failed`。

引用 ID 会注入 Agent context，前端在回答下方展示引用标题、版本、章节和摘录。单路检索降级时回答继续生成但明确标记；检索服务整体异常时使用 `retrieval_failed`，不会把空证据伪装成正常检索。

## Frontend

```powershell
cd E:\EchoMind\echomind-saas-agent\EchoMindFrontend
npm test
npm run build
```

## 当前分支

- Branch: `codex/echomind-saas-mvp`
- Foundation verified head: `4bccefd`
- No remote push was performed.
