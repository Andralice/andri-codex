"""No paid calls: exercise installed app-server using a local model fixture."""
import argparse
import os
import signal
import shlex
import tempfile
import sys
import importlib.util
import json
import pathlib
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

REPO = pathlib.Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--live-socket')
parser.add_argument('--wait-seconds', type=int, default=68)
parser.add_argument('--codex', default=str(pathlib.Path.home() / '.local/bin/codex'))
args_cli = parser.parse_args()
if not 1 <= args_cli.wait_seconds <= 600:
    parser.error('--wait-seconds must be between 1 and 600')
RESULTS = REPO / 'codex/question-policy-checks'
RESULTS.mkdir(exist_ok=True)
workspace = tempfile.TemporaryDirectory(prefix='question-policy-', dir=REPO / 'codex')
ROOT = pathlib.Path(workspace.name)
from fixture_runtime import isolated_codex_env
fixture_env = isolated_codex_env(ROOT)
spec = importlib.util.spec_from_file_location('policy', REPO / 'server/question_policy.py')
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)
state = {'requests': 0, 'bodies': []}
marker = ROOT / 'after-answer.txt'
class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        state['requests'] += 1
        state['bodies'].append(body)
        n = state['requests']
        if n == 1:
            item = {'type': 'function_call', 'id': 'fc_confirm', 'call_id': 'call_confirm', 'name': 'request_user_input',
                    'arguments': json.dumps({'questions': [{'id': 'confirm', 'header': 'Confirm', 'question': 'Proceed?',
                     'options': [{'label': 'Proceed', 'description': 'Continue'}, {'label': 'Stop', 'description': 'Cancel'}]}]}), 'status': 'completed'}
        else:
            item = {'type': 'message', 'id': 'msg_done', 'role': 'assistant', 'status': 'completed',
                    'content': [{'type': 'output_text', 'text': 'ANSWER RECEIVED', 'annotations': []}]}
        items = [item]
        if n == 1:
            command = 'python3 -c ' + shlex.quote('from pathlib import Path; Path(' + repr(str(marker)) + ').write_text("submitted")')
            code = 'text(await tools.exec_command(' + json.dumps({'cmd': command, 'max_output_tokens': 100}) + '));'
            items.append({'type': 'custom_tool_call', 'id': 'ct_action', 'call_id': 'call_after_answer',
                          'name': 'exec', 'namespace': 'functions', 'input': code, 'status': 'completed'})
        response = {'id': f'resp_{n}', 'object': 'response', 'created_at': int(time.time()), 'model': body['model'],
                    'status': 'completed', 'output': items, 'usage': {'input_tokens': 10, 'output_tokens': 5, 'total_tokens': 15}}
        events = [('response.created', {'type': 'response.created', 'response': {**response, 'status': 'in_progress', 'output': []}})]
        for index, output_item in enumerate(items):
            events += [('response.output_item.added', {'type': 'response.output_item.added', 'output_index': index, 'item': output_item}),
                       ('response.output_item.done', {'type': 'response.output_item.done', 'output_index': index, 'item': output_item})]
        events.append(('response.completed', {'type': 'response.completed', 'response': response}))
        self.send_response(200); self.send_header('Content-Type', 'text/event-stream'); self.end_headers()
        for event, data in events:
            self.wfile.write(('event: ' + event + '\ndata: ' + json.dumps(data) + '\n\n').encode()); self.wfile.flush()
server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
port = server.server_address[1]
sock = pathlib.Path(args_cli.live_socket) if args_cli.live_socket else ROOT / 'probe.sock'
if not args_cli.live_socket: sock.unlink(missing_ok=True)
args = [args_cli.codex, 'app-server', '--listen', 'unix://' + str(sock),
        '-c', 'features.apps=false', '-c', 'features.memories=false',
        '-c', 'model_provider="question_fixture"',
        '-c', f'model_providers.question_fixture={{name="local",base_url="http://127.0.0.1:{port}/v1",wire_api="responses",requires_openai_auth=false}}']
if not args_cli.live_socket:
    args = [sys.executable, '-B', str(REPO / 'server/question_policy.py'), '--codex', args_cli.codex,
            '--catalog', str(ROOT / 'catalog-blocking.json'), '--run', *args[1:]]
process = None if args_cli.live_socket else subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=(ROOT / 'reconnect-stderr.log').open('w'), start_new_session=True, env=fixture_env)
connection = None
thread_id = None
summary = {}
def until(connection, method):
    for e in connection.notifications:
        if e.get('method') == method: return e
    while True:
        e = connection.receive()
        connection.notifications.append(e)
        if e.get('method') == method: return e
