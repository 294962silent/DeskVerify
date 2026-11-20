# DeskVerify SaaS Platform

DeskVerify（原 EchoMind）是一个面向企业 SaaS 客服场景的 RAG Agent 平台。项目采用 Java/Spring Boot 后端与 Vue 3 前端，将多智能体路由、版本化知识检索、会话记忆、业务工具、回答校验和评测门禁整合为可追溯、可回归的客服后端。

> 为兼容现有数据库、类和构建配置，源码目录及 `com.echomind` Java 包名暂时保留；对外的项目名称统一使用 `DeskVerify`。

> 当前仓库是 Java 后端与 Vue 前端的单栈应用；`v0.1.0` 起提供 Windows Docker 一键启动。
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
- **可部署**：根目录 Docker Compose 启动 MySQL、Redis、Java 后端和 Nginx/Vue 前端。

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
│   └── README.md                  # Java 后端详细说明
├── EchoMindFrontend/              # Vue 3 + Vite 前端
│   ├── src/                       # 页面、组件和后端适配
│   ├── docker/                    # Nginx 与运行时配置
│   └── README.md                  # 前端详细说明
├── compose.yml                   # 四服务容器编排
├── start.ps1 / stop.ps1           # Windows 启停脚本
├── .env.example                  # 根目录本地配置参考
├── scripts/                      # 启动辅助与发布布局验证
├── docs/                         # 发布与开发指南
└── README.md
```

## Windows v0.1.2 一键启动

要求：

- Windows 10/11
- PowerShell 7
- 正在运行的 Docker Desktop（Linux 容器）
- 首次启动需要网络访问，以拉取镜像、依赖和 BGE 模型

在仓库根目录执行：

```powershell
.\start.ps1
```

脚本会自动：

1. 在根目录 `.env` 缺少配置时生成本地 MySQL、Redis 和 JWT 密钥；
2. 下载并校验约 95 MB 的 BGE ONNX 模型；
3. 生成有效期一天的 Acme 演示 JWT；
4. 启动 MySQL、Redis、Java 后端和 Vue 前端；
5. 等待后端 Actuator 和前端 HTTP 就绪后打开浏览器。

访问地址：

```text
前端：http://localhost:5173
Swagger：http://localhost:5173/api/java/docs 或 http://localhost:8080/docs
健康检查：http://localhost:8080/actuator/health
```

演示 JWT 位于 `.deskverify/demo-token.txt`，需要粘贴到页面中的“Java 访问令牌”输入框。
该身份是 Acme 客户，可读取演示 Commerce 数据，不能执行管理员评测或知识库管理。

停止服务：

```powershell
.\stop.ps1
```

默认保留数据库和运行时数据。只有明确执行 `.\stop.ps1 -Volumes` 才会删除持久化卷。

使用真实客服模型时，在安装目录已忽略的本地 `.env` 中配置原生提供商密钥。
不要把密钥放进命令行、浏览器或 Git。DeepSeek 用以下配置：

```dotenv
SPRING_PROFILES_ACTIVE=deepseek,demo
DEEPSEEK_MODEL=deepseek-flash
```

在同一个 `.env` 内填写 `DEEPSEEK_API_KEY`，再运行 `.\start.ps1`。
使用 Anthropic 时，改用 `SPRING_PROFILES_ACTIVE=anthropic,demo` 和 `ANTHROPIC_API_KEY`。
OpenCode Go 密钥不是原生 DeepSeek 密钥，不能用于客服对话。

不提供 LLM API Key 时，登录后的订单与履约查询仍读取有权限的真实记录，
明确说“转人工”可以提交工单。其他问题仍可能使用本地降级回复。
模型密钥和本地 JWT 不应分享或提交。

在 Swagger 中点击 **Authorize** 并填写 DeskVerify JWT（不带 `Bearer` 前缀），
即可在现有权限内调试接口。Swagger 的脚本和样式随应用本地提供。

详细说明见 [Windows v0.1.2 Release](./docs/RELEASE_WINDOWS_V0_1_2.md) 和
[本地开发与验证](./docs/LOCAL_DEVELOPMENT.md)。
如遇 `RemoteSigned` 阻止脚本，请按[下载阻止说明](./docs/RELEASE_WINDOWS_V0_1_2.md#windows-download-blocking)处理，
不要更改全局执行策略。

## 核心接口

| 方法 | 接口 | 说明 |
| --- | --- | --- |
| GET | `/actuator/health` | 无需 JWT 的启动健康检查 |
| POST | `/chat` | 多 Agent 智能对话 |
| POST | `/search` | 知识库检索 |
| POST | `/knowledge/add` | 添加知识库文档 |
| POST | `/knowledge/upload` | 上传知识库文件 |
| GET | `/monitor` | 查看监控摘要 |
| POST | `/eval/jobs` | 提交异步评测任务 |
| GET | `/eval/jobs/{jobId}` | 查询评测任务 |
| POST | `/eval/golden-rag/jobs` | 提交版本化 Golden RAG/转人工评测（租户管理员） |
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

评测模块用于验证意图识别和端到端回答质量。评测接口需要通过后端授权的
`TENANT_ADMIN` 会话；启动脚本生成的客户 JWT 不具备此权限。

异步评测推荐使用 `/eval/jobs`，再通过任务 ID 查询进度和最终报告。版本化 RAG
评测使用 `/eval/golden-rag/jobs`：

```json
{
  "dataset_version": "rag-support-golden-v1"
}
```

该数据集包含 30 条检索案例和 20 条转人工案例，采用 DEV/HOLDOUT 切分，固定
文档、版本、Chunk ID、checksum 和期望引用。当前为合成方法验证数据，不声称真实
用户泛化能力，V1 外部 LLM token 消耗为 0。运行与验证方式见
[本地开发与验证](./docs/LOCAL_DEVELOPMENT.md#golden-rag-and-handoff)。

该评测现在包含 release gate：ACL violation 必须为 0，DEV/HOLDOUT 的
Recall@5、MRR@5 和引用命中率相对版本化 baseline 最多下降 2 个百分点，
转人工 Recall 不低于 0.95，误转人工率和漏转人工率不高于 0.10。P95 在 V1
作为 2000 ms 告警项，不阻塞小规模基准。门禁失败的任务状态为 `failed`，但
JSON/Markdown 证据仍会保存并可下载。

GitHub Actions 会在真实 MySQL 8.4 和 BGE ONNX 上执行完整后端测试，校验
Golden E2E 没有被 skip，然后上传 Surefire、Golden 报告和前端构建 artifact。

## 配置与安全

- 不要把真实 API Key、Redis 密码或本地 `.env` 文件提交到 Git。
- `start.ps1` 管理根目录的本地 `.env`；根目录 `.env.example` 仅供参考。
- Foundation 已提供 JWT 认证、租户隔离、角色授权和稳定错误契约；后续业务模块必须继续使用 `ActorScope`，不得从请求体读取授权身份。
- 不要把演示端口暴露给不受信任的网络。正式环境需要单独配置 TLS、身份签发、
  数据库与 Redis 凭据，以及外部工具审计。
- 当前项目定位为可运行的 SaaS 客服技术项目原型；Commerce、工单和版本化 RAG
  已接入本地 MySQL 与真实 ONNX embedding，生产接入仍需补组织级 SLA、容量验证、
  旧模型文档迁移和外部身份/支付系统联调。

## 相关文档

- [Java 后端 README](./EchoMindJava/README.md)
- [Vue 前端 README](./EchoMindFrontend/README.md)

## License

This project is licensed under the [MIT License](./LICENSE).

