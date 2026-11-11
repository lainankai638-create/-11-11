# V0.4.3 截图开关更新

- 原因：MainActivity 使用 Android `WindowManager.LayoutParams.FLAG_SECURE` 默认阻止系统截图。
- 修复：新增「设置 → 临时允许截图」；必须手动确认才会 `clearFlags(FLAG_SECURE)`，并可随时重新关闭。
- 隐私：`onPause()` 总会复原 `FLAG_SECURE`，重新打开应用默认不可截图；没有保存该开关。
- 版本：`applicationId` 未变，`versionCode` 7；没有数据库迁移或新增任何权限。
- 在已安装 V0.4.2 的同一手机 IDE 中使用原调试密钥覆盖安装；如果签名冲突，请先停下，绝不能为了换图标/截图功能而卸载有数据的应用。
- 测试：开启开关并截取无隐私测试页面，然后切到桌面再返回，确认截图恢复被阻止。
- 此包提供源码，不是 APK。未进行 Android SDK 编译或真机测试。
