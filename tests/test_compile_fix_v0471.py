from pathlib import Path
root=Path(__file__).resolve().parents[1]
src=root/'app/src/main/java/com/ounan/knowledge'
s=(src/'SpiderOverlay.kt').read_text()
o=(src/'WeChatOcrService.kt').read_text()
g=(root/'app/build.gradle.kts').read_text()
assert 'strokeWidth = ctx.resources.displayMetrics.density * 2.5f' in s and 'h.postDelayed(this, 175L)' in s
assert 'import java.util.Locale' in o and 'Locale.CHINA' in o
assert 'versionCode = 28' in g and 'versionName = "0.5.1.2"' in g
assert 'applicationId = "com.ounan.secondbrain"' in g
print('PASS: two errors in build_output(1).txt corrected; package identity unchanged')
