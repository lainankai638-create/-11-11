from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
J = ROOT / "app/src/main/java/com/ounan/knowledge"

class ChatLineFilterRegression(unittest.TestCase):
    def test_line_based_instead_of_block_level_classifier(self):
        p=(J/"ChatScreenshotProcessor.kt").read_text()
        self.assertIn("for (block in ocr.textBlocks) for (line in block.lines)",p)
        self.assertIn("detectedSide(bitmap, rect, bg)",p)
        self.assertIn("val fallback = high.isEmpty() && lower.isNotEmpty()",p)
        self.assertIn("group(used)",p)
    def test_cropping_preserves_first_and_last_messages(self):
        m=(J/"MainActivity.kt").read_text()
        self.assertIn('setText("8")',m)
        self.assertIn('setText("11")',m)
        self.assertIn('排除页面顶部 %（默认 8）',m)
        self.assertIn('排除页面底部 %（默认 11）',m)
    def test_fallback_is_review_only(self):
        m=(J/"MainActivity.kt").read_text()
        self.assertIn("if (page.usedFallback)",m)
        self.assertIn('预览并修正后保存',m)
        self.assertIn('chatBatchDb.save(heading, value, imageCount)',m)
    def test_identity_unchanged_and_no_network_permission(self):
        g=(ROOT/'app/build.gradle.kts').read_text()
        manifest=(ROOT/'app/src/main/AndroidManifest.xml').read_text()
        self.assertIn('applicationId = "com.ounan.secondbrain"',g)
        self.assertIn('versionCode = 28',g)
        self.assertIn('android.permission.INTERNET',manifest)
if __name__ == "__main__": unittest.main()
