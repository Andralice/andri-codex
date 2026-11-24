"""Policy invariants; live app-server waiting is checked by the local fixture."""
import importlib.util
from pathlib import Path
import sys
import unittest

SOURCE = Path(__file__).resolve().parents[2] / "server" / "question_policy.py"
spec = importlib.util.spec_from_file_location("question_policy", SOURCE)
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)
sys.modules["question_policy"] = policy
restart_spec = importlib.util.spec_from_file_location("restart_when_idle", SOURCE.with_name("restart_when_idle.py"))
restart = importlib.util.module_from_spec(restart_spec)
restart_spec.loader.exec_module(restart)


class QuestionPolicyChecks(unittest.TestCase):
    def test_removes_both_aliases_and_preserves_other_capabilities(self):
        original = {"models": [{"slug": "fixture", "instructions": "unchanged", "experimental_supported_tools": [
            "clock", "send_user_message_async", "request_user_input_async", "other_tool",
        ]}]}
        actual = policy.blocking_catalog(original)
        self.assertEqual(actual["models"][0]["experimental_supported_tools"], ["clock", "other_tool"])
        self.assertEqual(actual["models"][0]["instructions"], "unchanged")
        self.assertEqual(len(original["models"][0]["experimental_supported_tools"]), 4)

    def test_rejects_empty_catalog_instead_of_changing_model_defaults(self):
        with self.assertRaises(ValueError):
            policy.blocking_catalog({"models": []})

    def test_explicitly_enables_waiting_tool_in_default_mode(self):
        edits = {item["keyPath"]: item["value"] for item in policy.policy_edits(SOURCE)}
        self.assertTrue(edits["features.default_mode_request_user_input"])
        self.assertTrue(edits["tools.experimental_request_user_input.enabled"])

    def test_restart_waits_for_tasks_and_unanswered_questions(self):
        for flags in ([], ["waitingOnUserInput"]):
            class Connection:
                def call(self, method, params):
                    if method == "thread/loaded/list":
                        return {"data": ["running"], "nextCursor": None}
                    return {"thread": {"status": {"type": "active", "activeFlags": flags}}}
            self.assertFalse(restart.all_threads_idle(Connection()))

    def test_restart_checks_every_page_of_loaded_threads(self):
        class Connection:
            def call(self, method, params):
                if method == "thread/loaded/list":
                    return ({"data": ["idle"], "nextCursor": "page2"} if not params["cursor"]
                            else {"data": ["running"], "nextCursor": None})
                return {"thread": {"status": {"type": "idle" if params["threadId"] == "idle" else "active"}}}
        self.assertFalse(restart.all_threads_idle(Connection()))

    def test_restart_read_failure_does_not_count_as_idle(self):
        class Connection:
            def call(self, method, params):
                raise ConnectionError("connection lost")
        with self.assertRaises(ConnectionError):
            restart.all_threads_idle(Connection())


if __name__ == "__main__":
    unittest.main()
