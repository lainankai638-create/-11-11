from pathlib import Path
import unittest
P=Path(__file__).resolve().parents[1]
S=P/'app/src/main/java/com/ounan/knowledge'
class AgentChecks(unittest.TestCase):
 def test_api_key_never_bundled(self):
  for f in S.glob('*.kt'):
   s=f.read_text()
   self.assertNotIn('sk-proj-',s)
   self.assertNotIn('OPENAI_API_KEY',s)
  self.assertNotIn('OPENAI_API_KEY',(P/'app/build.gradle.kts').read_text())
 def test_app_permission_identity_version(self):
  m=(P/'app/src/main/AndroidManifest.xml').read_text()
  g=(P/'app/build.gradle.kts').read_text()
  self.assertIn('android.permission.INTERNET',m)
  self.assertIn('android:usesCleartextTraffic="false"',m)
  self.assertIn('com.ounan.secondbrain',g)
  self.assertIn('versionCode = 28',g)
 def test_only_typed_sql_not_ai_generated(self):
  s=(S/'GptToolBridge.kt').read_text()
  for word in ('search_records','get_record','create_record','edit_record','delete_record','undo_change'):
   self.assertIn(word,s)
  self.assertNotIn('execSQL',s)
  self.assertIn('agentTools.isWrite(name)',(S/'MainActivity.kt').read_text())
  self.assertIn('批准本轮操作',(S/'MainActivity.kt').read_text())
 def test_stored_token_not_saved(self):
  s=(S/'MainActivity.kt').read_text()
  self.assertIn('private var agentToken = ""',s)
  self.assertIn('"agent_endpoint",agentEndpoint',s)
  self.assertNotIn('putString("agent_token"',s)
 def test_relay_limits(self):
  s=(P/'server/ounan_relay.py').read_text()
  for word in ('"store": False','hmac.compare_digest','MAX_BYTES = 128000','TTL = 15 * 60','"127.0.0.1"','"function_call_output"'):
   self.assertIn(word,s)
 def test_history_labels(self):
  s=(S/'AdminHistoryDisplay.kt').read_text()
  self.assertIn('fun title(',s)
  self.assertIn('fun summary(',s)
  self.assertIn('fun details(',s)
