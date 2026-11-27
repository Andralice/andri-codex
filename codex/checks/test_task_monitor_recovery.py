"""Persistent recovery of read-only review failures; execution and user holds stay intact."""
import json
from pathlib import Path
import sys
import tempfile
import time
import unittest

REPO=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(REPO/'server'))
from task_monitor import Monitor
from test_task_monitor import Model, turn
from task_monitor_inspections import request, Service
from task_monitor_preferences import save


class RecoveryChecks(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='recovery-unit-',dir=REPO/'codex')
        self.model=Model()
        self.config={'runtime':self.temp.name,'sockets':['source-socket','other'],
                     'monitor_socket':'source-socket','monitor_thread':'monitor','allow_followups':True}
        self.monitor=Monitor(self.config,self.model.connect)
        self.monitor.since=time.time()-60
    def tearDown(self):self.monitor.store.db.close();self.temp.cleanup()
    def job(self):return self.monitor.store.db.execute('SELECT * FROM jobs ORDER BY created LIMIT 1').fetchone()
    def start_review(self):self.monitor.scan();self.monitor.work();self.assertEqual(self.job()['phase'],'reviewing')
    def due(self):
        with self.monitor.store.db:self.monitor.store.db.execute('UPDATE review_recovery SET retry_at=0')
        self.monitor.recover_reviews()

    def test_failed_review_waits_and_retries_with_distinct_dispatch_identity_after_restart(self):
        self.start_review()
        first=next(p['clientUserMessageId'] for _,m,p in self.model.calls if m=='turn/start')
        self.model.turns['reviewer'][0]['status']='failed'
        self.monitor.work()
        self.assertEqual(self.job()['phase'],'retry_wait')
        calls=len(self.model.calls);self.monitor.recover_reviews();self.monitor.work()
        self.assertEqual(len(self.model.calls),calls)
        self.monitor.store.db.close();self.monitor=Monitor(self.config,self.model.connect)
        self.due();self.monitor.work();self.monitor.work();self.monitor.work()
        self.assertEqual(self.job()['phase'],'followup_queued')
        review_calls=[p for _,m,p in self.model.calls if m=='turn/start']
        self.assertNotEqual(first,review_calls[-1]['clientUserMessageId'])
        self.assertIn('review_recovery',review_calls[-1]['input'][0]['text'])
        self.assertEqual(sum(m=='thread/queue/add' for _,m,_ in self.model.calls),1)

    def test_invalid_result_can_recover_to_done_without_starting_original_work(self):
        self.start_review();self.model.turns['reviewer'][0]['items'][-1]['text']='not JSON'
        self.monitor.work();self.assertEqual(self.job()['phase'],'retry_wait')
        self.model.review=json.dumps({'event_id':'','action':'done','summary':'原请求已完成','next_prompt':'','question':''})
        self.due();self.monitor.work();self.monitor.work();self.monitor.work()
        self.assertEqual(self.job()['phase'],'done')
        self.assertEqual(self.model.queue,[])

    def test_backoff_grows_and_caps_frequency_without_a_permanent_attempt_limit(self):
        self.monitor.scan();job=self.job()
        delays=[]
        for _ in range(8):
            before=time.time();self.monitor.retry_review(job,'invalid_review_output')
            row=self.monitor.store.db.execute('SELECT * FROM review_recovery').fetchone()
            delays.append(round(row['retry_at']-before))
        self.assertEqual(delays,[60,120,240,480,960,1800,1800,1800])
        self.assertEqual(row['attempts'],8)
        self.assertEqual(self.job()['phase'],'retry_wait')

    def test_one_failed_event_does_not_block_unrelated_pending_events(self):
        self.start_review();self.model.turns['reviewer'][0]['status']='failed';self.monitor.work()
        self.model.threads['another']={**self.model.threads['source'],'id':'another'}
        self.model.turns['another']=[turn('another-turn')]
        self.monitor.scan();self.monitor.work()
        self.assertEqual(self.job()['phase'],'retry_wait')
        other=self.monitor.store.db.execute("SELECT phase FROM jobs WHERE thread_id='another'").fetchone()
        self.assertEqual(other[0],'reviewing')

    def test_newer_source_supersedes_failed_event_instead_of_replaying_it(self):
        self.start_review();self.model.turns['reviewer'][0]['status']='failed';self.monitor.work()
        self.model.turns['source'].insert(0,turn('new-user-turn'))
        self.monitor.scan();self.due()
        self.assertEqual(self.job()['phase'],'superseded')
        self.assertEqual(self.model.queue,[])

    def test_source_waiting_for_user_blocks_automatic_recovery(self):
        self.start_review();self.model.turns['reviewer'][0]['status']='failed';self.monitor.work()
        with self.monitor.store.db:self.monitor.store.db.execute("INSERT INTO attention VALUES('source','[\"waitingOnUserInput\"]',?)",(time.time(),))
        self.due()
        self.assertEqual(self.job()['phase'],'retry_wait')
        self.assertEqual(self.model.queue,[])

    def test_manager_question_is_never_recovered_as_an_internal_failure(self):
        self.monitor.scan();job=self.job()
        decision={'event_id':job['id'],'action':'ask_user','summary':'需要决定','question':'是否发布？','next_prompt':''}
        self.monitor.store.change(job,phase='needs_attention',note='invalid_review_output',result=json.dumps(decision))
        self.monitor.recover_reviews()
        self.assertEqual(self.job()['phase'],'needs_attention')
        self.assertIsNone(self.monitor.store.db.execute('SELECT * FROM review_recovery').fetchone())

    def test_native_question_hold_prevents_recovering_legacy_error(self):
        self.monitor.scan();job=self.job()
        self.monitor.store.change(job,phase='needs_attention',note='invalid_review_output')
        with self.monitor.store.db:self.monitor.store.db.execute("INSERT INTO questions(event_id,state,prompt,created) VALUES(?,'shown','native hold',?)",(job['id'],time.time()))
        self.monitor.recover_reviews()
        self.assertEqual(self.job()['phase'],'needs_attention')

    def test_unknown_execution_ack_and_original_failure_are_not_retried(self):
        self.monitor.scan()
        for reason in ('followup_delivery_unknown_no_retry','followup_start_unknown_no_retry','original_turn_not_successful','human_decision'):
            self.monitor.store.change(self.job(),phase='needs_attention',note=reason)
            self.monitor.recover_reviews()
            self.assertEqual(self.job()['phase'],'needs_attention')
        self.assertEqual(self.model.queue,[])

    def test_legacy_error_is_adopted_but_completed_event_is_never_reopened(self):
        self.monitor.scan();self.monitor.store.change(self.job(),phase='needs_attention',note='invalid_review_output')
        self.monitor.recover_reviews();self.assertEqual(self.job()['phase'],'retry_wait')
        self.monitor.store.change(self.job(),phase='done',note='invalid_review_output')
        self.due();self.assertEqual(self.job()['phase'],'done')

    def test_scope_change_prevents_recovery(self):
        self.start_review();self.model.turns['reviewer'][0]['status']='failed';self.monitor.work()
        self.monitor.config['ignored_threads']=['source']
        self.due();self.assertEqual(self.job()['phase'],'ignored')

    def test_stuck_readonly_reviewer_is_interrupted_by_exact_id_then_retried(self):
        self.start_review();self.model.turns['reviewer'][0]['status']='inProgress'
        self.monitor.store.change(self.job(),sent=time.time()-601)
        self.monitor.work()
        interrupted=[p for _,m,p in self.model.calls if m=='turn/interrupt']
        self.assertEqual(interrupted,[{'threadId':'reviewer','turnId':'review-turn'}])
        self.monitor.work();self.assertEqual(self.job()['phase'],'retry_wait')

    def test_manager_conversation_is_not_interrupted_by_review_watchdog(self):
        self.start_review();review=self.model.turns['reviewer'][0];review['status']='inProgress'
        self.model.turns['monitor']=[review]
        self.monitor.store.change(self.job(),review_thread='monitor',sent=time.time()-601)
        self.monitor.work()
        self.assertFalse(any(m=='turn/interrupt' for _,m,_ in self.model.calls))

    def test_existing_report_is_reused_in_corrective_review_instead_of_redelegation(self):
        self.start_review();self.monitor.work()
        inspection=request(self.config,'source','已有检查',self.job()['id'])
        report={'summary':'有未完成项','findings':[],'evidence':[],'limitations':[],'progress':'uncertain'}
        Service(self.config).change(inspection['inspection_id'],state='completed',report=json.dumps(report))
        value={'event_id':self.job()['id'],'action':'inspect','summary':'又要求检查','next_prompt':'重复检查','question':''}
        self.monitor.store.change(self.job(),result=json.dumps(value))
        self.monitor.work();self.assertEqual(self.job()['phase'],'retry_wait')
        self.assertEqual(self.monitor.store.db.execute('SELECT COUNT(*) FROM inspections').fetchone()[0],1)
        self.due();self.monitor.work()
        prompt=[p['input'][0]['text'] for _,m,p in self.model.calls if m=='turn/start'][-1]
        self.assertIn('有未完成项',prompt)
        self.assertIn('inspection_already_returned_use_existing_evidence',prompt)

    def test_stale_inspection_decision_cannot_bind_to_a_newer_source(self):
        self.start_review();self.monitor.work()
        self.model.turns['source'].insert(0,turn('new-user-turn'));self.monitor.scan()
        value={'event_id':self.job()['id'],'action':'inspect','summary':'检查','next_prompt':'检查成果','question':''}
        self.monitor.store.change(self.job(),result=json.dumps(value))
        self.monitor.work();self.assertEqual(self.job()['phase'],'superseded')
        self.assertEqual(self.monitor.store.db.execute('SELECT COUNT(*) FROM inspections').fetchone()[0],0)

    def test_paused_monitor_does_not_release_due_recovery(self):
        self.start_review();self.model.turns['reviewer'][0]['status']='failed';self.monitor.work()
        save(self.temp.name,{'enabled':False})
        with self.monitor.store.db:self.monitor.store.db.execute('UPDATE review_recovery SET retry_at=0')
        self.monitor.tick();self.assertEqual(self.job()['phase'],'retry_wait')


if __name__=='__main__':unittest.main()
