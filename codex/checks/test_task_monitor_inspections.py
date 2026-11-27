"""Inspection dispatch, evidence boundaries and report lifecycle."""
import json
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

REPO=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(REPO/'server'))
from task_monitor import Store, Monitor, decision
from task_monitor_inspections import request, results, Service, inspect_tool, safe_path
from task_monitor_mcp import execute
from task_monitor_control import resolve
from test_task_monitor import Model, turn


class InspectionChecks(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(prefix='inspection-unit-',dir=REPO/'codex')
        self.root=Path(self.tmp.name);self.workspace=self.root/'project';self.workspace.mkdir()
        self.runtime=self.root/'runtime';self.runtime.mkdir()
        self.store=Store(self.runtime/'state.sqlite3')
        self.config={'runtime':str(self.runtime),'monitor_thread':'manager','monitor_socket':'backend','sockets':['backend']}
        (self.runtime/'config.json').write_text(json.dumps(self.config))
        self.context={'thread_id':'source','cwd':str(self.workspace),'history_excerpt':[{'turn_id':'source-turn','status':'completed','user':'修复函数','assistant':'已完成'}]}
        self.context_save()
        (self.workspace/'main.py').write_text('answer = 42\n')

    def tearDown(self):self.store.db.close();self.tmp.cleanup()
    def context_save(self):
        with self.store.db:self.store.db.execute('INSERT OR REPLACE INTO contexts VALUES(?,?)',('source',json.dumps(self.context)))
    def requested(self):return request(self.config,'source','核对实际成果和是否停滞')['inspection_id']
    def running(self):
        ident=self.requested();Service(self.config).change(ident,state='running');return ident

    def test_duplicate_and_different_focus_reuse_active_worker(self):
        a=self.requested();b=request(self.config,'source','另一个重点')
        self.assertEqual(a,b['inspection_id']);self.assertTrue(b['deduplicated'])
    def test_new_source_never_reuses_older_active_inspection(self):
        old=self.requested();self.context['history_excerpt'][-1]['turn_id']='new-source';self.context_save()
        waiting=request(self.config,'source','新回合检查')
        self.assertFalse(waiting['requested']);self.assertEqual(waiting['state'],'previous_source_cleanup_pending')
        Service(self.config).tick()
        new=request(self.config,'source','新回合检查')
        self.assertTrue(new['requested']);self.assertNotEqual(new['inspection_id'],old)

    def test_request_rejects_unobserved_or_outside_scope(self):
        with self.assertRaises(ValueError):request(self.config,'missing','检查')
        with self.assertRaises(ValueError):request({**self.config,'included_projects':['/absent']},'source','检查')
    def test_request_rejects_unfinished_source(self):
        self.context['history_excerpt'][-1]['status']='inProgress';self.context_save()
        with self.assertRaises(ValueError):self.requested()
    def test_failed_source_can_be_inspected_without_retrying_it(self):
        self.context['history_excerpt'][-1]['status']='failed';self.context_save()
        ident=self.running()
        self.assertIn('answer = 42',inspect_tool(self.runtime,ident,'inspection_read',{'path':'main.py'})['content'][0]['text'])

    def test_user_request_requires_live_user_guard(self):
        with patch('task_monitor_mcp.direct_user_only',side_effect=ValueError('background')):
            with self.assertRaises(ValueError):execute(self.runtime,'monitor_request_inspection',{'thread_id':'source','focus':'检查'})
        self.assertEqual(results(self.runtime),[])
    def test_tools_read_actual_text_and_audit_hash(self):
        ident=self.running();value=inspect_tool(self.runtime,ident,'inspection_read',{'path':'main.py'})
        self.assertIn('answer = 42',value['content'][0]['text'])
        self.assertEqual(results(self.runtime,ident)[0]['observations'][0]['path'],'main.py')
        self.assertEqual(len(results(self.runtime,ident)[0]['observations'][0]['sha256']),64)
    def test_context_retains_history_when_command_output_is_null_or_missing(self):
        self.context['source_endpoint']='backend';self.context_save()
        ident=self.running()
        turns=[{'id':'source-turn','items':[
            {'type':'commandExecution','command':'no-output','aggregatedOutput':None,'exitCode':None},
            {'type':'commandExecution','command':'empty-output','aggregatedOutput':'','exitCode':0},
            {'type':'commandExecution','command':'missing-output','exitCode':1},
            {'type':'commandExecution','command':'long-output','aggregatedOutput':'x'*5000,'exitCode':0}]}]
        with patch('task_monitor_inspections.Service.valid',return_value=True), \
             patch('task_monitor_inspections.Rpc') as rpc:
            rpc.return_value.__enter__.return_value.call.return_value={'data':turns}
            value=inspect_tool(self.runtime,ident,'inspection_context',{})
        body=json.loads(value['content'][0]['text'])
        self.assertEqual(body['context']['history_excerpt'],self.context['history_excerpt'])
        self.assertNotIn('source_endpoint',body['context'])
        commands=body['execution_records'][0]['commands']
        self.assertEqual([c['output_available'] for c in commands],[False,True,False,True])
        self.assertEqual([c['output'] for c in commands],['','','','x'*4000])
        self.assertIsNone(commands[0]['exit_code'])
        self.assertEqual(commands[2]['exit_code'],1)
        self.assertEqual(results(self.runtime,ident)[0]['observations'][0]['tool'],'inspection_context')

    def test_context_keeps_bounded_commands_and_chronological_turns(self):
        from task_monitor_inspections import context_execution_records
        records=context_execution_records([
            {'id':'new','items':[{'type':'commandExecution','command':str(i),'aggregatedOutput':None} for i in range(12)]},
            {'id':'old','items':[]}])
        self.assertEqual([r['turn_id'] for r in records],['old','new'])
        self.assertEqual([c['command'] for c in records[1]['commands']],[str(i) for i in range(2,12)])

    def test_internal_failure_keeps_rpc_id_and_does_not_claim_scope_rejection(self):
        from contextlib import redirect_stdout
        from io import StringIO
        from task_monitor_inspection_tools import main
        request={'id':42,'method':'tools/call','params':{'name':'inspection_context','arguments':{}}}
        output=StringIO()
        with patch.object(sys,'argv',['tool','--runtime',str(self.runtime),'--inspection-id','fixture']), \
             patch.object(sys,'stdin',StringIO(json.dumps(request)+'\n')), redirect_stdout(output), \
             patch('task_monitor_inspection_tools.inspect_tool',side_effect=TypeError('sensitive diagnostic')):
            main()
        response=json.loads(output.getvalue())
        self.assertEqual(response['id'],42)
        self.assertTrue(response['result']['isError'])
        body=json.loads(response['result']['content'][0]['text'])
        self.assertEqual(body['code'],'inspection_internal_error')
        self.assertNotIn('sensitive diagnostic',output.getvalue())

    def test_scope_and_backend_failures_remain_distinct(self):
        from task_monitor_inspection_tools import error_result
        from task_monitor_rpc import RpcError
        for error,code in [(ValueError('scope'),'inspection_read_rejected'),
                           (RpcError('backend'),'inspection_unavailable')]:
            self.assertEqual(json.loads(error_result(error)['content'][0]['text'])['code'],code)
    def test_image_returns_actual_image_block(self):
        import base64
        (self.workspace/'image.png').write_bytes(base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jY1sAAAAASUVORK5CYII='))
        content=inspect_tool(self.runtime,self.running(),'inspection_read',{'path':'image.png'})['content']
        self.assertEqual(content[1]['type'],'image');self.assertEqual(content[1]['mimeType'],'image/png')
    def test_path_escape_secret_and_symlink_rejected(self):
        (self.workspace/'escape').symlink_to(self.root)
        for path in ('../outside','/etc/passwd','.env','config/credentials.json','escape/runtime/config.json','.git/config'):
            with self.subTest(path=path),self.assertRaises(ValueError):safe_path(self.workspace,path)
    def test_oversized_binary_and_invalid_arguments_rejected(self):
        ident=self.running();(self.workspace/'binary.bin').write_bytes(b'\0a')
        for name,args in [('inspection_read',{'path':'binary.bin'}),('inspection_read',{'path':'main.py','start_line':True}),('inspection_context',{'command':'rm'}),('monitor_update_preferences',{})]:
            with self.subTest(name=name,args=args),self.assertRaises(ValueError):inspect_tool(self.runtime,ident,name,args)
    def test_changed_source_revokes_worker_reads(self):
        ident=self.running();self.context['history_excerpt'][-1]['turn_id']='new-turn';self.context_save()
        with self.assertRaises(ValueError):inspect_tool(self.runtime,ident,'inspection_read',{'path':'main.py'})
    def test_completed_worker_cannot_keep_reading(self):
        ident=self.running();Service(self.config).change(ident,state='completed')
        with self.assertRaises(ValueError):inspect_tool(self.runtime,ident,'inspection_read',{'path':'main.py'})
    def test_unknown_dispatch_is_not_retried(self):
        ident=self.requested();service=Service(self.config);service.change(ident,state='creating')
        with patch.object(service,'start') as start:service.tick();start.assert_not_called()
        self.assertEqual(results(self.runtime,ident)[0]['state'],'unknown')
    def test_daily_cap_rejects_new_jobs(self):
        with self.assertRaises(ValueError):request({**self.config,'max_inspections_per_day':0},'source','检查')
    def test_persistent_uncapped_inspections_override_the_default_and_config_limit(self):
        from task_monitor_preferences import save,visible
        config={**self.config,'max_inspections_per_day':0}
        saved=save(self.runtime,{'max_inspections_per_day':None},expected_revision=0)
        self.assertEqual(saved['revision'],1)
        self.assertIsNone(visible(config)['preferences']['max_inspections_per_day'])
        self.assertTrue(request(config,'source','检查')['requested'])
    def test_automatic_inspection_rejects_source_handoff_before_any_worker_is_created(self):
        from task_monitor_inspections import InspectionSourceChanged
        with self.assertRaises(InspectionSourceChanged):
            request(self.config,'source','检查','event',expected_source_turn='old-turn')
        self.assertEqual(results(self.runtime),[])
    def test_uncapped_policy_keeps_numeric_legacy_fallback_and_respects_later_user_override(self):
        from task_monitor_preferences import visible,save
        config={**self.config,'max_inspections_per_day':0,'auto_inspections_uncapped':True}
        self.assertIsNone(visible(config)['preferences']['max_inspections_per_day'])
        inspection=request(config,'source','检查')
        self.assertTrue(inspection['requested'])
        Service(config).change(inspection['inspection_id'],state='completed')
        save(self.runtime,{'max_inspections_per_day':0},expected_revision=0)
        self.assertEqual(visible(config)['preferences']['max_inspections_per_day'],0)
        with self.assertRaises(ValueError):request(config,'source','其他检查')
    def test_report_without_observations_is_marked_uncertain(self):
        ident=self.running();service=Service(self.config)
        service.change(ident,worker_thread='worker',worker_turn='wt',endpoint='backend')
        report={'summary':'完成','findings':[],'progress':'progress','evidence':[],'limitations':[]}
        class Connection:
            def __enter__(self):return self
            def __exit__(self,*_):pass
            def call(self,method,params):
                if method=='thread/archive':return {}
                return {'data':[turn('wt',text=json.dumps(report))]}
        service.connect=lambda _:Connection();service.tick()
        value=results(self.runtime,ident)[0]
        self.assertEqual(value['state'],'completed');self.assertEqual(value['report']['progress'],'uncertain')
        self.assertTrue(value['report']['limitations'])
    def test_readonly_diff_uses_fixed_argv(self):
        import subprocess
        ident=self.running()
        with patch('task_monitor_inspections.subprocess.run',side_effect=[subprocess.CompletedProcess([],0,b'11',b''),subprocess.CompletedProcess([],0,b'answer = 0\n',b'')]) as run:
            inspect_tool(self.runtime,ident,'inspection_diff',{'path':'main.py'})
        args,kwargs=run.call_args
        self.assertIn('cat-file',args[0]);self.assertIn('blob',args[0]);self.assertNotIn('shell',kwargs)
    def test_diff_does_not_run_repository_clean_filters(self):
        import subprocess
        def git(*args):return subprocess.run(['git',*args],cwd=self.workspace,check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        git('init','-q');git('add','main.py');git('-c','user.name=fixture','-c','user.email=fixture@example.invalid','commit','-qm','fixture')
        (self.workspace/'.gitattributes').write_text('main.py filter=fixture\n')
        sentinel=self.workspace/'FILTER_EXECUTED'
        git('config','filter.fixture.clean','touch '+str(sentinel))
        (self.workspace/'main.py').write_text('answer = 43\n')
        result=inspect_tool(self.runtime,self.running(),'inspection_diff',{'path':'main.py'})
        self.assertIn('+answer = 43',result['content'][0]['text']);self.assertFalse(sentinel.exists())
    def test_stale_request_is_marked_without_dispatch(self):
        ident=self.requested();self.context['history_excerpt'][-1]['turn_id']='new';self.context_save()
        service=Service(self.config)
        with patch.object(service,'start') as start:service.tick();start.assert_not_called()
        self.assertEqual(results(self.runtime,ident)[0]['state'],'stale')
    def test_scope_revocation_blocks_existing_worker(self):
        ident=self.running();config={**self.config,'ignored_threads':['source']}
        (self.runtime/'config.json').write_text(json.dumps(config))
        with self.assertRaises(ValueError):inspect_tool(self.runtime,ident,'inspection_read',{'path':'main.py'})
    def test_internal_exclusions_cannot_be_inspected(self):
        with self.assertRaises(ValueError):request({**self.config,'excluded_threads':['source']},'source','检查')

    def test_closed_event_requires_completed_inspection_before_explicit_reopen(self):
        self.store.db.execute("INSERT INTO jobs(id,thread_id,turn_id,endpoint,title,payload,phase,created) VALUES('event','source','source-turn','backend','task','{}','done',?)",(time.time(),));self.store.db.commit()
        with self.assertRaises(ValueError):resolve(self.runtime,'event','follow_up','用户要求继续','修复报告指出的问题')
        ident=self.requested()
        self.assertEqual(results(self.runtime,ident)[0]['event_id'],'event')
        with self.assertRaises(ValueError):resolve(self.runtime,'event','follow_up','用户要求继续','修复报告指出的问题')
        Service(self.config).change(ident,state='completed',report=json.dumps({'summary':'发现问题'}))
        self.assertTrue(resolve(self.runtime,'event','follow_up','用户要求继续','修复报告指出的问题')['saved'])
    def test_explicit_inspection_does_not_automatically_reopen_closed_event(self):
        self.store.db.execute("INSERT INTO jobs(id,thread_id,turn_id,endpoint,title,payload,phase,created) VALUES('event','source','source-turn','backend','task','{}','done',?)",(time.time(),));self.store.db.commit()
        ident=self.requested();Service(self.config).change(ident,state='completed')
        self.assertEqual(self.store.db.execute("SELECT phase FROM jobs WHERE id='event'").fetchone()[0],'done')
    def test_closed_event_cannot_use_inspection_of_a_different_source_turn(self):
        ident=self.requested();Service(self.config).change(ident,state='completed',event_id='event')
        self.store.db.execute("INSERT INTO jobs(id,thread_id,turn_id,endpoint,title,payload,phase,created) VALUES('event','source','different-turn','backend','task','{}','done',?)",(time.time(),));self.store.db.commit()
        with self.assertRaises(ValueError):resolve(self.runtime,'event','follow_up','用户要求继续','修复报告指出的问题')

    def test_inspect_decision_requires_specific_focus(self):
        value={'event_id':'event','action':'inspect','summary':'需核对','next_prompt':'比较两轮成果','question':''}
        self.assertEqual(decision(json.dumps(value))['action'],'inspect')
        value['next_prompt']=''
        with self.assertRaises(ValueError):decision(json.dumps(value))


class BackgroundInspectionChecks(unittest.TestCase):
    def test_background_inspect_enters_wait_and_enriches_report(self):
        with tempfile.TemporaryDirectory(prefix='inspection-bg-',dir=REPO/'codex') as root:
            config={'runtime':root,'sockets':['backend'],'monitor_socket':'backend','monitor_thread':'monitor','allow_followups':True}
            model=Model();model.review=json.dumps({'event_id':'','action':'inspect','summary':'核对成果','next_prompt':'看代码和进展','question':''})
            monitor=Monitor(config,model.connect)
            try:
                monitor.since=time.time()-60;monitor.scan();monitor.work();monitor.work();monitor.work()
                job=monitor.store.db.execute('SELECT * FROM jobs').fetchone()
                self.assertEqual(job['phase'],'inspecting')
                inspection=results(root)[0]
                Service(config).change(inspection['id'],state='completed',report=json.dumps({'summary':'确实有进展'}))
                self.assertEqual(monitor.enriched(job)['inspection_report']['report']['summary'],'确实有进展')
                self.assertFalse(any(method=='thread/queue/add' for _,method,_ in model.calls))
            finally:monitor.store.db.close()

    def test_worker_excluded_even_without_task_whitelist(self):
        with tempfile.TemporaryDirectory(prefix='inspection-scan-',dir=REPO/'codex') as root:
            config={'runtime':root,'sockets':['backend'],'monitor_socket':'backend','monitor_thread':'monitor'}
            model=Model();model.threads['worker']={**model.threads['source'],'id':'worker'};model.turns['worker']=[turn('worker-turn')]
            monitor=Monitor(config,model.connect)
            try:
                monitor.since=time.time()-60
                with monitor.store.db:
                    monitor.store.db.execute("INSERT INTO inspections(id,thread_id,source_turn,focus,payload,state,created,updated,worker_thread) VALUES('i','source','source-turn','检查','{}','completed',?,?, 'worker')",(time.time(),time.time()))
                monitor.scan()
                self.assertFalse(monitor.store.db.execute("SELECT 1 FROM jobs WHERE thread_id='worker'").fetchone())
                self.assertFalse(monitor.store.db.execute("SELECT 1 FROM contexts WHERE thread_id='worker'").fetchone())
            finally:monitor.store.db.close()


if __name__=='__main__':unittest.main()
