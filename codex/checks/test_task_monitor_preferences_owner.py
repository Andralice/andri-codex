"""Preference authorization follows the live owner and never trusts completed snapshots."""

import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / 'server'))
from task_monitor import Store
from task_monitor_mcp import ControlError, direct_user_only, execute, error_result
from task_monitor_preferences import visible, PreferenceConflict, PreferenceValidationError
from task_monitor_questions import MARKER


class Backends:
    def __init__(self):
        self.loaded = {'desktop': False, 'mobile': True}
        self.turns = {name: {'id': 'live-user' if name == 'mobile' else 'old-snapshot',
                          'status': 'inProgress' if name == 'mobile' else 'completed',
                          'items': [{'type': 'userMessage', 'content': [{'type': 'text', 'text': '只跟进未解决问题，保存这个偏好'}]}]}
                      for name in self.loaded}
        self.unavailable = set()
        self.calls = []
        self.recheck_hook = None

    def connect(self, endpoint):
        model = self
        class Connection:
            def __enter__(self):
                if endpoint in model.unavailable:raise ConnectionError('unavailable')
                return self
            def __exit__(self, *_):pass
            def call(self, method, params):
                model.calls.append((endpoint, method))
                if method == 'thread/loaded/list':
                    if model.recheck_hook and len([c for c in model.calls if c == ('desktop', 'thread/loaded/list')]) == 2:
                        model.recheck_hook()
                    return {'data': ['manager'] if model.loaded[endpoint] else [], 'nextCursor': None}
                if method == 'thread/read':return {'thread': {'status': {'type': 'active' if model.loaded[endpoint] else 'notLoaded'}}}
                if method == 'thread/turns/list':return {'data': [model.turns[endpoint]]}
                raise AssertionError(method)
        return Connection()


class OwnerChecks(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='monitor-owner-', dir=REPO / 'codex')
        self.root = Path(self.tmp.name)
        self.store = Store(self.root / 'state.sqlite3')
        self.backends = Backends()
        self.config = {'runtime': str(self.root), 'monitor_thread': 'manager', 'monitor_socket': 'desktop', 'sockets': ['desktop', 'mobile']}
        (self.root / 'config.json').write_text(json.dumps(self.config))
        self.mock = patch('task_monitor_mcp.Rpc', self.backends.connect)
        self.mock.start()

    def tearDown(self):
        self.mock.stop();self.store.db.close();self.tmp.cleanup()

    def save(self):
        return execute(self.root, 'monitor_update_preferences', {'changes': {'decision_guidelines': '仅跟进未解决问题'},
                                                               'expected_revision': 0, 'reason': '明确用户指令'})

    def assert_blocked(self, code):
        with self.assertRaises(ControlError) as ctx:self.save()
        self.assertEqual(ctx.exception.code, code)
        self.assertFalse((self.root / 'preferences.json').exists())

    def test_mobile_owner_accepts_genuine_turn_while_desktop_only_has_completed_snapshot(self):
        result = self.save()
        self.assertTrue(result['saved'])
        self.assertEqual(result['authorization_turn_id'], 'live-user')
        self.assertEqual(result['revision'], 1)
        self.assertEqual(visible(self.config)['preferences']['decision_guidelines'], '仅跟进未解决问题')
        self.assertNotIn(('desktop', 'thread/turns/list'), self.backends.calls)

    def test_desktop_owner_still_works_after_handoff(self):
        self.backends.loaded = {'desktop': True, 'mobile': False}
        self.backends.turns['desktop']['status'] = 'inProgress'
        self.assertTrue(self.save()['saved'])

    def test_no_owner_refuses_completed_disk_snapshot(self):
        self.backends.loaded = {'desktop': False, 'mobile': False}
        self.assert_blocked('manager_unloaded')

    def test_ambiguous_ownership_refuses_write(self):
        self.backends.loaded = {'desktop': True, 'mobile': True}
        self.assert_blocked('owner_ambiguous')

    def test_unavailable_observer_refuses_write(self):
        self.backends.unavailable.add('desktop')
        self.assert_blocked('backend_unavailable')

    def test_completed_turn_on_actual_owner_cannot_authorize(self):
        self.backends.turns['mobile']['status'] = 'completed'
        self.assert_blocked('no_current_user_turn')

    def test_empty_current_user_cannot_authorize(self):
        self.backends.turns['mobile']['items'] = []
        self.assert_blocked('no_current_user_turn')

    def test_background_markers_still_refuse_on_actual_mobile_owner(self):
        for marker in [MARKER, '监控事件编号：test', '【任务管家自动跟进；只读检查】']:
            with self.subTest(marker=marker):
                self.backends.turns['mobile']['items'][0]['content'][0]['text'] = marker
                self.assert_blocked('background_not_authorized')

    def test_registered_notice_without_marker_still_refuses(self):
        with self.store.db:
            self.store.db.execute("INSERT INTO questions(event_id,state,prompt,created,turn_id) VALUES('event','shown','notice',0,'live-user')")
        self.assert_blocked('background_not_authorized')

    def test_owner_handoff_during_validation_refuses_write(self):
        self.backends.recheck_hook = lambda: self.backends.loaded.update({'desktop': True, 'mobile': False})
        self.assert_blocked('owner_changed')

    def test_user_turn_change_during_validation_refuses_write(self):
        self.backends.recheck_hook = lambda: self.backends.turns['mobile'].update({'id': 'new-user'})
        self.assert_blocked('owner_changed')

    def test_revision_conflict_has_distinct_error_and_preserves_saved_value(self):
        self.save()
        with self.assertRaises(PreferenceConflict) as ctx:self.save()
        self.assertEqual(error_result(ctx.exception)['code'], 'preference_conflict')
        self.assertEqual(visible(self.config)['revision'], 1)

    def test_error_diagnostics_do_not_mislabel_generic_valueerror(self):
        self.assertEqual(error_result(ValueError('sensitive internal details'))['code'], 'operation_rejected')
        self.assertNotIn('sensitive', json.dumps(error_result(ValueError('sensitive internal details'))))
        self.assertEqual(error_result(PreferenceValidationError('invalid field'))['code'], 'invalid_preference_value')
        self.assertEqual(error_result(ControlError('owner_changed'))['code'], 'owner_changed')


if __name__ == '__main__':unittest.main()
