# 架构

- **输入**：Intent.ACTION_SEND 文字/图片、SAF 导入图像、经 Android MediaProjection 授权的短时屏幕 OCR、三分钟经用户开启的微信范围 AccessibilityService。
- **解析**：中文 Google ML Kit 离线 OCR；简单关键词标签，不使用远程 LLM。
- **持久化**：知识 `ounan_fragments_encrypted_v1.db` 和微信 `wechat_archive.db`；AES-GCM 加密 JSON 负载；HMAC-SHA256 去重；时间索引。
- **查询**：本地逐条解密后全文过滤（当前为线性检索，数据量很大时需优化加密搜索索引），标签关联显示。
- **备份**：各自密码加密 PBKDF2-SHA256 / AES-GCM 文件，跨设备可恢复，不依赖 Android Keystore 导出。
- **GPT/云端**：V0.4 未接入。未来通过显式授权的选择性检索工具接入，密码保险库与微信私密档案默认不可供大模型读取。
- **威胁模型**：保护应用静态数据库文件泄露，不保证抵抗设备解锁后拥有高权限的恶意软件，也不替代经审计的密码管理器。
