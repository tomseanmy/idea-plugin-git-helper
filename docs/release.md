# 发布指南

打 tag 即自动发布到 JetBrains Marketplace。下面是一次性配置步骤。

## 一、注册插件（首次，必须手动一次）

> ⚠️ **关键前置**：JetBrains Marketplace 要求插件**首次必须手动上传一次**，建立插件记录（填 license、仓库 URL 等元数据）。CI 自动发布只能发布「已存在插件的新版本」，**无法创建插件本身**。否则 CI 会报：
> ```
> Cannot find plugin. Note that you need to upload the plugin to the repository at least once manually.
> ```

**手动上传步骤（只做一次）：**
1. 用 `./gradlew buildPlugin` 构建，产物在 `build/distributions/*.zip`
2. 打开 https://plugins.jetbrains.com/ 登录
3. 点 **Upload Plugin**，选 zip，填写：
   - License（如 MIT）
   - Repository URL（你的 GitHub 仓库地址）
   - 其他必填信息
4. 提交 → 等待人工审核（1-3 工作日）

审核通过后，插件记录就建立了，**之后 CI 才能 `publishPlugin` 推新版本**。

## 二、生成上传 Token

1. 打开 https://plugins.jetbrains.com/author/me/tokens
2. 点 **Generate Token**，名字随意（如 `github-ci`），**复制 token**（只显示一次）

## 三、配置 GitHub Secret（发布所需）

在你的 GitHub 仓库：**Settings → Secrets and variables → Actions → New repository secret**：

| Secret 名 | 值 | 何时需要 |
|-----------|-----|----------|
| `PUBLISH_TOKEN` | 第二步生成的上传 token | **必需**，每次发布 |

> 只有这一个 Secret 是发布必需的。签名证书不是必需的，见下面说明。

## 四、关于签名证书（重要：首次发布不需要）

JetBrains Marketplace **接受未签名插件上传**，安装时 IDE 只是弹一个"不受信任插件"警告。所以：

- **首次发布**：无需签名证书，直接发布。CI workflow 检测到没有签名 Secret 时会自动跳过签名步骤。
- **签名证书的来源**：签名证书是 JetBrains 在**你的插件首次通过审核后**，通过邮件单独发放的（不在 Tokens 页面）。所以第一次发布时你根本拿不到证书，这是正常的。
- **拿到证书后**：JetBrains 会发邮件给你，含 Certificate Chain 和 Private Key。这时再把三个值配成 Secret：
  - `SIGNING_CERT_CHAIN`（Certificate Chain 全文，多行整段粘贴）
  - `SIGNING_PRIVATE_KEY`（Private Key 全文，多行整段粘贴）
  - `SIGNING_PASSWORD`（生成证书时设置的密码）
  
  配好后，以后的发布会自动签名。

## 五、发布

```bash
git tag v0.1.0
git push origin v0.1.0
```

推上去后：
1. GitHub Actions 自动触发 `release.yml`
2. 构建 → `verifyPlugin` 校验 → `signPlugin` 签名 → `publishPlugin` 上传 Marketplace
3. 同时创建 GitHub Release 并附带 zip

进度看仓库的 **Actions** 标签。Marketplace 审核结果会邮件通知。

## 六、本地手动发布（可选）

如果 CI 挂了想本地发布（首次无需签名）：

```bash
export PUBLISH_TOKEN="你的token"
./gradlew publishPlugin
```

拿到签名证书后，本地发布加上签名：

```bash
export PUBLISH_TOKEN="你的token"
export SIGNING_CERT_CHAIN="$(cat cert.pem)"
export SIGNING_PRIVATE_KEY="$(cat private.pem)"
export SIGNING_PASSWORD="你的密码"
./gradlew publishPlugin
```

## 故障排查

- **`Token is not provided`**：`PUBLISH_TOKEN` secret 没配或拼错
- **签名相关警告**：首次发布无需理会；拿到证书后配齐三个 Secret 即可自动签名
- **`Plugin ... already exists`**：version 号重复，改 `gradle.properties` 的 `pluginVersion` 再发
- **workflow 里 `signing enabled: false`**：正常现象，表示首次发布未签名
- 完整文档：https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html
