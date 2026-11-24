"""Bounded, read-only observations. No model-provided commands are executed."""

import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET


def tool_evidence(turn):
    commands, changes = [], 0
    for item in turn.get('items', []):
        if item.get('type') == 'commandExecution':
            command = item.get('command', '')
            if not isinstance(command, str):
                command = ''
            kind = ('tests' if re.search(r'pytest|unittest|gradlew.*(?:test|check)|npm\s+(?:run\s+)?test', command) else
                    'build' if re.search(r'gradlew.*assemble|npm\s+(?:run\s+)?build|mvn.*package', command) else
                    'syntax' if re.search(r'py_compile|compileall|kotlinc|tsc.*noEmit', command) else
                    'diff' if 'diff --check' in command else 'other')
            commands.append({'status': item.get('status'), 'exit_code': item.get('exitCode'), 'kind': kind})
        elif item.get('type') == 'fileChange':
            changes += len(item.get('changes', []))
    return {'command_results': commands[-30:], 'changed_files': changes,
            'failed_commands': sum(c['exit_code'] not in (None, 0) for c in commands),
            'boundary': '工具退出码只证明对应命令退出，不证明所有需求、远端部署或设备行为通过验收。'}


def evidence(payload):
    history = payload.get('history_excerpt', [])
    failures = sum(t.get('evidence', {}).get('failed_commands', 0) for t in history)
    commands = sum(len(t.get('evidence', {}).get('command_results', [])) for t in history)
    artifacts, reports = [], []
    # Only stat/hash explicitly mentioned artifacts inside this recorded workspace.
    # No arbitrary reads, symlink escapes, secret files, subprocesses, SSH or network.
    cwd = payload.get('cwd')
    if cwd and Path(cwd).is_absolute() and Path(cwd).is_dir():
        root = Path(cwd).resolve()
        started = history[-1].get('started_at', 0) if history else 0
        for report in root.glob('app/build/test-results/*/TEST-*.xml'):
            if len(reports) == 20:
                break
            actual = report.resolve()
            if not actual.is_relative_to(root) or not actual.is_file() or actual.stat().st_size > 1024 * 1024:
                continue
            try:
                suite = ET.parse(actual).getroot()
                reports.append({'name': actual.name, 'tests': int(suite.get('tests', 0)),
                                'failures': int(suite.get('failures', 0)), 'errors': int(suite.get('errors', 0)),
                                'fresh_for_turn': bool(started and actual.stat().st_mtime >= started)})
            except (ET.ParseError, ValueError, OSError):
                continue
        text = '\n'.join(t.get('assistant', '') for t in history[-2:])
        for token in dict.fromkeys(re.findall(r'(?:/[\w.\-/\u4e00-\u9fff]+|[\w.\-/]+)\.(?:apk|jar|mp4|png|jpg|xml)\b', text)):
            if len(artifacts) == 8:
                break
            path = Path(token)
            path = (path if path.is_absolute() else root / path).resolve()
            if not path.is_relative_to(root):
                continue
            if not path.is_file():
                artifacts.append({'name': path.name, 'exists': False})
                continue
            stat = path.stat()
            item = {'name': path.name, 'exists': True, 'bytes': stat.st_size, 'modified_at': stat.st_mtime}
            if path.suffix == '.apk' and stat.st_size <= 128 * 1024 * 1024:
                digest = hashlib.sha256()
                with path.open('rb') as stream:
                    for block in iter(lambda: stream.read(1024 * 1024), b''):
                        digest.update(block)
                item['sha256'] = digest.hexdigest()
            artifacts.append(item)
    return {'level': 'tool_observed' if commands else 'reported_only',
            'observed_commands': commands, 'failed_commands': failures, 'artifacts': artifacts, 'test_reports': reports,
            'boundary': '已观察工具结果及允许范围内的本地文件；未独立验收需求，未核验远端实时状态或真机效果。'}


def plain_conversation(history):
    """Skip only narrow factual exchanges without execution or outstanding work."""
    if not history:
        return False
    latest = history[-1]
    user, answer = latest.get('user', ''), latest.get('assistant', '')
    if latest.get('status') != 'completed' or latest.get('evidence', {}).get('command_results') or latest.get('evidence', {}).get('changed_files'):
        return False
    if any(re.search(r'未完成|失败|误删|待处理|下一步|阻塞|报错|权限|删除|部署|发布|生成|修复|继续|执行|开始|安装|清理', t.get('user', '') + t.get('assistant', '')) for t in history[-3:]):
        return False
    return bool(re.fullmatch(r'(谢谢[你啦]?|好的|知道了|明白了|你好)[。！!~～\s]*', user.strip()) or
                (len(user) < 120 and re.search(r'是什么|什么意思|有什么区别', user) and len(answer) < 4000))


def entities(text):
    return set(re.findall(r'(?i)(?<![a-z0-9_])[a-z][a-z0-9_.-]{2,}(?![a-z0-9_])', text)) - {
        'codex', 'server', 'ubuntu', 'home', 'repos', 'true', 'false', 'null', 'json', 'http', 'https',
        'completed', 'assistant', 'user', 'turn_id', 'status', 'evidence', 'command_results'}


def related_context(payload, contexts):
    own = payload.get('thread_id')
    text = payload.get('title', '') + '\n'.join(t.get('user', '') + '\n' + t.get('assistant', '') for t in payload.get('history_excerpt', []))
    needles = {s.lower() for s in entities(text)}
    related = []
    for context in contexts:
        if context['thread_id'] == own:
            continue
        turns = context.get('history_excerpt', [])
        source = context.get('title', '') + '\n'.join(t.get('user', '') + '\n' + t.get('assistant', '') for t in turns)
        matches = needles & {s.lower() for s in entities(source)}
        if not matches:
            continue
        # Shared workspace alone is insufficient to transfer context or authorization.
        matches -= {s.lower() for s in entities(context.get('cwd') or '')}
        if matches:
            selected = [t for t in turns if matches & {s.lower() for s in entities(t.get('user', '') + t.get('assistant', ''))}]
            selected = [{**t, 'user': t.get('user', '')[:2500], 'assistant': t.get('assistant', '')[:1000]} for t in selected[-6:]]
            related.append({'thread_id': context['thread_id'], 'title': context.get('title'),
                            'matched_entities': sorted(matches)[:12], 'history_excerpt': selected})
    return related[-5:]
