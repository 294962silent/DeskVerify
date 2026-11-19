# DeskVerify Frontend

DeskVerify 的 Vue 3 + Vite 管理端。

## 功能

- 客服 Agent 对话、Citation 引用链和检索降级状态
- 知识库检索、导入、版本发布和迁移
- Commerce 订单、订阅和发票查询
- 工单创建、认领、解决、关闭和重开
- Golden RAG、转人工策略和评测报告
- Java JWT 会话鉴权

## Release Runtime

`v0.1.0` 推荐在仓库或发布包根目录用 PowerShell 7 启动：

```powershell
.\start.ps1
```

浏览器访问：

```text
http://localhost:5173
```

前端 Nginx 将 `/api/java/` 反向代理到 Compose 内部的 Java 后端。启动、端口和停止说明见
[Windows 发布指南](../docs/RELEASE_WINDOWS_V0_1_0.md)。

## Local Development

开发前端时需要 Node.js 22。先启动 Java 后端，再从 `EchoMindFrontend` 目录运行：

```powershell
npm ci
npm run dev
```

Vite 默认将 `/api/java` 代理到：

```text
http://localhost:8080
```

如果 Docker 前端已使用 `5173`，可以用 `npm run dev -- --port 5174` 启动 Vite。

需要覆盖后端地址时：

```powershell
$env:VITE_JAVA_API_URL = "http://localhost:8080"
npm run dev
```

## Authentication

页面没有账号密码登录。请将根目录 `.deskverify/demo-token.txt` 中的本地演示 JWT，
或由当前后端信任的身份系统签发的 JWT，粘贴到“Java 访问令牌”输入框。
演示身份是 Acme 客户；评测与知识库管理需要后端授权的租户管理员身份。

## Tests

```powershell
npm test -- --run
npm run build
```
