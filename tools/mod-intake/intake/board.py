"""P3 board: one self-contained HTML file (<data dir>/board.html) to browse dossiers and triage, and correct it.

Opened as a file, corrections stay in the browser until exported (a JSON file for 'overrides import'). Served by
'board --serve' on 127.0.0.1, they go straight into catalog/overrides.yaml and the catalog is rebuilt from cached
judgments (no git, no API calls). The board holds message text, so it lives in the data folder like the dossiers.

Discord text is untrusted: the page builds every element with textContent, links only for http(s) URLs, and the
data sits in a JSON script tag with <, > and & escaped.
"""
import http.server
import json
import secrets
import threading
from pathlib import Path

from . import triage

MAX_POST_BYTES = 1_000_000
DEFAULT_PORT = 8791


def _json_for_html(value):
    text = json.dumps(value, ensure_ascii=False)
    return (text.replace("&", "\\u0026").replace("<", "\\u003c").replace(">", "\\u003e")
            .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029"))


def render(data, token=""):
    return (PAGE.replace("__TOKEN__", token if token.isalnum() else "")
            .replace("__DATA__", _json_for_html(data)))


def write(run_out, paths):
    html = render(triage.board_data(run_out, paths))
    paths.board.parent.mkdir(parents=True, exist_ok=True)
    part = paths.board.with_suffix(".part")
    part.write_text(html, "utf-8")
    part.replace(paths.board)
    return paths.board


class BoardServer:
    """127.0.0.1 only. POSTs need the page's token (a header other sites cannot send without reading the page) and
    a Host of 127.0.0.1 or localhost (no DNS rebinding)."""

    def __init__(self, run_out, paths, port=DEFAULT_PORT, log=print):
        self.run_out = run_out
        self.paths = paths
        self.token = secrets.token_hex(16)
        self.log = log
        self.lock = threading.Lock()
        handler = self._handler()
        self.httpd = http.server.HTTPServer(("127.0.0.1", port), handler)
        self.port = self.httpd.server_address[1]

    @property
    def url(self):
        return f"http://127.0.0.1:{self.port}/"

    def serve_forever(self):
        self.httpd.serve_forever()

    def shutdown(self):
        self.httpd.shutdown()
        self.httpd.server_close()

    def page(self):
        with self.lock:
            return render(triage.board_data(self.run_out, self.paths), self.token)

    def save(self, unit_id, fields, replace=True):
        with self.lock:
            if unit_id not in {d["id"] for d in self.run_out.dossiers}:
                raise ValueError(f"no thread {unit_id}")
            triage.save_override(self.paths.overrides, unit_id, fields, replace=replace)
            triage.assemble(self.run_out, self.paths)
            triage.write_catalog(self.paths.mods, self.run_out.entries,
                                 triage.summary(self.run_out.entries, self.run_out.model))
            write(self.run_out, self.paths)
            data = triage.board_data(self.run_out, self.paths)
            unit = next(u for u in data["units"] if u["dossier"]["id"] == unit_id)
            return {"unit": unit, "overrides": data["overrides"]}

    def _handler(self):
        server = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, fmt, *args):
                pass

            def _host_ok(self):
                host = (self.headers.get("Host") or "").lower()
                return host in (f"127.0.0.1:{server.port}", f"localhost:{server.port}")

            def _send(self, status, body, content_type):
                data = body.encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(len(data)))
                self.send_header("Cache-Control", "no-store")
                self.send_header("X-Content-Type-Options", "nosniff")
                self.send_header("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'; "
                                 "style-src 'unsafe-inline'; connect-src 'self'; img-src 'self' data:")
                self.end_headers()
                self.wfile.write(data)

            def do_GET(self):
                if not self._host_ok():
                    return self._send(403, "wrong host", "text/plain; charset=utf-8")
                if self.path not in ("/", "/index.html"):
                    return self._send(404, "not found", "text/plain; charset=utf-8")
                self._send(200, server.page(), "text/html; charset=utf-8")

            def do_POST(self):
                try:
                    length = int(self.headers.get("Content-Length") or 0)
                except ValueError:
                    length = -1
                if not 0 <= length <= MAX_POST_BYTES:
                    self.close_connection = True
                    return self._send(413, json.dumps({"error": "body too large"}), "application/json")
                raw = self.rfile.read(length)  # read before any answer: Windows resets a socket left with unread data
                if not self._host_ok() or self.headers.get("X-Board-Token") != server.token:
                    return self._send(403, json.dumps({"error": "forbidden"}), "application/json")
                if self.path != "/api/override":
                    return self._send(404, json.dumps({"error": "not found"}), "application/json")
                try:
                    body = json.loads(raw.decode("utf-8"))
                    result = server.save(str(body["unit"]), body.get("fields") or {}, bool(body.get("replace", True)))
                except (ValueError, KeyError, TypeError) as e:
                    return self._send(400, json.dumps({"error": str(e)}), "application/json")
                server.log(f"saved correction for {body['unit']}")
                self._send(200, json.dumps(result, ensure_ascii=False), "application/json")

        return Handler


