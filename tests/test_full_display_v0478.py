from pathlib import Path
import unittest
root = Path(__file__).resolve().parents[1]
src = (root / "app/src/main/java/com/ounan/knowledge/MainActivity.kt").read_text()

class ScreenConsent(unittest.TestCase):
    def test_explicit_full_display_on_android14_plus(self):
        self.assertIn("MediaProjectionConfig.createConfigForDefaultDisplay()", src)
        self.assertIn("Build.VERSION_CODES.UPSIDE_DOWN_CAKE", src)
        self.assertIn("manager.createScreenCaptureIntent()", src)  # fallback for Android < 14

    def test_both_modes_share_same_authorization_helper(self):
        self.assertIn("fullDisplayConsentIntent(mgr), REQ_WECHAT_OCR", src)
        self.assertIn("fullDisplayConsentIntent(manager), REQ_PROJECTION", src)
        self.assertNotIn("startActivityForResult(mgr.createScreenCaptureIntent()", src)
        self.assertNotIn("startActivityForResult(manager.createScreenCaptureIntent()", src)

    def test_same_application_identity(self):
        gradle = (root/'app/build.gradle.kts').read_text()
        self.assertIn('applicationId = "com.ounan.secondbrain"', gradle)
        self.assertIn('versionCode = 28', gradle)

if __name__ == "__main__":
    unittest.main()
