# Andri server browser tools

This MCP server launches a persistent headless Chromium process on the Codex
server. It is intentionally separate from the Android WebView, so URLs hosted
on the server can be opened as `http://localhost:<port>/...` by the AI.

Tools expose navigation, page inspection (visible text, form controls, console
and network errors), screenshot, click, fill, key press, scroll, refresh, and
close operations. Browser state lives only for the MCP process lifetime; each
new server session starts with an empty page.

## Runtime

The server uses a user-scoped Playwright installation and Chromium runtime in
`/home/ubuntu/.codex/browser-runtime`. The original Playwright browser download
is retained under `/mnt/codex-storage/andri-browser-runtime`; Chromium cannot
be executed directly from the SSHFS mount, so the runnable copy stays on the
server's local disk.

The Codex config entry is:

```toml
[mcp_servers.andri_server_browser]
command = "/usr/bin/node"
args = ["/home/ubuntu/repos/andri-codex/server/browser-mcp/server.cjs"]
startup_timeout_sec = 60
tool_timeout_sec = 60
env = { PLAYWRIGHT_MODULE = "/home/ubuntu/.codex/browser-runtime/node_modules/playwright", PLAYWRIGHT_BROWSERS_PATH = "/home/ubuntu/.codex/browser-runtime/browsers" }
```

Validate syntax with `node --check server/browser-mcp/server.cjs`. The MCP
transport uses stdio and writes protocol messages only to stdout; diagnostics
go to stderr.

## Access model

The browser can reach server-local HTTP/HTTPS services and external sites. It
does not attach to or inspect a separate desktop Chrome profile. The page
contents and screenshots are returned to the current Codex conversation.
