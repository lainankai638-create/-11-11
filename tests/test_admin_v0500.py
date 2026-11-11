from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/com/ounan/knowledge'
class ManagerChecks(unittest.TestCase):
 def test_app_identity_and_offline(self):
  gradle = (ROOT/'app/build.gradle.kts').read_text()
  manifest = (ROOT/'app/src/main/AndroidManifest.xml').read_text()
  self.assertIn('versionName = "0.5.1.2"',gradle)
  self.assertIn('applicationId = "com.ounan.secondbrain"',gradle)
  self.assertIn('android.permission.INTERNET', manifest)
 def test_three_sources_and_commands(self):
  admin = (JAVA/'LocalKnowledgeAdmin.kt').read_text()
  for key in ('const val NOTES', 'const val WECHAT', 'const val SCREENSHOTS', 'fun search(', 'fun get(', 'fun create(', 'fun edit(', 'fun delete(', 'fun undo('):
   self.assertIn(key,admin)
  self.assertNotIn('execSQL(',admin.split('internal class LocalKnowledgeAdmin',1)[1])
 def test_history_encrypted_and_reversible(self):
  admin = (JAVA/'LocalKnowledgeAdmin.kt').read_text()
  for key in ('ArchiveCrypto.seal(it.json())','ArchiveCrypto.open(s)', '"pending"','"applied"','"undone"','current != change.next','journal.begin','journal.applied','journal.markUndone'):
   self.assertIn(key,admin)
 def test_restore_original_ids(self):
  for name,table in [('KnowledgeDb.kt','notes'),('WeChatArchive.kt','excerpts'),('ChatBatchArchive.kt','chats')]:
   src=(JAVA/name).read_text()
   self.assertIn('fun restore(',src)
   self.assertIn('put("id",',src)
   self.assertIn('SQLiteDatabase.CONFLICT_IGNORE',src)
 def test_admin_interface(self):
  s=(JAVA/'MainActivity.kt').read_text()
  for key in ('"管理" to "admin"','"admin" -> showAdmin(content)','private fun showAdmin(', 'private fun showAdminHistory(', 'private fun openAdminEditor(', 'private fun chooseAdminCreate(', 'admin.undo(h.id)'):
   self.assertIn(key,s)
 def test_legacy_delete_uses_history(self):
  s=(JAVA/'MainActivity.kt').read_text()
  for key in ('admin.delete(LocalKnowledgeAdmin.NOTES, note.id)', 'admin.delete(LocalKnowledgeAdmin.WECHAT, item.id)','admin.delete(LocalKnowledgeAdmin.SCREENSHOTS, entry.id)'):
   self.assertIn(key,s)
if __name__=='__main__': unittest.main()
