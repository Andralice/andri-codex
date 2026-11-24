"""Two installed app-servers + phone gateway + real MCP, local fake model only."""

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

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / 'server'))
from task_monitor import Monitor
from task_monitor_rpc import Rpc, RpcError
from thread_gateway import Gateway

GUIDELINE = '仅跟进原请求未完成或有待解决问题的任务；已完成、已答复和仅有优化空间的任务不跟进。'
stages = {}
outputs = []


class Model(BaseHTTPRequestHandler):
    def log_message(self, *_):pass
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        users = [i for i in body.get('input', []) if i.get('role') == 'user']
        content = users[-1].get('content', []) if users else []
        prompt = content if isinstance(content, str) else '\n'.join(i.get('text', '') for i in content)
        stage = stages.get(prompt, 0);stages[prompt] = stage + 1
        tool = None
        if 'SAVE_MOBILE_FIXTURE' in prompt and stage == 0:
            code = ('const read=ALL_TOOLS.find(t=>t.name.endsWith("monitor_show_preferences"));'
                    'const write=ALL_TOOLS.find(t=>t.name.endsWith("monitor_update_preferences"));'
                    'if(!read||!write)throw Error("Missing manager tools");'
                    'const before=await tools[read.name]({});'
                    'const current=JSON.parse(before.content[0].text);'
                    'text(await tools[write.name]({changes:{decision_guidelines:' + json.dumps(GUIDELINE, ensure_ascii=False) + '},'
                    'expected_revision:current.revision,reason:"fixture authorized user via mobile entry"}));')
            tool = {'type': 'custom_tool_call', 'id': 'ct_save', 'call_id': 'call_save', 'name': 'exec',
                    'namespace': 'functions', 'input': code, 'status': 'completed'}
        for item in body.get('input', []):
            if item.get('type') in ('function_call_output', 'custom_tool_call_output'):
                outputs.append(item.get('output'))
        item = tool or {'type': 'message', 'id': 'msg_done', 'role': 'assistant', 'status': 'completed',
                        'content': [{'type': 'output_text', 'text': 'fixture turn complete', 'annotations': []}]}
        response = {'id': 'resp_fixture', 'object': 'response', 'created_at': int(time.time()), 'model': body['model'],
                    'status': 'completed', 'output': [item], 'usage': {'input_tokens': 10, 'output_tokens': 10, 'total_tokens': 20}}
        events = [('response.created', {'type': 'response.created', 'response': {**response, 'status': 'in_progress', 'output': []}}),
                  ('response.output_item.added', {'type': 'response.output_item.added', 'output_index': 0, 'item': item}),
                  ('response.output_item.done', {'type': 'response.output_item.done', 'output_index': 0, 'item': item}),
                  ('response.completed', {'type': 'response.completed', 'response': response})]
        self.send_response(200);self.send_header('Content-Type', 'text/event-stream');self.end_headers()
        try:
            for event, data in events:
                self.wfile.write(('event: ' + event + '\ndata: ' + json.dumps(data) + '\n\n').encode());self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):pass


def completed(rpc, tid, turn_id, seconds=25):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            turns = rpc.call('thread/turns/list', {'threadId': tid, 'limit': 1, 'itemsView': 'full', 'sortDirection': 'desc'})['data']
            if turns and turns[0]['id'] == turn_id and turns[0]['status'] == 'completed':return
        except RpcError:pass
        time.sleep(.1)
    raise AssertionError('Fixture turn did not complete')


def value(result):
    # Native API wraps the MCP CallToolResult under result.
    if 'content' not in result and isinstance(result.get('result'), dict):result = result['result']
    return json.loads(result['content'][0]['text'])


