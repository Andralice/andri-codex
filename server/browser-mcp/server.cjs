#!/usr/bin/env node
'use strict';

// Small stdio MCP server for server-side frontend testing. Playwright is loaded
// from a user-scoped runtime so this source tree does not vendor browser binaries.
const readline = require('node:readline');

const playwrightPath = process.env.PLAYWRIGHT_MODULE;
if (!playwrightPath) throw new Error('PLAYWRIGHT_MODULE must point to the installed Playwright package');
const { chromium } = require(playwrightPath);

const SERVER_INFO = { name: 'andri-server-browser', version: '1.0.0' };
const PROTOCOL_VERSION = '2024-11-05';
const MAX_EVENTS = 200;
const events = [];
let browser;
let page;

function send(message) {
  process.stdout.write(JSON.stringify(message) + '\n');
}

function event(kind, message) {
  events.push({ time: new Date().toISOString(), kind, message: String(message).slice(0, 1600) });
  if (events.length > MAX_EVENTS) events.splice(0, events.length - MAX_EVENTS);
}

async function ensurePage() {
  if (!browser || !browser.isConnected()) {
    browser = await chromium.launch({ headless: true });
    browser.on('disconnected', () => { browser = undefined; page = undefined; });
  }
  if (!page || page.isClosed()) {
    page = await browser.newPage({ viewport: { width: 1365, height: 900 }, deviceScaleFactor: 1 });
    page.setDefaultTimeout(15_000);
    page.on('console', message => {
      if (message.type() === 'error' || message.type() === 'warning')
        event(`console.${message.type()}`, `${message.text()} (${message.location().url}:${message.location().lineNumber})`);
    });
    page.on('pageerror', error => event('pageerror', error.stack || error.message));
    page.on('requestfailed', request => event('requestfailed', `${request.method()} ${request.url()} — ${request.failure()?.errorText || 'failed'}`));
    page.on('response', response => {
      if (response.status() >= 400) event(`http.${response.status()}`, `${response.request().method()} ${response.url()}`);
    });
  }
  return page;
}

function content(text, extra = []) {
  return { content: [{ type: 'text', text: String(text) }, ...extra] };
}

async function inspectPage() {
  const current = await ensurePage();
  const details = await current.evaluate(() => {
    const visible = element => {
      const rect = element.getBoundingClientRect();
      const style = getComputedStyle(element);
      return rect.width > 0 && rect.height > 0 && style.display !== 'none' && style.visibility !== 'hidden';
    };
    const elements = [...document.querySelectorAll('a,button,input,textarea,select,[role="button"],[onclick]')]
      .filter(visible).slice(0, 80).map(element => ({
        tag: element.tagName.toLowerCase(),
        id: element.id || '',
        name: element.getAttribute('name') || '',
        role: element.getAttribute('role') || '',
        type: element.getAttribute('type') || '',
        label: element.getAttribute('aria-label') || element.getAttribute('placeholder') || '',
        text: (element.innerText || element.value || '').replace(/\s+/g, ' ').trim().slice(0, 100),
        href: element.href || '',
        disabled: Boolean(element.disabled),
        checked: Boolean(element.checked)
      }));
    return {
      url: location.href,
      title: document.title,
      readyState: document.readyState,
      viewport: { width: innerWidth, height: innerHeight },
      documentSize: { width: document.documentElement.scrollWidth, height: document.documentElement.scrollHeight },
      text: (document.body?.innerText || '').slice(0, 10_000),
      elements
    };
  });
  return { ...details, browserEvents: events.slice(-80) };
}

const toolList = [
  {
    name: 'server_browser_open',
    description: 'Open an HTTP(S) frontend URL in a persistent headless Chromium browser running on the Codex server. Server localhost addresses are reachable from this browser. Returns page text, interactive elements, and load errors.',
    inputSchema: {
      type: 'object', properties: { url: { type: 'string', description: 'HTTP(S) URL, including server-local URLs such as http://localhost:5173/chat.' } },
      required: ['url'], additionalProperties: false
    }
  },
  {
    name: 'server_browser_inspect',
    description: 'Inspect the current server browser page: title, URL, visible page text, interactive controls, browser console errors, failed requests, and HTTP error responses.',
    inputSchema: { type: 'object', properties: {}, additionalProperties: false }
  },
  {
    name: 'server_browser_click',
    description: 'Click an element in the server browser by CSS selector or by exact visible text. Returns the resulting page summary.',
    inputSchema: {
      type: 'object', properties: {
        selector: { type: 'string' }, text: { type: 'string' },
        waitMs: { type: 'integer', minimum: 0, maximum: 5000 }
      }, additionalProperties: false
    }
  },
  {
    name: 'server_browser_fill',
    description: 'Fill a form control or contenteditable element in the server browser, then return the updated page summary.',
    inputSchema: {
      type: 'object', properties: { selector: { type: 'string' }, value: { type: 'string' } },
      required: ['selector', 'value'], additionalProperties: false
    }
  },
  {
    name: 'server_browser_press',
    description: 'Focus a page element and press a key such as Enter, Tab, Escape, or ArrowDown.',
    inputSchema: {
      type: 'object', properties: { selector: { type: 'string' }, key: { type: 'string' } },
      required: ['selector', 'key'], additionalProperties: false
    }
  },
  {
    name: 'server_browser_scroll',
    description: 'Scroll the current page or a scrollable element and return the resulting scroll position.',
    inputSchema: {
      type: 'object', properties: {
        selector: { type: 'string' }, deltaY: { type: 'integer' }, deltaX: { type: 'integer' }
      }, additionalProperties: false
    }
  },
  {
    name: 'server_browser_screenshot',
    description: 'Capture the visible viewport of the current server browser page and return it as an image.',
    inputSchema: {
      type: 'object', properties: { fullPage: { type: 'boolean' } }, additionalProperties: false
    }
  },
  {
    name: 'server_browser_refresh', description: 'Reload the current page in server Chromium.',
    inputSchema: { type: 'object', properties: {}, additionalProperties: false }
  },
  {
    name: 'server_browser_close', description: 'Close the current server browser page and its Chromium process.',
    inputSchema: { type: 'object', properties: {}, additionalProperties: false }
  }
];

