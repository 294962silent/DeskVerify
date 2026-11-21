# DeskVerify Java 后端

DeskVerify 的 Java/Spring 后端负责客服对话、租户与角色授权、知识库、Commerce、
工单、评测和监控。`EchoMindJava` 目录与 `com.echomind` 包名为内部兼容保留。

## Windows 启动

从**仓库根目录**运行 `.\start.ps1`。脚本会验证 BGE 模型文件、准备本地演示配置，
并通过根目录 Compose 启动 MySQL、Redis、Java 后端和 Vue/Nginx 前端。
不需要在 `EchoMindJava` 下单独运行 Compose。

| 入口 | 默认地址 | 说明 |
| --- | --- | --- |
| Java 健康检查 | `http://localhost:8080/actuator/health` | 无需 JWT；就绪时 `status` 为 `UP` |
| Swagger UI | `http://localhost:8080/docs` | 加载 CDN 静态资源；受保护接口需授权 |
| OpenAPI JSON | `http://localhost:8080/v3/api-docs` | 接口与请求字段定义 |
| Vue 前端 | `http://localhost:5173` | 业务请求通过 `/api/java` 同源代理 |

默认的 MySQL 和 Redis 端口只在 Compose 网络内使用。根目录的 `.\stop.ps1` 停止
服务但保留三个持久卷。配置、JWT 生成和数据清理详见
[Windows 发布指南](../docs/RELEASE_WINDOWS_V0_1_3.md)。

## 技术栈与数据

| 能力 | 实现 |
| --- | --- |
| 应用和构建 | Java 21、Spring Boot 3.5、Spring AI 1.1、Maven Wrapper |
| 对话 | Anthropic / DeepSeek，可选本地降级；General、Technical、Billing Agent |
| 检索 | MySQL ngram FULLTEXT、BGE FP32 ONNX、RRF、rerank 和结构化引用 |
| 业务持久化 | MySQL 租户/授权、Commerce、工单、知识文档及版本 |
| 记忆 | Redis 工作记忆；`/app/data/java` 中的本地 JSON 保存情景记忆和画像 |
| 可观测性 | Spring Boot Actuator、Micrometer、Prometheus 指标、Springdoc OpenAPI |

默认 `anthropic,demo` profile 启用合成 Commerce 种子。可在根目录 `.env` 切换到
`deepseek,demo`，并配置对应服务的真实 API key。没有真实 key 且启用
`LLM_FALLBACK_ENABLED=true` 时，LLM 路径可返回本地降级结果。BGE embedding
在后端内运行，与外部聊天模型和 API key 无关。

## 对话与版本化知识库

```text
POST /chat
  -> 读取工作记忆、会话摘要和用户画像
  -> 租户内词法/向量召回、融合、重排与结构化引用
  -> 路由到 General / Technical / Billing Agent
  -> 校验答案并决定是否转人工
  -> 更新记忆
```

管理员用 `POST /api/v1/knowledge-documents` 建文档，再向
`/api/v1/knowledge-documents/{documentId}/versions` 提交版本并通过同一文档的
`/publish` 发布。发布原子切换当前活动版本；只对当前 BGE 模型版本执行向量召回。
旧 embedding 版本仍可参与词法召回，并明确返回 `embedding_model_mismatch`。
缺失模型或推理失败时仅保留可用召回路径，并报告 `degraded_modes`。

管理员可用 `POST /api/v1/knowledge/embedding-migrations` 对旧模型活动版本执行
可重复迁移，支持 `dry_run`、`limit` 和 `after_document_id`。模型不可用返回
`503 EMBEDDING_UNAVAILABLE`。迁移与并发发布的边界见
[本地开发与验证](../docs/LOCAL_DEVELOPMENT.md#versioned-rag)。

## 主要接口

除 Actuator `/actuator/health` 和 API 文档入口外，下列业务接口要求有效 Bearer JWT，
且后端仍会校验租户成员关系、客户范围和角色。默认 Acme 演示会话是普通客户身份；
它不能执行知识发布或管理员评测。`/health` 是要求认证的兼容接口。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/chat` | 对话，响应包含 `citations` 和 `retrieval_degraded_modes` |
| POST | `/api/v1/knowledge/search` | 租户与 ACL 范围内的版本化知识检索 |
| GET | `/api/v1/commerce/customers/{customerId}` | 客户、订阅、订单和发票 |
| POST/GET | `/api/v1/tickets` | 创建/查询工单；创建需 `Idempotency-Key` |
| GET | `/monitor` | 监控摘要 |
| GET | `/actuator/prometheus` | Prometheus 指标 |
| POST | `/eval/golden-rag/jobs` | `TENANT_ADMIN` 提交异步 Golden 评测 |
| GET | `/eval/jobs/{jobId}` | 评测状态及报告摘要 |
| GET | `/eval/jobs/{jobId}/report.md` | 下载 Markdown 报告 |

## Golden RAG 与开发验证

`rag-support-golden-v1` 是合成回归数据集，包含 12 篇文档、30 条检索案例和
20 条转人工策略案例。它使用真实 BGE ONNX 和 MySQL 检索，不调用外部 LLM。
这不是生产效果或数据集代表性的证明。Release gate 阻止 ACL 泄漏、检索降级、
超过容差的指标回退和不满足阈值的转人工结果；P95 延迟是 advisory 指标。
参见 [本地开发与验证](../docs/LOCAL_DEVELOPMENT.md#golden-rag-and-handoff)。

仅开发或完整测试需要本机 JDK 21。安装 JDK 之后，在 `EchoMindJava` 下执行：

```powershell
.\scripts\download-bge-small-zh-v1.5.ps1
.\mvnw.cmd test
```

完整套件需要 Docker Desktop 的 Linux 容器用于 Testcontainers。Docker 发布启动会
自行准备模型，模型文件、Wrapper 下载产物和 `target/` 构建目录不随 Windows ZIP 分发。
