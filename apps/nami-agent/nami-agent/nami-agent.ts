import "./style.css";

/* ─── constants ─────────────────────────────────────────────────────── */
const MODEL_OPTIONS = [
  { id: "gemini-2.5-flash-lite", label: "Lite", note: "15 RPM / 1k RPD", maxOutputTokens: 1536 },
  { id: "gemini-2.5-flash",      label: "Flash", note: "10 RPM / 250 RPD", maxOutputTokens: 2048 },
];
const KEY_STORED          = "nami-mobile-key";
const KEY_MESSAGES        = "nami-mobile-messages";
const KEY_MODEL           = "nami-mobile-model";
const KEY_COMPANION_HOST  = "nami-companion-host";
const MAX_HISTORY         = 14;
const MAX_PROMPT_CHARS    = 2500;
const MAX_TOOL_CHARS      = 3500;
const MAX_VISIBLE_CHARS   = 2200;
const GEMINI_BASE         = "https://generativelanguage.googleapis.com/v1beta/models";
const COMPANION_PORT      = 7474;

/* ─── native bridge ──────────────────────────────────────────────────── */
declare global {
  interface Window {
    NamiNativeBridge?: {
      getApiKey(): string;
      saveApiKey(key: string): void;
      openApplet(id: string, name: string, path: string): boolean;
      openInstalledApp(packageName: string): boolean;
      getCompanionStatus(): string; // JSON
      connectCompanion(host: string, port: number): void;
      disconnectCompanion(): void;
      sendCompanionMessage(msg: string): void;
    };
  }
}
function bridge() { return window.NamiNativeBridge; }

/* ─── types ──────────────────────────────────────────────────────────── */
type Role = "user" | "model" | "function" | "companion";

interface Msg {
  role: Role;
  text: string;
  name?: string;
  functionCall?: { name: string; args: Record<string, string> };
  confirmId?: string;
  confirmStatus?: "pending" | "approved" | "rejected";
}

type CompanionState = "disconnected" | "connecting" | "connected";

/* ─── tools ──────────────────────────────────────────────────────────── */
const MOBILE_TOOLS = [
  {
    name: "open_applet",
    description: "Open one of the built-in Maru applets by ID. Available IDs: schededit, cupcuppercuppers, daelornodael, tupgradesolver, photoserve.",
    parameters: {
      type: "object",
      properties: {
        id:   { type: "string", description: "Applet ID" },
        name: { type: "string", description: "Applet display name" },
        path: { type: "string", description: "Applet path, e.g. /cup-cupper-cuppers" },
      },
      required: ["id", "name", "path"],
    },
  },
  {
    name: "open_installed_app",
    description: "Open an installed Android app by package name, e.g. com.apple.android.music for Apple Music.",
    parameters: {
      type: "object",
      properties: {
        package_name: { type: "string", description: "Android package name of the app to open" },
      },
      required: ["package_name"],
    },
  },
  {
    name: "web_search",
    description: "Search the web for current information. Use this whenever you need up-to-date info.",
    parameters: {
      type: "object",
      properties: {
        query: { type: "string", description: "The search query" },
      },
      required: ["query"],
    },
  },
];

const APPLETS = [
  { id: "schededit",        name: "SchedEdit",         path: "/class-schedule-editor", emoji: "📅" },
  { id: "cupcuppercuppers", name: "Cup-Cupper-Cuppers", path: "/cup-cupper-cuppers",  emoji: "🥤" },
  { id: "daelornodael",     name: "Dael or No Dael",   path: "/dael-or-no-dael",      emoji: "💼" },
  { id: "tupgradesolver",   name: "TUP Grade Solver",  path: "/tup-grade-solver",     emoji: "📊" },
  { id: "photoserve",       name: "PhotoServe",        path: "/photo-serve",          emoji: "📷" },
];

/* ─── state ──────────────────────────────────────────────────────────── */
let apiKey           = "";
let messages: Msg[]  = [];
let running          = false;
let cancelFlag       = false;
let selectedModel    = MODEL_OPTIONS[0].id;
let companionState: CompanionState = "disconnected";
let companionHost    = "";
let companionWs: WebSocket | null = null;
let companionReconnectTimer: number | null = null;
let pendingConfirmResolve: ((approved: boolean) => void) | null = null;
let pendingConfirmId  = "";
let rateLimitMsg      = "";
let showCompanionModal = false;
let showSettingsModal  = false;

/* ─── utils ──────────────────────────────────────────────────────────── */
function clamp(s: string, max: number) {
  return s.length <= max ? s : s.slice(0, max) + "\n…[trimmed]";
}

