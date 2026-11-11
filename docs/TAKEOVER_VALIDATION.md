# V0.5.3.1 工程接管与构建验证

验证日期：2026-11-11。依据用户在当前任务粘贴的 [Issue #1](https://github.com/lainankai638-create/-11-11/issues/1) 完整要求执行。
范围仅为源码恢复、敏感信息检查、构建支持与首次 APK 验证，不开发新功能，不安装到手机，不部署中继。

## 归档与源码完整性

- 基线提交：`7b934843db25c6c3b7d542d0616e8020b0d30e0e`。
- 原归档：`OunanKnowledge_v0531_GitHub_ready.zip`，保持原路径和内容。
- 归档 SHA-256：`fe78cd1ef56b788677502479d69479c74abb82276a0d8bfc8a06ef3eea57aa68`。
- 检查了 105 个归档条目，无路径穿越、符号链接或 CRC 错误；105 个文件均已恢复。
- 101 个文件与归档逐字节一致。四处调整仅为 README、`.gitignore`、GitHub Actions 和 Wrapper properties；原 README 另完整保存于 `docs/history/README-v0531-archive.md`。
- `app/`、`tests/`、`server/`、`cloudflare/` 中共 64 个文件全部与 ZIP 逐字节一致。
- 补充由官方 Gradle 8.11.1 生成的 `gradlew`、`gradlew.bat` 和 Wrapper JAR，并固定分发包 SHA-256。
- Wrapper JAR SHA-256 与官方发布校验值一致：`2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046`。

Kotlin 代码、AndroidManifest、Gradle 配置、图标/样式/XML 资源、原有测试和可选中继均已完整恢复。
应用 ID `com.ounan.secondbrain`、版本 `0.5.3.1` / `31`、SDK 配置及应用签名配置没有修改。
SQLite 知识库、GPT、微信截图 OCR、蜘蛛采集、历史记录和界面代码原样保留；这并不代表这些功能已在真机验收。

## 隐私检查

对归档及待提交源码检查了常见 OpenAI/GitHub/AWS/Google 密钥格式、私钥头、带凭据 URL、长凭据字符串赋值，以及数据库、密钥容器和环境文件名；没有发现填写的真实密钥或用户数据文件。
手动核对了 Python 中继和 Worker 的凭据入口：均从运行环境读取，源码中没有真实值。
原始 ZIP 仅包含代码、文档和图标等工程文件，保留为备份，不包含构建机器的调试签名密钥。
没有读取手机、私人目录、真实数据库、真实聊天记录或实际 API 凭据，也没有发出真实 OpenAI 请求。

`.gitignore` 补充环境文件、Worker 本地凭据、签名材料、SQLite 及 sidecar、`.ounan` 私人备份、Python 缓存和本地状态。
通过 `git check-ignore --stdin` 检查了 14 个本地/敏感示例路径（不创建示例数据）；均被忽略。
原始 ZIP、Wrapper JAR 和应用图标仍可跟踪。
忽略规则不等于内容安全保证；提交前仍需检查暂存文件，不能向归档或源码加入真实数据。

已有边界：`ounan_chat_screenshots.db` 的标题和正文是明文；旧 `PRIVACY.md` 中“无 INTERNET 权限”等描述已不适用于本版。
GPT 可发送用户授权的检索片段与工具结果，连接参数已由 `GptSecureConnection` 加密保存。
这些行为均原样保留，本次不进行数据迁移或安全功能重写，也不宣称完成安全审计。

以下数据库文件名、版本 1、建表语句和相关密钥别名均未修改：

| 数据库 | 内容 |
| --- | --- |
| `ounan_fragments_encrypted_v1.db` | 知识记录 |
| `wechat_archive.db` | 微信留档 |
| `ounan_chat_screenshots.db` | 批量截图聊天 |
| `ounan_admin_changes.db` | 操作历史 |
| `ounan_gpt_conversations.db` | GPT 对话历史 |

## 构建结果

Linux x86_64，Temurin JDK 17.0.17+10（含 `javac`），Gradle 8.11.1，AGP 8.10.1，Kotlin 2.2.0，Android SDK 35，Build Tools 35.0.0。
复用前一步云环境准备的工具，依赖和缓存位于仓库外。
环境缺少完整 JDK 及 Android 工具的问题已解决；Java 使用环境提供的 CA 信任库，未关闭 TLS 或校验。

在仓库根目录运行：

```bash
./gradlew --no-daemon --max-workers=2 clean assembleDebug testDebugUnitTest
```

结果为 `BUILD SUCCESSFUL in 39s`，`41 actionable tasks: 41 executed`，进程退出码 0。
本次通过 Wrapper 清理后重新构建，而非引用仓库外工作副本的旧 APK。
完整构建输出见 [validation/build-debug.log](validation/build-debug.log)，已将本机绝对目录替换成占位符并清除行尾空格。

- APK：`app/build/outputs/apk/debug/app-debug.apk`，47,568,418 字节。
- SHA-256：`332673dd932bede408c078ab24dff4cc1e1e192b42336321edd386532aa028bb`。
- `aapt dump badging` 确认应用 ID `com.ounan.secondbrain`、`versionName=0.5.3.1`、`versionCode=31`、minSdk 26、target/compileSdk 35。
- `apksigner verify --verbose` 退出码 0，v2 签名有效，1 个签名者。
- APK 和本机调试密钥不提交到 Git；CI 配置在 PR/main/手动运行时构建并上传工件，远端运行结果尚未验证。

签名配置未修改，APK 使用当前环境的默认 Debug 签名。没有原安装版证书供比对，因此不能声称可覆盖用户手机上的旧版。
本次未安装、卸载或清空任何应用；持有原签名的构建环境才能验证覆盖升级。

编译警告：旧 `kotlinOptions.jvmTarget` API 已弃用；`ChatScreenshotProcessor.kt:78` 的条件恒为 true；ML Kit 的预编译原生库未剥离符号，按原样打包；Android analytics 尝试写入只读用户目录失败。以上未阻断构建，没有为消除警告改动应用源码。

## 测试结果

| 检查 | 实际结果 |
| --- | --- |
| `python3 -m unittest discover -s server -v` | 3 项通过，退出码 0；使用模拟 API，验证工具往返、伪造 call ID 拒绝及未知工具拒绝 |
| `python3 -m unittest discover -s tests -v` | 54 项结果：42 通过、11 失败、1 导入错误；退出码 1；无跳过 |
| `tests/ChatPageStitcherRegression.kt` | Kotlin 2.2.0 编译、运行成功，退出码 0 |
| `testDebugUnitTest` | `NO-SOURCE`，没有 Android 单元测试执行，不计为通过 |
| 真机/模拟器、OCR/无障碍、覆盖安装、备份恢复、真实 GPT 调用 | 未运行 |

Python 项目检查的完整测试名称与状态见 [validation/test-summary.txt](validation/test-summary.txt)。
11 项失败中，8 项断言旧版号 `0.5.1.2` / `28`，另 3 项依赖旧的令牌保存/回答显示/错误显示源码字符串。
导入错误是 `test_compile_fix_v0471.py:9` 在模块顶层断言旧版号触发 `AssertionError`，不是缺少 Python 依赖。
当前实现已使用 `GptSecureConnection`、聊天气泡和独立 `shown` 错误文本，历史静态字符串测试未同步；这不能替代 UI 行为验收。
本次保留这些测试和失败，不禁用断言、不降级应用版号；后续可单独评审更新测试。

Kotlin 回归使用构建依赖缓存中的官方 `kotlin-compiler-embeddable:2.2.0` 的 `org.jetbrains.kotlin.cli.jvm.K2JVMCompiler`，编译原有三个规则文件与回归入口，再用 JVM 执行 `ChatPageStitcherRegressionKt`。
覆盖重叠去重、相同消息保留、静止页、缺口、80% 重叠、知识排版和会话标题匹配；成功输出：

```text
PASS: overlap, identical messages, stationary, gaps, 80% common, formatter, chat title
```

## 提交边界

所有变更仅在独立分支 `codex/issue-1-project-takeover` 提交，通过 PR 等待审核，不直接推送或合并到 `main`。
GitHub Actions 只新增 PR 构建入口、已有中继测试和正确的工件版本名，不部署应用或服务。
本次没有开发远程控制，也没有重写功能、改变安装包名、数据库或应用签名配置。
