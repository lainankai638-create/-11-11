from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
src=ROOT/'app/src/main/java/com/ounan/knowledge'
ocr=(src/'WeChatOcrService.kt').read_text()
app=(src/'MainActivity.kt').read_text()
assert 'ocr_startup_diagnostics' in ocr and 'ocr_startup_diagnostics' in app
assert '已收到 OCR 启动请求' in ocr and '前台服务运行中' in ocr
assert '已收到首帧屏幕画面' in ocr and 'OCR 已成功运行' in ocr
assert '系统终止屏幕采集' in ocr and '启动失败' in ocr
assert '查看 OCR 启动诊断' in app
assert 'putString("chat"' not in ocr.split('private fun trace(')[1].split('override fun onCreate()')[0]
print('OCR diagnostics checks: passed')