def import_corrections(paths, file_path):
    """Merge a board export ({"overrides": {unit: fields}}) into overrides.yaml; returns how many threads."""
    data = json.loads(Path(file_path).read_text("utf-8"))
    corrections = data.get("overrides") if isinstance(data, dict) else None
    if not isinstance(corrections, dict):
        raise ValueError("not a board export: expected {\"overrides\": {...}}")
    for unit_id, fields in corrections.items():
        triage.validate_override(fields)
    for unit_id, fields in corrections.items():
        triage.save_override(paths.overrides, str(unit_id), fields, replace=True)
    return len(corrections)


PAGE = r"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="board-token" content="__TOKEN__">
<title>Mod catalog board</title>
<style>
:root {
  --bg: #f6f5f2; --panel: #ffffff; --ink: #1d1d1b; --muted: #6b6a66; --line: #e2e0da; --accent: #2f5fb3;
  --chip: #eeece6; --ok: #2e7d4f; --warn: #a86400; --bad: #a33a3a; --info: #2f5fb3; --code: #f1efe9;
  --bar: #c9d6ef; --sel: #e8eefa;
}
@media (prefers-color-scheme: dark) {
  :root:not([data-theme="light"]) {
    --bg: #161615; --panel: #1f1f1d; --ink: #ecebe6; --muted: #a09e97; --line: #34332f; --accent: #8fb0ee;
    --chip: #2b2a27; --ok: #6cc28f; --warn: #e0a64a; --bad: #e57b7b; --info: #8fb0ee; --code: #262522;
    --bar: #33466b; --sel: #24304a;
  }
}
:root[data-theme="dark"] {
  --bg: #161615; --panel: #1f1f1d; --ink: #ecebe6; --muted: #a09e97; --line: #34332f; --accent: #8fb0ee;
  --chip: #2b2a27; --ok: #6cc28f; --warn: #e0a64a; --bad: #e57b7b; --info: #8fb0ee; --code: #262522;
  --bar: #33466b; --sel: #24304a;
}
* { box-sizing: border-box; }
body { margin: 0; background: var(--bg); color: var(--ink); font: 14px/1.45 system-ui, -apple-system, "Segoe UI",
       sans-serif; }
header { padding: 14px 16px 10px; border-bottom: 1px solid var(--line); background: var(--panel); }
h1 { font-size: 17px; margin: 0 0 4px; }
.sub { color: var(--muted); font-size: 12.5px; }
.filters { display: flex; flex-wrap: wrap; gap: 8px; padding: 10px 16px; border-bottom: 1px solid var(--line); }
.filters input[type=search] { flex: 1 1 220px; }
input, select, textarea, button { font: inherit; color: inherit; background: var(--panel); border: 1px solid var(--line);
       border-radius: 6px; padding: 4px 8px; }