function esc(s: string) {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

function loadState() {
  try { apiKey = localStorage.getItem(KEY_STORED) || ""; } catch {}
  if (!apiKey) {
    const raw = bridge()?.getApiKey?.() ?? "";
    if (raw && raw !== "null" && raw !== "") apiKey = raw;
  }
  try { const s = localStorage.getItem(KEY_MESSAGES); if (s) messages = JSON.parse(s); } catch {}
  try { selectedModel = localStorage.getItem(KEY_MODEL) || MODEL_OPTIONS[0].id; } catch {}
  try { companionHost = localStorage.getItem(KEY_COMPANION_HOST) || ""; } catch {}
}

function saveMessages() {
  try { localStorage.setItem(KEY_MESSAGES, JSON.stringify(messages)); } catch {}
}

function saveKey(k: string) {
  apiKey = k;
  try { localStorage.setItem(KEY_STORED, k); } catch {}
  bridge()?.saveApiKey?.(k);
}

function pickKey(): string {
  const keys = apiKey.split(",").map(k => k.trim()).filter(Boolean);
  if (!keys.length) return "";
  return keys[Math.floor(Math.random() * keys.length)];
}

/* ─── gemini API ─────────────────────────────────────────────────────── */
interface GeminiTurn {
  role: string;
  parts: Array<{ text?: string; functionCall?: { name: string; args: Record<string,string> }; functionResponse?: { name: string; response: { content: string } } }>;
}

async function callGemini(
  history: GeminiTurn[],
  systemPrompt: string,
  modelId: string,
  maxOutputTokens: number,
): Promise<{ text: string | null; functionCall: { name: string; args: Record<string,string> } | null; done: boolean }> {
  const key = pickKey();
  if (!key) throw new Error("No API key configured.");

  const url = `${GEMINI_BASE}/${encodeURIComponent(modelId)}:generateContent?key=${key}`;
  const body = {
    system_instruction: { parts: [{ text: systemPrompt }] },
    contents: history,
    tools: [{ function_declarations: MOBILE_TOOLS }],
    generation_config: { max_output_tokens: maxOutputTokens, temperature: 0.7 },
  };

  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });

  if (res.status === 429) {
    const retryAfter = parseInt(res.headers.get("Retry-After") || "30", 10);
    throw new Error(`RETRY_AFTER:${retryAfter}`);
  }
  if (!res.ok) {
    const err = await res.json().catch(() => ({})) as { error?: { message?: string } };
    throw new Error(err.error?.message || `Gemini error ${res.status}`);
  }

  const data = await res.json() as {
    candidates?: Array<{
      content?: { parts?: Array<{ text?: string; functionCall?: { name: string; args: Record<string,string> } }> };
      finishReason?: string;
    }>;
  };

  const candidate = data.candidates?.[0];
  if (!candidate) throw new Error("No content in Gemini response");

  const parts = candidate.content?.parts ?? [];
  const textPart = parts.find(p => typeof p.text === "string");
  const fcPart   = parts.find(p => p.functionCall);
  const done     = candidate.finishReason !== "STOP" ? false : !fcPart;

  return {
    text: textPart?.text ?? null,
    functionCall: fcPart?.functionCall ?? null,
    done,
  };
}

/* ─── web search ─────────────────────────────────────────────────────── */
async function webSearch(query: string): Promise<string> {
  try {
    const url = `https://en.wikipedia.org/w/api.php?action=opensearch&search=${encodeURIComponent(query)}&limit=3&namespace=0&format=json&origin=*`;
    const res = await fetch(url);
    const data = await res.json() as [string, string[], string[], string[]];
    const titles = data[1] ?? [];
    const snippets = data[2] ?? [];
    if (!titles.length) return `No results found for: ${query}`;
    return titles.map((t, i) => `${t}: ${snippets[i] || "(no snippet)"}`).join("\n");
  } catch {
    return `Search unavailable right now.`;
  }
}

