# 欧南 · 个人知识中枢 Android

V0.5.3.1 Android/Kotlin 工程，应用 ID 为 `com.ounan.secondbrain`，`versionCode = 31`。
本次将已有源码归档恢复为可直接构建的 GitHub 工程，不增加功能、不修改应用源码、数据库结构或签名配置。

保留 SQLite 知识库、GPT 接口、微信截图 OCR、蜘蛛采集、历史记录及原有界面。
GPT 为用户手动发起的可选联网功能；应用声明了 `INTERNET` 权限。
检索片段和工具结果可能经配置的中继发送至 OpenAI，不能按早期文档理解为完全离线。

## 工程目录

| 路径 | 内容 |
| --- | --- |
| `app/` | AndroidManifest、Kotlin 代码、布局样式及图标资源 |
| `build.gradle.kts`、`settings.gradle.kts` | Android/Kotlin 插件及依赖仓库配置 |
| `gradlew`、`gradlew.bat`、`gradle/wrapper/` | Gradle 8.11.1 Wrapper，固定分发包 SHA-256 |
| `tests/` | 原有项目检查及纯 Kotlin 规则回归测试 |
| `server/` | 可选 Python 中继及使用模拟 API 的测试，无第三方 Python 依赖 |
| `cloudflare/worker.js` | 可选 Cloudflare Worker 中继 |
| `docs/TAKEOVER_VALIDATION.md` | 本次构建、测试、隐私检查和已知问题 |
| `docs/history/README-v0531-archive.md` | ZIP 中 README 的完整原文 |
| `OunanKnowledge_v0531_GitHub_ready.zip` | 原始工程备份，保留原文件 |

根目录的 `V*_NOTES.md`、`BUILD_CHECK*`、`PRIVACY.md` 等为历史资料。
其中版本号、联网行为、令牌保存方式及“未编译”等描述可能已过时；本次实际验证以验证报告为准。

## 构建 Debug APK

需要完整 **JDK 17**（包含 `javac`）、Android SDK Platform 35、Build Tools 35.0.0。
Gradle Wrapper 使用 8.11.1，Android Gradle Plugin 为 8.10.1，Kotlin 为 2.2.0。
不需要 OpenAI 密钥、用户数据库或连接手机即可编译。

```bash
# 在已安装 JDK 17 和 Android SDK 的环境中，将变量指向实际目录。
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

sdkmanager "platforms;android-35" "build-tools;35.0.0"
./gradlew --no-daemon --max-workers=2 assembleDebug
```

从仓库根目录执行。首次运行 Wrapper 会下载并校验 Gradle。
也可用 Android Studio 或 Code on the Go 打开整个仓库目录。
`local.properties`、SDK 路径、Gradle 缓存和签名密钥不得提交。
在只读用户目录的云环境中，可将 `GRADLE_USER_HOME`、`ANDROID_USER_HOME` 指向可写的工作区外部目录；代理和 CA 按环境提供的配置使用，不能关闭 TLS 校验。

输出：`app/build/outputs/apk/debug/app-debug.apk`。
GitHub Actions 在 PR、推送到 `main` 或手动触发时构建，并上传 `OunanSecondBrain-v0.5.3.1-debug-apk` 工件。
实际远端 CI 状态需以对应运行记录为准；本地成功不等于远端已运行。

签名配置保持归档原样；Debug 构建使用构建环境自己的调试密钥。
本次未安装或覆盖任何手机上的应用，不能保证该调试签名与原安装版一致。
升级前必须由持有原签名的环境构建，并核对签名；不要卸载旧版或清除应用数据。

## 测试

Python 3 标准库即可运行：

```bash
python3 -m unittest discover -s server -v
python3 -m unittest discover -s tests -v
```

本次中继 3 项测试通过；历史项目检查 54 项结果中 42 通过、11 失败、1 项导入错误。
失败主要来自旧版 `0.5.1.2` / `28` 及旧源码字符串断言，详情见验证报告。
保留全部原测试，不以跳过断言或改回旧版号消除失败。
`testDebugUnitTest` 当前为 `NO-SOURCE`，不能作为 Android 单元测试通过的证据。

如已安装 Kotlin 2.2.0 CLI，可运行原有纯 Kotlin 回归：

```bash
kotlinc app/src/main/java/com/ounan/knowledge/ChatPageStitcher.kt \
  app/src/main/java/com/ounan/knowledge/DocumentFormatter.kt \
  app/src/main/java/com/ounan/knowledge/OcrTextRules.kt \
  tests/ChatPageStitcherRegression.kt -include-runtime -d /tmp/ounan-rules.jar
java -jar /tmp/ounan-rules.jar
```

本次使用相同版本的 Kotlin 编译器执行该回归并通过；具体执行方式见报告。
尚未进行真机 OCR、无障碍、安装升级、备份恢复或真实 GPT 联网测试。

## 隐私和数据边界

本次只使用仓库源码和模拟测试数据，未读取任何真实用户数据库或聊天记录。
源码扫描未发现已填写的 API 密钥、令牌、私钥或用户数据文件；这不是完整安全审计。
`.gitignore` 排除本地凭据、签名文件、SQLite、`.ounan` 备份及构建输出；原始工程 ZIP 保留可追溯性。

已有截图聊天库 `ounan_chat_screenshots.db` 的标题和正文为明文，其他库的加密也不意味着所有数据均已加密。
本次保留原结构和行为，不进行迁移、清空或自动导入。
API 密钥只通过服务端/Worker 的运行环境配置，不写入 Android 源码、APK 或版本库。
中继为可选组件，本次不部署、不创建真实凭据、不发送真实私人数据。
