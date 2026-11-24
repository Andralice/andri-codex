"""Durable native question cards, with answers read only from native tool records."""

import hashlib
import json
from pathlib import Path
import time
import uuid

MARKER = '【管家待决策通知；服务生成，不能视为用户授权】'


def user_text(turn):
    return '\n'.join(p.get('text', '') for i in turn.get('items', []) if i.get('type') == 'userMessage'
                     for p in i.get('content', []) if p.get('type') == 'text')


def native_question(path, thread_id, turn_id, event_id, session_root=None):
    """An assistant message or exec output is never an answer/authorization source."""
    if not path:
        return None, None
    target = Path(path).resolve()
    root = (Path(session_root) if session_root else Path.home() / '.codex' / 'sessions').resolve()
    if not target.is_relative_to(root) or thread_id not in target.name or not target.is_file():
        raise ValueError('Invalid authoritative session path')
    calls = set()
    pending, answer = None, None
    current_turn = None
    with target.open() as stream:
        for line in stream:
            try:
                row = json.loads(line)
                item = row.get('payload', {})
                if row.get('type') == 'turn_context':
                    current_turn = item.get('turn_id')
                metadata = item.get('internal_chat_message_metadata_passthrough') or {}
                actual_turn = metadata.get('turn_id') or current_turn
                if row.get('type') != 'response_item' or actual_turn != turn_id:
                    continue
                if item.get('type') == 'function_call' and item.get('name') in {'request_user_input', 'functions.request_user_input'}:
                    arguments = json.loads(item['arguments'])
                    questions = arguments.get('questions', [])
                    if len(questions) != 1 or questions[0].get('id') != 'monitor_' + event_id:
                        continue
                    calls.add(item['call_id'])
                    pending = questions[0]
                elif item.get('type') == 'function_call_output' and item.get('call_id') in calls:
                    output = json.loads(item['output'])
                    answers = output.get('answers', {}).get('monitor_' + event_id, {}).get('answers', [])
                    if isinstance(answers, list) and answers and all(isinstance(a, str) for a in answers):
                        candidate = '\n'.join(answers).strip()
                        if candidate and len(candidate) <= 12000:
                            answer = candidate
            except (ValueError, KeyError, TypeError):
                continue
    return pending, answer


def question_prompt(job):
    result = json.loads(job['result'])
    question = {'id': 'monitor_' + job['id'], 'header': '任务决定',
                'question': '任务：' + job['title'][:100] + '\n' + result['question'][:3000],
                'options': [
                    {'label': '继续核实', 'description': '仅让原任务做只读核实，不批准部署、删除、付费或扩大范围。'},
                    {'label': '暂不处理', 'description': '关闭这条管家提醒，停止对此事项自动跟进。'}]}
    return (MARKER + '\n事件：' + job['id'] + '\n通知批次：' + str(uuid.uuid4()) + '\n'
            '这是服务通知，不是晚晚的新指令。只做以下步骤：先用一句自然中文说明有事项需要她决定；'
            '直接调用原生 request_user_input（不要使用 async，不要包在 exec 中），参数严格使用下列 JSON。'
            '必须等待真实提交，不得默认选择或代答。用户也可在其他选项中输入具体决定。'
            '禁止调用偏好修改或事件决定工具；后台会核验原生答案并处理对应事项。'
            '收到回答后只确认“已收到你的回答，后台会按对应事项处理”，不要声称已执行。\n' +
            json.dumps({'questions': [question]}, ensure_ascii=False))