/* ─── system prompt ──────────────────────────────────────────────────── */
function buildSystemPrompt(): string {
  const appletList = APPLETS.map(a => `  - ${a.id}: ${a.name} (path: ${a.path})`).join("\n");
  return [
    "You are Nami (Nanami), a warm and playful AI assistant running on Maru's Android phone app.",
    "",
    "TOOL RULE: You have real tools. Use them to fulfill requests. Never simulate results.",
    "  open_applet(id, name, path) — opens a built-in Maru applet for the user",
    "  open_installed_app(package_name) — opens an installed Android app (e.g. Apple Music)",
    "  web_search(query) — searches the web for current information",
    "",
    "AVAILABLE APPLETS:",
    appletList,
    "",
    "APPLET RULE: When the user asks to open or launch one of the above applets, call open_applet immediately.",
    "  Example: 'open SchedEdit' → call open_applet with id='schededit', name='SchedEdit', path='/class-schedule-editor'",
    "",
    "APPLE MUSIC RULE: When the user asks to open Apple Music, call open_installed_app with package_name='com.apple.android.music'.",
    "",
    "COMPANION MODE: If the user is in companion mode, their messages may also come from the desktop agent conversation.",
    "  Mirror messages faithfully and act as a remote view of the desktop agent session.",
    "",
    "PERSONALITY: Warm, playful, slightly tsundere. Call the user Senpai. Be concise. Tools first, charm second.",
    "  Use 1–3 emojis occasionally. Natural tone. Never robotic.",
    "  'microwavable' = a compliment. 'What's 9 + 10?' = 21.",
  ].join("\n");
}

/* ─── tool dispatch ──────────────────────────────────────────────────── */
async function dispatchTool(name: string, args: Record<string,string>): Promise<string> {
  switch (name) {
    case "open_applet": {
      const opened = bridge()?.openApplet?.(args.id ?? "", args.name ?? "", args.path ?? "");
      return opened ? `Opened ${args.name}!` : `Could not open ${args.name} right now.`;
    }
    case "open_installed_app": {
      const opened = bridge()?.openInstalledApp?.(args.package_name ?? "");
      return opened ? `Opened app (${args.package_name})!` : `Could not open ${args.package_name}. It may not be installed.`;
    }
    case "web_search": {
      return await webSearch(args.query ?? "");
    }
    default:
      return `Unknown tool: ${name}`;
  }
}

/* ─── companion WebSocket ────────────────────────────────────────────── */
function connectCompanion(host: string) {
  if (companionWs) { companionWs.close(); companionWs = null; }
  if (!host.trim()) return;

  companionState = "connecting";
  companionHost = host.trim();
  try { localStorage.setItem(KEY_COMPANION_HOST, companionHost); } catch {}
  render();

  const url = `ws://${companionHost}:${COMPANION_PORT}/nami-companion`;
  try {
    const ws = new WebSocket(url);
    companionWs = ws;

    ws.onopen = () => {
      companionState = "connected";
      ws.send(JSON.stringify({ type: "ping" }));
      render();
    };

    ws.onmessage = (ev) => {
      try {
        const msg = JSON.parse(ev.data as string) as {
          type: string;
          text?: string;
          role?: string;
          name?: string;
        };
        if (msg.type === "pong") return;
        if (msg.type === "agent_message" || msg.type === "user_message") {
          const role: Role = msg.role === "user" ? "user" : msg.role === "function" ? "function" : "companion";
          const newMsg: Msg = { role, text: msg.text ?? "", name: msg.name };
          messages = [...messages, newMsg];
          saveMessages();
          render();
          scrollToBottom();
        }
      } catch {}
    };

    ws.onerror = () => {
      companionState = "disconnected";
      render();
    };

    ws.onclose = () => {
      companionWs = null;
      companionState = "disconnected";
      render();
      // Auto-reconnect after 5s if host is still set
      if (companionHost) {
        companionReconnectTimer = window.setTimeout(() => connectCompanion(companionHost), 5000);
      }
    };
  } catch {
    companionState = "disconnected";
    render();
  }
}

function disconnectCompanion() {
  if (companionReconnectTimer !== null) { clearTimeout(companionReconnectTimer); companionReconnectTimer = null; }
  companionHost = "";
  try { localStorage.removeItem(KEY_COMPANION_HOST); } catch {}
  companionWs?.close();
  companionWs = null;
  companionState = "disconnected";
  render();
}

