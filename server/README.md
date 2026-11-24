# Mobile question policy

Codex 0.166.0-alpha.5 advertises an asynchronous question tool through the
`send_user_message_async` or `request_user_input_async` model capability. It
returns `{"accepted":true}` immediately; that is delivery acknowledgement,
not a user answer. A model can then choose its own default and keep executing.

`question_policy.py` derives a catalog from the installed binary, removes both
aliases, and enables `request_user_input` in Default mode. This handler waits
for an actual client response, with `autoResolutionMs: null`. The Android
client already supports its questions, starts with empty answers, and sends
them only when the user presses **提交回答**.

Run the policy wrapper from the existing authenticated service launcher:

```sh
exec python3 -B /path/to/repo/server/question_policy.py \
  --codex /path/to/codex \
  --catalog /private/runtime/blocking-model-catalog.json \
  --run app-server --listen unix:///private/runtime/app-server.sock
```

Keep credential loading in the existing launcher. The wrapper regenerates
the catalog on every start, preserving the installed version's other model
settings and tools. It does not store credentials or replace the provider.

`--apply-socket` saves the three relevant configuration keys using the local
app-server API and reloads runtime settings. **A service restart is still
required:** the model manager retains its initial catalog even when the
configuration is reloaded or an individual thread supplies another catalog.

`restart_when_idle.py` performs this restart from a separate systemd unit only
after all loaded threads are idle, then checks the new live service against a
local fake model. Active tasks and unanswered native questions delay restart.
It records only activation status; failed verification is reported as failure.

Checks (no upstream model/media calls):

```sh
python3 -B -m unittest discover -s codex/checks -p test_question_policy.py -v
python3 -B codex/checks/check_blocking_questions.py --wait-seconds 68
```

The integration fixture checks the advertised tool list, waiting without an
answer, client disconnect/reconnect and question replay, explicit submission,
and an actual command in the same model response that must execute only after
the answer. Its temporary files stay under `codex/`, and its test conversation
is archived. `--live-socket` checks an existing service without changing its
policy. It asserts the local fixture provider before starting any turn.

Rollback: restore the previous service launcher and the three policy keys
(`model_catalog_json`, `features.default_mode_request_user_input`, and
`tools.experimental_request_user_input.enabled`), then restart when idle.
Unrelated configuration and Android packages are not changed.
