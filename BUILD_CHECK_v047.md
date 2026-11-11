# V0.4.7 构建与验证记录

- 已执行：纯 Kotlin `ChatPageStitcher`, `DocumentFormatter`, `OcrTextRules` 与 `tests/ChatPageStitcherRegression.kt` 使用 `kotlinc` 编译并运行，通过。
- 覆盖测试：同屏不重复、跨屏两行重叠、相同文字的真实重复发言、80% 内容重叠时新增内容不能丢弃、翻页无重叠提示、中文段落排版、微信标题基本匹配、排版不删同侧重复发言。
- 已检查：Manifest XML 解析、同一 applicationId、AES-GCM 数据库及密钥类不变、没有 INTERNET 权限、工程压缩包可解压、相较旧版不存在删除数据库的迁移代码。
- 未验证：Android SDK 真正的 Kotlin + Android 构建，Code on the Go 26.44 真机安装及运行、不同微信版本 OCR 识别效果、真实聊天记录准确性、不同屏幕分辨率下悬浮轨迹位置。
- 程序目前仍是开发测试版。任何编译失败需以用户手机的完整 Gradle 日志为准，不应声称 APK 已通过构建。