try:
    for _ in range(100):
        if sock.exists(): break
        if process and process.poll() is not None: raise RuntimeError('app-server failed to start')
        time.sleep(.05)
    connection = policy.RpcConnection(sock)
    config = {'model_provider': 'question_fixture', 'model_providers.question_fixture': {
        'name': 'local', 'base_url': f'http://127.0.0.1:{port}/v1', 'wire_api': 'responses', 'requires_openai_auth': False}}
    if not args_cli.live_socket:
        policy.write_catalog(args_cli.codex, ROOT / 'catalog-blocking.json')
        config.update({item['keyPath']: item['value'] for item in policy.policy_edits(ROOT / 'catalog-blocking.json')})
    result = connection.call('thread/start', {'cwd': str(REPO), 'model': 'gpt-6-luna', 'modelProvider': 'question_fixture', 'ephemeral': False,
        'approvalPolicy': 'never', 'sandbox': 'danger-full-access', 'config': config,
        'baseInstructions': 'Test fixture. Ask with request_user_input before continuing.'})
    thread_id = result['thread']['id']
    assert result['modelProvider'] == 'question_fixture', 'Fixture must never use a real provider'
    connection.call('turn/start', {'threadId': thread_id, 'input': [{'type': 'text', 'text': 'Ask whether to proceed.'}]})
    pending = until(connection, 'item/tool/requestUserInput')
    assert pending['params']['autoResolutionMs'] is None
    assert state['requests'] == 1
    print('waiting started; no automatic resolution configured', flush=True)
    deadline = time.monotonic() + args_cli.wait_seconds
    while time.monotonic() < deadline:
        time.sleep(min(15, max(0, deadline - time.monotonic())))
        assert state['requests'] == 1, 'Model continued without a submitted answer'
        assert not marker.exists(), 'Following action ran without a submitted answer'
        print('still paused without answer', flush=True)
    summary['no_answer_wait_seconds'] = args_cli.wait_seconds
    # Closing the client connection must not submit an empty/default answer.
    connection.close(); connection = None
    time.sleep(2)
    assert state['requests'] == 1, 'Disconnect supplied a response'
    connection = policy.RpcConnection(sock)
    connection.send({'id': 800, 'method': 'thread/resume', 'params': {'threadId': thread_id, 'excludeTurns': True}})
    while True:
        event = connection.receive()
        connection.notifications.append(event)
        if event.get('id') == 800 and 'method' not in event:
            if 'error' in event: print('resume error', event['error'], flush=True)
            assert 'result' in event
            break
    replay = until(connection, 'item/tool/requestUserInput')
    print('pending question replayed after reconnect', flush=True)
    assert state['requests'] == 1
    summary['disconnect_reconnect_preserves_question'] = True
    connection.send({'id': replay['id'], 'result': {'answers': {'confirm': {'answers': ['Proceed']}}}})
    completed = until(connection, 'turn/completed')
    assert completed['params']['turn']['status'] == 'completed'
    assert state['requests'] == 2
    if not marker.exists():
        outputs = [item for item in state['bodies'][-1].get('input', []) if 'output' in item.get('type','')]
        print('following action outputs', outputs[-3:], flush=True)
    assert marker.exists(), 'Following action must execute after the answer, or the probe is invalid'
    summary['same_response_action_waits_for_answer'] = True
    summary['continues_only_after_submit'] = True
    def tool_names(items):
        for item in items:
            if 'name' in item:
                yield item['name']
            yield from tool_names(item.get('tools', []))
    tools = list(tool_names(state['bodies'][0].get('tools', [])))
    for item in state['bodies'][0]['input']:
        if item.get('type') == 'additional_tools':
            tools += list(tool_names(item.get('tools', [])))
    print('question tools', [t for t in tools if 'user_input' in t], flush=True)
    assert 'request_user_input_async' not in tools, tools
    assert 'request_user_input' in tools, tools
    summary['async_tool_absent_native_tool_present'] = True
    print(json.dumps(summary), flush=True)
finally:
    if connection:
        if thread_id:
            try: connection.call('thread/archive', {'threadId': thread_id})
            except Exception: pass
        connection.close()
    if process:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=10)
    server.shutdown()
    summary['fixture_model_requests'] = state['requests']
    (RESULTS / 'last-result.json').write_text(json.dumps(summary, indent=2))
    workspace.cleanup()
