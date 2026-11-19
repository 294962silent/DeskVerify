# DeskVerify SaaS Platform

DeskVerify（原 EchoMind）是一个面向企业 SaaS 客服场景的 RAG Agent 平台。项目采用 Java/Spring Boot 后端与 Vue 3 前端，将多智能体路由、版本化知识检索、会话记忆、业务工具、回答校验和评测门禁整合为可追溯、可回归的客服后端。

> 为兼容现有数据库、类和构建配置，源码目录及 `com.echomind` Java 包名暂时保留；对外的项目名称统一使用 `DeskVerify`。

> 当前仓库包含 Java 后端和 Vue 前端；Python 版本不在本仓库中。前端仍保留对 Python/Java 两种后端响应格式的兼容适配。
>
> **当前阶段：** SaaS 客服主链路已完成。JWT 认证、真实租户隔离、MySQL/Flyway、
> Commerce 订单/订阅读取、工单状态机、版本化 BGE Hybrid RAG、结构化 citation
> chain、幂等、Transactional Outbox、评测与 Trace 隔离均已接入；异步 DAG
> 编排仍按后续计划实施。

## 项目特点

- **多 Agent 协作**：根据用户意图在通用、技术支持、账单/账户等 Agent 之间路由，并支持人工升级。
- **RAG 知识库问答**：MySQL ngram FULLTEXT + `bge-small-zh-v1.5` ONNX
  混合检索、RRF、重排、结构化引用和显式降级。
- **多轮会话记忆**：使用 Redis 保存工作记忆；遗留 JSON 存储仍待迁移，新租户数据已使用 MySQL/Flyway。
- **回答质量校验**：对回答进行可信度、知识库依据和人工转接判断。
- **可重复评测**：保留意图识别、Macro-F1、LLM Judge、baseline 对比，并新增
  `rag-support-golden-v1` 小型方法验证数据集，覆盖 Recall@K、引用命中率、ACL、
  P95 延迟和策略级转人工指标。
- **可观测性**：提供 Actuator、Micrometer、Prometheus 指标、调用链路记录和监控摘要。
- **身份与隔离**：JWT Resource Server、租户成员校验、角色授权、Trace/评测所有权隔离。
- **可靠平台基座**：MySQL 幂等记录、租户级 Inbox、事务 Outbox、claim/lease fencing。
- **前后端分离**：Vue 前端支持聊天引用链、知识库管理、Commerce、工单、监控和评测页面。
- **可部署**：提供 Docker Compose、Redis、ChromaDB、Prometheus 和 Nginx 配置。

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 后端 | Java 21、Spring Boot、Spring Security、Spring AI、LangChain4j |
| 模型 | Anthropic、DeepSeek、`bge-small-zh-v1.5` FP32 ONNX |
| 持久化与检索 | MySQL、Flyway、Redis、版本化 Hybrid RAG |
| 评测与监控 | Macro-F1、LLM Judge、Spring Boot Actuator、Micrometer、Prometheus |
| 前端 | Vue 3、Vite、JavaScript |
| 部署 | Docker、Docker Compose、Nginx |

## 项目结构

```text
DeskVerify 企业 SaaS 智能支持平台/
├── EchoMindJava/                 # Java/Spring Boot 后端
│   ├── src/main/java/             # 对话、Agent、RAG、记忆、评测和监控
│   ├── skills/                    # Agent Skill 与领域提示词
│   ├── config/                    # Prometheus、Nginx 等配置
│   ├── .env.example               # 本地配置模板
│   └── README.md                  # Java 后端详细说明
├── EchoMindFrontend/              # Vue 3 + Vite 前端
│   ├── src/                       # 页面、组件和后端适配
│   ├── docker/                    # Nginx 与运行时配置
│   └── README.md                  # 前端详细说明
├── .gitignore
└── README.md
```

## 快速开始

当前机器的 Docker、JDK 21 测试环境和完整验证命令见：

- [本地开发与验证](./docs/LOCAL_DEVELOPMENT.md)
- [BGE ONNX 迁移实验与问题记录](./docs/experiments/BGE_ONNX_MIGRATION.md)

### 1. 启动 Java 后端

环境要求：

- JDK 21 或更高版本
- Docker Desktop（用于启动 Redis 等依赖）
- PowerShell 7
- Anthropic 或 DeepSeek 的 API Key；也可以开启本地降级回复

进入后端目录：

```powershell
cd EchoMindJava
Copy-Item .env.example .env
.\scripts\download-bge-small-zh-v1.5.ps1
docker compose up -d redis chromadb
```

模型约 95 MB，不提交到 Git。下载脚本会校验 SHA-256；模型缺失时服务仍可启动，
但 RAG 会进入 lexical-only 降级并返回 `embedding_unavailable` /
`vector_unavailable`。

选择一个模型启动。以 DeepSeek 为例：

```powershell
$env:SPRING_PROFILES_ACTIVE="deepseek"
$env:DEEPSEEK_API_KEY="your_key"
.\mvnw.cmd spring-boot:run
```