button { cursor: pointer; }
button.primary { background: var(--accent); color: var(--panel); border-color: var(--accent); }
main { display: grid; grid-template-columns: minmax(300px, 40%) 1fr; height: calc(100vh - 118px); }
#list { overflow: auto; border-right: 1px solid var(--line); }
#detail { overflow: auto; padding: 14px 18px 40px; }
.row { padding: 8px 14px; border-bottom: 1px solid var(--line); cursor: pointer; }
.row:hover { background: var(--chip); }
.row.sel { background: var(--sel); }
.row .t { font-weight: 600; overflow-wrap: anywhere; }
.row .m { color: var(--muted); font-size: 12px; margin-top: 2px; }
.chip { display: inline-block; padding: 0 7px; border-radius: 10px; background: var(--chip); font-size: 11.5px;
        margin-right: 4px; white-space: nowrap; }
.s-candidate { color: var(--info); } .s-skipped { color: var(--muted); } .s-porting, .s-ported { color: var(--warn); }
.s-shipped { color: var(--ok); } .review { color: var(--bad); } .pending { color: var(--warn); }
h2 { font-size: 16px; margin: 0 0 4px; overflow-wrap: anywhere; }
h3 { font-size: 13px; text-transform: uppercase; letter-spacing: .04em; color: var(--muted); margin: 20px 0 6px; }
.kv { display: grid; grid-template-columns: 130px 1fr; gap: 2px 10px; font-size: 13px; }
.kv div:nth-child(odd) { color: var(--muted); }
pre { background: var(--code); padding: 8px 10px; border-radius: 6px; overflow: auto; max-height: 360px;
      white-space: pre-wrap; overflow-wrap: anywhere; font: 12.5px/1.4 ui-monospace, Consolas, monospace; }
.text { white-space: pre-wrap; overflow-wrap: anywhere; }
.msg { border: 1px solid var(--line); border-radius: 8px; padding: 8px 10px; margin: 8px 0; background: var(--panel); }
.msg .h { color: var(--muted); font-size: 12px; margin-bottom: 4px; display: flex; gap: 8px; flex-wrap: wrap;
          align-items: center; }
.bars { display: grid; grid-template-columns: 140px 1fr 44px; gap: 3px 8px; align-items: center; font-size: 12.5px; }
.bar { height: 9px; border-radius: 5px; background: var(--bar); }
.why li { margin: 2px 0; }
.form { display: grid; grid-template-columns: 120px 1fr; gap: 6px 10px; align-items: center; max-width: 640px; }
.form textarea { min-height: 54px; }
.note { padding: 8px 10px; border-radius: 6px; background: var(--chip); margin: 8px 0; font-size: 13px; }
a { color: var(--accent); overflow-wrap: anywhere; }
details > summary { cursor: pointer; color: var(--muted); }
.empty { color: var(--muted); padding: 20px; }
@media (max-width: 760px) {
  main { grid-template-columns: 1fr; height: auto; }
  #list { max-height: 45vh; border-right: 0; border-bottom: 1px solid var(--line); }
  .kv, .form { grid-template-columns: 1fr; }
}
</style>
</head>
<body>
<header>
  <h1>Mod catalog board</h1>
  <div class="sub" id="summary"></div>
  <div class="sub" id="mode"></div>
</header>
<div class="filters">
  <input type="search" id="q" placeholder="Search title, author, id, text">
  <select id="f-status"></select><select id="f-kind"></select><select id="f-version"></select>
  <select id="f-family"></select><select id="f-applies"></select>
  <label><input type="checkbox" id="f-review"> needs review</label>
  <button id="export" hidden>Export corrections</button>
</div>
<main><section id="list"></section><section id="detail"><div class="empty">Pick a thread.</div></section></main>
<script id="board-data" type="application/json">__DATA__</script>
<script>
"use strict";
const DATA = JSON.parse(document.getElementById("board-data").textContent);
const TOKEN = document.querySelector('meta[name="board-token"]').content;
const LIVE = TOKEN !== "" && location.protocol.startsWith("http");
const STORE_KEY = "mod-board-corrections";
let pending = {};
try { pending = JSON.parse(localStorage.getItem(STORE_KEY) || "{}") || {}; } catch (e) { pending = {}; }
let units = DATA.units;
let selected = null;
const FILTERS = ["status", "kind", "version", "family", "applies"];

