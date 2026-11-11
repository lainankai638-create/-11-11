from pathlib import Path
import unittest
P=Path(__file__).resolve().parents[1]
J=P/'app/src/main/java/com/ounan/knowledge'
class BatchChatImport(unittest.TestCase):
 def test_multi_picker_and_app_id(self):
  main=(J/'MainActivity.kt').read_text()
  gradle=(P/'app/build.gradle.kts').read_text()
  self.assertIn('Intent.EXTRA_ALLOW_MULTIPLE, true', main)
  self.assertIn('data.clipData', main)
  self.assertIn('applicationId = "com.ounan.secondbrain"',gradle)
  self.assertIn('versionName = "0.5.1.2"',gradle)
 def test_order_review_edit_before_storage(self):
  main=(J/'MainActivity.kt').read_text()
  for token in ('java.util.Collections.swap', 'ChatScreenshotStitcher.merge(pages)', 'reviewChatBatch(chatName', '预览并修正后保存', 'chatBatchDb.save(heading, value, imageCount)'):
   self.assertIn(token,main)
 def test_no_remote_upload_or_force_encryption(self):
  manifest=(P/'app/src/main/AndroidManifest.xml').read_text()
  archive=(J/'ChatBatchArchive.kt').read_text()
  self.assertIn('android.permission.INTERNET',manifest)
  self.assertIn('SQLiteOpenHelper',archive)
  self.assertNotIn('ArchiveCrypto.',archive)
 def test_strict_bubble_only_and_safe_dedup(self):
  processor=(J/'ChatScreenshotProcessor.kt').read_text()
  stitch=(J/'ChatScreenshotStitcher.kt').read_text()
  for tok in ('topPercent', 'bottomPercent', 'detectedSide', 'guessedSide', 'strict', 'omitted'):
   self.assertIn(tok,processor)
  for tok in ('overlap', 'gaps', 'normalized(bubbles[0].text).length >= 10', 'if (a.side != b.side) return false'):
   self.assertIn(tok,stitch)
if __name__ == '__main__': unittest.main()