def main():
    results = {};processes = [];client = gateway = monitor = None;tid = None
    report = REPO / 'codex/task-monitor/mobile-preferences-check-result.json'
    report.parent.mkdir(parents=True, exist_ok=True)
    temporary = tempfile.TemporaryDirectory(prefix='monitor-mobile-pref-', dir=REPO / 'codex')
    root = Path(temporary.name)
    http = ThreadingHTTPServer(('127.0.0.1', 0), Model)
    threading.Thread(target=http.serve_forever, daemon=True).start()
    paths = {k: str(root / (k + '.sock')) for k in ('desktop', 'mobile')}
    provider = {'name': 'local only', 'base_url': f'http://127.0.0.1:{http.server_port}/v1', 'wire_api': 'responses', 'requires_openai_auth': False}
    settings = {'model_provider': 'mobile_pref_fixture', 'model_providers.mobile_pref_fixture': provider,
                'features.apps': False, 'features.memories': False, 'features.shell_tool': False, 'features.multi_agent': False}
    config = {'runtime': str(root / 'runtime'), 'sockets': list(paths.values()), 'monitor_socket': paths['desktop'],
              'monitor_thread': 'placeholder', 'allow_followups': True}
    try:
        for name, endpoint in paths.items():
            args = [shutil.which('codex'), 'app-server', '--listen', 'unix://' + endpoint]
            for key, setting in settings.items():
                literal = '{' + ','.join(k + '=' + json.dumps(v) for k,v in setting.items()) + '}' if isinstance(setting, dict) else json.dumps(setting)
                args.extend(['-c', key + '=' + literal])
            with (root / (name + '.log')).open('w') as log:
                processes.append(subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=log, start_new_session=True))
        deadline = time.monotonic() + 15
        while not all(Path(p).exists() for p in paths.values()):
            assert time.monotonic() < deadline, 'Fixture servers failed to start';time.sleep(.05)
        monitor = Monitor(config)
        runtime = Path(config['runtime'])
        with Rpc(paths['desktop']) as desktop:
            result = desktop.call('thread/start', {'cwd': str(monitor.chat_workspace), 'model': 'gpt-6-luna',
                                  'modelProvider': 'mobile_pref_fixture', 'config': settings,
                                  'sandbox': 'read-only', 'approvalPolicy': 'never'})
            assert result['modelProvider'] == 'mobile_pref_fixture', 'Cannot use upstream model'
            tid = result['thread']['id'];config['monitor_thread'] = tid
            (runtime / 'config.json').write_text(json.dumps(config))
            ready = desktop.call('turn/start', {'threadId': tid, 'input': [{'type': 'text', 'text': 'READY_FIXTURE'}]})['turn']['id']
            completed(desktop, tid, ready)
            # Leave only a completed snapshot on desktop, then hand off via the phone entry.
            desktop.call('thread/archive', {'threadId': tid});desktop.call('thread/unarchive', {'threadId': tid})
            assert tid not in desktop.call('thread/loaded/list', {'limit': 100})['data']
        endpoint = root / 'phone.sock';gateway = Gateway(endpoint, paths)
        threading.Thread(target=gateway.serve_forever, daemon=True).start()
        client = Rpc(endpoint, initialize_params={'clientInfo': {'name': 'codex-server-android', 'version': 'fixture'},
                                                 'capabilities': {'experimentalApi': True}})
        client.call('thread/resume', {'threadId': tid, 'excludeTurns': True})
        with Rpc(paths['mobile']) as mobile:
            assert tid in mobile.call('thread/loaded/list', {'limit': 100})['data']
        results['phone_entry_takes_over_manager_while_desktop_has_only_snapshot'] = True
        turn = client.call('turn/start', {'threadId': tid, 'input': [{'type': 'text', 'text': 'SAVE_MOBILE_FIXTURE 请保存已确认的跟进规则。'}]})['turn']['id']
        completed(client, tid, turn)
        saved = json.loads((runtime / 'preferences.json').read_text())
        assert saved['revision'] == 1 and saved['values']['decision_guidelines'] == GUIDELINE
        assert 'saved' in json.dumps(outputs) and 'true' in json.dumps(outputs), 'No real write result reached model'
        results['real_private_tool_saves_from_in_progress_mobile_user_turn'] = True
        results['revision_increments_and_confirmed_guideline_persists'] = True
        client.close();client = Rpc(endpoint)
        client.call('thread/resume', {'threadId': tid, 'excludeTurns': True})
        reread = value(client.call('mcpServer/tool/call', {'threadId': tid, 'server': 'andri_monitor', 'tool': 'monitor_show_preferences', 'arguments': {}}))
        assert reread['revision'] == 1 and reread['preferences']['decision_guidelines'] == GUIDELINE
        results['reconnect_and_actual_tool_reread_preserve_preference'] = True
        rejected = value(client.call('mcpServer/tool/call', {'threadId': tid, 'server': 'andri_monitor',
                         'tool': 'monitor_update_preferences', 'arguments': {'changes': {'enabled': False}, 'expected_revision': 1, 'reason': 'no current user'}}))
        assert rejected['saved'] is False and rejected['code'] == 'no_current_user_turn'
        assert json.loads((runtime / 'preferences.json').read_text())['revision'] == 1
        results['idle_completed_turn_cannot_authorize_new_write'] = True
        results['rejection_has_specific_code_instead_of_generic_parameter_diagnosis'] = True
        print(json.dumps(results, ensure_ascii=False, indent=2))
    finally:
        if client:client.close()
        if gateway:gateway.shutdown();gateway.server_close()
        if tid:
            try:
                with Rpc(paths['mobile']) as rpc:rpc.call('thread/archive', {'threadId': tid})
            except (RpcError, OSError):pass
        if monitor:monitor.store.db.close()
        for process in reversed(processes):
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
                try:process.wait(timeout=5)
                except subprocess.TimeoutExpired:os.killpg(process.pid, signal.SIGKILL);process.wait(timeout=5)
        http.shutdown()
        report.write_text(json.dumps({'checks': results, 'fixture': 'installed app-servers + phone gateway + local fake model; no physical phone'}, ensure_ascii=False, indent=2) + '\n')
        if len(results) < 6:
            for p in root.glob('*.log'):shutil.copyfile(p, report.parent / ('mobile-pref-' + p.name))
        temporary.cleanup()


if __name__ == '__main__':main()
