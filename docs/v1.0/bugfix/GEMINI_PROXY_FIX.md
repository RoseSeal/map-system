# Gemini Proxy Fix Record

> 文档状态：active
> 最后更新：2026-04-22

## 1. 背景

前端通过 WebSocket 触发聊天请求后，后端持续返回：

`LLM chat request timed out after 15000 ms.`

Gemini 控制台未出现对应请求记录。问题说明请求未稳定到达 Gemini API，而不是模型响应过慢。

## 2. 根因

项目当前通过 `google-genai` Java SDK 接入 Gemini。`GeminiConfig` 仅创建默认 `Client`：

```java
new Client.Builder()
        .apiKey(...)
        .build();
```

`google-genai 1.0.0` 内部使用 Apache HttpClient 创建默认 HTTP 客户端，但未暴露代理配置入口，也未启用 `useSystemProperties()`。因此：

1. SDK 不会自动读取 WSL 注入的 `http_proxy` / `https_proxy`
2. SDK 默认尝试直连 `https://generativelanguage.googleapis.com`
3. 在依赖 Windows 代理/VPN 才能访问外网的网络环境中，Gemini 请求会在 Java 进程内超时

## 3. 采取的修复

本次修复保持现有业务链路不变，仅改造 Gemini 客户端装配层：

1. 在 `llm.gemini` 下新增代理配置
2. 启动时校验代理参数完整性
3. 先创建原始 `Client`
4. 通过反射获取 SDK 内部 `apiClient.httpClient`
5. 用显式配置代理的 Apache `CloseableHttpClient` 替换默认直连客户端

该方案只影响 Gemini Bean，不影响：

- Chat 业务流程
- WebSocket 协议
- Zhipu 配置
- 其他服务的网络行为

## 4. 新增配置

```properties
llm.gemini.proxy.enabled=true
llm.gemini.proxy.host=127.0.0.1
llm.gemini.proxy.port=7897
llm.gemini.proxy.scheme=http
```

说明：

- `enabled`：是否启用 Gemini 代理
- `host`：代理监听地址
- `port`：代理监听端口
- `scheme`：代理协议。当前环境建议使用 `http` 配合混合端口 `7897`

## 5. 风险与限制

### 5.1 SDK 内部结构依赖

反射替换依赖 `google-genai` 当前版本的内部字段名：

- `Client.apiClient`
- `ApiClient.httpClient`

若后续升级 SDK 并调整内部结构，该修复可能失效，需要重新验证。

### 5.2 失败策略

若反射替换失败，系统在启动阶段直接抛出异常，不允许静默退回默认直连模式。该策略用于避免“表面启动成功但实际请求全部超时”的隐蔽故障。

## 6. 503 高负载补充

在代理问题修复后，Gemini 请求已能到达服务端，但仍可能遇到平台高负载返回：

`503 Service Unavailable`

该错误属于 Gemini 侧临时容量不足，而不是本地代理故障。为降低瞬时高负载对聊天链路的影响，聊天路径增加了以下补充处理：

1. 仅对 Gemini 的 `503 UNAVAILABLE` 启用指数退避重试
2. 最大重试次数限制为 2 次
3. 初始退避时间为 1000 ms，后续指数递增
4. 最终仍失败时，前端收到“服务端临时高负载，请稍后重试”的明确提示

对应配置：

```properties
llm.gemini.retry.max-retries=2
llm.gemini.retry.initial-backoff-ms=1000
```

## 7. 验证方式

修复后应按以下顺序验证：

1. 启动日志出现 `Gemini client initialized with proxy ...`
2. 前端发送一次聊天请求
3. 后端不再输出 `LLM chat request timed out after 15000 ms`
4. Gemini 控制台出现对应请求记录
5. 若遇到短时高负载，日志中出现 503 重试记录
6. 稳定情况下前端收到 `CHAT_REPLY`
