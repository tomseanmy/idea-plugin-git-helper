# git-helper

一款轻量级 JetBrains（IntelliJ IDEA / WebStorm / PyCharm 等）插件，主打 **AI 生成 Git Commit Message**，可自由配置任意 OpenAI / Anthropic 兼容的供应商。

> 起因：通义灵码 Lingma 收费并更名为 Qoder，但日常真正高频用到的只有「Git 操作 + AI 生成提交说明」。
> 与其被绑定到单一收费供应商，不如做一款**可自由配置任意 AI 供应商**的轻量插件，永久免费、数据直连你自己的 API。

## 核心特性

- 🤖 **AI 生成 Commit Message**：基于 `git diff --cached` 自动生成规范的提交说明，支持 Conventional Commits。
- 🔌 **多协议支持**：兼容 OpenAI / Anthropic 两大主流协议。DeepSeek、智谱、Kimi、OpenRouter、自部署 vLLM / Ollama 等开箱即用。
- ⚙️ **完全自定义**：供应商 URL、API Key、模型、Temperature、Max Tokens、System Prompt、自定义请求头，全部可配置，支持多个 Profile 一键切换。
- 🌊 **流式输出**：SSE 流式逐字写入 commit message 编辑器，实时可见。
- 🔒 **本地优先**：API Key 经平台 `PasswordSafe` 存入 **macOS Keychain**；请求由 IDE 直连供应商，不经过任何第三方。
- 🪶 **轻量专注**：只做 Git + AI 这一件事，不捆绑代码补全、不做重资产功能。零三方运行时依赖。

## 快速开始

### 安装

**方式 A：从源码构建**（当前推荐）

```bash
git clone <repo-url> git-helper
cd git-helper
./gradlew buildPlugin
```

产物：`build/distributions/git-helper-0.1.0.zip`

然后在 IDE 中：`Settings → Plugins → ⚙️ → Install Plugin from Disk…` 选择该 zip。

**方式 B：从 JetBrains Marketplace 安装**（待上架）

### 配置供应商

1. `Settings → Tools → Git Helper`
2. 点击 **Add** 新建一个 Profile，填入：
   - **Name**：任意名称，如 `DeepSeek`
   - **Protocol**：`OPENAI` 或 `ANTHROPIC`
   - **Base URL**：例如
     - OpenAI：`https://api.openai.com/v1`
     - DeepSeek：`https://api.deepseek.com`
     - Anthropic：`https://api.anthropic.com`
     - 本地 Ollama：`http://localhost:11434/v1`
   - **Model**：如 `deepseek-chat` / `claude-3-5-sonnet-20241022` / `gpt-4o-mini`
   - **API Key**：你的密钥（存入系统 Keychain）
3. 点击 **Test connection** 验证连通性。
4. 选中该 Profile，点击 **Set Active**。
5. 可选：设置 Commit message style、回填策略（APPEND/REPLACE/PREVIEW）、是否流式输出。

### 使用

- 暂存改动后（`git add`），在以下任一处触发 **Generate Commit Message**：
  - VCS 菜单（`VCS → Generate Commit Message`）
  - 编辑器右键菜单
  - 快捷键 `Ctrl+Shift+G`（macOS：`Cmd+Shift+G`）
- 生成的提交说明会按策略写入 commit message 输入框。

## 支持的供应商示例

| 供应商 | Protocol | Base URL |
|--------|----------|----------|
| OpenAI | OPENAI | `https://api.openai.com/v1` |
| DeepSeek | OPENAI | `https://api.deepseek.com` |
| 智谱 GLM | OPENAI | `https://open.bigmodel.cn/api/paas/v4` |
| Kimi | OPENAI | `https://api.moonshot.cn/v1` |
| OpenRouter | OPENAI | `https://openrouter.ai/api/v1` |
| Anthropic Claude | ANTHROPIC | `https://api.anthropic.com` |
| 本地 Ollama | OPENAI | `http://localhost:11434/v1` |
| 自部署 vLLM | OPENAI | `http://localhost:8000/v1` |

> 凡是实现了 `/v1/chat/completions`（OpenAI 兼容）或 `/v1/messages`（Anthropic 兼容）的端点都可使用。

## 项目结构

```
src/main/kotlin/com/github/tomseanmy/githelper/
├── GitHelperBundle.kt            # 插件入口、图标加载
├── ai/                           # AI 调用层
│   ├── AiProvider.kt             #   统一抽象接口
│   ├── OpenAiProvider.kt         #   OpenAI 协议（含 SSE 流式）
│   ├── AnthropicProvider.kt      #   Anthropic 协议（含 SSE 流式）
│   ├── HttpExecutor.kt           #   JDK HttpClient 封装
│   ├── Json.kt                   #   Gson 工具
│   └── ProviderFactory.kt        #   工厂 + 测试连接
├── git/                          # Git 集成层
│   ├── GitDiffProvider.kt        #   读 staged/unstaged diff（调用本地 git）
│   └── CommitMessageGenerator.kt #   diff → prompt → 调用模型
├── action/                       # Action 入口
│   ├── GenerateCommitMessageAction.kt  # 生成并回填（流式）
│   └── CommitMessageWriter.kt          # 写入 commit message 编辑器
└── settings/                     # 配置层
    ├── GitHelperSettings.kt      #   持久化状态
    ├── ProviderProfile.kt        #   Profile 数据模型
    ├── Keychain.kt               #   Keychain（PasswordSafe）封装
    ├── GitHelperConfigurable.kt  #   Settings 主面板
    ├── ProfileEditorPanel.kt     #   Profile 编辑表单
    └── ProfileListCellRenderer.kt
```

## 技术栈

| 事项 | 选型 |
|------|------|
| 构建 | IntelliJ Platform Gradle Plugin 2.12.0 + Gradle 9.5.1 |
| 语言 | Kotlin 2.2.20（JDK 21） |
| 目标平台 | IntelliJ Platform 2025.3（sinceBuild 253） |
| HTTP | JDK `java.net.http.HttpClient`（零三方依赖） |
| JSON | 平台内置 Gson |
| 密钥存储 | 平台 `PasswordSafe`（macOS Keychain） |

## 开发

```bash
# 在沙盒 IDE 中运行调试
./gradlew runIde

# 构建
./gradlew buildPlugin

# API 兼容性验证
./gradlew verifyPlugin
```

## 文档

- 详细设计：[docs/DESIGN.md](docs/DESIGN.md)

## License

MIT
