from pathlib import Path
import unittest
R = Path(__file__).resolve().parents[1]
S = (R / 'app/src/main/java/com/ounan/knowledge/MainActivity.kt').read_text()
C = (R / 'app/src/main/java/com/ounan/knowledge/GptRelayClient.kt').read_text()

class GptErrorVisibility(unittest.TestCase):
    def test_answer_view_attached_to_layout(self):
        self.assertIn('chat.addView(agentAnswer)', S)
        self.assertLess(S.index('agentAnswer = text('), S.index('chat.addView(agentAnswer)'))
        self.assertIn('setTextIsSelectable(true)', S)
    def test_network_failure_details_displayed(self):
        self.assertIn('detail.startsWith("服务器返回 HTTP ")', S)
        self.assertIn('agentAnswer?.text = if (detail.isNotBlank()) "错误详情：$detail"', S)
        self.assertIn('HTTP $code', C)
        self.assertIn('JSONObject(value).optString("error")', C)
    def test_successful_response_visible(self):
        self.assertIn('val answerText = data.optString("answer"', S)
        self.assertIn('agentAnswer?.text=if (agentToolCount == 0 && agentDbRequested)', S)
    def test_no_bundled_api_secret(self):
        self.assertNotIn('sk-proj-', S)
        self.assertEqual('com.ounan.secondbrain', __import__('re').search(r'applicationId = "([^"]+)"', (R / 'app/build.gradle.kts').read_text()).group(1))
