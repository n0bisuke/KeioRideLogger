// Keio Ride Logger viewer core: CSV parsing, BLE protocol v1, live history, cell labels.
// No DOM access, so it runs in the browser and in Node tests. Nothing here sends data anywhere.
"use strict";

const KRL = (() => {
  // -------------------------------------------------------------------------
  // Constants shared by CSV and BLE views.
  const GPS_GAP_MS = 5000;        // longer GPS gaps break lines
  const SIGNAL_GAP_MS = 10000;    // longer serving-cell gaps break lines
  const NEAR_MS = 5000;           // max distance when tying an event to a GPS point

  // -------------------------------------------------------------------------
  // CSV (RFC 4180 style quotes, LF/CRLF, BOM).
  function parseCsv(text) {
    if (text.charCodeAt(0) === 0xfeff) text = text.slice(1);
    const records = [];
    let row = [], field = "", quoted = false, i = 0;
    const n = text.length;
    while (i < n) {
      const c = text[i];
      if (quoted) {
        if (c === '"') {
          if (text[i + 1] === '"') { field += '"'; i += 2; continue; }
          quoted = false; i++; continue;
        }
        field += c; i++; continue;
      }
      if (c === '"' && field === "") { quoted = true; i++; continue; }
      if (c === ",") { row.push(field); field = ""; i++; continue; }
      if (c === "\n" || c === "\r") {
        row.push(field); records.push(row); row = []; field = "";
        i += (c === "\r" && text[i + 1] === "\n") ? 2 : 1;
        continue;
      }
      field += c; i++;
    }
    const unterminated = quoted;
    if (field !== "" || row.length) { row.push(field); records.push(row); }
    return { records, unterminated };
  }

  const num = v => {
    if (v === undefined || v === null || String(v).trim() === "") return null;
    const x = Number(v);
    return Number.isFinite(x) ? x : null;
  };

  const cellLabelRaw = (rat, pci, arfcn) => `${rat || "?"} PCI ${pci ?? "?"} ARFCN ${arfcn ?? "?"}`;
  const srcOf = note => { const m = /src=([a-z_]+)/.exec(note || ""); return m ? m[1] : null; };
  function prevKeyParts(note) {
    const m = /prev=([^;]+)/.exec(note || "");
    if (!m || m[1] === "none") return null;
    const p = m[1].split("/");
    return p.length >= 4 ? { rat: p[0], cellId: p[1] === "?" ? null : p[1], pci: p[2] === "?" ? null : p[2], arfcn: p[3] === "?" ? null : p[3] } : null;
  }

  const KNOWN_EVENTS = new Set(["session_start", "session_stop", "sensor_status", "sensor_rate", "accel", "gyro", "mag",
    "pressure", "gps", "gps_status", "cell", "cell_poll", "cell_change", "cell_status", "display_info", "signal_strength",
    "network", "http_probe"]);

  /** Builds a session model from CSV text. Bad rows are counted, never fatal. */
  function buildCsvSession(text) {
    if (!text || !text.trim()) return { error: "ファイルが空です。" };
    const { records, unterminated } = parseCsv(text);
    const header = records.shift() || [];
    const missing = ["time_utc", "event"].filter(c => !header.includes(c));
    if (missing.length) return { error: `Keio Ride Logger の CSV ではないようです（必要な列 ${missing.join(", ")} がありません）。` };
    const idx = Object.fromEntries(header.map((h, i) => [h, i]));
    const get = (r, col) => (idx[col] === undefined ? "" : (r[idx[col]] ?? ""));

    let brokenRows = unterminated ? 1 : 0, badTimeRows = 0;
    const rows = [];
    for (const r of records) {
      if (r.length === 1 && r[0] === "") continue;
      if (r.length !== header.length) { brokenRows++; continue; }
      const t = Date.parse(get(r, "time_utc"));
      if (!Number.isFinite(t)) { badTimeRows++; continue; }
      rows.push({ t, ev: get(r, "event"), r });
    }
    rows.sort((a, b) => a.t - b.t);
    if (!rows.length) return { error: "読み取れるデータ行がありません。", brokenRows, badTimeRows };

    const startRow = rows.find(x => x.ev === "session_start");
    const stopRow = [...rows].reverse().find(x => x.ev === "session_stop");
    const s = emptySession("csv");
    s.t0 = startRow ? startRow.t : rows[0].t;
    s.t1 = Math.max(stopRow ? stopRow.t : rows[rows.length - 1].t, s.t0 + 1);
    s.hasSessionBounds = !!startRow && !!stopRow;
    s.rows = rows.length; s.brokenRows = brokenRows; s.badTimeRows = badTimeRows;
    s.hasCellColumns = header.includes("registered");

    const seenServing = new Set();
    for (const x of rows) {
      s.counts[x.ev] = (s.counts[x.ev] || 0) + 1;
      const g = col => get(x.r, col);
      switch (x.ev) {
        case "gps": {
          const lat = num(g("lat")), lon = num(g("lon"));
          if (lat === null || lon === null) break;
          const mps = num(g("speed_mps"));
          s.gps.push({ t: x.t, lat, lon, kmh: mps === null ? null : mps * 3.6, acc: num(g("h_accuracy_m")) });
          break;
        }
        case "cell": {
          const reg = g("registered");
          if (reg !== "true" && reg !== "false") break;
          const c = {
            t: x.t, rat: g("rat") || "?", cellId: g("cell_id") || null, pci: g("pci") || null, arfcn: g("arfcn") || null,
            rsrp: num(g("rsrp_dbm")), rsrq: num(g("lte_rsrq_db")) ?? num(g("nr_ss_rsrq_db")),
            snr: num(g("lte_rssnr_db")) ?? num(g("nr_ss_sinr_db")), src: srcOf(g("note")),
          };
          if (reg === "false") { s.neighbors.push(c); break; }
          // Several API paths can report the same sample; keep one.
          const k = `${c.t}|${cellKey(c)}|${c.rsrp}`;
          if (!seenServing.has(k)) { seenServing.add(k); s.serving.push(c); }
          break;
        }
        case "cell_change": {
          const status = g("status"), note = g("note");
          const to = { rat: g("rat") || "?", cellId: g("cell_id") || null, pci: g("pci") || null, arfcn: g("arfcn") || null };
          const legacy = !status;
          s.changes.push({
            t: x.t, status: legacy ? (/prev=none/.test(note) ? "baseline" : "switched") : status,
            to, from: prevKeyParts(note), src: srcOf(note), legacy,
          });
          if (legacy) s.legacyChanges++;
          break;
        }
        case "cell_poll": {
          s.cellPoll.total++;
          const m = /registered=(\d+)/.exec(g("note"));
          if (m && Number(m[1]) > 0) s.cellPoll.registeredPositive++;
          break;
        }
        case "cell_status": s.cellStatusNotes.push(g("status")); break;
        case "network":
          s.network.push({ t: x.t, status: g("status"), transport: g("transport") || null, validated: g("validated") === "" ? null : g("validated") === "true" });
          break;
        case "http_probe":
          s.probes.push({ t: x.t, status: g("status"), ms: num(g("duration_ms")), http: num(g("http_status")), transport: g("transport") || null });
          break;
      }
    }
    s.unknownEvents = Object.keys(s.counts).filter(e => !KNOWN_EVENTS.has(e));
    labelCells(s);
    return s;
  }

  function emptySession(kind) {
    return {
      kind, t0: 0, t1: 1, rows: 0, brokenRows: 0, badTimeRows: 0, hasSessionBounds: false, hasCellColumns: true,
      gps: [], serving: [], neighbors: [], changes: [], network: [], probes: [], counts: {},
      cellPoll: { total: 0, registeredPositive: 0 }, cellStatusNotes: [], legacyChanges: 0, unknownEvents: [],
      cellNames: new Map(),
    };
  }

  // -------------------------------------------------------------------------
  // Anonymous cell labels: セルA, セルB, ... in order of first appearance as a serving cell.
  const cellKey = c => `${c.rat || "?"}/${c.cellId ?? "?"}/${c.pci ?? "?"}/${c.arfcn ?? "?"}`;
  function anonName(i) {
    const letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    return i < letters.length ? `セル${letters[i]}` : `セル${i + 1}`;
  }
  function nameFor(session, c) {
    if (!c) return null;
    const k = cellKey(c);
    if (!session.cellNames.has(k)) session.cellNames.set(k, anonName(session.cellNames.size));
    return session.cellNames.get(k);
  }
  function labelCells(session) {
    for (const c of session.serving) nameFor(session, c);
    for (const ch of session.changes) { if (ch.from) nameFor(session, ch.from); nameFor(session, ch.to); }
  }

  /** Why there may be no switches: distinguishes "no switch seen" from "no serving cell info". */
  function cellDiagnosis(s) {
    const switched = s.changes.filter(c => c.status === "switched").length;
    if (!s.serving.length && !s.neighbors.length && !s.changes.length && s.kind === "csv" && (!s.hasCellColumns || !s.counts.cell)) {
      return { key: "no_cell_data", text: "セル情報（cell 行）がありません。" };
    }
    if (switched > 0) return { key: "switched", text: `観測変更候補 ${switched} 件（最初の在圏セルは数えません。API の更新間隔の制限で見逃しがありうるため、ハンドオーバーの確定記録ではありません）。` };
    if (s.serving.length > 0) return { key: "no_switch", text: "確認できた切り替わりなし（在圏セルは観測できています）。" };
    return { key: "no_serving", text: `在圏セル（registered=true）を取得できず、切り替わりの有無は判定できません。周辺セル ${s.neighbors.length} 件は在圏セルとして扱いません。` };
  }

  // -------------------------------------------------------------------------
  // BLE protocol v1 (same as BleProtocol.kt):
  // fragment = [version u8][seq u32 LE][index u8][count u8][body slice]; body = UTF-8 JSON <= 2048 B.
  const BLE = {
    SERVICE_UUID: "20896d82-0d9e-4791-8522-22bc245b46bb",
    CHAR_UUID: "b58ccb41-6443-4ee3-9d29-0d7e7a56cbe8",
    VERSION: 1, HEADER: 7, MAX_FRAME: 2048,
  };

  function bleParseFragment(bytes) {
    if (!(bytes instanceof Uint8Array) || bytes.length < BLE.HEADER || bytes[0] !== BLE.VERSION) return null;
    const seq = (bytes[1] | (bytes[2] << 8) | (bytes[3] << 16) | (bytes[4] << 24)) >>> 0;
    const index = bytes[5], count = bytes[6];
    if (count === 0 || index >= count) return null;
    return { seq, index, count, data: bytes.slice(BLE.HEADER) };
  }

  /** Rebuilds frames; drops incomplete, duplicate, older and malformed ones. */
  class BleReassembler {
    constructor() { this.lastSeq = -1; this.seq = -1; this.parts = null; }
    accept(bytes) {
      const f = bleParseFragment(bytes);
      if (!f || f.seq <= this.lastSeq) return null;
      if (f.seq !== this.seq) {
        if (f.seq < this.seq) return null;
        this.seq = f.seq; this.parts = new Array(f.count).fill(null);
      }
      if (this.parts.length !== f.count) return null;
      this.parts[f.index] = f.data;
      if (this.parts.some(p => p === null)) return null;
      const size = this.parts.reduce((n, p) => n + p.length, 0);
      if (size > BLE.MAX_FRAME) return null;
      const body = new Uint8Array(size);
      let at = 0;
      for (const p of this.parts) { body.set(p, at); at += p.length; }
      this.lastSeq = f.seq; this.seq = -1; this.parts = null;
      return body;
    }
  }

  function bleDecode(body) {
    try {
      const o = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(body));
      if (!o || typeof o !== "object" || Array.isArray(o) || o.v !== BLE.VERSION || !Number.isInteger(o.seq) || !Number.isFinite(o.t)) return null;
      return o;
    } catch { return null; }
  }

  // -------------------------------------------------------------------------
  // Live BLE history: same shape as a CSV session so the same views draw it. Memory only.
  const LIVE_MAX_AGE_MS = 2 * 60 * 60 * 1000;   // keep the last 2 hours
  const LIVE_MAX_POINTS = 7200;                 // per series (1 Hz x 2 h)

  class LiveStore {
    constructor() { this.reset(); }
    reset() {
      this.session = emptySession("ble");
      this.lastSeq = -1;
      this.missedFrames = 0;
      this.frames = 0;
      this.last = null;            // { payload, receivedAt }
      this.lastSwitchCount = null;
      this.baselineMarked = false;
    }
    /** Adds one decoded payload. Returns false for an old or duplicate frame. */
    ingest(p, receivedAt) {
      if (p.seq <= this.lastSeq) return false;
      if (this.lastSeq >= 0 && p.seq > this.lastSeq + 1) this.missedFrames += p.seq - this.lastSeq - 1;
      this.lastSeq = p.seq;
      this.frames++;
      this.last = { payload: p, receivedAt };
      const s = this.session;
      const push = (arr, item, same) => {
        const prev = arr[arr.length - 1];
        if (prev && (item.t < prev.t || same(prev, item))) return; // repeats of an old sample
        arr.push(item);
      };
      if (p.gps && Number.isFinite(p.gps.lat) && Number.isFinite(p.gps.lon)) {
        const t = p.t - (p.gps.ageMs ?? 0);
        push(s.gps, { t, lat: p.gps.lat, lon: p.gps.lon, kmh: p.gps.kmh ?? null, acc: p.gps.accM ?? null },
          (a, b) => Math.abs(a.t - b.t) < 500);
      }
      if (p.cell) {
        const t = p.t - (p.cell.ageMs ?? 0);
        const c = { t, rat: p.cell.rat ?? "?", cellId: p.cell.id ?? null, pci: p.cell.pci ?? null, arfcn: p.cell.arfcn ?? null,
          rsrp: p.cell.rsrp ?? null, rsrq: p.cell.rsrq ?? null, snr: p.cell.snr ?? null, src: p.cell.src ?? null };
        push(s.serving, c, (a, b) => Math.abs(a.t - b.t) < 500 && cellKey(a) === cellKey(b));
        nameFor(s, c);
        if (!this.baselineMarked) {
          s.changes.push({ t, status: "baseline", to: c, from: null, src: c.src });
          this.baselineMarked = true;
        }
      }
      // A switch is reported through the running count plus the latest switch details.
      if (Number.isInteger(p.switches)) {
        if (this.lastSwitchCount !== null && p.switches > this.lastSwitchCount && p.lastSwitch) {
          s.changes.push({ t: p.lastSwitch.t, status: "switched", to: { label: p.lastSwitch.to }, from: { label: p.lastSwitch.from }, src: null,
            missed: p.switches - this.lastSwitchCount - 1 });
        }
        this.lastSwitchCount = p.switches;
      }
      if (p.probe && Number.isFinite(p.probe.ageMs)) {
        // The same probe is repeated every second with a growing age: plot it once.
        const t = p.t - p.probe.ageMs;
        push(s.probes, { t, status: p.probe.status, ms: p.probe.ms ?? null, http: p.probe.http ?? null, transport: p.net?.transport ?? null },
          (a, b) => Math.abs(a.t - b.t) < 1500 && a.status === b.status && a.ms === b.ms);
      }
      if (p.net) {
        push(s.network, { t: p.t, status: "live", transport: p.net.transport, validated: p.net.validated },
          (a, b) => a.transport === b.transport && a.validated === b.validated);
      }
      if (p.cellStatus) s.cellStatusNotes = [p.cellStatus];
      s.rows = this.frames;
      this.trim(p.t);
      const times = [s.gps[0]?.t, s.serving[0]?.t, s.probes[0]?.t, p.t].filter(Number.isFinite);
      s.t0 = Math.min(...times);
      s.t1 = Math.max(p.t, s.t0 + 1);
      return true;
    }
    trim(now) {
      const s = this.session, from = now - LIVE_MAX_AGE_MS;
      for (const k of ["gps", "serving", "probes", "changes", "network"]) {
        let arr = s[k].filter(x => x.t >= from);
        if (arr.length > LIVE_MAX_POINTS) arr = arr.slice(arr.length - LIVE_MAX_POINTS);
        s[k] = arr;
      }
    }
  }

  // -------------------------------------------------------------------------
  // Web Mercator (shared by map tiles and the track, so they always line up).
  const TILE = 256;
  const merc = {
    x0: lon => (lon + 180) / 360 * TILE,
    y0: lat => {
      const c = Math.max(-85.05112878, Math.min(85.05112878, lat)) * Math.PI / 180;
      return (1 - Math.asinh(Math.tan(c)) / Math.PI) / 2 * TILE;
    },
    metersPerPx0: lat => 156543.03392 * Math.cos(lat * Math.PI / 180),
  };

  // -------------------------------------------------------------------------
  // Shared lookups.
  function nearestByTime(arr, t, maxMs) {
    let lo = 0, hi = arr.length - 1;
    if (hi < 0) return null;
    while (lo < hi) { const m = (lo + hi) >> 1; if (arr[m].t < t) lo = m + 1; else hi = m; }
    let best = arr[lo];
    if (lo > 0 && Math.abs(arr[lo - 1].t - t) < Math.abs(best.t - t)) best = arr[lo - 1];
    return Math.abs(best.t - t) <= maxMs ? best : null;
  }

  /** JST wall-clock helpers (the timetable is in JST regardless of the viewer's zone). */
  const JST_OFFSET_MS = 9 * 60 * 60 * 1000;
  function jstClock(t, withSeconds = true) {
    const d = new Date(t + JST_OFFSET_MS);
    const p = n => String(n).padStart(2, "0");
    return `${p(d.getUTCHours())}:${p(d.getUTCMinutes())}${withSeconds ? ":" + p(d.getUTCSeconds()) : ""}`;
  }
  /** Epoch ms of HH:MM JST on the JST calendar day that contains [refT]. */
  function jstTimeOnDay(refT, hh, mm) {
    const d = new Date(refT + JST_OFFSET_MS);
    return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate(), hh, mm) - JST_OFFSET_MS;
  }

  return {
    GPS_GAP_MS, SIGNAL_GAP_MS, NEAR_MS, KNOWN_EVENTS, BLE, LIVE_MAX_AGE_MS, LIVE_MAX_POINTS, TILE,
    parseCsv, buildCsvSession, emptySession, cellKey, nameFor, cellLabelRaw, cellDiagnosis,
    bleParseFragment, BleReassembler, bleDecode, LiveStore, merc, nearestByTime, jstClock, jstTimeOnDay,
  };
})();

if (typeof module !== "undefined") module.exports = KRL;
