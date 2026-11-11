# V0.4 构建与静态检查

本环境未安装 Android SDK、aapt2、Gradle 或 Android Gradle Plugin，也未连接安卓真机，**未能编译 APK 或实际运行**。源代码只能视为待构建和真机验证的原型，不能保证不存在编译或运行问题。

已完成的非运行时静态检查：

- Android XML 清单、UI 资源、无障碍配置均可由 XML 解析器解析。
- Kotlin 源码的括号/花括号/常见字符串边界检查通过（不是 Kotlin 编译器校验）。
- 清单没有 INTERNET 权限，只有一个微信无障碍服务声明，包名仅为 `com.tencent.mm`。
- 两个数据库记录的数据写入使用 AES-GCM 密文，均提供密码加密备份和导入代码。
- Android Studio/AndroidIDE 或 GitHub Actions 实际执行 `assembleDebug` 后，还要检验安装、权限、OCR、微信界面兼容性、微信采集边界和加密备份跨设备恢复。

上线前特别需要做安全审计、UI 正确性测试、电量与内存压力测试；请勿在原型中保存密码或把它当唯一的微信聊天备份。
