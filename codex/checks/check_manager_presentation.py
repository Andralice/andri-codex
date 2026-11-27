"""Real isolated app-server/gateway provenance and native-question protocol; local model only."""
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
from task_monitor import Store
from task_monitor_presentation import FOLLOWUP_PREFIX
from task_monitor_rpc import Rpc
from thread_gateway import Gateway
from fixture_runtime import isolated_codex_env

class Model(BaseHTTPRequestHandler):
    asked=False
    def log_message(self,*_):pass
    def do_POST(self):
        body=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        users=[i for i in body.get('input',[]) if i.get('role')=='user']
        prompt=str(users[-1].get('content','')) if users else ''
        if 'NATIVE_QUESTION_FIXTURE' in prompt and not Model.asked:
            Model.asked=True
            item={'type':'function_call','id':'fc_question','call_id':'call_question','name':'request_user_input','status':'completed',
                  'arguments':json.dumps({'questions':[{'id':'choice','header':'Test','question':'Fixture choice?',
                    'options':[{'label':'Continue','description':'Fixture only'},{'label':'Stop','description':'Fixture only'}]}]})}
        else:
            item={'type':'message','id':'msg_done','role':'assistant','status':'completed',
                  'content':[{'type':'output_text','text':'Fixture result; device acceptance not performed.','annotations':[]}]}
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

def collect(rpc, predicate, timeout=20):
    seen=list(rpc.notifications);rpc.notifications.clear()
    end=time.monotonic()+timeout
    while True:
        for i,event in enumerate(seen):
            if predicate(event):
                rpc.notifications.extend(seen[i+1:]);return event,seen[:i+1]
        seen.append(rpc.receive(end))

def finished(rpc,turn):
    return collect(rpc,lambda e:e.get('method')=='turn/completed' and e.get('params',{}).get('turn',{}).get('id')==turn)

