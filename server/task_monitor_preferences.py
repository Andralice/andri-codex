"""Validated, versioned preferences for the supervisor's own conversation."""

import fcntl
import json
import os
from pathlib import Path, PurePosixPath
import tempfile
import time


BOOL_KEYS = {"enabled", "allow_followups"}
LIMITS = {"poll_seconds": (5, 3600), "max_reviews_per_day": (1, 1000),
          "max_followups_per_day": (0, 200), "max_followups_per_chain": (0, 20),
          "max_inspections_per_day": (0, 1000)}
OPTIONAL_LIMITS = {"max_reviews_per_day", "max_followups_per_day", "max_followups_per_chain", "max_inspections_per_day"}
LIST_KEYS = {"included_projects", "excluded_projects", "ignored_threads", "ask_before"}
TEXT_KEYS = {"decision_guidelines", "communication_style"}
ALLOWED = BOOL_KEYS | set(LIMITS) | LIST_KEYS | TEXT_KEYS
ASK_BEFORE = {"deploy", "publish", "delete_data", "paid_generation", "scope_change"}


class PreferenceValidationError(ValueError):
    """Invalid preference fields or values, never a session-ownership error."""


class PreferenceConflict(ValueError):
    """The caller must reread the revision instead of overwriting newer changes."""


def validate(changes):
    if not isinstance(changes, dict) or set(changes) - ALLOWED:
        raise PreferenceValidationError("Unsupported preference keys")
    result = {}
    for key, value in changes.items():
        if key in BOOL_KEYS and type(value) is not bool:
            raise PreferenceValidationError("Preference must be boolean: " + key)
        if key in LIMITS:
            low, high = LIMITS[key]
            if not (key in OPTIONAL_LIMITS and value is None) and (type(value) is not int or not low <= value <= high):
                raise PreferenceValidationError("Preference outside supported range: " + key)
        if key in LIST_KEYS:
            if not isinstance(value, list) or len(value) > 200 or not all(isinstance(v, str) and 0 < len(v) <= 1000 for v in value):
                raise PreferenceValidationError("Invalid preference list: " + key)
            value = list(dict.fromkeys(value))
            if key in {"included_projects", "excluded_projects"}:
                if any(not v.startswith("/") or ".." in PurePosixPath(v).parts for v in value):
                    raise PreferenceValidationError("Projects must be absolute paths on this server")
                value = [str(PurePosixPath(v)) for v in value]
            if key == "ask_before" and set(value) - ASK_BEFORE:
                raise PreferenceValidationError("Unsupported confirmation category")
        if key in TEXT_KEYS and (not isinstance(value, str) or len(value) > 12000):
            raise PreferenceValidationError("Invalid preference text: " + key)
        result[key] = value
    return result


def load(runtime):
    path = Path(runtime) / "preferences.json"
    if not path.exists():
        return {"revision": 0, "values": {}, "history": []}
    result = json.loads(path.read_text())
    if type(result.get("revision")) is not int or result["revision"] < 0:
        raise ValueError("Invalid preference revision")
    validate(result["values"])
    return result


def save(runtime, changes, reason="", expected_revision=None):
    changes = validate(changes)
    if not isinstance(reason, str) or len(reason) > 2000:
        raise PreferenceValidationError("Invalid preference reason")
    runtime = Path(runtime)
    runtime.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (runtime / "preferences.lock").open("a") as lock:
        os.chmod(runtime / "preferences.lock", 0o600)
        fcntl.flock(lock, fcntl.LOCK_EX)
        previous = load(runtime)
        if expected_revision is not None and expected_revision != previous["revision"]:
            raise PreferenceConflict("Preferences changed; read current settings before updating")
        values = {**previous["values"], **changes}
        revision = previous["revision"] + 1
        stamp = time.time()
        history = previous.get("history", [])[-29:] + [{"revision": revision, "changes": changes,
                                                       "reason": reason, "at": stamp}]
        result = {"revision": revision, "updated_at": stamp, "values": values, "history": history}
        fd, temporary = tempfile.mkstemp(prefix=".preferences-", dir=runtime)
        try:
            os.fchmod(fd, 0o600)
            with os.fdopen(fd, "w") as stream:
                json.dump(result, stream, ensure_ascii=False, indent=2)
                stream.write("\n")
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, runtime / "preferences.json")
            directory = os.open(runtime, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
    return result


def effective(config):
    preferences = load(config["runtime"])
    result = {**config, **preferences["values"], "preference_revision": preferences["revision"]}
    # Older, still-loaded MCPs require a numeric installation fallback while a
    # native question is pending. New services honor the uncapped policy; an
    # explicit subsequent user preference always overrides this compatibility flag.
    if config.get("auto_inspections_uncapped") is True and "max_inspections_per_day" not in preferences["values"]:
        result["max_inspections_per_day"] = None
    return result


def visible(config):
    result = effective(config)
    defaults = {"enabled": True, "allow_followups": False, "poll_seconds": 20,
                "max_reviews_per_day": 100, "max_followups_per_day": 20,
                "max_followups_per_chain": 2, "max_inspections_per_day": 20, "included_projects": [], "excluded_projects": [],
                "ignored_threads": [], "ask_before": [], "decision_guidelines": "",
                "communication_style": "中文，简洁、具体，如实说明判断依据和不确定性。"}
    return {"revision": result["preference_revision"],
            "preferences": {key: result.get(key, value) for key, value in defaults.items()}}


def in_scope(config, thread_id, cwd):
    if thread_id in config.get("ignored_threads", []):
        return False
    if config.get("thread_ids") is not None and thread_id not in config["thread_ids"]:
        return False
    path = PurePosixPath(cwd or "/")

    def matches(root):
        return path == PurePosixPath(root) or PurePosixPath(root) in path.parents

    included = config.get("included_projects", [])
    return (not included or any(matches(p) for p in included)) and not any(matches(p) for p in config.get("excluded_projects", []))
