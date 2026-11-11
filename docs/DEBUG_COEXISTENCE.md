# 与正式版共存的独立 Debug APK

| 构建 | 应用 ID | 显示名称 |
| --- | --- | --- |
| Release | `com.ounan.secondbrain` | 欧南知识中枢 |
| Debug | `com.ounan.secondbrain.debug` | 欧南知识中枢·测试版 |

仅 Debug 设置 `applicationIdSuffix = ".debug"`，通过 Debug 资源覆盖名称。
namespace 继续为 `com.ounan.knowledge`，全部 Kotlin、SQLite 表结构、OCR、蜘蛛采集、历史和 GPT 实现原样保留。
没有改变签名配置、禁用签名校验或迁移原版数据；原始 ZIP 保留不动。

## 数据和组件隔离

两个包由 Android 分配不同应用 UID 和私有数据目录，SQLite、SharedPreferences、文件与 Android Keystore 分别归属各自应用。
数据库文件名及密钥别名可以保持相同，它们处于不同应用沙箱中，不会因此共享数据。
没有 `sharedUserId`、硬编码正式版私有目录、`createPackageContext` 或自动跨应用数据导入。
原有服务和通知 PendingIntent 使用明确组件，身份随所属应用变化；相同内部 action 字符串不会将命令路由到另一应用。
备份恢复和系统文件选择器仍为原有的手动操作，不应将真实私人备份导入测试版。

项目没有自定义 ContentProvider 或 FileProvider。检查了包含依赖的最终合并 Manifest：

| Provider | Release authority | Debug authority |
| --- | --- | --- |
| ML Kit | `com.ounan.secondbrain.mlkitinitprovider` | `com.ounan.secondbrain.debug.mlkitinitprovider` |
| AndroidX Startup | `com.ounan.secondbrain.androidx-startup` | `com.ounan.secondbrain.debug.androidx-startup` |

以上 Provider 均 `exported=false`，authority 无交集；依赖生成的 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` 也按各自应用 ID 区分。
`allowBackup=false` 保持不变。无障碍和录屏权限必须分别由用户授权，不会继承正式版授权。
截图/无障碍本身仍能处理用户主动选择的可见内容；测试时不要对正式版私人页面或真实聊天启动采集。

## 构建与自动校验

在配置好 JDK 17、Android SDK 35、Build Tools 35.0.0 的仓库根目录运行：

```bash
./gradlew --no-daemon --max-workers=2 assembleDebug processReleaseManifest
python3 scripts/verify_debug_isolation.py
python3 -m unittest discover -s server -v
```

`ANDROID_HOME` 需指向 SDK。校验器读取实际 APK badging 及 Debug/Release 合并清单，检查包名、测试版名称、SDK、原组件、Provider 和权限隔离、无共享 UID，校验失败即退出非零。
CI 执行同样检查，并上传 `OunanSecondBrain-v0.5.3.1-independent-debug-apk` 工件，内含 `app-debug.apk`。

本地结果：构建成功，隔离校验通过，中继 3 项模拟测试通过，APK v2 签名校验通过。
本地 APK SHA-256：`1a634c29636978f5407fd6df885915886fb5b93331e0ddb88b08d3ca82b50484`。
CI 的调试密钥可能与本地不同，因此 CI 工件哈希可能不同；包名和显示名称同样由 CI 校验。
历史项目测试的旧版本断言问题见 `TAKEOVER_VALIDATION.md`，本次没有修改或隐藏这些测试。

## Redmi K40S / Android 14 人工验收

本次仅完成构建与制品/Manifest 验证，未在 Redmi K40S 执行安装或运行测试，不宣称已完成真机验收。

1. 保留原版安装和现有数据，从本 PR 的成功 Actions 运行下载独立测试版工件并解压。
2. 正常安装 `app-debug.apk`。应新增「欧南知识中枢·测试版」，而非提示更新原版；若显示为更新原版，停止安装并核对下载来源。
3. 在系统应用信息中确认两款应用分别存在，测试版包名为 `com.ounan.secondbrain.debug`。确认原版仍可打开，既有记录未变化。
4. 测试版初始应无原版知识/聊天/GPT 配置。仅在测试版创建虚构记录，重启测试版后检查保留；原版不应出现该记录。
5. 仅对虚构截图/公开测试内容授权并检查 OCR、蜘蛛与本地历史。GPT 接口按测试版独立配置，避免发送真实私人数据。

不要卸载或清空原版，也不要更改系统签名验证。独立包名用于新增安装，不解决同一个包名下不同签名之间的覆盖升级问题。
