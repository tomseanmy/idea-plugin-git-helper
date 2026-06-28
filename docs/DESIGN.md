# git-helper 设计文档

> 版本：v0.1（初始设计，欢迎讨论修订）
> 最后更新：2026-06-27

## 1. 背景与目标

### 1.1 背景
通义灵码（Lingma）收费并更名为 Qoder。它功能很多（代码补全、Chat、Agent 等），但对于**只用到 Git 操作和 AI 生成提交说明**的用户而言：
- 为低频使用的全套功能付费不划算；
- 被绑定到单一供应商，无法用自己更便宜/更快的模型；
- 数据要经过第三方。

### 1.2 目标
打造一款**轻量、专注、可自由配置供应商**的 JetBrains 插件 `git-helper`：

| 维度 | 目标 |
|------|------|
| 核心功能 | AI 生成 Commit Message + 少量 Git 辅助操作 |
| 协议兼容 | OpenAI Chat Completions、Anthropic Messages |
| 可配置性 | URL / Key / 模型 / 采样参数 / Prompt / 上下文，多 Profile |
| 隐私 | API Key 仅本地存储；请求 IDE 直连供应商 |
| 体积与依赖 | 轻量，尽量复用 IDE 自带 Git4Idea，不引入重型 HTTP 框架 |
| 平台 | 基于 IntelliJ Platform（兼容 2022.3+ 范围，后续定） |

### 1.3 非目标（明确不做）
- ❌ 代码补全 / Copilot 式行内建议
- ❌ 通用 Chat / Agent / 多文件改写
- ❌ 内置任何固定供应商账号或代理转发服务
- ❌ 代码索引、向量检索

## 2. 用户场景

### 场景 A：生成提交说明（主场景）
1. 用户在 IDE 里 `git add` 暂存若干改动；
2. 点击工具栏 / Commit 面板上的「✨ Generate Commit Message」按钮；
3. 插件读取 `git diff --cached`，按配置拼装 Prompt 调用 AI；
4. 在 Commit Message 输入框回填结果，用户可编辑后提交。

### 场景 B：切换供应商
- 用户在配置里维护多个 Profile（如「DeepSeek 便宜款」「Claude 高质量」「本地 Ollama」），一键切换。

### 场景 C：辅助 Git 操作（轻量）
- 一键 `git add -A` / 查看 staged 文件清单 / 复制 diff 到剪贴板（供粘贴到外部 Chat）。

## 3. 总体架构

```
┌──────────────────────────────────────────────────────────┐
│                      IDEA Plugin                          │
│                                                            │
│  ┌──────────────┐   ┌──────────────┐   ┌──────────────┐   │
│  │   UI Layer   │   │  Action/     │   │  Settings    │   │
│  │ (Tool Window,│   │  Service     │   │  (Persistent │   │
│  │  Editor, Pop)│   │  (Git + AI)  │   │   State)     │   │
│  └──────┬───────┘   └──────┬───────┘   └──────┬───────┘   │
│         │                  │                   │           │
│         └──────────┬───────┴───────────┬───────┘           │
│                    ▼                   ▼                    │
│         ┌─────────────────┐   ┌─────────────────┐          │
│         │  GitProvider    │   │  AI Provider    │          │
│         │ (基于 git4idea) │   │  Abstraction    │          │
│         └────────┬────────┘   └────────┬────────┘          │
│                  │                     │                    │
└──────────────────┼─────────────────────┼────────────────────┘
                   │                     │
            ┌──────▼──────┐        ┌──────▼──────┐
            │  本地 Git    │        │ 外部 AI API │
            │ (git CLI)   │        │ (HTTP/HTTPS)│
            └─────────────┘        └─────────────┘
```

### 3.1 模块划分
- **Settings 模块**：`Configurable` + `PersistentStateComponent`，管理多 Profile 与全局参数。
- **AI Provider 抽象**：定义统一接口，OpenAI / Anthropic 两套实现。
- **Git Provider**：优先复用 `git4idea` 提供的 `ChangeListManager` / `Git` API，必要时直接读 `git diff`。
- **Action / Service**：`AnAction` 触发，后台任务走 `Task.Backgroundable`，结果通过 `ProgressManager` 异步回填。
- **UI**：Commit 面板注入按钮（`BackgroundableActionEnableAction` / `CommitWorkflow` 扩展点）、可选 Tool Window。

## 4. AI 协议抽象

