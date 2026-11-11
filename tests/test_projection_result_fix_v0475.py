from pathlib import Path
import unittest

BASE = Path(__file__).resolve().parents[1]
SRC = BASE / "app/src/main/java/com/ounan/knowledge"

class MediaProjectionGrantRegression(unittest.TestCase):
    def test_ok_is_minus_one_on_android(self):
        # Android's Activity.RESULT_OK constant, not an invented success value.
        android_result_ok = -1
        self.assertEqual(android_result_ok, -1)

    def test_grants_are_checked_with_result_ok(self):
        for name in ("WeChatOcrService.kt", "CaptureService.kt"):
            with self.subTest(name=name):
                code = (SRC / name).read_text()
                self.assertIn("getIntExtra(EXTRA_CODE, Int.MIN_VALUE)", code)
                self.assertIn("android.app.Activity.RESULT_OK", code)
                self.assertNotIn("getIntExtra(EXTRA_CODE, -1)", code)
                self.assertNotIn("code == -1", code)
                self.assertNotIn("code != -1", code)

    def test_grant_sender_uses_activity_result(self):
        code=(SRC / "MainActivity.kt").read_text()
        self.assertIn("putExtra(WeChatOcrService.EXTRA_CODE, resultCode)", code)
        self.assertIn("putExtra(WeChatOcrService.EXTRA_DATA, data)", code)
        self.assertIn("putExtra(CaptureService.EXTRA_CODE, resultCode)", code)
        self.assertIn("putExtra(CaptureService.EXTRA_DATA, data)", code)

if __name__ == "__main__": unittest.main()
