"""Real app-server + phone gateway + private MCP inspection chain, local fake model."""
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

REPO=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(REPO/'server'))
from task_monitor import Monitor
from task_monitor_inspections import Service, results
from task_monitor_rpc import Rpc
from thread_gateway import Gateway
from check_task_monitor_mobile_preferences import completed, value

stages={};tool_outputs=[];source_id=''


class Model(BaseHTTPRequestHandler):
    def log_message(self,*_):pass
    def do_POST(self):
        body=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        users=[i for i in body.get('input',[]) if i.get('role')=='user'];content=users[-1].get('content',[]) if users else []
        prompt=content if isinstance(content,str) else '\n'.join(i.get('text','') for i in content)
        stage=stages.get(prompt,0);stages[prompt]=stage+1;code=None
        for i in body.get('input',[]):
            if i.get('type') in ('function_call_output','custom_tool_call_output'):tool_outputs.append(i.get('output'))
        text='已完成全部 fixture 工作。'
        if 'REQUEST_INSPECTION_FIXTURE' in prompt and stage==0:
            code=('const t=ALL_TOOLS.find(t=>t.name.endsWith("monitor_request_inspection"));'
                  'if(!t)throw Error("Missing delegation tool");text(await tools[t.name]({thread_id:'+json.dumps(source_id)+',focus:"核对 main.py 的实际成果和是否停滞"}));')
        elif '检查编号：' in prompt:
            if stage==0:
                code=('for(const [suffix,args] of [["inspection_context",{}],["inspection_read",{path:"main.py"}],["inspection_diff",{path:"main.py"}]])'
                      '{const t=ALL_TOOLS.find(t=>t.name.endsWith(suffix));if(!t)throw Error("Missing evidence tool");text(await tools[t.name](args));}')
            else:
                text=json.dumps({'summary':'实际文件 answer=42，代码有进展；无法证明需求全部验收。','findings':['当前文件与 HEAD 相比由0改为42'],
                    'progress':'progress','evidence':['已调用 inspection_read 和 inspection_diff 读取 main.py，当前为 answer = 42'],
                    'limitations':['本地假模型验证协议链路，不代表真实模型判断能力或手机真机验收。']},ensure_ascii=False)
        elif '监控事件编号：' in prompt:
            event=prompt.split('监控事件编号：',1)[1].split('\n',1)[0]
            payload=json.loads(prompt.split(event+'\n\n',1)[1])
            background='BACKGROUND_INSPECTION_FIXTURE' in payload['history_excerpt'][-1]['user']
            action=('follow_up' if payload.get('inspection_report') else 'inspect') if background else 'done'
            text=json.dumps({'event_id':event,'action':action,'summary':'fixture 独立检查后继续原授权工作' if background else 'fixture 工作已结束',
                'next_prompt':'核对 main.py 的实际成果和是否停滞' if action=='inspect' else ('完成原授权修复并报告' if action=='follow_up' else ''),'question':'',
                'remaining_work':[] if action=='done' else [{'task':'修复及验收main.py','state':'unknown' if action=='inspect' else 'actionable',
                    'reason':'需核对成果' if action=='inspect' else '独立检查已返回可行下一步','authorization':'原用户要求完成修复及验收'}]})
        elif 'BACKGROUND_INSPECTION_FIXTURE' in prompt:
            text='修复已做，实际成果和进展需要独立核对。'
        item=({'type':'custom_tool_call','id':'ct_'+str(stage),'call_id':'call_'+str(stage),'name':'exec','namespace':'functions','input':code,'status':'completed'} if code else
            {'type':'message','id':'msg_'+str(stage),'role':'assistant','status':'completed','content':[{'type':'output_text','text':text,'annotations':[]}]})
        response={'id':'resp_fixture','object':'response','created_at':int(time.time()),'model':body['model'],'status':'completed','output':[item],
                  'usage':{'input_tokens':10,'output_tokens':10,'total_tokens':20}}
        events=[('response.created',{'type':'response.created','response':{**response,'status':'in_progress','output':[]}}),
                ('response.output_item.added',{'type':'response.output_item.added','output_index':0,'item':item}),
                ('response.output_item.done',{'type':'response.output_item.done','output_index':0,'item':item}),
                ('response.completed',{'type':'response.completed','response':response})]
        self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers()
        try:
            for event,data in events:self.wfile.write(('event: '+event+'\ndata: '+json.dumps(data)+'\n\n').encode());self.wfile.flush()
        except (BrokenPipeError,ConnectionResetError):pass