### 4.1 统一接口
```kotlin
interface AiProvider {
    val protocol: Protocol            // OPENAI / ANTHROPIC
    fun chat(request: ChatRequest): ChatResponse
}

data class ChatRequest(
    val model: String,
    val systemPrompt: String,
    val messages: List<ChatMessage>,
    val temperature: Double?,
    val maxTokens: Int?,
    val extraHeaders: Map<String, String>,
)

data class ChatMessage(val role: String, val content: String)
data class ChatResponse(val content: String, val usage: TokenUsage?, val rawError: String?)
```

### 4.2 协议适配
| 协议 | Endpoint 默认 | 鉴权头 | Body 关键差异 |
|------|--------------|--------|---------------|
| OpenAI | `{baseUrl}/chat/completions` | `Authorization: Bearer {key}` | `messages` 数组含 system role；`max_tokens` |
| Anthropic | `{baseUrl}/v1/messages` | `x-api-key: {key}` + `anthropic-version: 2023-06-01` | system 走顶层 `system` 字段；`max_tokens` 必填 |

> URL 全部可自定义（含路径前缀），适配网关 / 代理 / 自部署。

## 5. 配置模型（Settings）

### 5.1 数据结构
```kotlin
data class ProviderProfile(
    var name: String,                // "DeepSeek", "Claude", "Local-Ollama"
    var protocol: String,            // OPENAI / ANTHROPIC
    var baseUrl: String,
    var apiKey: String,              // 本地存储（见 §8 安全）
    var model: String,
    var temperature: Double = 0.2,
    var maxTokens: Int = 1024,
    var systemPrompt: String = "<默认 commit 生成 prompt>",
    var extraHeaders: Map<String, String> = emptyMap(),
)

data class GlobalSettings(
    var profiles: List<ProviderProfile>,
    var activeProfileName: String,
    var commitStyle: String,         // CONVENTIONAL / FREESTYLE / CUSTOM
    var includeUnstagedInContext: Boolean = false,
)
```

### 5.2 配置 UI
- Settings → Tools → Git Helper
- 左侧 Profile 列表（增删改、复制、设为激活），右侧详情表单；
- 表单含「测试连接」按钮：发一条 `ping` 消息验证 URL/Key/Model 是否可用；
- Prompt 编辑区支持变量提示：`{{diff}}` `{{stagedFiles}}` `{{branch}}`。

## 6. 核心功能详细设计

### 6.1 生成 Commit Message
**输入**：当前仓库的 staged diff（可选叠加 unstaged）。
**流程**：
1. 取 `ChangeListManager.getChangeList(...)` 中 staged 的 `Change`；
2. 通过 `git4idea` 或直接 `git diff --cached` 获取 diff 文本；
3. diff 过长时按 token 预算截断/分块（保留关键 hunks，附文件名摘要）；
4. 渲染 system prompt + user prompt（含 diff）；
5. 调用激活 Profile 的 AI 接口；
6. 把返回的 commit message 回填到 `CommitMessage` 编辑器（替换或追加，可配置）。

**Prompt 默认模板（示例）**：
```
You are a commit message generator. Read the git diff and write a concise,
Conventional Commits style message. Output ONLY the message, no explanation.

Branch: {{branch}}
Staged files: {{stagedFiles}}

Diff:
{{diff}}
```

**边界**：
- diff 为空 → 提示「无暂存改动」；
- 超长 → 截断并提示已省略；
- 失败 → toast 显示 `rawError`，不破坏用户已输入内容。

### 6.2 Commit 面板按钮注入
- 通过 `commitMessageProvider` / `CheckinHandler` 或在 `VcsConfiguration` 自定义；
- 备选：Toolbar Action 挂在 Commit 对话框工具栏。
- 图标用 SVG，深浅色两套。

### 6.3 辅助 Git 操作（可选，后续迭代）
- 「Copy staged diff」Action；
- 「Add all & generate」一键流程；
- 不做重复造轮子的 Git 客户端功能。

## 7. 技术选型

| 事项 | 选型 | 说明 |
|------|------|------|
| 构建工具 | Gradle + `gradle-intellij-plugin` | 官方推荐 |
| 语言 | Kotlin | 一等公民，与平台 API 契合 |
| 最低平台 | IntelliJ Platform 2023.2 (232) | 兼顾用户量与 API 稳定性，最终再定 |
| HTTP | `java.net.http.HttpClient`（JDK 11+）| 无需三方依赖，平台已含 |
| JSON | 平台自带 `com.google.gson` 或 IDE 内 JSON | 避免引入新依赖 |
| Git | `git4idea`（已随 IDE 附带） | 优先用，避免 shell |
| UI | Swing + Kotlin DSL | 平台原生 |