def main():
    checks={};process=gateway=phone=direct=store=None
    http=ThreadingHTTPServer(('127.0.0.1',0),Model);threading.Thread(target=http.serve_forever,daemon=True).start()
    report=REPO/'codex/manager-ui/protocol-result.json'
    try:
        with tempfile.TemporaryDirectory(prefix='manager-protocol-',dir=REPO/'codex') as temp:
            root=Path(temp);sock=root/'backend.sock';runtime=root/'runtime';runtime.mkdir()
            settings={'model_provider':'presentation_fixture','model_providers.presentation_fixture':{'name':'local fixture','base_url':f'http://127.0.0.1:{http.server_port}/v1','wire_api':'responses','requires_openai_auth':False},
                      'features.apps':False,'features.memories':False,'features.shell_tool':False,'features.multi_agent':False}
            args=[shutil.which('codex'),'app-server','--listen','unix://'+str(sock)]
            for k,v in settings.items():
                literal='{'+','.join(x+'='+json.dumps(y) for x,y in v.items())+'}' if isinstance(v,dict) else json.dumps(v)
                args.extend(['-c',k+'='+literal])
            with (root/'backend.log').open('w') as log:process=subprocess.Popen(args,stdout=subprocess.DEVNULL,stderr=log,start_new_session=True,env=isolated_codex_env(root))
            end=time.monotonic()+15
            while not sock.exists():
                assert time.monotonic()<end;time.sleep(.05)
            direct=Rpc(sock)
            def new(rpc=None,**extra):
                r=(rpc or direct).call('thread/start',{'cwd':str(root),'model':'gpt-6-luna','modelProvider':'presentation_fixture',
                    'config':settings,'sandbox':'read-only','approvalPolicy':'never',**extra})
                assert r['modelProvider']=='presentation_fixture';return r['thread']['id']
            manager=new();source=new()
            for tid in (manager,source):
                ready=direct.call('turn/start',{'threadId':tid,'input':[{'type':'text','text':'READY'}]})['turn']['id'];finished(direct,ready)
            store=Store(runtime/'state.sqlite3')
            (runtime/'config.json').write_text(json.dumps({'runtime':str(runtime),'monitor_thread':manager}))
            (runtime/'status.json').write_text(json.dumps({'updated_at':time.time(),'enabled':True}))
            gateway=Gateway(root/'phone.sock',{'mobile':str(sock)},runtime)
            threading.Thread(target=gateway.serve_forever,daemon=True).start()
            phone=Rpc(root/'phone.sock',initialize_params={'clientInfo':{'name':'codex-server-android','version':'fixture'},'capabilities':{'experimentalApi':True}})
            assert phone.call('andri/monitor/overview',{})['managerThreadId']==manager
            checks['overview_without_model_turn']=True
            for tid in (source,manager):phone.call('thread/resume',{'threadId':tid,'excludeTurns':True})
            # Warm the projection, then dispatch inside its normal cache interval.
            result={'summary':'补充验证','next_prompt':'Verify original authorized work.','action':'follow_up'}
            with store.db:store.db.execute("INSERT INTO jobs(id,thread_id,turn_id,endpoint,title,payload,phase,created,result) VALUES(?,?,?,?,?,?,'followup_starting',?,?)",('event',source,'previous','local','Original','{}',time.time(),json.dumps(result)))
            prompt=FOLLOWUP_PREFIX+'\n监控事件：event\n'+result['next_prompt']+'\n保留原任务的权限和审批规则；不要扩大范围。已有用户问题或审批时等待用户，不得代答。\n完成后如实报告结果和验证边界。'
            turn=phone.call('turn/start',{'threadId':source,'input':[{'type':'text','text':prompt}]})['turn']['id']
            with store.db:store.db.execute("UPDATE jobs SET phase='followed_up',followup_turn=?",(turn,))
            event,live=finished(phone,turn)
            user=[e for e in live if e.get('params',{}).get('item',{}).get('type')=='userMessage']
            assert user and all(e['params']['item']['andriOrigin']['kind']=='monitor_followup' for e in user)
            checks['live_before_ack_is_tagged']=True
            history=phone.call('thread/read',{'threadId':source,'includeTurns':True})['thread']
            tagged=next(t for t in history['turns'] if t['id']==turn)
            assert tagged['andriOrigin']['kind']=='monitor_followup'
            raw=direct.call('thread/read',{'threadId':source,'includeTurns':True})['thread']
            original=next(t for t in raw['turns'] if t['id']==turn)
            assert 'andriOrigin' not in original and original['items'][0]['content']==tagged['items'][0]['content']
            checks['history_annotations_preserve_original_backend_history']=True
            ordinary=phone.call('turn/start',{'threadId':source,'input':[{'type':'text','text':prompt}]})['turn']['id'];finished(phone,ordinary)
            raw=phone.call('thread/read',{'threadId':source,'includeTurns':True})['thread']
            assert 'andriOrigin' not in next(t for t in raw['turns'] if t['id']==ordinary)
            checks['identical_human_text_not_hidden']=True
            manual=phone.call('turn/start',{'threadId':manager,'input':[{'type':'text','text':'Tell me what you did.'}]})['turn']['id'];finished(phone,manual)
            manager_data=phone.call('thread/read',{'threadId':manager,'includeTurns':True})['thread']
            assert manager_data['andriRole']=='manager' and 'andriOrigin' not in next(t for t in manager_data['turns'] if t['id']==manual)
            checks['manual_manager_chat_remains_visible']=True
            overview=phone.call('andri/monitor/overview',{})
            assert overview['entries'][0]['followup']==result['next_prompt'] and overview['followupsStarted']==1
            checks['dashboard_contains_followup_reason_and_instruction']=True
            before_manager=phone.call('thread/turns/list',{'threadId':manager,'limit':1,'itemsView':'full','sortDirection':'desc'})['data'][0]['id']
            with store.db:
                for identity,action,question in [('inbox-question','ask_user','Fixture question?'),('inbox-blocked','follow_up','')]:
                    store.db.execute("INSERT INTO jobs(id,thread_id,turn_id,endpoint,title,payload,phase,created,result) VALUES(?,?,?,?,?,'{}','needs_attention',?,?)",
                        (identity,source,identity,str(root/'backend.sock'),'fixture',time.time(),json.dumps({'action':action,'summary':'fixture inbox','question':question})))
            inbox=phone.call('andri/monitor/overview',{'inboxLimit':1})['inbox']
            assert inbox['questions'][0]['id']=='inbox-question' and inbox['questionsCount']==1
            assert inbox['notifications'][0]['id']=='event:inbox-blocked'
            assert phone.call('thread/turns/list',{'threadId':manager,'limit':1,'itemsView':'full','sortDirection':'desc'})['data'][0]['id']==before_manager
            assert store.db.execute("SELECT COUNT(*) FROM questions WHERE event_id='inbox-question'").fetchone()[0]==0
            checks['question_and_notice_inbox_is_read_only_and_creates_no_model_turn']=True
            # Native question on an otherwise hidden worker must still arrive.
            phone.notifications.clear();worker=new(rpc=phone,ephemeral=True)
            workerturn=direct.call('turn/start',{'threadId':worker,'input':[{'type':'text','text':'NATIVE_QUESTION_FIXTURE'}]})['turn']['id']
            request,events=collect(phone,lambda e:e.get('method')=='item/tool/requestUserInput')
            assert 'id' in request and request['params']['threadId']==worker
            assert not any(e.get('params',{}).get('threadId')==worker and e.get('method') in ('turn/started','item/started','item/completed') for e in events)
            checks['hidden_worker_deltas_suppressed_native_question_forwarded']=True
            phone.send({'id':request['id'],'result':{'answers':{'choice':{'answers':['Continue']}}}})
            # No real user choice is answered: this is an isolated, synthetic fixture.
            collect(phone,lambda e:e.get('method')=='serverRequest/resolved')
            checks['native_question_answer_and_resolution_route_back_correctly']=True
            print(json.dumps(checks,ensure_ascii=False,indent=2))
    finally:
        if phone:phone.close()
        if direct:direct.close()
        if gateway:gateway.shutdown();gateway.server_close()
        if store:store.db.close()
        if process and process.poll() is None:
            os.killpg(process.pid,signal.SIGTERM)
            try:process.wait(timeout=5)
            except subprocess.TimeoutExpired:os.killpg(process.pid,signal.SIGKILL);process.wait(timeout=5)
        http.shutdown();report.parent.mkdir(parents=True,exist_ok=True)
        report.write_text(json.dumps({'checks':checks,'boundary':'Isolated installed app-server + gateway + local fake model. No physical phone UI acceptance.'},ensure_ascii=False,indent=2)+'\n')

if __name__=='__main__':main()
