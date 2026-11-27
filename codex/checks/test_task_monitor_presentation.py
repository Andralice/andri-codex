"""Monitor UI provenance, read-only dashboard and compatibility boundaries."""
from concurrent.futures import Future
import json
from pathlib import Path
import sys
import tempfile
import time
import unittest
import uuid

REPO=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(REPO/'server'))
from task_monitor import Store
from task_monitor_presentation import MonitorPresentation, FOLLOWUP_PREFIX, NOTICE_PREFIX
from thread_gateway import Session


class ProjectionChecks(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='manager-ui-',dir=REPO/'codex')
        self.runtime=Path(self.temp.name);self.store=Store(self.runtime/'state.sqlite3')
        (self.runtime/'config.json').write_text(json.dumps({'runtime':str(self.runtime),'monitor_thread':'manager'}))
        (self.runtime/'status.json').write_text(json.dumps({'updated_at':time.time(),'enabled':True,'inspections':{}}))
        result={'action':'follow_up','summary':'需要补充验证','next_prompt':'核对测试报告，不要重复修改。','question':''}
        self.result=result
        with self.store.db:
            self.store.db.execute("INSERT INTO jobs(id,thread_id,turn_id,endpoint,title,payload,phase,created,followup_turn,result,review_thread,review_turn,review_token) VALUES('event','source','source-turn','backend','原任务','{}','followed_up',?,'follow-turn',?,'manager','review-turn','review-token')",(time.time(),json.dumps(result)))
        self.view=MonitorPresentation(self.runtime)
    def tearDown(self):self.store.db.close();self.temp.cleanup()
    def item(self,text,id='user'):return {'id':id,'type':'userMessage','content':[{'type':'text','text':text}]}
    def turn(self,text,tid='follow-turn'):return {'id':tid,'status':'completed','items':[self.item(text),{'id':'answer','type':'agentMessage','text':'测试通过'}]}
    def prompt(self):return FOLLOWUP_PREFIX+'\n监控事件：event\n'+self.result['next_prompt']+'\n保留原任务的权限和审批规则；不要扩大范围。已有用户问题或审批时等待用户，不得代答。\n完成后如实报告结果和验证边界。'

    def test_registered_followup_is_tagged_without_rewriting_raw_text(self):
        original=self.turn(self.prompt());result=self.view.tag_turn('source',original)
        self.assertEqual(result['andriOrigin']['kind'],'monitor_followup')
        self.assertEqual(result['items'][0]['andriOrigin']['kind'],'monitor_followup')
        self.assertEqual(result['items'][0]['content'],original['items'][0]['content'])
        self.assertNotIn('andriOrigin',original)
    def test_similar_user_text_never_authorizes_a_service_tag(self):
        for tid in ('ordinary-turn','another-turn'):
            result=self.view.tag_turn('source',self.turn(self.prompt(),tid))
            self.assertNotIn('andriOrigin',result)
        self.assertNotIn('andriOrigin',self.view.tag_turn('other',self.turn(self.prompt())))
    def test_user_steering_stays_user_authored_in_an_automatic_turn(self):
        turn=self.turn(self.prompt());turn['items'].insert(1,self.item('先不要改，给我看方案','real-user'))
        result=self.view.tag_turn('source',turn)
        self.assertNotIn('andriOrigin',result['items'][1])
    def test_prefixed_but_modified_user_steering_keeps_its_identity(self):
        turn=self.turn(self.prompt());turn['items'].insert(1,self.item(self.prompt()+'\n我补充的新要求','real-user'))
        result=self.view.tag_turn('source',turn)
        self.assertNotIn('andriOrigin',result['items'][1])
    def test_dispatch_discovered_inside_projection_cache_window(self):
        self.view.refresh()
        with self.store.db:self.store.db.execute("UPDATE jobs SET phase='followup_starting',followup_turn=NULL")
        result=self.view.tag_turn('source',self.turn(self.prompt(),'new-turn'))
        self.assertEqual(result['andriOrigin']['kind'],'monitor_followup')
    def test_live_dispatch_before_ack_uses_exact_registered_pending_prompt(self):
        with self.store.db:self.store.db.execute("UPDATE jobs SET phase='followup_starting',followup_turn='queue-item'")
        self.assertIsNone(self.view.origin('source','new-turn',[self.item(self.prompt()+'用户自己加的要求')]))
        meta=self.view.origin('source','new-turn',[self.item(self.prompt())]);self.assertEqual(meta['kind'],'monitor_followup')
        delta=self.view.notification({'method':'item/agentMessage/delta','params':{'threadId':'source','turnId':'new-turn','delta':'正在核对'}})
        self.assertEqual(delta['params']['andriOrigin']['kind'],'monitor_followup')
    def test_manager_review_and_real_manager_questions_are_distinct(self):
        actual=self.view.tag_turn('manager',self.turn('真实问题','human-turn'))
        self.assertNotIn('andriOrigin',actual)
        review=self.view.tag_turn('manager',self.turn('后台审查数据','review-turn'))
        self.assertEqual(review['andriOrigin']['kind'],'monitor_review')
    def test_review_pending_token_cannot_match_ordinary_quotes(self):
        with self.store.db:self.store.db.execute("UPDATE jobs SET phase='review_sending',review_turn=NULL")
        self.assertIsNone(self.view.origin('manager','user-turn',[self.item('请解释\n审查请求：review-token\n监控事件编号：event\n')]))
    def test_legacy_review_requires_persisted_event_and_native_service_client_id(self):
        item=self.item('你是晚晚的服务器任务管家，小玖。\n\n监控事件编号：event\n\n{}')
        self.assertIsNone(self.view.origin('manager','old-turn',[item]))
        item['clientId']=str(uuid.uuid5(uuid.NAMESPACE_URL,'event'))
        self.assertEqual(self.view.origin('manager','old-turn',[item])['kind'],'monitor_review')
        self.assertIsNone(self.view.origin('source','old-turn',[item]))
    def test_notice_tag_keeps_native_question_payload(self):
        prompt=NOTICE_PREFIX+'\n事件：event\n等待原生回复'
        with self.store.db:self.store.db.execute("INSERT INTO questions(event_id,state,prompt,created,turn_id) VALUES('event','shown',?,?,'notice-turn')",(prompt,time.time()))
        turn=self.turn(prompt,'notice-turn');turn['items'].append({'type':'agentMessage','id':'question','questions':[{'title':'是否继续？'}]})
        tagged=self.view.tag_turn('manager',turn)
        self.assertEqual(tagged['items'][-1]['questions'],turn['items'][-1]['questions'])
        self.assertEqual(tagged['andriOrigin']['kind'],'monitor_notice')
    def test_history_resume_and_live_share_origin(self):
        turn=self.turn(self.prompt())
        response=self.view.response('thread/resume',{'threadId':'source'},{'result':{'thread':{'id':'source'},'initialTurnsPage':{'data':[turn],'nextCursor':'cursor'}}})
        meta=response['result']['initialTurnsPage']['data'][0]['andriOrigin']
        live=self.view.notification({'method':'item/completed','params':{'threadId':'source','turnId':'follow-turn','item':turn['items'][0]}})
        self.assertEqual(meta,live['params']['item']['andriOrigin'])
        self.assertEqual(response['result']['initialTurnsPage']['nextCursor'],'cursor')
    def test_only_actual_manager_is_marked_even_when_titles_match(self):
        self.assertEqual(self.view.tag_thread({'id':'manager','name':'服务器任务管家'})['andriRole'],'manager')
        self.assertNotIn('andriRole',self.view.tag_thread({'id':'human','name':'服务器任务管家'}))
    def test_overview_explains_dispatch_and_outcome_without_raw_history(self):
        with self.store.db:
            self.store.db.execute("INSERT INTO jobs(id,thread_id,turn_id,endpoint,title,payload,phase,created,result) VALUES('child','source','follow-turn','backend','原任务','{}','done',?,?)",(time.time()+1,json.dumps({'action':'done','summary':'补充验证已返回，真机仍未验收'})))
        result=self.view.overview({'limit':1})
        self.assertTrue(result['available']);self.assertTrue(result['nextCursor'])
        page=self.view.overview({'limit':1,'cursor':result['nextCursor']})
        self.assertEqual(page['entries'][0]['outcome'],'补充验证已返回，真机仍未验收')
        self.assertEqual(page['entries'][0]['followup'],self.result['next_prompt'])
        self.assertNotIn('payload',str(result));self.assertNotIn('endpoint',str(result))
        self.assertEqual(result['followupsStarted'],1)
    def test_read_only_overview_preserves_database_and_preferences(self):
        before=self.store.db.total_changes
        self.view.overview({});self.view.tag_turn('source',self.turn(self.prompt()))
        self.assertEqual(before,self.store.db.total_changes)
        self.assertFalse((self.runtime/'preferences.json').exists())
    def test_unconfigured_gateway_preserves_original_responses(self):
        view=MonitorPresentation();response={'result':{'data':[self.turn(self.prompt())]}}
        self.assertEqual(response,view.response('thread/turns/list',{'threadId':'source'},response))
        self.assertFalse(view.overview({})['available'])
    def test_initialized_gateway_handles_module_query_without_forwarding_or_resuming(self):
        class Client:
            def __init__(self):self.messages=[]
            def send(self,message):self.messages.append(message)
        client=Client();session=Session(client,{},self.runtime);session.initialize_params={}
        session.handle({'id':1,'method':'andri/monitor/overview','params':{}})
        self.assertTrue(client.messages[0]['result']['available']);self.assertEqual(session.backends,{})
    def test_bad_module_parameters_are_rejected(self):
        with self.assertRaises(ValueError):self.view.overview({'limit':True})
        with self.assertRaises(ValueError):self.view.overview({'limit':1000})

    def add_question(self, identity, created, phase='needs_attention', delivery=None):
        result=json.dumps({'action':'ask_user','question':'是否继续 '+identity+'？','summary':'需要用户决定'})
        with self.store.db:
            self.store.db.execute("INSERT INTO jobs(id,thread_id,turn_id,endpoint,title,payload,phase,created,result) VALUES(?,?,?,'backend','原任务','{}',?,?,?)",
                (identity,'source',identity+'-turn',phase,created,result))
            if delivery:
                self.store.db.execute('INSERT INTO questions(event_id,state,prompt,created) VALUES(?,?,?,?)',
                    (identity,delivery,'private service prompt',created))

    def test_pending_questions_are_independent_of_work_history_page(self):
        self.add_question('old-question',1,delivery='queued')
        for i in range(45):
            self.add_question(f'closed-{i}',100+i,phase='done')
        result=self.view.overview({'limit':1})
        self.assertNotEqual(result['entries'][0]['id'],'old-question')
        self.assertEqual(result['inbox']['questionsCount'],1)
        question=result['inbox']['questions'][0]
        self.assertEqual(question['id'],'old-question')
        self.assertEqual(question['delivery'],'queued')
        self.assertNotIn('prompt',question)

    def test_pending_questions_exclude_answered_retracted_and_closed(self):
        for i,state in enumerate(['shown','queued','failed','unknown','answered','retracted']):
            self.add_question(state,i+10,delivery=state)
        self.add_question('closed',99,phase='done',delivery='shown')
        self.add_question('superseded',98,phase='superseded',delivery='shown')
        result=self.view.overview({})
        self.assertEqual(set(result['inbox']['pendingQuestionIds']),{'shown','queued','failed','unknown'})
        self.assertEqual(result['inbox']['questionsCount'],4)
        self.assertFalse(next(r for r in result['entries'] if r['id']=='answered')['question'])

    def test_question_pagination_keeps_equal_timestamp_entries(self):
        for identity in ('a','b','c'):self.add_question(identity,5)
        first=self.view.overview({'inboxLimit':2})['inbox']
        self.assertEqual([r['id'] for r in first['questions']],['c','b'])
        second=self.view.overview({'inboxLimit':2,'questionsCursor':first['questionsNextCursor']})['inbox']
        self.assertEqual([r['id'] for r in second['questions']],['a'])
        self.assertIsNone(second['questionsNextCursor'])
        self.assertEqual(second['questionsCount'],3)

    def test_notifications_skip_routine_done_and_include_blockers_and_followup_closure(self):
        with self.store.db:
            for identity,phase,turn in [('routine','done','unrelated'),('blocked','needs_attention','blocked-turn'),('outcome','done','follow-turn')]:
                self.store.db.execute("INSERT INTO jobs(id,thread_id,turn_id,endpoint,title,payload,phase,created,result) VALUES(?,'source',?,'backend','原任务','{}',?,?,?)",
                    (identity,turn,phase,time.time(),json.dumps({'action':'done' if phase=='done' else 'follow_up','summary':identity})))
        self.add_question('question',1)
        notices=self.view.overview({})['inbox']['notifications']
        self.assertEqual({r['id'] for r in notices},{'event:blocked','event:outcome'})
        self.assertEqual({r['kind'] for r in notices},{'blocked','followup_closed'})
        self.assertTrue(all('不代表' in r['detail'] or '仍需' in r['detail'] for r in notices))

    def test_inspection_notification_exposes_evidence_and_boundaries_without_private_payload(self):
        report={'summary':'证据不足，不能确认完成','findings':['没有产物'],'evidence':['已读 main.py'],'limitations':['未做真机验证']}
        with self.store.db:
            self.store.db.execute("INSERT INTO inspections(id,thread_id,source_turn,event_id,focus,payload,state,created,updated,report) VALUES('inspect','source','old-turn','event','focus',?,'completed',1,2,?)",
                (json.dumps({'source_endpoint':'private','title':'title'}),json.dumps(report)))
        notice=self.view.overview({})['inbox']['notifications'][0]
        self.assertEqual(notice['message'],report['summary'])
        self.assertIn('未做真机验证',notice['detail'])
        self.assertIn('已读 main.py',notice['detail'])
        self.assertIn('不能代表当前进度',notice['detail'])
        self.assertNotIn('source_endpoint',str(notice))
        self.assertNotIn('private',str(notice))

    def test_notifications_have_independent_pagination(self):
        for i in range(3):
            with self.store.db:
                self.store.db.execute("INSERT INTO inspections(id,thread_id,source_turn,focus,payload,state,created,updated) VALUES(?,'source',?,'focus','{}','failed',?,?)",(str(i),str(i),i,i))
        first=self.view.overview({'inboxLimit':1})['inbox']
        second=self.view.overview({'inboxLimit':1,'notificationsCursor':first['notificationsNextCursor']})['inbox']
        self.assertEqual(first['notificationsCount'],3)
        self.assertEqual(first['notifications'][0]['id'],'inspection:2')
        self.assertEqual(second['notifications'][0]['id'],'inspection:1')

    def test_inbox_query_does_not_answer_or_dispatch_questions(self):
        self.add_question('pending',1,delivery='shown')
        before=[tuple(r) for r in self.store.db.execute('SELECT * FROM questions')]
        self.view.overview({})
        self.assertEqual(before,[tuple(r) for r in self.store.db.execute('SELECT * FROM questions')])

    def test_invalid_inbox_parameters_are_rejected(self):
        for params in ({'inboxLimit':True},{'inboxLimit':81},{'questionsCursor':'bad'},
                       {'notificationsCursor':'nan:id'},{'questionsCursor':3}):
            with self.subTest(params=params),self.assertRaises(ValueError):self.view.overview(params)


if __name__=='__main__':unittest.main()