## 8. 安全与隐私
- API Key 通过 `PersistentStateComponent` 存入 IDE 配置目录，不打印到日志；
- 调用链路：IDE 进程 → 供应商 baseUrl，**无中间服务器**；
- 提供「Key 掩码显示」开关；
- `extraHeaders` 同样本地存储；
- README 与首次启动提示数据流向。

## 9. 错误处理与可观测
- 网络错误、鉴权 401、模型不存在、超时 → 友好 toast + 可选「查看详情」；
- 调用耗时、token 用量记录在 Tool Window 面板（可关闭）；
- 不收集任何遥测。

## 10. 项目结构（初始）
```
git-helper/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── src/main/
│   ├── kotlin/com/<org>/githelper/
│   │   ├── settings/        # Settings + UI + 持久化
│   │   ├── ai/              # AiProvider 抽象 + OpenAI/Anthropic 实现
│   │   ├── git/             # GitProvider（diff 读取）
│   │   ├── action/          # AnAction 入口
│   │   ├── ui/              # Tool Window、图标
│   │   └── util/
│   └── resources/
│       ├── META-INF/plugin.xml
│       └── icons/
└── src/test/kotlin/...
```

## 11. 里程碑

| 阶段 | 内容 | 产出 |
|------|------|------|
| **M1 脚手架** | Gradle 工程、plugin.xml、空 Action 能加载 | 可在 IDE 运行的空插件 |
| **M2 AI 调用打通** | AiProvider 抽象 + OpenAI 实现 + 测试连接 | 配置面板能成功调通一个供应商 |
| **M3 核心功能** | 读 diff + 拼 Prompt + 回填 commit | 手动触发能生成 commit message |
| **M4 体验完善** | Anthropic 协议、多 Profile、Commit 面板按钮、错误提示 | 可日常使用 |
| **M5 发布** | 图标、文档、签名打包、上架 Marketplace | 0.1.0 发布 |

## 12. 已确认决策

| 决策项 | 结论 | 落地位置 |
|--------|------|----------|
| 最低兼容版本 | **IntelliJ Platform 2025.3**（sinceBuild=253） | `gradle.properties` / `plugin.xml` |
| API Key 存储 | **macOS Keychain**，经平台 `PasswordSafe` + `CredentialAttributes` | `settings/Keychain.kt` |
| 回填策略 | 默认 **APPEND**（追加），可选 REPLACE / PREVIEW | `GitHelperSettings.refillPolicy` |
| 流式输出 | **支持 SSE**，两种协议均已实现，逐块写入 commit 编辑器 | `ai/*Provider.kt` + `action/...` |
| 包名 | `com.github.tomseanmy` | 全部源码包路径 |
| 域名 / 文档 | GitHub Pages（`tomseanmy.github.io`），文档最后补充 | `README.md` |
| Git 操作实现 | **直接调用本地 `git` 命令**，不依赖 git4idea 内部易变 API（`GitLineHandler` 等） | `git/GitDiffProvider.kt` |
| HTTP | JDK `java.net.http.HttpClient`，零三方依赖 | `ai/HttpExecutor.kt` |
| JSON | 平台内置 Gson | `ai/Json.kt` |
| 构建工具 | IntelliJ Platform Gradle Plugin **2.12.0** + Kotlin **2.2.20** + Gradle **9.5.1** + JDK 21 | `build.gradle.kts` |

### 12.1 验证结果（2026-06-27）
- `./gradlew buildPlugin` ✅ 产出 `build/distributions/git-helper-0.1.0.zip`（约 76 KB）
- `./gradlew verifyPlugin` ✅ 对 IU-253.33813.25 结论：**Compatible**
- 动态插件资格：✅ 可在不重启 IDE 的情况下启用/禁用

### 12.2 后续可选项
- [ ] commit 对话框工具栏原生按钮注入（当前已挂在 VCS 菜单 + 编辑器右键 + 快捷键 `Ctrl+Shift+G`）；
- [ ] diff 超长时的智能分块（当前为截断）；
- [ ] JetBrains Marketplace 上架（需账号与签名）。

---
*本文档随开发持续更新，重要决策请在此记录。*
