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
