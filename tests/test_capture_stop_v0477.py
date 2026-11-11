from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[1]
CODE=ROOT/'app/src/main/java/com/ounan/knowledge'
class SafeStop(unittest.TestCase):
 def test_version(self):
  g=(ROOT/'app/build.gradle.kts').read_text()
  self.assertIn('versionCode = 28',g)
  self.assertIn('versionName = "0.5.1.2"',g)
 def test_stop_does_not_relaunch_a_dead_service(self):
  m=(CODE/'MainActivity.kt').read_text()
  a=m[m.index('private fun stopSpider()'):m.index('private fun showWeChatEntry(')]
  self.assertIn('stopService(Intent(this, WeChatOcrService::class.java))',a)
  self.assertNotIn('startService(Intent(this, WeChatOcrService::class.java)',a)
  self.assertIn('putBoolean("paused", true)',a)
  self.assertIn('putLong("id", 0L)',a)
 def test_orphan_session_cleaned_after_reboot(self):
  m=(CODE/'MainActivity.kt').read_text()
  s=(CODE/'WeChatReaderService.kt').read_text()
  self.assertIn('clearOrphanedOcrSession()',m)
  self.assertIn('WeChatOcrService.activeSessionId != id',m)
  self.assertIn('WeChatOcrService.activeSessionId != persisted',s)
 def test_overlay_stop_and_stale_notification(self):
  a=(CODE/'WeChatReaderService.kt').read_text()
  b=(CODE/'WeChatOcrService.kt').read_text()
  stop=a[a.index('private fun requestStop()'):a.index('private fun togglePause()')]
  self.assertIn('stopService(Intent(this, WeChatOcrService::class.java))',stop)
  self.assertIn('if (thisSession == 0L',b)
  self.assertIn('activeSessionId = 0L',b)
 def test_preserve_privacy_layout_and_data(self):
  s=(CODE/'MainActivity.kt').read_text(); o=(CODE/'SpiderOverlay.kt').read_text()
  self.assertIn('系统截图（测试开放）',s)
  self.assertIn('strokeWidth = ctx.resources.displayMetrics.density * 2.5f',o)
  self.assertIn('h.postDelayed(this, 175L)',o)
  self.assertIn('WeChatArchive(this)',s)