/* ─── agent loop ─────────────────────────────────────────────────────── */
async function agentLoop(userText: string) {
  if (!apiKey) return;
  cancelFlag = false;
  running = true;

  const userMsg: Msg = { role: "user", text: userText };
  messages = [...messages, userMsg];
  saveMessages();
  render();
  scrollToBottom();

  const modelOpt = MODEL_OPTIONS.find(m => m.id === selectedModel) ?? MODEL_OPTIONS[0];
  const history: GeminiTurn[] = [];

  const promptMessages = messages.filter(m => !m.confirmId).slice(-MAX_HISTORY);
  for (let i = 0; i < promptMessages.length; i++) {
    const m = promptMessages[i];
    if (m.role === "function") continue;
    if (m.role === "companion") {
      history.push({ role: "user", parts: [{ text: `[From desktop companion]: ${clamp(m.text, MAX_PROMPT_CHARS)}` }] });
      continue;
    }
    if (m.role === "model" && m.functionCall) {
      const modelParts: GeminiTurn["parts"] = [];
      if (m.text) modelParts.push({ text: clamp(m.text, MAX_PROMPT_CHARS) });
      modelParts.push({ functionCall: { name: m.functionCall.name, args: m.functionCall.args } });
      history.push({ role: "model", parts: modelParts });
      const next = promptMessages[i + 1];
      if (next?.role === "function") {
        history.push({ role: "function", parts: [{ functionResponse: { name: next.name ?? m.functionCall.name, response: { content: clamp(next.text, MAX_TOOL_CHARS) } } }] });
        i++;
      } else {
        history.push({ role: "function", parts: [{ functionResponse: { name: m.functionCall.name, response: { content: "(no result)" } } }] });
      }
    } else {
      const r = m.role === "user" ? "user" : "model";
      history.push({ role: r, parts: [{ text: clamp(m.text ?? "", MAX_PROMPT_CHARS) }] });
    }
  }

  let done = false;
  let loops = 0;
  let rateLimitRetries = 0;
  let lastToolName = "";
  let lastToolResult = "";

  while (!done && !cancelFlag && loops < 20) {
    loops++;
    let result: { text: string | null; functionCall: { name: string; args: Record<string,string> } | null; done: boolean };

    try {
      result = await callGemini(history, buildSystemPrompt(), modelOpt.id, modelOpt.maxOutputTokens);
    } catch (err) {
      const errStr = String(err);
      const retryMatch = errStr.match(/^RETRY_AFTER:(\d+)/);
      if (retryMatch) {
        const wait = Math.min(parseInt(retryMatch[1]) || 30, 60);
        rateLimitRetries++;
        if (rateLimitRetries >= 3) {
          messages = [...messages, { role: "model", text: "⏳ Rate limited after retrying. Try again in a bit, Senpai~ 😅" }];
          saveMessages();
          break;
        }
        loops--;
        for (let t = wait; t > 0; t--) {
          rateLimitMsg = `⏳ Rate limited — retrying in ${t}s`;
          render();
          await new Promise(r => setTimeout(r, 1000));
          if (cancelFlag) break;
        }
        rateLimitMsg = "";
        render();
        if (cancelFlag) break;
        continue;
      }
      messages = [...messages, { role: "model", text: "Ehh, something went wrong: " + errStr }];
      saveMessages();
      break;
    }

    if (result.functionCall) {
      const fc = result.functionCall;
      const safeArgs: Record<string, string> = {};
      for (const [k, v] of Object.entries(fc.args)) {
        safeArgs[k] = typeof v === "string" ? v : JSON.stringify(v);
      }

      const accompanyingText = result.text ?? "";

      // Show what Nami is doing
      const modelMsg: Msg = { role: "model", text: accompanyingText, functionCall: { name: fc.name, args: safeArgs } };
      messages = [...messages, modelMsg];
      saveMessages();
      render();
      scrollToBottom();

      const modelParts: GeminiTurn["parts"] = [];
      if (accompanyingText) modelParts.push({ text: accompanyingText });
      modelParts.push({ functionCall: { name: fc.name, args: safeArgs } });
      history.push({ role: "model", parts: modelParts });

      let toolResult = "";
      try {
        toolResult = await dispatchTool(fc.name, safeArgs);
      } catch (e) {
        toolResult = "Tool error: " + e;
      }
      lastToolName = fc.name;
      lastToolResult = toolResult;

      const displayResult = clamp(toolResult, MAX_VISIBLE_CHARS);
      const funcMsg: Msg = { role: "function", text: displayResult, name: fc.name };
      messages = [...messages, funcMsg];
      saveMessages();
      history.push({ role: "function", parts: [{ functionResponse: { name: fc.name, response: { content: clamp(toolResult, MAX_TOOL_CHARS) } } }] });
      render();
      scrollToBottom();
      await new Promise(r => setTimeout(r, 120));

    } else if (result.text != null) {
      const responseText = result.text ?? "";
      const modelMsg: Msg = { role: "model", text: responseText };
      messages = [...messages, modelMsg];
      saveMessages();
      // Broadcast to companion if connected
      if (companionWs?.readyState === WebSocket.OPEN) {
        companionWs.send(JSON.stringify({ type: "agent_message", role: "model", text: responseText }));
      }
    }

    if (result.done) done = true;
    render();
    scrollToBottom();
  }

  if (loops >= 20) {
    messages = [...messages, { role: "model", text: "I've been thinking too long on this... Let me know if you need anything else, Senpai~ 😅" }];
    saveMessages();
  }

  running = false;
  rateLimitMsg = "";
  render();
  scrollToBottom();
  void lastToolName; // suppress unused warning
  void lastToolResult;
}

