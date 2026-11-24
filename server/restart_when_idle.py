"""Activate the question policy after all mobile turns have finished.

Run from an independent systemd unit so restarting the mobile server cannot
kill this controller. No conversation text or credentials are logged.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time

from question_policy import RpcConnection


def all_threads_idle(connection):
    cursor = None
    while True:
        page = connection.call("thread/loaded/list", {"cursor": cursor, "limit": 100})
        for thread_id in page["data"]:
            thread = connection.call("thread/read", {"threadId": thread_id, "includeTurns": False})["thread"]
            if thread["status"]["type"] not in ("idle", "notLoaded"):
                return False
        cursor = page.get("nextCursor")
        if not cursor:
            return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--socket", required=True)
    parser.add_argument("--status", required=True)
    parser.add_argument("--check", required=True)
    args = parser.parse_args()
    status_path = Path(args.status)

    def status(phase, **details):
        temporary = status_path.with_suffix(".tmp")
        temporary.write_text(json.dumps({"phase": phase, **details}) + "\n")
        os.replace(temporary, status_path)

    status("waiting_for_idle")
    deadline = time.monotonic() + 1800
    consecutive_idle = 0
    while time.monotonic() < deadline:
        connection = None
        try:
            connection = RpcConnection(args.socket)
            idle = all_threads_idle(connection)
        except (OSError, ValueError, RuntimeError):
            # An unreadable state is never permission to interrupt a task.
            idle = False
        finally:
            if connection:
                connection.close()
        consecutive_idle = consecutive_idle + 1 if idle else 0
        if consecutive_idle >= 2:
            break
        time.sleep(2)
    else:
        status("not_activated", reason="server_did_not_become_idle")
        return 1

    status("restarting")
    try:
        subprocess.run(["sudo", "-n", "systemctl", "restart", "codex-mobile.service"],
                       check=True, timeout=120, capture_output=True)
        ready_deadline = time.monotonic() + 30
        while True:
            connection = None
            try:
                connection = RpcConnection(args.socket)
                connection.call("config/read", {"includeLayers": False})
                break
            except (OSError, ValueError, RuntimeError):
                if time.monotonic() >= ready_deadline:
                    raise OSError("Restarted service did not become ready")
                time.sleep(1)
            finally:
                if connection:
                    connection.close()
        status("verifying")
        # A real local model fixture checks the newly running server, including
        # an actual queued command that must wait for the submitted answer.
        verification = subprocess.run(
            [sys.executable, "-B", args.check, "--live-socket", args.socket, "--wait-seconds", "3"],
            capture_output=True, text=True, timeout=120,
        )
        if verification.returncode:
            status_path.with_suffix(".verification.log").write_text(verification.stdout + verification.stderr)
            status("verification_failed", check_exit_code=verification.returncode)
            return 1
        subprocess.run(["systemctl", "is-active", "--quiet", "codex-mobile.service"],
                       check=True, timeout=10)
        status("verified", checks=["no_async_question_tool", "no_answer_waits", "reconnect_preserves_question",
                                   "following_command_waits", "explicit_submit_continues"])
        return 0
    except (subprocess.SubprocessError, OSError):
        status("activation_failed")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
