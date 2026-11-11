from pathlib import Path
import unittest
R=Path(__file__).resolve().parents[1]
W=(R/'cloudflare/worker.js').read_text()
S=(R/'app/src/main/java/com/ounan/knowledge/MainActivity.kt').read_text()
class GptEvidence(unittest.TestCase):
    def test_force_search_on_explicit_db_questions(self):
        self.assertIn("const toolChoice = forcedSearch ? {type:'function', name:'search_records'} : 'auto';",W)
        self.assertIn('forcedSearch = needsDatabaseSearch(payload.question)',W)
        self.assertIn("if (forcedSearch && !result.calls.some(c => c.name === 'search_records'))",W)
    def test_no_forced_search_on_plain_conversation(self):
        self.assertIn("async function callOpenAI(history, env, toolChoice = 'auto')",W)
        self.assertIn('不需要(?:读取|查询',W)
    def test_audit_not_faked_by_no_pending_calls(self):
        self.assertIn('private var agentToolCount = 0',S)
        self.assertIn('agentToolCount += auditLines.size',S)
        self.assertIn('本轮没有执行本地数据库工具',S)
        self.assertIn('本轮本机工具调用记录',S)
        self.assertIn('已完成 · 本轮实际执行 $agentToolCount 次本地工具',S)
    def test_no_change_to_db_names(self):
        self.assertIn('com.ounan.secondbrain',(R/'app/build.gradle.kts').read_text())