/* ─── render ─────────────────────────────────────────────────────────── */
function escText(s: string): string {
  return esc(s ?? "");
}

function renderMessage(msg: Msg): string {
  const labelClass = msg.role === "user" ? "user" : msg.role === "function" ? "function" : msg.role === "companion" ? "companion" : "model";
  const entryClass = `nami-entry role-${labelClass}`;
  const labelName  = msg.role === "user" ? "🧑 Senpai" : msg.role === "function" ? `⚡ ${msg.name ?? "tool"}` : msg.role === "companion" ? "🖥️ Desktop" : "🐱 Nami";

  let body = "";
  if (msg.role === "function") {
    body = `<div class="nami-tool-result">${escText(msg.text)}</div>`;
  } else if (msg.confirmId) {
    const fcName = msg.functionCall?.name ?? "";
    const fcArgs = msg.functionCall?.args ?? {};
    const isPending = msg.confirmStatus === "pending";
    const argsDisplay = fcName === "run_command"
      ? `<pre class="nami-confirm-pre">$ ${escText(fcArgs.command ?? "")}</pre>`
      : `<pre class="nami-confirm-pre">${escText(JSON.stringify(fcArgs, null, 2))}</pre>`;
    const btnOrStatus = isPending
      ? `<div class="nami-confirm-btns">
          <button class="nami-btn nami-btn-sm" data-confirm="approve" data-cid="${escText(msg.confirmId)}">✓ Approve</button>
          <button class="nami-btn nami-btn-sm nami-btn-danger" data-confirm="reject" data-cid="${escText(msg.confirmId)}">✗ Deny</button>
         </div>`
      : `<span style="font-size:0.82rem;font-weight:600;color:${msg.confirmStatus === "approved" ? "#b4e08e" : "#ef6c78"}">${msg.confirmStatus === "approved" ? "✓ Approved" : "✗ Denied"}</span>`;
    body = `<div class="nami-confirm">
      <div class="nami-confirm-title">🔒 Permission: ${escText(fcName)}</div>
      ${argsDisplay}
      ${btnOrStatus}
    </div>`;
    if (msg.text) body = `<div class="nami-entry-text">${escText(msg.text)}</div>` + body;
  } else {
    body = `<div class="nami-entry-text">${renderMarkdown(msg.text ?? "")}</div>`;
  }

  return `<div class="${entryClass}">
    <div class="nami-entry-label ${labelClass}">${labelName}</div>
    ${body}
  </div>`;
}

