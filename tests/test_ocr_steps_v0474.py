from pathlib import Path
import unittest

class StepChecks(unittest.TestCase):
    def test_diagnostic_stages(self):
        s=(Path(__file__).resolve().parents[1]/"app/src/main/java/com/ounan/knowledge/WeChatOcrService.kt").read_text()
        for stage in ["创建通知渠道", "启动媒体投射前台服务", "读取系统录屏授权令牌", "读取屏幕尺寸", "创建画面缓冲区", "初始化中文 OCR 模型", "取得系统 MediaProjection", "注册录屏停止回调", "创建虚拟显示屏"]:
            self.assertIn(stage,s)
        self.assertIn("sanitizedError(t)",s)
        self.assertIn("safeWidth > 0 && safeHeight > 0",s)
        self.assertIn("android.permission.INTERNET",(Path(__file__).resolve().parents[1]/"app/src/main/AndroidManifest.xml").read_text())
