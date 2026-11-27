"""Mixed dependencies must not stop independently authorized work."""
import json
import hashlib
import sys
import unittest
from test_task_monitor import turn, REPO
import test_task_monitor_review as fixtures

sys.path.insert(0, str(REPO / 'server'))
from task_monitor import decision


def item(state, task='复核现有原件'):
    return {'task': task, 'state': state, 'reason': '已有原件可核对' if state == 'actionable' else '缺少当前人员证明',
            'authorization': '原用户要求完成草稿并验证' if state == 'actionable' else ''}


def result(action, work):
    return {'event_id': '', 'action': action, 'summary': '剩余工作已分类',
            'next_prompt': '核对现有原件；缺失人员证明保留待确认，不猜填' if action in ('follow_up', 'inspect') else '',
            'question': '请补充人员证明' if action == 'ask_user' else '', 'remaining_work': work}


class InventoryChecks(unittest.TestCase):
    # Reuse fixture setup, without inheriting/rerunning its tests.
    setUp = fixtures.ReviewChecks.setUp
    tearDown = fixtures.ReviewChecks.tearDown
    job = fixtures.ReviewChecks.job

    def review(self, value):
        self.model.review = json.dumps(value)
        self.monitor.scan()
        self.monitor.work()
        self.monitor.work()

    def test_mixed_dependencies_cannot_stop_or_close_task(self):
        for action in ('ask_user', 'done'):
            with self.assertRaises(ValueError):
                decision(json.dumps(result(action, [item('actionable'), item('blocked', '人员证明')])), True)

    def test_uncertain_dependencies_require_investigation_instead_of_waiting(self):
        for action in ('ask_user', 'done'):
            with self.assertRaises(ValueError):decision(json.dumps(result(action, [item('unknown')])), True)
        self.assertEqual(decision(json.dumps(result('inspect', [item('unknown')])), True)['action'], 'inspect')

    def test_all_remaining_work_really_blocked_can_ask(self):
        self.review(result('ask_user', [item('blocked')]))
        self.monitor.work()
        self.assertEqual(self.job()['phase'], 'needs_attention')

    def test_partial_blocker_allows_followup_without_filling_missing_facts(self):
        self.review(result('follow_up', [item('actionable'), item('blocked', '人员证明')]))
        self.monitor.work()
        self.assertEqual(self.job()['phase'], 'followup_queued')
        self.assertIn('不猜填', self.model.queue[0]['input'][0]['text'])
        self.assertIsNone(self.monitor.store.db.execute('SELECT * FROM questions').fetchone())

    def test_inconsistent_live_review_retries_readonly_without_question_or_original_work(self):
        self.review(result('ask_user', [item('actionable'), item('blocked')]))
        self.assertEqual(self.job()['phase'], 'retry_wait')
        self.assertEqual(self.model.queue, [])
        self.assertIsNone(self.monitor.store.db.execute('SELECT * FROM questions').fetchone())

    def test_new_contract_rejects_missing_inventory_but_accepts_inflight_legacy_review(self):
        self.model.review = json.dumps(result('done', []))
        self.monitor.scan(); self.monitor.work()
        v = json.loads(self.model.turns['reviewer'][0]['items'][-1]['text']);v.pop('remaining_work')
        self.model.turns['reviewer'][0]['items'][-1]['text'] = json.dumps(v)
        self.monitor.work();self.assertEqual(self.job()['phase'], 'retry_wait')
        self.monitor.store.change(self.job(), phase='reviewing', review_contract=None)
        self.monitor.work();self.assertEqual(self.job()['phase'], 'decided')

    def test_empty_blocker_or_unauthorized_next_step_is_invalid(self):
        for value in (result('ask_user', []), result('follow_up', [item('blocked')])):
            with self.assertRaises(ValueError):decision(json.dumps(value), True)
        value = result('follow_up', [item('actionable')]);value['remaining_work'][0]['authorization']=''
        with self.assertRaises(ValueError):decision(json.dumps(value), True)

    def test_malformed_or_unbounded_inventory_is_invalid(self):
        for work in ({}, ['todo'], [dict(item('blocked'), reason='')], [item('blocked')] * 81):
            with self.assertRaises(ValueError):decision(json.dumps(result('inspect', work)), True)

    def test_finished_task_stays_finished(self):
        self.review(result('done', []));self.monitor.work()
        self.assertEqual(self.job()['phase'], 'done')
        self.assertEqual(self.model.queue, [])

    def test_inventory_carried_across_turns_with_source_identity(self):
        self.review(result('follow_up', [item('actionable'), item('blocked')]))
        self.monitor.work();self.monitor.work()
        self.model.turns['source'].insert(0, turn('new-result', text='原件核对完，但缺证明'))
        self.monitor.scan()
        job = self.monitor.store.db.execute("SELECT * FROM jobs WHERE turn_id='new-result'").fetchone()
        prior = self.monitor.enriched(job)['prior_work_inventory']
        self.assertEqual(prior[0]['source_turn'], 'source-turn')
        self.assertEqual(len(prior[0]['remaining_work']), 2)

    def test_native_wait_is_not_bypassed_even_with_actionable_work(self):
        self.review(result('follow_up', [item('actionable'), item('blocked')]))
        self.model.threads['source']['status']={'type':'active','activeFlags':['waitingOnUserInput']}
        self.monitor.work()
        self.assertEqual(self.model.queue, [])
        self.assertNotEqual(self.job()['phase'], 'followed_up')

    def test_real_user_decision_keeps_legacy_command_contract(self):
        self.monitor.scan()
        self.monitor.store.change(self.job(),phase='needs_attention',result=json.dumps(result('ask_user',[item('blocked')])))
        self.monitor.accept_answer(self.job(), '暂不处理')
        self.monitor.recover_reviews()
        self.assertEqual(self.job()['phase'], 'done')

    def test_upgrade_does_not_reopen_existing_user_question(self):
        self.monitor.scan()
        payload = self.monitor.enriched(self.job())
        self.assertNotIn('prior_work_inventory', payload)
        fingerprint = hashlib.sha256(json.dumps(payload,sort_keys=True,ensure_ascii=False).encode()).hexdigest()
        legacy = result('ask_user', [item('blocked')]);legacy.pop('remaining_work')
        self.monitor.store.change(self.job(),phase='needs_attention',context_hash=fingerprint,result=json.dumps(legacy))
        self.monitor.reconcile_attention()
        self.assertEqual(self.job()['phase'],'needs_attention')

    def test_loaded_idle_reviewer_gets_new_developer_rules_once(self):
        self.monitor.scan();self.monitor.work()
        updates=[p for _,m,p in self.model.calls if m=='thread/resume' and p.get('threadId')=='reviewer']
        self.assertEqual(len(updates),1)
        self.assertIn('部分工作缺资料不代表整项任务阻塞',updates[0]['developerInstructions'])
        with self.model.connect('source-socket') as rpc:self.monitor.ensure_reviewer(rpc)
        self.assertEqual(sum(m=='thread/resume' and p.get('threadId')=='reviewer' for _,m,p in self.model.calls),1)


if __name__ == '__main__':unittest.main()