async function callTool(name, args) {
  if (name === 'server_browser_open') {
    const url = new URL(String(args.url || ''));
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password)
      throw new Error('Only HTTP(S) URLs without embedded credentials are supported.');
    const current = await ensurePage();
    events.length = 0;
    await current.goto(url.href, { waitUntil: 'domcontentloaded', timeout: 30_000 });
    await current.waitForTimeout(200);
    return content(JSON.stringify(await inspectPage()));
  }
  if (name === 'server_browser_close') {
    if (browser) await browser.close();
    browser = undefined;
    page = undefined;
    events.length = 0;
    return content('Closed the server-side browser.');
  }

  const current = await ensurePage();
  if (name === 'server_browser_inspect') return content(JSON.stringify(await inspectPage()));
  if (name === 'server_browser_refresh') {
    await current.reload({ waitUntil: 'domcontentloaded', timeout: 30_000 });
    return content(JSON.stringify(await inspectPage()));
  }
  if (name === 'server_browser_click') {
    if (args.selector) await current.locator(String(args.selector)).first().click();
    else if (args.text) {
      const text = String(args.text);
      const button = current.getByRole('button', { name: text, exact: true }).first();
      if (await button.count()) await button.click();
      else {
        const link = current.getByRole('link', { name: text, exact: true }).first();
        if (await link.count()) await link.click();
        else await current.getByText(text, { exact: true }).first().click();
      }
    } else throw new Error('Provide selector or text.');
    const waitMs = Number(args.waitMs || 0);
    if (waitMs > 0) await current.waitForTimeout(Math.min(waitMs, 5000));
    return content(JSON.stringify(await inspectPage()));
  }
  if (name === 'server_browser_fill') {
    await current.locator(String(args.selector)).first().fill(String(args.value));
    return content(JSON.stringify(await inspectPage()));
  }
  if (name === 'server_browser_press') {
    await current.locator(String(args.selector)).first().press(String(args.key));
    return content(JSON.stringify(await inspectPage()));
  }
  if (name === 'server_browser_scroll') {
    const deltaY = Number(args.deltaY || 0);
    const deltaX = Number(args.deltaX || 0);
    if (args.selector) {
      await current.locator(String(args.selector)).evaluate((element, delta) => element.scrollBy(delta), { top: deltaY, left: deltaX });
    } else {
      await current.evaluate(delta => window.scrollBy(delta), { top: deltaY, left: deltaX });
    }
    await current.waitForTimeout(150);
    return content(JSON.stringify(await current.evaluate(() => ({
      url: location.href, title: document.title, x: scrollX, y: scrollY,
      pageHeight: document.documentElement.scrollHeight
    }))));
  }
  if (name === 'server_browser_screenshot') {
    const image = await current.screenshot({ type: 'jpeg', quality: 70, fullPage: Boolean(args.fullPage) });
    return content(`Screenshot of ${current.url()}`, [{ type: 'image', data: image.toString('base64'), mimeType: 'image/jpeg' }]);
  }
  throw new Error(`Unknown tool: ${name}`);
}

async function handle(message) {
  if (message.method === 'initialize') {
    return { jsonrpc: '2.0', id: message.id, result: {
      protocolVersion: PROTOCOL_VERSION,
      capabilities: { tools: {} },
      serverInfo: SERVER_INFO
    } };
  }
  if (message.method === 'ping') return { jsonrpc: '2.0', id: message.id, result: {} };
  if (message.method === 'tools/list') return { jsonrpc: '2.0', id: message.id, result: { tools: toolList } };
  if (message.method === 'tools/call') {
    try {
      const result = await callTool(message.params?.name, message.params?.arguments || {});
      return { jsonrpc: '2.0', id: message.id, result };
    } catch (error) {
      return { jsonrpc: '2.0', id: message.id, result: { ...content(error.message || String(error)), isError: true } };
    }
  }
  if (message.id !== undefined) return { jsonrpc: '2.0', id: message.id, error: { code: -32601, message: `Method not found: ${message.method}` } };
  return null;
}

// MCP stdio transport is newline-delimited JSON. Serialize tool calls to keep
// page state deterministic when a model issues adjacent actions.
const input = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
let queue = Promise.resolve();
input.on('line', line => {
  if (!line.trim()) return;
  queue = queue.then(async () => {
    let message;
    try { message = JSON.parse(line); }
    catch { return; }
    const response = await handle(message);
    if (response) send(response);
  }).catch(error => {
    process.stderr.write(`browser MCP error: ${error.message || error}\n`);
  });
});

async function shutdown() {
  if (browser) await browser.close().catch(() => {});
  process.exit(0);
}
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
