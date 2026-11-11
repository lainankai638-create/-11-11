from pathlib import Path
import unittest
CODE=Path(__file__).resolve().parents[1]/'app/src/main/java/com/ounan/knowledge'
class CaptureLifecycle(unittest.TestCase):
 def test_accessibility_does_not_stop_ocr_on_teardown(self):
  s=(CODE/'WeChatReaderService.kt').read_text()
  finish=s[s.index('private fun finishSession('):s.index('private fun clock(')]
  assert 'WeChatOcrService.ACTION_STOP' not in finish
  stop=s[s.index('private fun requestStop()'):s.index('private fun togglePause()')]
  assert 'stopService(Intent(this, WeChatOcrService::class.java))' in stop
 def test_durable_events_and_ui_copy(self):
  s=(CODE/'OcrLifecycleLog.kt').read_text()
  o=(CODE/'WeChatOcrService.kt').read_text()
  m=(CODE/'MainActivity.kt').read_text()
  assert 'MAX_EVENTS = 32' in s and '.commit()' in s
  assert 'MediaProjection.onStop 已回调' in o
  assert 'main.post(heartbeat)' in o
  assert '复制诊断' in m and 'OcrLifecycleLog.read(this)' in m
 if __name__ == '__main__': unittest.main()