def main():
    global source_id
    checks={};processes=[];monitor=gateway=client=None;threads=[]
    tmp=tempfile.TemporaryDirectory(prefix='inspection-live-',dir=REPO/'codex');root=Path(tmp.name)
    report=REPO/'codex/task-monitor/inspection-check-result.json'
    server=ThreadingHTTPServer(('127.0.0.1',0),Model);threading.Thread(target=server.serve_forever,daemon=True).start()
    sockets={n:str(root/(n+'.sock')) for n in ('desktop','mobile')}
    settings={'model_provider':'inspection_fixture','model_providers.inspection_fixture':{'name':'local fixture','base_url':f'http://127.0.0.1:{server.server_port}/v1','wire_api':'responses','requires_openai_auth':False},
              'features.apps':False,'features.memories':False,'features.shell_tool':False,'features.multi_agent':False}
    config={'runtime':str(root/'runtime'),'sockets':list(sockets.values()),'monitor_socket':sockets['desktop'],'monitor_thread':'placeholder','allow_followups':True}
    project=root/'project';project.mkdir();(project/'main.py').write_text('answer = 0\n')
    for args in (['git','init','-q'],['git','add','main.py'],['git','-c','user.name=fixture','-c','user.email=fixture@example.invalid','commit','-qm','fixture']):
        subprocess.run(args,cwd=project,check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    (project/'main.py').write_text('answer = 42\n')
    from fixture_runtime import isolated_codex_env
    fixture_env=isolated_codex_env(root)
    try:
        for name,path in sockets.items():
            args=[shutil.which('codex'),'app-server','--listen','unix://'+path]
            for k,v in settings.items():
                literal='{'+','.join(x+'='+json.dumps(y) for x,y in v.items())+'}' if isinstance(v,dict) else json.dumps(v)
                args.extend(['-c',k+'='+literal])
            with (root/(name+'.log')).open('w') as log:processes.append(subprocess.Popen(args,stdout=subprocess.DEVNULL,stderr=log,start_new_session=True,env=fixture_env))
        deadline=time.monotonic()+20
        while not all(Path(p).exists() for p in sockets.values()):
            assert time.monotonic()<deadline;time.sleep(.05)
        monitor=Monitor(config);runtime=Path(config['runtime'])
        with Rpc(sockets['desktop']) as rpc:
            def new(cwd):
                response=rpc.call('thread/start',{'cwd':str(cwd),'model':'gpt-6-luna','modelProvider':'inspection_fixture','config':settings,'sandbox':'read-only','approvalPolicy':'never'})
                assert response['modelProvider']=='inspection_fixture';threads.append((sockets['desktop'],response['thread']['id']));return response['thread']['id']
            manager=new(monitor.chat_workspace);source_id=new(project);config['monitor_thread']=manager
            config['thread_ids']=[source_id];monitor.base_config=dict(config);monitor.config=dict(config)
            (runtime/'config.json').write_text(json.dumps(config))
            for tid in (manager,source_id):
                t=rpc.call('turn/start',{'threadId':tid,'input':[{'type':'text','text':'READY_INSPECTION_FIXTURE'}]})['turn']['id'];completed(rpc,tid,t)
            rpc.call('thread/archive',{'threadId':manager});rpc.call('thread/unarchive',{'threadId':manager})
        # Force the same manager through phone takeover, with desktop snapshot left unloaded.
        monitor.scan()
        endpoint=root/'phone.sock';gateway=Gateway(endpoint,sockets);threading.Thread(target=gateway.serve_forever,daemon=True).start()
        client=Rpc(endpoint,initialize_params={'clientInfo':{'name':'codex-server-android','version':'fixture'},'capabilities':{'experimentalApi':True}})
        client.call('thread/resume',{'threadId':manager,'excludeTurns':True})
        turn=client.call('turn/start',{'threadId':manager,'input':[{'type':'text','text':'REQUEST_INSPECTION_FIXTURE 请独立检查刚才指定任务。'}]})['turn']['id'];completed(client,manager,turn)
        jobs=results(runtime);assert len(jobs)==1,tool_outputs
        ident=jobs[0]['id'];checks['phone_manager_invokes_real_delegation_tool']=True
        service=Service(config);service.tick()
        row=monitor.store.db.execute('SELECT * FROM inspections WHERE id=?',(ident,)).fetchone()
        assert row['state']=='running',dict(row)
        worker=row['worker_thread'];threads.append((row['endpoint'],worker))
        with Rpc(row['endpoint']) as rpc:
            completed(rpc,worker,row['worker_turn'])
            inventory=rpc.call('mcpServerStatus/list',{'threadId':worker,'limit':100})['data']
            assert any(s['name']=='andri_inspection' and len(s.get('tools',{}))==4 for s in inventory)
            assert not any(s['name']=='andri_monitor' and s.get('tools') for s in inventory)
        checks['attached_worker_has_only_four_read_tools']=True
        service.tick();done=results(runtime,ident)[0];assert done['state']=='completed',done
        assert {'inspection_read','inspection_diff'} <= {o['tool'] for o in done['observations']}
        assert any('answer = 42' in str(o) for o in tool_outputs)
        checks['worker_reads_actual_file_and_git_diff']=True
        checks['structured_report_and_observation_hashes_persist']=bool(done['report']['evidence']) and any(o.get('sha256') for o in done['observations'])
        reread=value(client.call('mcpServer/tool/call',{'threadId':manager,'server':'andri_monitor','tool':'monitor_inspections','arguments':{'inspection_id':ident}}))
        assert reread[0]['state']=='completed' and reread[0]['report']==done['report']
        checks['manager_receives_completed_report_through_phone_tool']=True
        # Same request again must reuse the completed inspection, including after service recreation.
        turn=client.call('turn/start',{'threadId':manager,'input':[{'type':'text','text':'REQUEST_INSPECTION_FIXTURE 再查同一个检查结果。'}]})['turn']['id'];completed(client,manager,turn)
        assert len(results(runtime))==1
        checks['repeat_request_deduplicates_completed_inspection']=True
        monitor.scan()
        assert not monitor.store.db.execute('SELECT 1 FROM contexts WHERE thread_id=?',(worker,)).fetchone()
        checks['worker_is_not_monitored_as_an_original_task']=True
        new=Monitor(config);assert results(runtime,ident)[0]['report']==done['report'];new.store.db.close()
        checks['report_survives_monitor_restart']=True
        # Background reviewer -> inspector -> reviewer -> original task -> final review.
        with Rpc(sockets['desktop']) as rpc:
            source_turn=rpc.call('turn/start',{'threadId':source_id,'input':[{'type':'text','text':'BACKGROUND_INSPECTION_FIXTURE 请完成 main.py 的修复及验收。'}]})['turn']['id']
            completed(rpc,source_id,source_turn)
        deadline=time.monotonic()+50
        while time.monotonic()<deadline:
            monitor.tick()
            bg=monitor.store.db.execute('SELECT * FROM jobs WHERE thread_id=? AND turn_id=?',(source_id,source_turn)).fetchone()
            if bg and bg['phase']=='followed_up':break
            time.sleep(.1)
        assert bg and bg['phase']=='followed_up',dict(bg) if bg else None
        inspected=monitor.store.db.execute('SELECT * FROM inspections WHERE source_turn=?',(source_turn,)).fetchone()
        assert inspected and inspected['state']=='completed'
        threads.append((inspected['endpoint'],inspected['worker_thread']))
        checks['background_reviewer_delegates_and_receives_inspection']=True
        restored=Monitor(config)
        assert restored.review_endpoint==sockets['mobile'];restored.store.db.close()
        checks['review_backend_survives_restart_after_mobile_takeover']=True
        checks['report_is_reviewed_before_original_followup']=bg['sent']>=inspected['updated']
        deadline=time.monotonic()+25
        while time.monotonic()<deadline:
            monitor.tick()
            tail=monitor.store.db.execute('SELECT * FROM jobs WHERE thread_id=? AND turn_id=?',(source_id,bg['followup_turn'])).fetchone()
            if tail and tail['phase']=='done':break
            time.sleep(.1)
        assert tail and tail['phase']=='done'
        checks['original_followup_result_is_reviewed_and_closed']=True
        print(json.dumps(checks,ensure_ascii=False,indent=2))
    finally:
        if client:client.close()
        if gateway:gateway.shutdown();gateway.server_close()
        for endpoint,tid in threads:
            try:
                with Rpc(endpoint) as rpc:rpc.call('thread/archive',{'threadId':tid})
            except Exception:pass
        if monitor:
            if monitor.reviewer:
                try:
                    with Rpc(monitor.review_endpoint) as rpc:rpc.call('thread/archive',{'threadId':monitor.reviewer})
                except Exception:pass
            monitor.store.db.close()
        for p in processes:
            if p.poll() is None:
                os.killpg(p.pid,signal.SIGTERM)
                try:p.wait(timeout=5)
                except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);p.wait(timeout=5)
        server.shutdown();report.parent.mkdir(parents=True,exist_ok=True)
        report.write_text(json.dumps({'checks':checks,'fixture':'installed app-servers + phone gateway + actual private MCP + local fake model; no physical device, no upstream model'},ensure_ascii=False,indent=2)+'\n')
        if len(checks)<12:
            for p in root.glob('*.log'):shutil.copyfile(p,report.parent/('inspection-'+p.name))
        tmp.cleanup()


if __name__=='__main__':main()