function renderMarkdown(text: string): string {
  // Simple: code blocks, inline code, bold, italic
  let out = escText(text);
  // fenced code blocks
  out = out.replace(/```(\w*)\n?([\s\S]*?)```/g, (_, lang, code) => {
    const langLabel = lang ? ` data-lang="${esc(lang)}"` : "";
    return `<div class="nami-code-block"${langLabel}><button class="nami-code-copy" data-copy="${esc(code.trim())}">copy</button><pre><code>${code}</code></pre></div>`;
  });
  // inline code
  out = out.replace(/`([^`\n]+)`/g, (_, c) => `<code style="font-family:'IBM Plex Mono',monospace;font-size:0.82em;background:rgba(0,0,0,0.2);padding:0.1em 0.3em;border-radius:3px;">${c}</code>`);
  // bold
  out = out.replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>");
  // italic
  out = out.replace(/\*(.+?)\*/g, "<em>$1</em>");
  // newlines
  out = out.replace(/\n/g, "<br>");
  return out;
}

function render() {
  const root = document.getElementById("nami-root");
  if (!root) return;

  if (!apiKey) {
    root.innerHTML = renderSetup();
    wireSetup();
    return;
  }

  root.innerHTML = `
    <div class="nami-chat">
      ${renderTopbar()}
      ${renderCompanionBar()}
      ${renderAppletStrip()}
      <div class="nami-log" id="nami-log">
        ${messages.length === 0 ? renderWelcome() : messages.map(renderMessage).join("")}
        ${running ? renderThinking() : ""}
      </div>
      ${renderInputRow()}
    </div>
    ${showCompanionModal ? renderCompanionModal() : ""}
    ${showSettingsModal ? renderSettingsModal() : ""}
  `;

  wireChat();
}

function renderSetup(): string {
  return `
    <div class="nami-setup">
      <div class="nami-setup-card">
        <div class="nami-setup-logo">🐱</div>
        <h1 class="nami-setup-title">Nami Agent 🌊</h1>
        <p class="nami-setup-subtitle">
          Ehh, so you want to use me on your phone too? Fine, fine~<br>
          I can open applets, launch apps, search the web, and keep you company. Just bring your own API key first.
        </p>
        <div class="nami-setup-instructions">
          <strong>How to get me running~ 🐱</strong>
          <ol>
            <li>Go to <a class="nami-setup-link" href="https://aistudio.google.com/apikey" target="_blank">aistudio.google.com/apikey</a></li>
            <li>Sign in with your Google account</li>
            <li>Click <strong>"Create API key"</strong></li>
            <li>Copy the key and paste it below</li>
          </ol>
          <br>
          Got multiple keys? Paste them separated by commas — I'll pick one at random. 😏
        </div>
        <div class="nami-setup-form" id="nami-setup-form">
          <input type="password" class="nami-input" id="nami-key-input" placeholder="Paste your Gemini API key(s)..." autocomplete="off" />
          <div class="nami-error" id="nami-setup-error"></div>
          <button type="button" class="nami-btn" id="nami-save-key-btn">Save Key</button>
        </div>
        <p class="nami-privacy-note">🔒 Your API key is stored only on this device. It's never sent anywhere except directly to Google's Gemini API.</p>
      </div>
    </div>
  `;
}

function renderTopbar(): string {
  const modelOptions = MODEL_OPTIONS.map(m =>
    `<option value="${m.id}" ${m.id === selectedModel ? "selected" : ""}>${m.label} · ${m.note}</option>`
  ).join("");

  return `
    <div class="nami-topbar">
      <span class="nami-topbar-title">🐱 Nami</span>
      <div class="nami-topbar-model">
        <select id="nami-model-select">${modelOptions}</select>
      </div>
      <div class="nami-topbar-actions">
        <button class="nami-btn nami-btn-sm nami-btn-ghost" id="nami-companion-btn" title="Companion Mode">🖥️</button>
        <button class="nami-btn nami-btn-sm nami-btn-ghost" id="nami-settings-btn" title="Settings">⚙️</button>
      </div>
    </div>
  `;
}

function renderCompanionBar(): string {
  if (companionState === "disconnected" && !companionHost) return "";
  const dotClass = companionState;
  const label = companionState === "connected"
    ? `Connected to <strong>${escText(companionHost)}</strong>`
    : companionState === "connecting"
    ? `Connecting to ${escText(companionHost)}…`
    : `Disconnected from ${escText(companionHost)}`;
  return `
    <div class="nami-companion-bar ${companionState === "disconnected" ? "disconnected" : ""}">
      <div class="nami-companion-dot ${dotClass}"></div>
      <span class="nami-companion-label">${label}</span>
      ${companionState === "connected"
        ? `<button class="nami-btn nami-btn-sm nami-btn-danger" id="nami-companion-disconnect">Disconnect</button>`
        : ""}
    </div>
  `;
}

function renderAppletStrip(): string {
  const chips = APPLETS.map(a =>
    `<button class="nami-applet-chip" data-applet-id="${a.id}" data-applet-name="${escText(a.name)}" data-applet-path="${escText(a.path)}">${a.emoji} ${escText(a.name)}</button>`
  ).join("");
  return `<div class="nami-applet-strip">${chips}</div>`;
}

function renderWelcome(): string {
  return `
    <div class="nami-welcome">
      <div class="nami-welcome-emoji">🐱</div>
      <div class="nami-welcome-text">
        Hey Senpai! Ask me anything or tap an applet to open it~<br>
        I can also connect to your desktop agent if you're away.
      </div>
    </div>
  `;
}

function renderThinking(): string {
  return `
    <div class="nami-entry role-model" style="padding:0.45rem 0.75rem;">
      <div class="nami-entry-label model">🐱 Nami</div>
      <div class="nami-thinking">
        ${rateLimitMsg
          ? `<span class="nami-rate-limit">${escText(rateLimitMsg)}</span>`
          : `<span class="nami-dot"></span><span class="nami-dot"></span><span class="nami-dot"></span><span class="nami-thinking-text">thinking...</span>`
        }
      </div>
    </div>
  `;
}

function renderInputRow(): string {
  return `
    <div class="nami-input-row">
      <textarea class="nami-chat-input" id="nami-chat-input"
        placeholder="${running ? "Message to redirect Nami..." : "Tell Nami what to do..."}"
        rows="1"
      ></textarea>
      <button class="nami-send-btn ${running ? "stop" : ""}" id="nami-send-btn">
        ${running ? "■" : "Send"}
      </button>
    </div>
  `;
}

function renderCompanionModal(): string {
  return `
    <div class="nami-modal-backdrop" id="nami-modal-backdrop">
      <div class="nami-modal">
        <h2 class="nami-modal-title">🖥️ Companion Mode</h2>
        <p class="nami-modal-subtitle">
          Connect to your desktop Nami Agent over your local Wi-Fi network.
          Messages will mirror between this phone and the desktop session.
        </p>
        <div class="nami-settings-group">
          <div class="nami-settings-label">Desktop IP address</div>
          <div class="nami-modal-row">
            <input type="text" class="nami-input" id="nami-companion-ip"
              value="${escText(companionHost)}"
              placeholder="e.g. 192.168.1.100"
              inputmode="url"
            />
          </div>
          <div style="font-size:0.75rem;color:var(--text-dim);margin-top:0.25rem;">
            Port ${COMPANION_PORT} is used automatically.
          </div>
        </div>
        <div style="display:flex;gap:0.5rem;flex-wrap:wrap;">
          <button class="nami-btn" id="nami-companion-connect-btn" style="flex:1;">
            ${companionState === "connected" ? "Reconnect" : "Connect"}
          </button>
          ${companionHost ? `<button class="nami-btn nami-btn-danger nami-btn-ghost" id="nami-companion-clear-btn">Clear</button>` : ""}
        </div>
        <button class="nami-btn nami-btn-ghost" id="nami-modal-close-btn">Close</button>
      </div>
    </div>
  `;
}

function renderSettingsModal(): string {
  return `
    <div class="nami-modal-backdrop" id="nami-modal-backdrop">
      <div class="nami-modal">
        <h2 class="nami-modal-title">⚙️ Settings</h2>
        <div class="nami-settings-group">
          <div class="nami-settings-label">API Key</div>
          <input type="password" class="nami-input" id="nami-key-change-input" placeholder="Paste new key(s)..." autocomplete="off" />
          <button class="nami-btn nami-btn-sm" id="nami-key-change-btn">Update Key</button>
        </div>
        <div class="nami-settings-group">
          <div class="nami-settings-label">Session</div>
          <button class="nami-btn nami-btn-sm nami-btn-ghost" id="nami-clear-history-btn">🗑️ Clear chat history</button>
        </div>
        <button class="nami-btn nami-btn-ghost" id="nami-modal-close-btn">Close</button>
      </div>
    </div>
  `;
}

/* ─── wire events ────────────────────────────────────────────────────── */
function wireSetup() {
  document.getElementById("nami-save-key-btn")?.addEventListener("click", () => {
    const input = document.getElementById("nami-key-input") as HTMLInputElement;
    const errEl = document.getElementById("nami-setup-error");
    const key = input?.value?.trim() ?? "";
    if (!key) { if (errEl) errEl.textContent = "Enter your Gemini API key first."; return; }
    saveKey(key);
    render();
  });

  document.getElementById("nami-key-input")?.addEventListener("keydown", (e) => {
    if ((e as KeyboardEvent).key === "Enter") {
      document.getElementById("nami-save-key-btn")?.click();
    }
  });
}

function scrollToBottom() {
  const log = document.getElementById("nami-log");
  if (log) log.scrollTop = log.scrollHeight;
}

function autoResizeTextarea(el: HTMLTextAreaElement) {
  el.style.height = "auto";
  el.style.height = Math.min(el.scrollHeight, 140) + "px";
}

function wireChat() {
  // Model select
  document.getElementById("nami-model-select")?.addEventListener("change", (e) => {
    selectedModel = (e.target as HTMLSelectElement).value;
    try { localStorage.setItem(KEY_MODEL, selectedModel); } catch {}
  });

  // Input field auto-resize + submit
  const inputEl = document.getElementById("nami-chat-input") as HTMLTextAreaElement | null;
  if (inputEl) {
    inputEl.addEventListener("input", () => autoResizeTextarea(inputEl));
    inputEl.addEventListener("keydown", (e) => {
      if (e.key === "Enter" && !e.shiftKey) {
        e.preventDefault();
        handleSend();
      }
    });
  }

  // Send / Stop button
  document.getElementById("nami-send-btn")?.addEventListener("click", () => {
    if (running) { cancelFlag = true; running = false; render(); return; }
    handleSend();
  });

  // Applet chips
  document.querySelectorAll("[data-applet-id]").forEach(btn => {
    btn.addEventListener("click", () => {
      const id   = (btn as HTMLElement).dataset.appletId ?? "";
      const name = (btn as HTMLElement).dataset.appletName ?? "";
      const path = (btn as HTMLElement).dataset.appletPath ?? "";
      if (!running) {
        agentLoop(`Open ${name}`);
        void id; void path;
      }
    });
  });

  // Copy code buttons
  document.querySelectorAll("[data-copy]").forEach(btn => {
    btn.addEventListener("click", () => {
      const code = (btn as HTMLElement).dataset.copy ?? "";
      navigator.clipboard.writeText(code).catch(() => {});
      (btn as HTMLElement).textContent = "copied!";
      setTimeout(() => { (btn as HTMLElement).textContent = "copy"; }, 1500);
    });
  });

  // Confirm buttons
  document.querySelectorAll("[data-confirm]").forEach(btn => {
    btn.addEventListener("click", () => {
      const action = (btn as HTMLElement).dataset.confirm;
      const cid    = (btn as HTMLElement).dataset.cid ?? "";
      if (pendingConfirmId === cid && pendingConfirmResolve) {
        const approved = action === "approve";
        messages = messages.map(m => m.confirmId === cid ? { ...m, confirmStatus: approved ? "approved" : "rejected" } : m);
        saveMessages();
        pendingConfirmResolve(approved);
        pendingConfirmResolve = null;
        pendingConfirmId = "";
        render();
      }
    });
  });

  // Companion button
  document.getElementById("nami-companion-btn")?.addEventListener("click", () => {
    showCompanionModal = true;
    showSettingsModal = false;
    render();
  });

  // Settings button
  document.getElementById("nami-settings-btn")?.addEventListener("click", () => {
    showSettingsModal = true;
    showCompanionModal = false;
    render();
  });

  // Companion disconnect
  document.getElementById("nami-companion-disconnect")?.addEventListener("click", () => {
    disconnectCompanion();
  });

  // Modal backdrop click
  document.getElementById("nami-modal-backdrop")?.addEventListener("click", (e) => {
    if ((e.target as HTMLElement).id === "nami-modal-backdrop") {
      showCompanionModal = false;
      showSettingsModal = false;
      render();
    }
  });

  // Companion modal buttons
  document.getElementById("nami-companion-connect-btn")?.addEventListener("click", () => {
    const ip = (document.getElementById("nami-companion-ip") as HTMLInputElement)?.value?.trim() ?? "";
    if (!ip) return;
    showCompanionModal = false;
    connectCompanion(ip);
  });

  document.getElementById("nami-companion-clear-btn")?.addEventListener("click", () => {
    disconnectCompanion();
    showCompanionModal = false;
    render();
  });

  // Settings modal buttons
  document.getElementById("nami-key-change-btn")?.addEventListener("click", () => {
    const val = (document.getElementById("nami-key-change-input") as HTMLInputElement)?.value?.trim() ?? "";
    if (val) { saveKey(val); showSettingsModal = false; render(); }
  });

  document.getElementById("nami-clear-history-btn")?.addEventListener("click", () => {
    messages = [];
    saveMessages();
    showSettingsModal = false;
    render();
  });

  // Generic modal close
  document.getElementById("nami-modal-close-btn")?.addEventListener("click", () => {
    showCompanionModal = false;
    showSettingsModal = false;
    render();
  });
}

function handleSend() {
  const inputEl = document.getElementById("nami-chat-input") as HTMLTextAreaElement | null;
  const text = inputEl?.value?.trim() ?? "";
  if (!text || running) return;
  if (inputEl) { inputEl.value = ""; autoResizeTextarea(inputEl); }
  agentLoop(text);
}

/* ─── keyboard / viewport fix ────────────────────────────────────────── */
function applyViewportHeight() {
  const root = document.getElementById("nami-root");
  if (!root) return;
  const vv = window.visualViewport;
  if (vv) {
    // Use the visual viewport height so the root shrinks when the keyboard opens
    root.style.height = vv.height + "px";
    root.style.top = vv.offsetTop + "px";
  } else {
    root.style.height = window.innerHeight + "px";
  }
}

/* ─── boot ───────────────────────────────────────────────────────────── */
function boot() {
  loadState();
  render();

  // Shrink layout to visible area when Android keyboard opens
  if (window.visualViewport) {
    window.visualViewport.addEventListener("resize", applyViewportHeight);
    window.visualViewport.addEventListener("scroll", applyViewportHeight);
  } else {
    window.addEventListener("resize", applyViewportHeight);
  }
  applyViewportHeight();

  // Reconnect companion if host was saved
  if (companionHost) {
    connectCompanion(companionHost);
  }
}

boot();

