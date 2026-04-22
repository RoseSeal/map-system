## Step 2 实现方案：System Prompt 集中管理

### Summary

目标是在不改变现有聊天/解释调用链、异步行为、超时处理和 provider 适配逻辑的前提下，把散落在 `LlmChatService` 和 `LlmExplanationService` 中的 system prompt 硬编码抽离到资源文件，并通过统一服务加载与分发。

本步范围按已选默认收口为：
- 只抽离 `system prompt`
- `LlmExplanationService` 的态势文本格式化仍保留在 service 内
- 模板资源缺失或为空时，应用启动失败，不做运行时兜底

### Key Changes

#### 1. 新增 PromptTemplateService
放置在 `com.whut.map.map_service.llm.prompt` 包下，职责单一：
- 启动时从 `classpath:prompts/` 加载固定模板（源码位置为 `src/main/resources/prompts/`）
- 以内存缓存形式保存模板内容
- 暴露 `getSystemPrompt(scene)` 方法，返回对应场景的 system prompt 文本
- 对缺失文件、空文件、空白内容直接抛出启动期异常，阻止应用启动

建议接口形态：
- `PromptScene` 枚举：`CHAT`、`RISK_EXPLANATION`
- `String getSystemPrompt(PromptScene scene)`

实现约束：
- 不做变量替换
- 不引入动态刷新
- 不引入配置驱动路径解析，路径固定为 classpath 下 `prompts/`

#### 2. 新增 prompt 资源文件
在 `classpath:prompts/` 下新增模板资源（源码位置为 `backend/map-service/src/main/resources/prompts/`）：
- `system-chat.txt`
- `system-risk-explanation.txt`

内容原则：
- 与当前行为保持一致，只做语言和职责表述的整理，不引入 Step 3/4 的风险上下文或历史对话要求
- `system-chat.txt` 保持“直接、简洁、2-3 句回答当前用户消息”的语义，不额外引入上下文注入要求
- `system-risk-explanation.txt` 保持“基于态势信息，用简洁中文描述风险并给建议”的语义

#### 3. 重构 LlmChatService
修改 [LlmChatService.java](../../../backend/map-service/src/main/java/com/whut/map/map_service/llm/service/LlmChatService.java)：
- 注入 `PromptTemplateService`
- `buildMessages()` 不再内联 system 文本，改为：
  - `SYSTEM`: `promptTemplateService.getSystemPrompt(PromptScene.CHAT)`
  - `USER`: `request.getContent()`
- 其余逻辑保持不变：
  - `ChatPayloadValidator`
  - `CompletableFuture` 异步执行
  - timeout / error 映射
  - provider 名称解析

#### 4. 重构 LlmExplanationService
修改 [LlmExplanationService.java](../../../backend/map-service/src/main/java/com/whut/map/map_service/llm/service/LlmExplanationService.java)：
- 注入 `PromptTemplateService`
- `buildMessages()` 中的 system message 改为：
  - `SYSTEM`: `promptTemplateService.getSystemPrompt(PromptScene.RISK_EXPLANATION)`
- 当前多行 `USER` 态势文本继续保留在 service 内，通过 `.formatted(...)` 组装
- 其余解释生成流程保持不变

#### 5. 边界与兼容性
- 不修改 `LlmClient`、`GeminiLlmClient`、`ZhipuLlmClient`
- 不修改 WebSocket/SSE 协议
- 不引入新的配置项
- 不依赖 `java.io.File` 或源码目录磁盘路径读取资源，统一按 classpath 资源处理
- 不在本步处理中清理 `LlmProperties.fallbackTemplateEnabled`；如果它当前未被使用，保持原样，避免越界重构

### Test Plan

需要新增或调整以下测试：

- `PromptTemplateServiceTest`
  - 能正确加载 `CHAT` 和 `RISK_EXPLANATION` 模板
  - 模板文件为空白时抛出异常
  - 模板文件缺失时抛出异常

- `LlmChatServiceTest`
  - 验证发送给 `llmClient.chat(...)` 的消息列表中包含：
    - 第一条为 `SYSTEM`
    - 内容来自模板服务
    - 第二条为用户原始问题
  - 现有成功/校验失败/LLM 失败行为保持通过

- `LlmExplanationServiceTest`
  - 新增测试覆盖 `buildMessages()` 结果或对 `llmClient.chat(...)` 的入参断言
  - 验证 `SYSTEM` 内容来自模板服务
  - 验证 `USER` 态势文本仍包含本船、目标船、风险等级、DCPA/TCPA 等关键字段

- 回归验证
  - `GeminiLlmClientTest`、`ZhipuLlmClientTest` 无需行为变更，应继续通过
  - `VoiceChatServiceTest` 只需在构造 `LlmChatService` 的测试夹具变化时做最小适配

### Assumptions

- Step 2 仅做 system prompt 管理，不提前实现 Step 3 的风险上下文注入，也不实现 Step 4 的对话记忆
- 模板资源是应用必需资源，缺失即视为部署错误，采用 fail-fast
- explanation 的用户模板骨架暂不抽离，避免本步同时引入模板变量替换或额外抽象
- 包结构遵循现有增强文档方向：模板管理归 `llm` 领域，不新建额外业务层
