from pathlib import Path
import unittest
P=Path(__file__).resolve().parents[1]
J=P/'app/src/main/java/com/ounan/knowledge/MainActivity.kt'

class BatchBitmapDecodeFix(unittest.TestCase):
    def test_headers_are_only_metadata(self):
        s=J.read_text()
        fn=s.split('private fun loadChatBitmap(uri: Uri): Bitmap {',1)[1].split('private fun chatReadFailure',1)[0]
        self.assertIn('inJustDecodeBounds = true',fn)
        self.assertIn('BitmapFactory.decodeStream(stream, null, bounds)',fn)
        self.assertIn('require(bounds.outWidth > 0 && bounds.outHeight > 0)',fn)
        self.assertNotIn('?: error("图片无法读取")',fn)
    def test_bitmap_read_and_decoder_fallback(self):
        s=J.read_text()
        self.assertIn('BitmapFactory.decodeStream(stream, null, options)?.let { return it }',s)
        self.assertIn('ImageDecoder.decodeBitmap(source)',s)
        self.assertIn('ImageDecoder.ALLOCATOR_SOFTWARE',s)
        self.assertIn('chatReadFailure(bitmap.exceptionOrNull()',s)
        self.assertIn('versionName = "0.5.1.2"',(P/'app/build.gradle.kts').read_text())
    def test_keep_multi_pick_and_storage(self):
        s=J.read_text()
        self.assertIn('Intent.EXTRA_ALLOW_MULTIPLE, true',s)
        self.assertIn('ChatScreenshotStitcher.merge(pages)',s)
        self.assertIn('chatBatchDb.save(heading, value, imageCount)',s)

if __name__=='__main__':unittest.main()