function el(tag, attrs, ...kids) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === undefined || v === null || v === false) continue;
    if (k === "class") e.className = v;
    else if (k === "text") e.textContent = v;
    else if (k.startsWith("on")) e.addEventListener(k.slice(2), v);
    else e.setAttribute(k, v === true ? "" : String(v));
  }
  for (const c of kids.flat()) {
    if (c === null || c === undefined || c === false) continue;
    e.append(typeof c === "string" || typeof c === "number" ? document.createTextNode(String(c)) : c);
  }
  return e;
}
function link(url, label) {
  if (typeof url !== "string" || !/^https?:\/\//i.test(url)) return el("span", {text: label || url || ""});
  return el("a", {href: url, target: "_blank", rel: "noopener noreferrer", text: label || url});
}
function chip(text, cls) { return el("span", {class: "chip " + (cls || ""), text: text}); }
function pct(p) { return p === null || p === undefined ? "" : Math.round(p * 100) + "%"; }
function saveLocal() { try { localStorage.setItem(STORE_KEY, JSON.stringify(pending)); } catch (e) {} }

function fillFilters() {
  for (const f of FILTERS) {
    const s = document.getElementById("f-" + f);
    s.append(el("option", {value: "", text: f + ": any"}));
    const values = new Set(DATA.options[f] || []);
    units.forEach(u => { if (u.entry[f]) values.add(u.entry[f]); });
    for (const v of values) s.append(el("option", {value: v, text: v}));
    s.addEventListener("change", renderList);
  }
  document.getElementById("q").addEventListener("input", renderList);
  document.getElementById("f-review").addEventListener("change", renderList);
  document.getElementById("export").addEventListener("click", exportCorrections);
}

function matches(u) {
  const e = u.entry, d = u.dossier;
  for (const f of FILTERS) {
    const v = document.getElementById("f-" + f).value;
    if (v && e[f] !== v) return false;
  }
  if (document.getElementById("f-review").checked && !e.review) return false;
  const q = document.getElementById("q").value.trim().toLowerCase();
  if (!q) return true;
  const hay = [e.id, e.title, e.author, e.thread, d.starter ? d.starter.text : "",
               (d.replies || []).map(r => r.text).join(" ")].join(" ").toLowerCase();
  return q.split(/\s+/).every(w => hay.includes(w));
}

function renderSummary() {
  const n = units.length;
  const count = s => units.filter(u => u.entry.status === s).length;
  const review = units.filter(u => u.entry.review).length;
  const judged = units.filter(u => u.entry.judged).length;
  document.getElementById("summary").textContent =
    `${n} threads: ${count("candidate")} candidate, ${count("skipped")} skipped, ` +
    `${count("porting") + count("ported") + count("shipped")} porting or later; ${review} need review; ` +
    `${judged} judged by Jev`;
  const np = Object.keys(pending).length;
  document.getElementById("mode").textContent = LIVE
    ? "Corrections save to catalog/overrides.yaml and rebuild mods.yaml."
    : `Opened as a file: corrections stay in this browser (${np} so far). Export them, then run ` +
      "'mod_intake.py overrides import <file>', or open the board with 'mod_intake.py board --serve'.";
  document.getElementById("export").hidden = LIVE || np === 0;
}

function renderList() {
  const list = document.getElementById("list");
  list.replaceChildren();
  const shown = units.filter(matches).sort((a, b) =>
    (b.dossier.created_at || "").localeCompare(a.dossier.created_at || ""));
  if (!shown.length) list.append(el("div", {class: "empty", text: "No thread matches."}));
  for (const u of shown) {
    const e = u.entry;
    list.append(el("div", {class: "row" + (selected === e.thread ? " sel" : ""), onclick: () => select(e.thread)},
      el("div", {class: "t", text: e.title}),
      el("div", {class: "m"},
        chip(e.status, "s-" + e.status), e.review ? chip("review", "review") : null,
        pending[e.thread] ? chip("unsaved correction", "pending") : null,
        chip(e.kind || "kind ?"), chip(e.version || "version ?"), e.family ? chip(e.family) : null,
        ` ${e.author || "?"} · ${e.posted || ""} · #${e.channel || ""}`)));
  }
}

function select(thread) {
  selected = thread;
  renderList();
  renderDetail(units.find(u => u.entry.thread === thread));
}

function bars(probs) {
  const box = el("div", {class: "bars"});
  for (const [k, p] of Object.entries(probs || {})) {
    box.append(el("div", {text: k}),
               el("div", {}, el("div", {class: "bar", style: `width:${Math.max(2, Math.round(p * 100))}%`})),
               el("div", {text: pct(p)}));
  }
  return box;
}

function renderDetail(u) {
  const box = document.getElementById("detail");
  box.replaceChildren();
  if (!u) { box.append(el("div", {class: "empty", text: "Pick a thread."})); return; }
  const e = u.entry, d = u.dossier;
  box.append(el("h2", {text: e.title}),
    el("div", {class: "sub"}, d.discord_url ? link(d.discord_url, "open in Discord") : null,
       ` · mod id ${e.id} · thread ${e.thread}`));
  box.append(el("div", {class: "kv"},
    el("div", {text: "status"}), el("div", {}, chip(e.status, "s-" + e.status), e.reason ? " " + e.reason : "",
                                    e.review ? chip("review", "review") : null),
    el("div", {text: "kind"}), el("div", {text: [e.kind, ...(e.includes || []).filter(k => k !== e.kind)]
                                                .filter(Boolean).join(", ") || "?"}),
    el("div", {text: "version / family"}), el("div", {text: `${e.version || "?"} / ${e.family || "?"}`}),
    el("div", {text: "applies"}), el("div", {text: (e.applies || "?") + (e.match ? ` (closest: ${e.match})` : "")}),
    el("div", {text: "author"}), el("div", {text: `${e.author || "?"}, ${e.posted || ""}, #${e.channel || ""}`}),
    el("div", {text: "tags"}), el("div", {text: (d.tags || []).join(", ") || "none"}),
    el("div", {text: "replies"}), el("div", {text: `${e.replies.total} (fix ${e.replies.fix || 0}, bug report ` +
                                                  `${e.replies.bug_report || 0})`})));
  if (e.overridden && e.overridden.length) {
    box.append(el("div", {class: "note"}, "Corrected by hand: " + e.overridden.join(", ") +
      (e.auto ? ". Automatic values: " + Object.entries(e.auto).map(([k, v]) =>
        `${k}=${typeof v === "object" && v ? JSON.stringify(v) : v}`).join(", ") : "") +
      (e.note ? ". Note: " + e.note : "")));
  }
  if (pending[e.thread]) box.append(el("div", {class: "note pending"},
    "Unsaved correction in this browser: " + JSON.stringify(pending[e.thread])));

  box.append(el("h3", {text: "Why"}), el("ul", {class: "why"}, (u.why || []).map(w => el("li", {text: w}))));
  if (e.judged) {
    box.append(el("h3", {text: "Jev judgments (" + (e.judged.model || "") + ")"}));
    for (const q of ["kind", "version", "family", "applies", "match"]) {
      if (e.judged[q]) box.append(el("div", {class: "sub", text: q}), bars(e.judged[q]));
    }
    if (e.judged.includes) box.append(el("div", {class: "sub", text: "includes (yes probability)"}),
                                      bars(e.judged.includes));
  }
  const ev = d.evidence.release;
  box.append(el("h3", {text: "Rule evidence"}), el("div", {class: "kv"},
    ...evidenceRows(ev)));
  box.append(el("h3", {text: "Correct"}), correctionForm(u));
  box.append(el("h3", {text: "First post"}));
  if (d.starter) box.append(message(d.starter, null));
  else box.append(el("div", {class: "empty", text: "The first post is not in the store."}));
  if ((d.revisions || []).length) {
    box.append(el("details", {}, el("summary", {text: `${d.revisions.length} earlier version(s)`}),
      ...d.revisions.map(r => el("div", {class: "msg"}, el("div", {class: "h", text: `until ${r.replaced_at || "?"}`}),
                                el("div", {class: "text", text: r.text || ""})))));
  }
  box.append(el("h3", {text: `Replies (${(d.replies || []).length})`}));
  for (const r of d.replies || []) box.append(message(r, u));
}

function evidenceRows(ev) {
  const rows = [];
  const add = (k, v) => { if (v && (!Array.isArray(v) || v.length) && (typeof v !== "object" || Object.keys(v).length))
    rows.push(el("div", {text: k}), el("div", {text: Array.isArray(v) ? v.map(x => typeof x === "object" ?
      `${x.address} (${x.our_modules.join(", ")})` : x).join(", ") : typeof v === "object" ?
      Object.entries(v).map(([a, b]) => `${a} ${b}`).join(", ") : String(v)})); };
  add("addresses", ev.addresses);
  add("shared with our client", ev.addresses_our_client_also_uses);
  add("layouts", ev.path_layouts);
  add("file types", ev.file_extensions);
  add("Java packages", ev.java_packages);
  add("class names", ev.identifiers);
  add("names", ev.family_words);
  add("version words", ev.version_words);
  add("code", ev.code_languages);
  add("attachments", ev.attachment_types);
  add("paths in text", ev.file_paths_in_text);
  if (!rows.length) rows.push(el("div", {text: "none"}), el("div", {}));
  return rows;
}

function message(m, u) {
  const cls = u && u.replies ? u.replies[m.id] : null;
  const head = el("div", {class: "h"}, el("strong", {text: m.author || "?"}), m.created_at ? m.created_at.slice(0, 16) : "",
    m.edited_at ? "(edited)" : null, m.by_release_author ? chip("release author") : null, m.bot ? chip("bot") : null,
    cls ? chip(`${cls.class}${cls.p !== null && cls.p !== undefined ? " " + pct(cls.p) : ""}${cls.corrected ? " (hand)" : ""}`,
               cls.class === "fix" ? "s-shipped" : cls.class === "bug_report" ? "review" : "") : null);
  const body = el("div", {class: "msg"}, head, el("div", {class: "text", text: m.text || ""}));
  for (const b of m.code_blocks || []) body.append(el("div", {class: "sub", text: b.language + (b.guessed ? " (guessed)" : "")}),
                                                   el("pre", {text: b.text}));
  for (const a of m.attachments || []) {
    body.append(el("div", {}, chip("file"), `${a.filename} · ${a.type} · ${Math.round((a.size || 0) / 1024)} KB · ` +
      (a.sha256 ? "sha256 " + a.sha256.slice(0, 12) : a.status)));
    if (a.excerpt) body.append(el("details", {}, el("summary", {text: "text"}), el("pre", {text: a.excerpt})));
  }
  for (const l of m.links || []) {
    const s = l.snapshot;
    const state = !s ? "not fetched" : s.status === "stored" ? (s.pinned_sha ? "pinned " + s.pinned_sha.slice(0, 12) : "stored")
      : `${s.status}: ${s.error || s.note || ""}`;
    const line = el("div", {}, chip(l.kind), link(l.url), " → " + state,
      l.in_botstory_history === true ? chip("in our history", "s-shipped") : null);
    body.append(line);
    if (l.files) body.append(el("details", {}, el("summary", {text: `${l.files.count} file(s) (${l.files.mode})`}),
      el("pre", {text: l.files.files.map(p => (l.files.status && l.files.status[p] ? l.files.status[p] + " " : "") + p).join("\n") +
        (l.files.count > l.files.files.length ? `\n... ${l.files.count - l.files.files.length} more` : "")})));
    if (l.excerpt) body.append(el("details", {}, el("summary", {text: "text"}), el("pre", {text: l.excerpt})));
  }
  if (u) {
    const sel = el("select", {"data-reply": m.id, "aria-label": "reply class"});
    sel.append(el("option", {value: "", text: "reply class: as judged"}));
    for (const c of DATA.options.reply) sel.append(el("option", {value: c, text: c}));
    const ov = (DATA.overrides[u.entry.thread] || {}).replies || {};
    const pend = ((pending[u.entry.thread] || {}).replies) || {};
    sel.value = pend[m.id] || ov[m.id] || "";
    sel.addEventListener("change", () => {
      const form = document.getElementById("correct");
      if (form) form.dataset.dirty = "1";
    });
    body.append(el("div", {class: "sub"}, "correct: ", sel));
  }
  return body;
}

function autoValue(e, f) {
  return e.auto && Object.prototype.hasOwnProperty.call(e.auto, f) ? e.auto[f] : e[f];
}

function correctionForm(u) {
  const e = u.entry;
  const ov = Object.assign({}, DATA.overrides[e.thread] || {}, pending[e.thread] || {});
  const form = el("form", {class: "form", id: "correct"});
  const label = (name, textValue) => el("label", {for: "c-" + name, text: textValue});
  const selectFor = (name, options) => {
    const s = el("select", {name, id: "c-" + name});
    s.append(el("option", {value: "", text: "as triaged (" + (autoValue(e, name) ?? "none") + ")"}));
    for (const o of options) s.append(el("option", {value: o, text: o}));
    s.value = ov[name] !== undefined && ov[name] !== null ? String(ov[name]) : "";
    return s;
  };
  const text = (name, multi) => {
    const t = el(multi ? "textarea" : "input", {name, id: "c-" + name});
    t.value = ov[name] || "";
    return t;
  };
  const matchOptions = ["none", ...DATA.inventory.map(m => m.id)];
  form.append(label("status", "status"), selectFor("status", DATA.options.status),
    label("reason", "reason"), text("reason"),
    label("kind", "kind"), selectFor("kind", DATA.options.kind),
    label("version", "version"), selectFor("version", DATA.options.version),
    label("family", "family"), selectFor("family", DATA.options.family),
    label("applies", "applies"), selectFor("applies", DATA.options.applies),
    label("match", "same as ours"), selectFor("match", matchOptions),
    label("id", "mod id"), text("id"),
    label("client_feature", "client feature"), text("client_feature"),
    label("server_mod", "server mod"), text("server_mod"),
    label("note", "note"), text("note", true),
    el("span"), el("div", {},
      el("button", {class: "primary", type: "submit", text: LIVE ? "Save" : "Keep in this browser"}), " ",
      el("button", {type: "button", text: "Clear corrections", onclick: () => submit(u, {}, true)}),
      el("span", {class: "sub", id: "save-msg"})));
  form.addEventListener("submit", ev => { ev.preventDefault(); submit(u, collect(form, u), false); });
  return form;
}

function collect(form, u) {
  const fields = {};
  for (const name of ["status", "kind", "version", "family", "applies", "match"]) {
    const v = form.elements[name].value;
    if (v) fields[name] = v === "none" && name === "match" ? "none" : v;
  }
  for (const name of ["reason", "id", "client_feature", "server_mod", "note"]) {
    const v = form.elements[name].value.trim();
    if (v) fields[name] = v;
  }
  const replies = {};
  document.querySelectorAll("select[data-reply]").forEach(s => { if (s.value) replies[s.dataset.reply] = s.value; });
  if (Object.keys(replies).length) fields.replies = replies;
  return fields;
}

async function submit(u, fields, clear) {
  const thread = u.entry.thread;
  const msg = document.getElementById("save-msg");
  if (!LIVE) {
    if (clear || !Object.keys(fields).length) delete pending[thread]; else pending[thread] = fields;
    saveLocal(); renderSummary(); renderList(); renderDetail(u);
    return;
  }
  try {
    const r = await fetch("/api/override", {method: "POST", headers: {"Content-Type": "application/json",
      "X-Board-Token": TOKEN}, body: JSON.stringify({unit: thread, fields: clear ? {} : fields, replace: true})});
    const body = await r.json();
    if (!r.ok) throw new Error(body.error || r.status);
    DATA.overrides = body.overrides;
    units = units.map(x => x.entry.thread === thread ? body.unit : x);
    renderSummary(); renderList(); renderDetail(body.unit);
    const m = document.getElementById("save-msg"); if (m) m.textContent = " saved";
  } catch (err) {
    if (msg) msg.textContent = " not saved: " + err.message;
  }
}

function exportCorrections() {
  const blob = new Blob([JSON.stringify({overrides: pending}, null, 1)], {type: "application/json"});
  const a = el("a", {href: URL.createObjectURL(blob), download: "board-corrections.json"});
  document.body.append(a); a.click(); a.remove();
}

fillFilters();
renderSummary();
renderList();
</script>
</body>
</html>
"""
