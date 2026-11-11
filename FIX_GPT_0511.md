# 欧南第二大脑 V0.5.1.1 · GPT 错误详情可见性修复

## 已定位问题
- `GptRelayClient.exchange()` 在 HTTP 非 2xx 时主动抛出 `IllegalStateException`，消息包含 HTTP 状态码及服务端的安全错误提示。
- 原 `MainActivity.showAgent()` 只创建了 `agentAnswer` 文字控件但没有 `chat.addView(agentAnswer)`，导致具体错误和正常 GPT 回答均不会显示。

## 修复
- 将 `agentAnswer` 真正加入聊天界面，并允许选取/复制诊断文本。
- HTTP 非成功请求显示状态码和服务端错误原因（如 HTTP 401 / 429 / 502 / 503）。
- 版本号 0.5.1.1，versionCode 27，`applicationId=com.ounan.secondbrain`，数据库格式不变。
- 不增加远程数据读写权限、不修改 Worker 网关配置、不引入密钥。

## 安装后测试
1. 先备份重要资料，在 Code on the Go 编译并覆盖安装（不要卸载或清除数据）。
2. 管理 -> GPT 管理员；输入网关地址与 `OUNAN_APP_TOKEN`（不要输入 API Key）。
3. 使用无个人数据的测试提问发送；如果失败，读取「错误详情」栏，并仅分享不含密钥的错误文本。

*本包完成静态与 Python 测试；此环境无法完成完整安卓 APK 构建或真机验证。*
