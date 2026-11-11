import json
import unittest
from unittest.mock import patch
import ounan_relay as relay

class RelayTests(unittest.TestCase):
    def setUp(self):
        relay.MEMORY.clear()

    def test_user_question_tools_and_results_roundtrip(self):
        fake1 = {"output":[{"type":"function_call","name":"search_records","call_id":"call_1",
                            "arguments":json.dumps({"query":"测试","source":"all","offset":0,"limit":5})}]}
        fake2 = {"output":[{"type":"message","content":[{"type":"output_text","text":"找到 1 条记录"}]}]}
        with patch.object(relay,"openai_response",side_effect=[fake1,fake2]):
            first=relay.run_turn({"question":"帮我搜索"})
            self.assertEqual(first["calls"][0]["name"],"search_records")
            next=relay.run_turn({"session_id":first["session_id"],"tool_results":[
                {"call_id":"call_1","output":{"ok":True,"results":[{"id":2}]}}]})
            self.assertIn("找到 1 条",next["answer"])
            self.assertFalse(next["calls"])
            self.assertIn("function_call_output",str(relay.MEMORY))

    def test_tool_results_cannot_forge_call_ids(self):
        with patch.object(relay,"openai_response",return_value={"output":[]}):
            first=relay.run_turn({"question":"测试"})
            with self.assertRaises(ValueError):
                relay.run_turn({"session_id":first["session_id"],"tool_results":[
                    {"call_id":"fake","output":{"ok":True}}]})

    def test_reject_unknown_tool(self):
        with patch.object(relay,"openai_response",return_value={"output":[
            {"type":"function_call","name":"run_shell","call_id":"call_1","arguments":"{}"}]}):
            with self.assertRaises(RuntimeError):
                relay.run_turn({"question":"hello"})

if __name__=="__main__": unittest.main()
