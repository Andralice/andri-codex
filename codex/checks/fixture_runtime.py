"""Keep live app-server fixtures out of the user's conversation store."""

import json
import os
from pathlib import Path


def isolated_codex_env(root: Path) -> dict[str, str]:
    home = root / "codex-home"
    home.mkdir()
    repo = Path(__file__).resolve().parents[2]
    # Isolate both rollout files and SQLite metadata, even when cleanup fails.
    config = f"sqlite_home = {json.dumps(str(home))}\n"
    # Live fixtures exercise native question cards without inheriting user config.
    config += "features.default_mode_request_user_input = true\n"
    config += "tools.experimental_request_user_input.enabled = true\n"
    for project in (repo, root):
        config += f"[projects.{json.dumps(str(project))}]\ntrust_level = \"trusted\"\n"
    (home / "config.toml").write_text(config, encoding="utf-8")
    return {**os.environ, "CODEX_HOME": str(home)}
