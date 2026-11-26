"""Visibility of internal sessions in the mobile conversation sidebar.

Fixture providers reserve a ``fixture`` token separated by underscores/hyphens.
Use that token for new live diagnostics, even when testing a production socket.
Normal titles and ordinary user forks do not determine visibility.
"""

import re


_fixture_provider = re.compile(r"(?:^|[_-])fixture(?:$|[_-])", re.IGNORECASE)
_fixture_workspace = re.compile(
    r"(?:^|/)codex/(?:inspection-live|monitor-live|monitor-mobile-pref|gateway-check|question-policy)-[a-z0-9_]{8}(?:/|$)"
)
_monitor_worker = re.compile(r"(?:^|/)codex/task-monitor/[^/]+/(?:review|inspectors)(?:/|$)")


def hidden_from_thread_list(thread):
    if thread.get("ephemeral") is True:
        return True
    if thread.get("attachedThreadParentId") or thread.get("attached_thread_parent_id"):
        return True
    source = thread.get("source")
    kinds = source.keys() if isinstance(source, dict) else [source]
    kinds = [*kinds, thread.get("threadSource")]
    if any(isinstance(kind, str) and kind.lower().replace("_", "").startswith("subagent") for kind in kinds):
        return True
    provider = thread.get("modelProvider") or ""
    if _fixture_provider.search(provider):
        return True
    cwd = thread.get("cwd") or ""
    return bool(_fixture_workspace.search(cwd) or _monitor_worker.search(cwd))