后端地址：

```text
http://localhost:8080
```

健康检查和接口文档：

```text
http://localhost:8080/health
http://localhost:8080/docs
```

### 2. 启动前端

另开终端进入前端目录：

```powershell
cd EchoMindFrontend
npm install
npm run dev
```

访问：

```text
http://localhost:5173
```

前端默认将 Java 后端请求转发到 `http://localhost:8080`。如需修改地址，可配置 `VITE_JAVA_API_URL`。

### 3. Docker 部署

后端和前端目录分别提供 Docker 配置，具体端口和服务说明见：

- [Java 后端说明](./EchoMindJava/README.md)
- [前端说明](./EchoMindFrontend/README.md)

## 核心接口

| 方法 | 接口 | 说明 |
| --- | --- | --- |
| GET | `/health` | 健康检查 |
| POST | `/chat` | 多 Agent 智能对话 |
| POST | `/search` | 知识库检索 |
| POST | `/knowledge/add` | 添加知识库文档 |
| POST | `/knowledge/upload` | 上传知识库文件 |
| GET | `/monitor` | 查看监控摘要 |
| POST | `/eval/jobs` | 提交异步评测任务 |
| GET | `/eval/jobs/{jobId}` | 查询评测任务 |
| POST | `/eval/golden-rag/jobs` | 提交版本化 Golden RAG/转人工评测 |
| GET | `/eval/jobs/{jobId}/report.md` | 下载 Golden 评测 Markdown 报告 |
| POST | `/eval/run` | 运行兼容评测接口 |

## 企业 SaaS 场景扩展方向

当前实现以通用客服、技术支持、账单/账户支持和人工升级为基础。面向企业 SaaS 产品支持，后续可以沿着以下边界扩展：

1. 在 `IntentCategory` 中增加产品功能咨询、API 对接、订阅管理、权限申请和工单流转等意图。
2. 新增对应 Agent 和 `skills/` 领域 Skill，控制角色 Prompt、工具范围和交接条件。
3. 按产品、版本和租户导入知识库，隔离不同企业的文档与检索范围。
4. 注册路由、领域打分和评测样例，使用评测结果验证扩展前后的准确率和回答质量。
5. 接入 SaaS 产品 API、工单系统和账户/订阅服务时，增加权限校验、租户隔离和高风险操作人工确认。

## 评测

评测模块保留在 Java 后端中，用于验证意图识别和端到端回答质量：

```powershell
curl.exe -X POST http://localhost:8080/eval/run `
  -H "Content-Type: application/json" `
  -d '{}'
```

异步评测推荐使用 `/eval/jobs`，再通过任务 ID 查询进度和最终报告。版本化 RAG
评测使用 `/eval/golden-rag/jobs`：

```json
{
  "dataset_version": "rag-support-golden-v1"
}
```

该数据集包含 30 条检索案例和 20 条转人工案例，采用 DEV/HOLDOUT 切分，固定
文档、版本、Chunk ID、checksum 和期望引用。当前为合成方法验证数据，不声称真实
用户泛化能力，V1 外部 LLM token 消耗为 0。实验记录见
[Golden RAG And Handoff V1](./docs/experiments/GOLDEN_RAG_HANDOFF_V1.md)。

该评测现在包含 release gate：ACL violation 必须为 0，DEV/HOLDOUT 的
Recall@5、MRR@5 和引用命中率相对版本化 baseline 最多下降 2 个百分点，
转人工 Recall 不低于 0.95，误转人工率和漏转人工率不高于 0.10。P95 在 V1
作为 2000 ms 告警项，不阻塞小规模基准。门禁失败的任务状态为 `failed`，但
JSON/Markdown 证据仍会保存并可下载。

GitHub Actions 会在真实 MySQL 8.4 和 BGE ONNX 上执行完整后端测试，校验
Golden E2E 没有被 skip，然后上传 Surefire、Golden 报告和前端构建 artifact。

## 配置与安全

- 不要把真实 API Key、Redis 密码或本地 `.env` 文件提交到 Git。
- 使用 `EchoMindJava/.env.example` 复制生成本地 `.env`。
- Foundation 已提供 JWT 认证、租户隔离、角色授权和稳定错误契约；后续业务模块必须继续使用 `ActorScope`，不得从请求体读取授权身份。
- 正式环境需要配置可信 JWT 签发方、数据库账号、Redis、知识库向量服务和外部工具审计。
- 当前项目定位为可运行的 SaaS 客服技术项目原型；Commerce、工单和版本化 RAG
  已接入本地 MySQL 与真实 ONNX embedding，生产接入仍需补组织级 SLA、容量验证、
  旧模型文档迁移和外部身份/支付系统联调。

## 相关文档

- [Java 后端 README](./EchoMindJava/README.md)
- [Vue 前端 README](./EchoMindFrontend/README.md)

## License

This project is licensed under the [MIT License](./LICENSE).