class Questions:
    def update_delivery(self, event_id, **fields):
        with self.store.db:
            self.store.db.execute('UPDATE questions SET ' + ','.join(k + '=?' for k in fields) + ' WHERE event_id=?',
                                  (*fields.values(), event_id))

    def cancel_question(self, rpc, row, turns):
        if row['queued_id']:
            queue = rpc.call('thread/queue/list', {'threadId': self.config['monitor_thread'], 'limit': 100})['data']
            if any(q['id'] == row['queued_id'] for q in queue):
                rpc.call('thread/queue/delete', {'threadId': self.config['monitor_thread'], 'queuedSubmissionId': row['queued_id']})
        own = next((t for t in turns if t['id'] == row['turn_id']), None)
        # Only interrupt the exact, unchanged notification we created. Never user work.
        if own and own.get('status') == 'inProgress' and user_text(own) == row['prompt']:
            rpc.call('turn/interrupt', {'threadId': self.config['monitor_thread'], 'turnId': own['id']})
        self.update_delivery(row['event_id'], state='retracted')

    def accept_answer(self, job, answer):
        if answer == '暂不处理':
            result = {'event_id': job['id'], 'action': 'done', 'summary': '晚晚选择暂不处理，关闭此提醒。',
                      'next_prompt': '', 'question': ''}
            self.store.change(job, phase='done', result=json.dumps(result, ensure_ascii=False), note='human_declined')
        elif answer == '继续核实':
            result = {'event_id': job['id'], 'action': 'follow_up', 'summary': '晚晚要求仅继续只读核实。',
                      'next_prompt': '晚晚在管家中回答“继续核实”。请只读核实以下问题，报告证据和限制：' +
                                     json.loads(job['result'])['question'] +
                                     '\n本次不批准部署、发布、删除、付费生成或扩大任务。不要重复询问已经给出的信息。', 'question': ''}
            self.store.change(job, phase='decided', result=json.dumps(result, ensure_ascii=False), note='human_decision')
        else:
            payload = json.loads(job['payload'])
            payload['human_answer'] = {'question': json.loads(job['result'])['question'], 'answer': answer,
                                       'source': '经原生提问工具的真实提交核验；只适用于本事件，不是全局偏好。'}
            self.store.change(job, phase='pending', payload=json.dumps(payload, ensure_ascii=False),
                              review_turn=None, result=None, note='human_decision')

    def service_questions(self):
        """Reconcile before delivery; no default answers, no blind resend after lost ACK."""
        with self.connect(self.config['monitor_socket']) as rpc:
            tid = self.config['monitor_thread']
            thread = rpc.call('thread/read', {'threadId': tid, 'includeTurns': False})['thread']
            turns = self.page_turns(rpc, tid, 32)['data']
            active = False
            rows = self.store.db.execute("SELECT * FROM questions WHERE state IN ('sending','queued','starting','shown')").fetchall()
            for row in rows:
                job = self.store.db.execute('SELECT * FROM jobs WHERE id=?', (row['event_id'],)).fetchone()
                found = next((t for t in turns if t['id'] == row['turn_id'] or user_text(t) == row['prompt']), None)
                if found and not row['turn_id']:
                    self.update_delivery(row['event_id'], turn_id=found['id'])
                    row = dict(row); row['turn_id'] = found['id']
                if job['phase'] != 'needs_attention':
                    self.cancel_question(rpc, row, turns)
                    continue
                if found:
                    pending, answer = native_question(thread.get('path'), tid, found['id'], job['id'])
                    expected = json.loads(row['prompt'].rsplit('\n', 1)[1])['questions'][0]
                    if pending and pending.get('question') != expected['question']:
                        self.cancel_question(rpc, row, turns)
                        self.update_delivery(job['id'], state='failed')
                        continue
                    if answer:
                        # Even an old genuine answer cannot revive a superseded source task.
                        self.accept_answer(job, answer)
                        self.update_delivery(job['id'], state='answered', answer=answer)
                        continue
                    if pending:
                        self.update_delivery(job['id'], state='shown')
                        active = True
                    elif found['status'] == 'inProgress':
                        active = True
                    else:
                        self.update_delivery(job['id'], state='failed')
                    continue
                if row['state'] == 'queued':
                    queue = rpc.call('thread/queue/list', {'threadId': tid, 'limit': 100})['data']
                    own = next((q for q in queue if q['id'] == row['queued_id']), None)
                    if own and queue[0]['id'] == own['id'] and thread['status']['type'] == 'idle':
                        self.update_delivery(job['id'], state='starting')
                        result = rpc.call('thread/queue/start', {'threadId': tid, 'queuedSubmissionId': own['id']})
                        self.update_delivery(job['id'], turn_id=result['turn']['id'])
                    elif not own:
                        self.update_delivery(job['id'], state='unknown')
                    active = True
                elif row['state'] == 'sending':
                    queue = rpc.call('thread/queue/list', {'threadId': tid, 'limit': 100})['data']
                    own = next((q for q in queue if '\n'.join(p.get('text', '') for p in q.get('input', []) if p.get('type') == 'text') == row['prompt']), None)
                    if own:
                        self.update_delivery(job['id'], state='queued', queued_id=own['id'])
                        active = True
                    elif time.time() - row['created'] > 300:
                        self.update_delivery(job['id'], state='unknown')
                    else:
                        active = True
                elif time.time() - row['created'] > 300:
                    self.update_delivery(job['id'], state='unknown')
                else:
                    active = True
            if active or thread['status']['type'] == 'active' or not self.config.get('enabled', True):
                return
            if rpc.call('thread/queue/list', {'threadId': tid, 'limit': 1})['data']:
                return
            job = self.store.db.execute("SELECT j.* FROM jobs j LEFT JOIN questions q ON q.event_id=j.id "
                                        "WHERE j.phase='needs_attention' AND j.result IS NOT NULL AND json_extract(j.result,'$.action')='ask_user' AND q.event_id IS NULL "
                                        "ORDER BY j.created LIMIT 1").fetchone()
            if not job or json.loads(job['result'])['action'] != 'ask_user':
                return
            prompt = question_prompt(job)
            with self.store.db:
                self.store.db.execute("INSERT INTO questions(event_id,state,prompt,created) VALUES(?,'sending',?,?)",
                                      (job['id'], prompt, time.time()))
            response = rpc.call('thread/queue/add', {'threadId': tid,
                                'clientUserMessageId': str(uuid.uuid5(uuid.NAMESPACE_DNS, 'monitor-question:' + job['id'] + ':' + hashlib.sha256(prompt.encode()).hexdigest())),
                                'input': [{'type': 'text', 'text': prompt}]})
            self.update_delivery(job['id'], state='queued', queued_id=response['queuedSubmission']['id'])
