// Keio Ride Logger dashboard UI shared by the movement monitor (/) and the Mt.TAKAO 3 LT page.
// Pages differ only by the config passed to KRLApp.start(). Data (CSV or BLE) stays in memory:
// nothing is uploaded, stored, or logged. Only OpenStreetMap tiles are fetched, when the map is on.
"use strict";

const KRLApp = (() => {
  const K = KRL;
  const STALE_MS = 5000;
  const PLAY_TICK_MS = 200;      // replay redraw interval (wall clock)
  const LOW_ACC_M = 30;
  const OSM_URL = "https://tile.openstreetmap.org";

  let cfg = null;
  const st = {
    csv: null,
    live: new K.LiveStore(),
    view: "none",                // "none" | "csv" | "ble"
    range: [0, 1],               // CSV window (fractions)
    liveWindowMs: 10 * 60 * 1000,
    selectedT: null,
    csvT: null,                  // CSV cursor kept while another view is shown
    play: { playing: false, speed: 1, timer: null, lastReal: 0 },
    map: { view: null, userMoved: false, enabled: true },
    markers: [],                 // manual "実測メモ" markers { t, label }
    ble: { device: null, char: null, reasm: null, state: "未接続", connected: false, userDisconnect: false },
  };
  const tiles = new Map();

  // ---------------------------------------------------------------------------------------------
  // Small helpers
  const $ = id => document.getElementById(id);
  const esc = s => String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const fmt = (v, d, unit = "") => (v === null || v === undefined || !Number.isFinite(v) ? null : `${v.toFixed(d)}${unit}`);
  const orNone = (v, text = "データなし") => (v === null ? `<span class="nodata">${text}</span>` : esc(v));
  const pad2 = n => String(n).padStart(2, "0");
  const fmtElapsed = ms => { const s = Math.max(0, Math.round(ms / 1000)); return `${Math.floor(s / 3600)}:${pad2(Math.floor(s / 60) % 60)}:${pad2(s % 60)}`; };
  const css = name => getComputedStyle(document.documentElement).getPropertyValue(name).trim();

  const session = () => (st.view === "csv" ? st.csv : st.view === "ble" ? st.live.session : null);
  function windowRange() {
    const s = session();
    if (!s) return [0, 1];
    if (st.view === "ble") {
      const end = st.live.last ? st.live.last.payload.t : s.t1;
      return [end - st.liveWindowMs, end];
    }
    return [s.t0 + st.range[0] * (s.t1 - s.t0), s.t0 + st.range[1] * (s.t1 - s.t0)];
  }
  const inWin = t => { const [a, b] = windowRange(); return t >= a && t <= b; };
  /** Last sample at or before [t] (CSV cards show values as of the selected time). */
  function lastAt(arr, t) {
    let lo = 0, hi = arr.length - 1, best = null;
    while (lo <= hi) { const m = (lo + hi) >> 1; if (arr[m].t <= t) { best = arr[m]; lo = m + 1; } else hi = m - 1; }
    return best;
  }
  const liveStale = () => !st.live.last || Date.now() - st.live.last.receivedAt > STALE_MS;
  /** The time the cards describe: selected time, else the end of the window. */
  const focusT = () => st.selectedT ?? windowRange()[1];

  function cellName(s, part) {
    if (!part) return null;
    if (part.label) {
      // Live switch details carry "RAT PCI n ARFCN m"; reuse the anonymous name of that cell.
      const hit = s.serving.find(c => K.cellLabelRaw(c.rat, c.pci, c.arfcn) === part.label);
      return hit ? K.nameFor(s, hit) : "未登録のセル";
    }
    return K.nameFor(s, part);
  }

  // ---------------------------------------------------------------------------------------------
  // Layout
  function buildLayout() {
    const other = cfg.otherPage ? `<a class="pagelink" href="${cfg.otherPage.href}">${esc(cfg.otherPage.label)}</a>` : "";
    document.body.innerHTML = `
<header class="top">
  <div class="title"><h1>${esc(cfg.title)}</h1><p class="sub">${esc(cfg.subtitle)}</p></div>
  <div class="status">
    <span class="badge" id="modeBadge">データ未読み込み</span>
    <span class="badge" id="bleBadge">BLE 未接続</span>
    <span class="clock" id="clock">--:--:-- JST</span>
    ${other}
  </div>
</header>
<main>
  <section class="panel loader" aria-label="データの読み込み">
    <div class="loadrow">
      <label class="filebtn">CSV を開く<input type="file" id="file" accept=".csv,text/csv"></label>
      <span class="drop" id="drop">または CSV をここへドロップ</span>
      <button id="bleConnect" type="button">BLE接続</button>
      <button id="bleReconnect" type="button" class="hidden">再接続</button>
      <button id="bleDisconnect" type="button" class="hidden">切断</button>
      <span class="viewswitch" role="group" aria-label="表示するデータ">
        <button id="viewCsv" type="button" disabled>CSV を表示</button>
        <button id="viewBle" type="button" disabled>BLE ライブを表示</button>
      </span>
    </div>
    <div id="messages"></div>
    <details class="privacy"><summary>データの扱い</summary>
      <p>CSV と BLE で受け取ったデータは、このブラウザのメモリ上でだけ扱います。アップロード・送信・保存はしません（ページを閉じると消えます）。背景地図をオンにすると、表示範囲の地図タイルを OpenStreetMap から取得するため、タイル座標（おおよその表示範囲）とあなたの IP アドレスが OpenStreetMap に伝わります。CSV の中身、個々の測位点、Cell ID は送りません。Android 端末が行う HTTP 応答時間の計測は、端末自身の通信で、このページの通信ではありません。BLE 接続には HTTPS と Web Bluetooth 対応ブラウザ（Chrome / Edge など）が必要です。</p>
    </details>
  </section>
  ${cfg.route ? `<section class="panel route" aria-labelledby="h-route">
    <div class="routehead"><h2 id="h-route">予定ルート <span class="tag">概略・縮尺なし・時刻は公式時刻表の予定</span></h2>
      <span class="chip routestatus" id="routeStatus">GPS 位置なし</span>
      <span class="markerrow"><label>実測メモ <select id="markerLabel">${cfg.route.memoLabels.map(l => `<option>${esc(l)}</option>`).join("")}</select></label><button id="addMarker" type="button">選択中の時刻に追加</button><span id="markerList" class="muted small"></span></span></div>
    <canvas id="routeCanvas" class="route-canvas" aria-label="ルート概略図"></canvas>
    <details class="routenote"><summary>この図について</summary><span id="routeNote"></span></details></section>` : ""}
  <div class="grid">
    <section class="panel mappanel" aria-labelledby="h-map">
      <h2 id="h-map">GPS軌跡・概略 <span class="tag" id="mapTag">背景: OpenStreetMap</span></h2>
      <div class="toolbar">
        <label><input type="checkbox" id="showMap" checked> 背景地図</label>
        <label><input type="checkbox" id="showAcc" checked> 精度の円</label>
        <button id="fit" type="button">全体を表示</button>
        <span class="muted" id="mapStatus"></span>
      </div>
      <div class="mapwrap"><canvas id="map" aria-label="GPS軌跡の概略図"></canvas>
        <div class="attribution" id="attribution"><a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">© OpenStreetMap contributors</a></div>
        <div class="maperror hidden" id="mapError">地図タイルを取得できません。軌跡は表示を続けます。</div>
      </div>
      <p class="legend">太線＝連続した測位（5 秒を超える欠測はつなぎません）・● 精度 ${LOW_ACC_M} m 以内・○ 低精度・◎ 最新位置（灰色は古い位置）・◆ 接続アンテナエリアの変化（推定）・× HTTP 失敗。GPS の座標そのままで、道路や線路への補正はしていません。</p>
    </section>
    <section class="side" aria-label="現在のネットワーク指標">
      <div class="panel character"><canvas id="charCanvas" class="sprite" aria-label="速度キャラクター"></canvas>
        <div><b id="charLabel">—</b><div id="charSub" class="small muted"></div><div class="small muted">GPS 速度に合わせた演出です。乗り物を判定したものではありません。</div></div></div>
      <div class="cards" id="cards"></div>
      <div class="panel"><h2>セル変更の履歴</h2><p class="help">「接続アンテナエリアの変化（推定）」は、記録された在圏セルの識別情報の変化から推定したものです。物理的な電波塔やアンテナの切り替えを確定するものではありません。</p><p class="muted small" id="cellDiag"></p><div class="history"><table id="history"></table></div></div>
      ${cfg.travelModes ? `<div class="panel small"><label>移動手段メモ（表示用・自動判定しません） <select id="travelMode">${cfg.travelModes.map(m => `<option>${esc(m)}</option>`).join("")}</select></label></div>` : ""}
    </section>
  </div>
  <section class="panel" aria-labelledby="h-ts">
    <h2 id="h-ts">時系列 <span class="tag" id="axisTag">JST</span></h2>
    <div class="toolbar" id="csvRange">
      <span>表示する時間帯</span>
      <input type="range" id="rangeStart" min="0" max="1000" value="0" aria-label="開始">
      <input type="range" id="rangeEnd" min="0" max="1000" value="1000" aria-label="終了">
      <span id="rangeLabel"></span>
      <button id="rangeReset" type="button">全期間</button>
    </div>
    <div class="toolbar replay" id="replay" role="group" aria-label="CSV の再生">
      <button id="playStart" type="button" aria-label="先頭へ">⏮ 先頭</button>
      <button id="playToggle" type="button" aria-label="再生" aria-pressed="false">▶ 再生</button>
      <button id="playEnd" type="button" aria-label="末尾へ">⏭ 末尾</button>
      <label>速度 <select id="playSpeed"><option value="0.5">0.5 倍</option><option value="1" selected>1 倍</option><option value="2">2 倍</option><option value="5">5 倍</option></select></label>
      <input type="range" id="cursor" min="0" max="10000" value="0" aria-label="再生位置の時刻">
      <span id="cursorLabel"></span>
      <span class="small muted">スペース: 再生・一時停止　← →: 1 秒（Shift で 10 秒）</span>
    </div>
    <div class="toolbar hidden" id="liveRange">
      <label>直近 <select id="liveWindow"><option value="300000">5 分</option><option value="600000" selected>10 分</option><option value="1800000">30 分</option><option value="3600000">60 分</option></select></label>
      <span class="muted">BLE の履歴はメモリ上に最大 2 時間分だけ保持します。</span>
    </div>
    <div class="charts">
      <div><h3>GPS 速度 [km/h]</h3><canvas id="chSpeed" class="chart"></canvas></div>
      <div><h3>在圏セル RSRP [dBm]</h3><canvas id="chRsrp" class="chart"></canvas></div>
      <div><h3>在圏セル RSRQ・SINR/RSSNR [dB]</h3><canvas id="chQual" class="chart"></canvas><p class="small muted">実線・● RSRQ　破線・○ SINR/RSSNR</p></div>
      <div><h3>HTTP 応答時間 [ms]（DNS・TCP・TLS・HTTP を含む総所要時間）</h3><canvas id="chHttp" class="chart"></canvas><p class="small muted">● 成功　▲ HTTP エラー　× タイムアウト・失敗</p></div>
    </div>
    <h3>セル変更の時系列</h3><canvas id="chLane" class="chart lane"></canvas>
    <p class="small muted">◆ 接続アンテナエリアの変化（推定、縦の破線）　○ 基準（最初の在圏セル、数えない）${cfg.route ? "　点線＝時刻表の予定（公式掲載時刻）　実線＝実測メモ" : ""}。値のない区間は線でつながず、0 で埋めません。</p>
  </section>
  <details class="panel sensors" id="sensorsPanel"><summary><h2>補助センサー（加速度・気圧）</h2></summary>
    <p class="small muted" id="sensorNote"></p>
    <div class="charts">
      <div><h3>端末の加速度 [m/s²]（重力を含む端末座標系の x・y・z。列車の進行方向とは限らない）</h3><canvas id="chAccel" class="chart"></canvas><p class="small muted">実線 x　破線 y　点線 z。描画のときだけ 1 ピクセル幅ごとの最小・最大に間引きます（データは間引きません）</p></div>
      <div><h3>気圧 [hPa]（補助観測。標高には換算しません）</h3><canvas id="chPressure" class="chart"></canvas><p class="small muted">車内の空調やセンサーの個体差でも変わります</p></div>
    </div>
  </details>
  <section class="panel" id="selectionPanel"><h2>選択中の時刻</h2><div id="selection" class="muted">地図の点、グラフ、または履歴をクリックしてください。</div></section>
</main>`;
  }

  // ---------------------------------------------------------------------------------------------
  // Messages and header
  function message(list) {
    $("messages").innerHTML = list.map(([kind, text]) => `<p class="msg ${kind}">${esc(text)}</p>`).join("");
  }

  function renderHeader() {
    const badge = $("modeBadge");
    badge.textContent = st.view === "csv" ? "表示: CSV（計測済みデータ）" : st.view === "ble" ? "表示: BLE ライブ" : "データ未読み込み";
    badge.className = `badge ${st.view}`;
    const b = $("bleBadge");
    const stale = st.ble.connected && liveStale();
    b.textContent = st.ble.connected ? (stale ? "BLE 接続中・受信停止（古い値）" : "BLE 接続中・受信中") : `BLE ${st.ble.state}`;
    b.className = `badge ${st.ble.connected ? (stale ? "warn" : "ok") : ""}`;
    $("viewCsv").disabled = !st.csv;
    $("viewBle").disabled = !st.live.last;
    $("viewCsv").classList.toggle("active", st.view === "csv");
    $("viewBle").classList.toggle("active", st.view === "ble");
    $("csvRange").classList.toggle("hidden", st.view !== "csv");
    $("liveRange").classList.toggle("hidden", st.view !== "ble");
  }

  // ---------------------------------------------------------------------------------------------
  // Cards (values as of the focus time; for BLE the latest payload with its own ages)
  function renderCards() {
    const s = session();
    const el = $("cards");
    if (!s) { el.innerHTML = `<div class="card wide"><b>データ</b><span class="nodata">CSV を開くか、BLE で接続してください</span></div>`; return; }
    const t = focusT();
    const live = st.view === "ble";
    const stale = live && liveStale();
    const ageText = ms => (ms === null ? "" : ms < 1000 ? "たった今" : `${Math.round(ms / 1000)} 秒前`);
    const card = (k, v, sub = "", cls = "") => `<div class="card ${cls}"><b>${k}</b><span>${v}</span>${sub ? `<div class="small muted">${sub}</div>` : ""}</div>`;

    const gps = lastAt(s.gps, t);
    const gpsAge = gps ? t - gps.t : null;
    const gpsFresh = gps && gpsAge <= STALE_MS;
    const cell = lastAt(s.serving, t);
    const cellAge = cell ? t - cell.t : null;
    const net = lastAt(s.network, t);
    const probe = lastAt(s.probes, t);
    const diag = K.cellDiagnosis(s);
    const cards = [];

    if (live) {
      const last = st.live.last;
      cards.push(card("BLE 受信", last ? `<span class="${stale ? "stale" : ""}">${ageText(Date.now() - last.receivedAt)}${stale ? "（古い値・更新なし）" : ""}</span>` : orNone(null),
        last ? `連番 ${last.payload.seq}・端末時刻 ${K.jstClock(last.payload.t)} JST${st.live.missedFrames ? `・欠落 ${st.live.missedFrames} フレーム` : ""}` : ""));
    } else {
      cards.push(card("時刻", `${K.jstClock(t)} JST`, `計測開始から ${fmtElapsed(t - s.t0)}`));
    }
    const kmh = gpsFresh ? fmt(gps.kmh, 1, " km/h") : null;
    cards.push(card("移動速度（GPS 推定）",
      gpsFresh ? (kmh ?? '<span class="nodata">速度なし（0 km/h ではない）</span>') : `<span class="nodata">${gps ? "GPS 更新なし" : "GPS 未測位"}</span>`,
      gps ? `最終測位 ${K.jstClock(gps.t)}（${ageText(gpsAge)}）・精度 ${fmt(gps.acc, 1, " m") ?? "不明"}` : "", stale || (gps && !gpsFresh) ? "dim" : ""));
    if (cfg.showDistance) cards.push(card("距離（参考）", distanceText(s, t), `精度 ${LOW_ACC_M} m 以内・欠測を除く測位点から`));
    // Each radio metric keeps a one-line plain-Japanese explanation that is always visible.
    const explained = (k, v, help, sub = "", cls = "") =>
      `<div class="card ${cls}"><b>${k}</b><span>${v}</span><div class="help">${help}</div>${sub ? `<div class="small muted">${sub}</div>` : ""}</div>`;
    const cellFresh = cell && cellAge <= 10000;
    const notTaken = '<span class="nodata">未取得</span>';
    cards.push(explained("在圏セル",
      cellFresh ? `${esc(cell.rat)} ・ ${esc(K.nameFor(s, cell))}` : `<span class="nodata">${diag.key === "no_serving" ? "在圏セル情報なし" : cell ? "更新なし" : "データなし"}</span>`,
      "端末が現在使っている携帯基地局のセル",
      cell ? `最終観測 ${K.jstClock(cell.t)}（${ageText(cellAge)}）${cell.src ? `・経路 ${esc(cell.src)}` : ""}` : "", !cellFresh ? "dim" : ""));
    cards.push(explained("RSRP",
      cellFresh ? (fmt(cell.rsrp, 0, " dBm") ?? notTaken) : notTaken,
      "基準信号の強さ（0 に近いほど強い）", "", !cellFresh ? "dim" : ""));
    cards.push(explained("RSRQ",
      cellFresh ? (fmt(cell.rsrq, 0, " dB") ?? notTaken) : notTaken,
      "受信品質の目安（干渉などの影響を含む）", "", !cellFresh ? "dim" : ""));
    cards.push(explained("SINR / RSSNR",
      cellFresh ? (fmt(cell.snr, 0, " dB") ?? notTaken) : notTaken,
      "信号と雑音・干渉の比（高いほど信号が明瞭）", "", !cellFresh ? "dim" : ""));
    const tr = { cellular: "携帯網", wifi: "Wi-Fi", vpn: "VPN", ethernet: "有線", bluetooth: "Bluetooth" };
    cards.push(card("既定ネットワーク（Android）",
      net && net.transport ? `${esc(net.transport.split("+").map(x => tr[x] ?? "その他").join("+"))}` : '<span class="nodata">データなし</span>',
      net && net.validated !== null ? (net.validated ? "インターネット接続を確認済み" : "接続未確認") : ""));
    const pl = { ok: "成功", http_error: "HTTP エラー", timeout: "タイムアウト", error: "失敗" };
    cards.push(card("最後の HTTP 応答時間",
      probe ? `${esc(pl[probe.status] ?? probe.status)}${probe.status === "ok" && probe.ms !== null ? ` ${probe.ms} ms` : ""}` : '<span class="nodata">データなし</span>',
      probe ? `${K.jstClock(probe.t)}${probe.http ? `・HTTP ${probe.http}` : ""}${probe.transport ? `・経路 ${esc(tr[probe.transport] ?? probe.transport)}` : ""}` : ""));
    cards.push(`<p class="card-note">RSRP・RSRQ・SINR/RSSNR は端末が観測した無線の指標で、通信速度や体感品質そのものではありません。端末や無線方式によって取得できない指標があり、その場合は「未取得」と表示します。</p>`);
    el.innerHTML = cards.join("");
    $("cellDiag").textContent = diag.text + (s.legacyChanges ? "（旧形式の cell_change: 最初の行を基準として扱っています）" : "");
  }

  function distanceText(s, t) {
    const pts = s.gps.filter(p => p.t <= t && inWin(p.t) && p.acc !== null && p.acc <= LOW_ACC_M);
    if (pts.length < 2) return '<span class="nodata">データなし</span>';
    let d = 0;
    for (let i = 1; i < pts.length; i++) {
      if (pts[i].t - pts[i - 1].t > K.GPS_GAP_MS) continue;
      const a = pts[i - 1], b = pts[i], k = Math.PI / 180;
      const x = (b.lon - a.lon) * k * Math.cos((a.lat + b.lat) / 2 * k), y = (b.lat - a.lat) * k;
      d += Math.hypot(x, y) * 6371008.8;
    }
    return d >= 1000 ? `${(d / 1000).toFixed(2)} km` : `${Math.round(d)} m`;
  }

  // ---------------------------------------------------------------------------------------------
  // Speed character (same bands, smoothing, hysteresis and freshness rules as the Android app)
  let sprite = null;
  function loadSprite() {
    const img = new Image();
    img.onload = () => { sprite = img; renderCharacter(); };
    img.onerror = () => { sprite = null; renderCharacter(); };
    img.src = `${cfg.assetBase}assets/glider_speed_sheet.png`;
  }
  /** Device-clock "now": CSV uses the focus time, BLE the last payload time plus time since receipt. */
  function speedNow() {
    if (st.view === "ble" && st.live.last) return st.live.last.payload.t + (Date.now() - st.live.last.receivedAt);
    return focusT();
  }
  function renderCharacter() {
    const s = session();
    const state = s ? K.speedStateAt(s.gps, speedNow()) : null;
    const band = state && state.band !== null ? K.SPEED_BANDS[state.band] : null;
    $("charLabel").textContent = !s ? "データ待ち" : band ? band.label : K.SPEED_REASONS[state.reason];
    $("charSub").textContent = !s ? "" : band ? `GPS 速度 ${state.kmh.toFixed(1)} km/h（直近 5 秒の中央値）${state.band === K.SPEED_BANDS.length - 1 ? "・演出上の速度帯で、列車の判定や速度計ではありません" : ""}` : "GPS 速度 不明（0 km/h ではありません）";
    const c = $("charCanvas");
    const { ctx, w, h } = setupCanvas(c);
    if (!sprite) { ctx.fillStyle = css("--muted"); ctx.font = "12px system-ui"; ctx.textAlign = "center"; ctx.fillText(band ? band.label : "—", w / 2, h / 2); ctx.textAlign = "left"; return; }
    const f = K.spriteFrame(sprite.naturalWidth || sprite.width, sprite.naturalHeight || sprite.height, band ? state.band : 0);
    const scale = Math.min(w / f.w, h / f.h), dw = f.w * scale, dh = f.h * scale;
    ctx.imageSmoothingEnabled = false;
    ctx.globalAlpha = band ? 1 : 0.25;
    ctx.drawImage(sprite, f.x, f.y, f.w, f.h, (w - dw) / 2, (h - dh) / 2, dw, dh);
    ctx.globalAlpha = 1;
  }

  // ---------------------------------------------------------------------------------------------
  // Cell change history
  function renderHistory() {
    const s = session();
    const table = $("history");
    if (!s) { table.innerHTML = ""; return; }
    const rows = s.changes.filter(c => inWin(c.t)).slice().reverse();
    if (!rows.length) { table.innerHTML = `<tr><td class="muted">この時間帯にセルイベントはありません</td></tr>`; return; }
    const label = { switched: "接続アンテナエリアの変化（推定）", baseline: "基準", indeterminate: "判定不能" };
    table.innerHTML = "<thead><tr><th>時刻 (JST)</th><th>種別</th><th>セル</th><th>経路</th></tr></thead><tbody>" + rows.map(c =>
      `<tr class="clickable${st.selectedT === c.t ? " selected" : ""}${st.view === "csv" && st.selectedT !== null && c.t > st.selectedT ? " future" : ""}" data-t="${c.t}"><td>${K.jstClock(c.t)}</td>` +
      `<td><span class="chip ${esc(c.status)}">${esc(label[c.status] ?? c.status)}</span>${c.missed ? `<div class="small muted">間に ${c.missed} 件の見逃し</div>` : ""}</td>` +
      `<td>${c.from ? esc(cellName(s, c.from)) + " → " : ""}${esc(cellName(s, c.to) ?? "")}</td><td>${esc(c.src ?? "—")}</td></tr>`).join("") + "</tbody>";
  }

  // ---------------------------------------------------------------------------------------------
  // Selection detail
  function renderSelection() {
    const el = $("selection"), s = session();
    if (!s || st.selectedT === null) { el.className = "muted"; el.textContent = "地図の点、グラフ、または履歴をクリックしてください。"; return; }
    el.className = "";
    const t = st.selectedT;
    const p = K.nearestByTime(s.gps, t, K.NEAR_MS);
    const c = K.nearestByTime(s.serving, t, K.SIGNAL_GAP_MS);
    const rows = [
      ["時刻", `${K.jstClock(t)} JST（計測開始から ${fmtElapsed(t - s.t0)}）`],
      ["GPS", p ? `${K.jstClock(p.t)} 測位・速度 ${fmt(p.kmh, 1, " km/h") ?? "不明"}・精度 ${fmt(p.acc, 1, " m") ?? "不明"}${p.acc !== null && p.acc > LOW_ACC_M ? "（低精度）" : ""}` : "±5 秒以内に測位なし"],
      ["在圏セル", c ? `${esc(c.rat)} ${esc(K.nameFor(s, c))}（PCI ${esc(c.pci ?? "?")}・ARFCN ${esc(c.arfcn ?? "?")}）RSRP ${fmt(c.rsrp, 0, " dBm") ?? "不明"}` : "±10 秒以内に在圏セルの記録なし"],
    ];
    el.innerHTML = "<dl>" + rows.map(([k, v]) => `<dt>${k}</dt><dd>${v}</dd>`).join("") + "</dl>";
  }

  // ---------------------------------------------------------------------------------------------
  // Map (Web Mercator; tiles and track share the projection)
  function setupCanvas(canvas) {
    const dpr = window.devicePixelRatio || 1;
    const w = canvas.clientWidth, h = canvas.clientHeight;
    if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(h * dpr)) {
      canvas.width = Math.round(w * dpr); canvas.height = Math.round(h * dpr);
    }
    const ctx = canvas.getContext("2d");
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);
    return { ctx, w, h };
  }
  function emptyNote(ctx, w, h, text) {
    ctx.fillStyle = css("--muted"); ctx.font = "14px system-ui"; ctx.textAlign = "center";
    ctx.fillText(text, w / 2, h / 2); ctx.textAlign = "left";
  }

  function fitMap(points) {
    const c = $("map"), w = c.clientWidth, h = c.clientHeight, pad = 30;
    if (!points.length) { st.map.view = null; return; }
    const xs = points.map(p => K.merc.x0(p.lon)), ys = points.map(p => K.merc.y0(p.lat));
    const midLat = points.reduce((a, p) => a + p.lat, 0) / points.length;
    const minSpan = 120 / K.merc.metersPerPx0(midLat);
    const sx = Math.max(Math.max(...xs) - Math.min(...xs), minSpan), sy = Math.max(Math.max(...ys) - Math.min(...ys), minSpan);
    st.map.view = {
      scale: Math.min((w - 2 * pad) / sx, (h - 2 * pad) / sy, 2 ** 19 * 2),
      cx: (Math.max(...xs) + Math.min(...xs)) / 2, cy: (Math.max(...ys) + Math.min(...ys)) / 2,
    };
  }
  const toScreen = (p, w, h) => [w / 2 + (K.merc.x0(p.lon) - st.map.view.cx) * st.map.view.scale, h / 2 + (K.merc.y0(p.lat) - st.map.view.cy) * st.map.view.scale];

  function drawTiles(ctx, w, h) {
    const v = st.map.view;
    const zoom = Math.max(0, Math.min(19, Math.floor(Math.log2(v.scale))));
    const n = 2 ** zoom, tilePx = K.TILE * v.scale / n;
    const left = (v.cx - w / 2 / v.scale) * n / K.TILE, top = (v.cy - h / 2 / v.scale) * n / K.TILE;
    let failed = false;
    for (let ty = Math.floor(top); ty < top + h / tilePx + 1; ty++) {
      if (ty < 0 || ty >= n) continue;
      for (let tx = Math.floor(left); tx < left + w / tilePx + 1; tx++) {
        const key = `${zoom}/${((tx % n) + n) % n}/${ty}`;
        let img = tiles.get(key);
        if (!img) {
          img = new Image(); img.loaded = false; img.failed = false;
          img.onload = () => { img.loaded = true; drawMap(); };
          img.onerror = () => { img.failed = true; drawMap(); };
          tiles.set(key, img);
          img.src = `${OSM_URL}/${key}.png`;
        }
        if (img.failed) failed = true;
        if (img.loaded) ctx.drawImage(img, (tx - left) * tilePx, (ty - top) * tilePx, tilePx + 0.5, tilePx + 0.5);
      }
    }
    return failed;
  }

  function drawMap() {
    const canvas = $("map");
    const { ctx, w, h } = setupCanvas(canvas);
    const s = session();
    const mapOn = $("showMap").checked;
    $("attribution").classList.toggle("hidden", !mapOn);
    $("mapError").classList.add("hidden");
    ctx.fillStyle = mapOn ? "#e8e5dc" : css("--canvas"); ctx.fillRect(0, 0, w, h);
    if (!s) { emptyNote(ctx, w, h, "データ未読み込み"); return; }
    const pts = s.gps.filter(p => inWin(p.t));
    if (!pts.length) {
      $("mapStatus").textContent = "";
      emptyNote(ctx, w, h, s.gps.length ? "この時間帯に GPS の測位がありません" : "GPS 未測位（位置データなし）。ネットワーク計測値は右と下に表示します");
      return;
    }
    if (!st.map.view || (st.view === "ble" && !st.map.userMoved)) fitMap(pts);
    if (mapOn && drawTiles(ctx, w, h)) $("mapError").classList.remove("hidden");
    // Casing makes the line readable on light tiles and on the dark no-map background.
    const core = mapOn ? "#c2185b" : "#4dd0e1", casing = mapOn ? "#ffffff" : "#0b1015", edge = mapOn ? "#3a0020" : "#e6eaee";
    const lowAcc = p => p.acc === null || p.acc > LOW_ACC_M;
    if ($("showAcc").checked) {
      for (const p of pts) {
        if (p.acc === null) continue;
        const r = p.acc / K.merc.metersPerPx0(p.lat) * st.map.view.scale;
        if (r < 1.5 || r > 4000) continue;
        const [x, y] = toScreen(p, w, h);
        ctx.beginPath(); ctx.arc(x, y, r, 0, Math.PI * 2);
        ctx.fillStyle = mapOn ? "rgba(194,24,91,0.10)" : "rgba(77,208,225,0.10)"; ctx.fill();
      }
    }
    // CSV replay: the whole track stays faint, the part up to the cursor is emphasised, and the
    // "current" position is the last fix at or before the cursor (nothing from the future).
    const cursor = st.view === "csv" ? st.selectedT : null;
    const isPast = t => cursor === null || t <= cursor;
    const past = pts.filter(p => isPast(p.t));
    const segments = (list, layers) => {
      for (const [color, width] of layers) {
        ctx.strokeStyle = color; ctx.lineWidth = width;
        for (let i = 1; i < list.length; i++) {
          if (list[i].t - list[i - 1].t > K.GPS_GAP_MS) continue;
          const [x1, y1] = toScreen(list[i - 1], w, h), [x2, y2] = toScreen(list[i], w, h);
          ctx.beginPath(); ctx.moveTo(x1, y1); ctx.lineTo(x2, y2); ctx.stroke();
        }
      }
    };
    ctx.lineCap = "round"; ctx.lineJoin = "round";
    if (cursor !== null) { ctx.globalAlpha = 0.35; segments(pts, [[core, 3]]); ctx.globalAlpha = 1; }
    segments(past, [[edge, 9], [casing, 7], [core, 4]]);
    for (const p of past) {
      const [x, y] = toScreen(p, w, h);
      ctx.beginPath(); ctx.arc(x, y, lowAcc(p) ? 4.5 : 3, 0, Math.PI * 2);
      ctx.fillStyle = lowAcc(p) ? casing : core; ctx.fill();
      ctx.lineWidth = 1.8; ctx.strokeStyle = lowAcc(p) ? css("--warn") : casing; ctx.stroke();
    }
    for (const c of s.changes) {
      if (c.status !== "switched" || !inWin(c.t)) continue;
      const p = K.nearestByTime(s.gps, c.t, K.NEAR_MS); if (!p) continue;
      const [x, y] = toScreen(p, w, h);
      ctx.globalAlpha = isPast(c.t) ? 1 : 0.3;
      ctx.beginPath(); ctx.moveTo(x, y - 9); ctx.lineTo(x + 9, y); ctx.lineTo(x, y + 9); ctx.lineTo(x - 9, y); ctx.closePath();
      ctx.fillStyle = css("--change"); ctx.fill(); ctx.strokeStyle = "#000"; ctx.lineWidth = 1.5; ctx.stroke();
    }
    for (const pr of s.probes) {
      if (pr.status === "ok" || !inWin(pr.t)) continue;
      const p = K.nearestByTime(s.gps, pr.t, K.NEAR_MS); if (!p) continue;
      const [x, y] = toScreen(p, w, h);
      ctx.globalAlpha = isPast(pr.t) ? 1 : 0.3;
      ctx.strokeStyle = css("--bad"); ctx.lineWidth = 3;
      ctx.beginPath(); ctx.moveTo(x - 6, y - 6); ctx.lineTo(x + 6, y + 6); ctx.moveTo(x + 6, y - 6); ctx.lineTo(x - 6, y + 6); ctx.stroke();
    }
    ctx.globalAlpha = 1;
    // Current position (latest fix, or the last fix before the replay cursor); grey when not current.
    const last = past.length ? past[past.length - 1] : null;
    const lastStale = !last ? true
      : st.view === "ble" ? (liveStale() || Date.now() - st.live.last.receivedAt + (st.live.last.payload.t - last.t) > STALE_MS)
      : cursor !== null && cursor - last.t > STALE_MS;
    if (last) {
      const [lx, ly] = toScreen(last, w, h);
      ctx.beginPath(); ctx.arc(lx, ly, 11, 0, Math.PI * 2); ctx.fillStyle = casing; ctx.fill();
      ctx.beginPath(); ctx.arc(lx, ly, 9, 0, Math.PI * 2); ctx.lineWidth = 4; ctx.strokeStyle = lastStale ? "#8e99a4" : css("--bad"); ctx.stroke();
    }
    if (st.selectedT !== null && st.view !== "csv") {
      const p = K.nearestByTime(s.gps, st.selectedT, K.NEAR_MS);
      if (p && inWin(p.t)) {
        const [x, y] = toScreen(p, w, h);
        ctx.beginPath(); ctx.arc(x, y, 13, 0, Math.PI * 2); ctx.lineWidth = 2.5; ctx.strokeStyle = mapOn ? "#000" : "#fff"; ctx.stroke();
      }
    }
    // Scale bar (top-left, away from the attribution).
    const mpp = K.merc.metersPerPx0((last ?? pts[pts.length - 1]).lat) / st.map.view.scale;
    const nice = [5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000];
    const m = nice.find(v => v / mpp >= 70) || nice[nice.length - 1];
    ctx.fillStyle = mapOn ? "rgba(255,255,255,.85)" : "rgba(0,0,0,.5)"; ctx.fillRect(6, 6, m / mpp + 16, 30);
    ctx.strokeStyle = mapOn ? "#222" : "#eee"; ctx.lineWidth = 2;
    ctx.beginPath(); ctx.moveTo(14, 28); ctx.lineTo(14 + m / mpp, 28); ctx.stroke();
    ctx.fillStyle = mapOn ? "#222" : "#eee"; ctx.font = "12px system-ui"; ctx.fillText(m >= 1000 ? `${m / 1000} km` : `${m} m`, 14, 22);
    $("mapStatus").textContent = !last ? "この時刻までに測位なし"
      : lastStale ? (cursor !== null ? "再生位置の時点で GPS 更新なし（灰色は最後の位置）" : "最新位置は古い値です（灰色）")
      : cursor !== null ? "薄い線は再生位置より後の軌跡" : "";
  }

  // ---------------------------------------------------------------------------------------------
  // Charts (shared time axis = current window, JST labels)
  const PAD = { l: 48, r: 12, t: 12, b: 24 };
  function scales(w, h, yMin, yMax) {
    const [x0, x1] = windowRange();
    return {
      sx: t => PAD.l + (t - x0) / (x1 - x0) * (w - PAD.l - PAD.r),
      sy: v => PAD.t + (1 - (v - yMin) / (yMax - yMin)) * (h - PAD.t - PAD.b),
      tx: px => x0 + (px - PAD.l) / (w - PAD.l - PAD.r) * (x1 - x0),
    };
  }
  function axes(ctx, w, h, sc, yMin, yMax, unit) {
    ctx.strokeStyle = css("--line"); ctx.fillStyle = css("--muted"); ctx.font = "11px system-ui"; ctx.lineWidth = 1;
    const span = yMax - yMin, step0 = span / 4, mag = 10 ** Math.floor(Math.log10(step0));
    const step = [1, 2, 5, 10].map(f => f * mag).find(sz => span / sz <= 4) || mag * 10;
    for (let v = Math.ceil(yMin / step) * step; v <= yMax + 1e-9; v += step) {
      const y = sc.sy(v); ctx.beginPath(); ctx.moveTo(PAD.l, y); ctx.lineTo(w - PAD.r, y); ctx.stroke();
      ctx.fillText(`${+v.toFixed(6)}`, 4, y + 4);
    }
    ctx.fillText(unit, 4, 10);
    const [x0, x1] = windowRange();
    const spanS = (x1 - x0) / 1000;
    const stepS = [5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200].find(v => spanS / v <= 7) || 7200;
    for (let t = Math.ceil(x0 / 1000 / stepS) * stepS * 1000; t <= x1; t += stepS * 1000) {
      const x = sc.sx(t); ctx.beginPath(); ctx.moveTo(x, PAD.t); ctx.lineTo(x, h - PAD.b); ctx.stroke();
      const label = K.jstClock(t, stepS < 60);
      const tw = ctx.measureText(label).width;
      ctx.fillText(label, Math.min(Math.max(x - tw / 2, PAD.l), w - tw - 2), h - 7);
    }
  }
  function verticals(ctx, w, h, sc) {
    const s = session();
    ctx.save();
    for (const c of s.changes) {
      if (c.status !== "switched" || !inWin(c.t)) continue;
      ctx.setLineDash([5, 4]); ctx.strokeStyle = css("--change"); ctx.lineWidth = 1.3;
      ctx.beginPath(); ctx.moveTo(sc.sx(c.t), PAD.t); ctx.lineTo(sc.sx(c.t), h - PAD.b); ctx.stroke();
    }
    for (const m of planMarkers()) {
      if (!inWin(m.t)) continue;
      ctx.setLineDash([2, 4]); ctx.strokeStyle = css("--plan"); ctx.lineWidth = 1.5;
      ctx.beginPath(); ctx.moveTo(sc.sx(m.t), PAD.t); ctx.lineTo(sc.sx(m.t), h - PAD.b); ctx.stroke();
      ctx.setLineDash([]); ctx.fillStyle = css("--plan"); ctx.font = "10px system-ui"; ctx.fillText(`予定 ${m.label}`, sc.sx(m.t) + 3, PAD.t + 9);
    }
    for (const m of st.markers) {
      if (!inWin(m.t)) continue;
      ctx.setLineDash([]); ctx.strokeStyle = css("--memo"); ctx.lineWidth = 1.5;
      ctx.beginPath(); ctx.moveTo(sc.sx(m.t), PAD.t); ctx.lineTo(sc.sx(m.t), h - PAD.b); ctx.stroke();
      ctx.fillStyle = css("--memo"); ctx.font = "10px system-ui"; ctx.fillText(`実測 ${m.label}`, sc.sx(m.t) + 3, h - PAD.b - 4);
    }
    if (st.selectedT !== null && inWin(st.selectedT)) {
      const x = sc.sx(st.selectedT);
      ctx.setLineDash([]); ctx.strokeStyle = css("--fg"); ctx.lineWidth = 1.5;
      ctx.beginPath(); ctx.moveTo(x, PAD.t); ctx.lineTo(x, h - PAD.b); ctx.stroke();
    }
    ctx.restore();
  }
  /** CSV replay: shade the part after the cursor (drawn over the data) so it does not read as "now". */
  function shadeFuture(ctx, w, h, sc) {
    if (st.view !== "csv" || st.selectedT === null || !inWin(st.selectedT)) return;
    const x = sc.sx(st.selectedT);
    ctx.fillStyle = "rgba(13,18,24,0.5)";
    ctx.fillRect(x + 1, PAD.t, w - PAD.r - x - 1, h - PAD.t - PAD.b);
  }
  function mark(ctx, kind, x, y, color) {
    ctx.fillStyle = color; ctx.strokeStyle = color;
    if (kind === "dot") { ctx.beginPath(); ctx.arc(x, y, 2.5, 0, Math.PI * 2); ctx.fill(); }
    else if (kind === "hollow") { ctx.beginPath(); ctx.arc(x, y, 3.5, 0, Math.PI * 2); ctx.lineWidth = 1.5; ctx.stroke(); }
    else if (kind === "tri") { ctx.beginPath(); ctx.moveTo(x, y - 6); ctx.lineTo(x + 6, y + 5); ctx.lineTo(x - 6, y + 5); ctx.closePath(); ctx.fill(); }
    else if (kind === "cross") { ctx.lineWidth = 2.5; ctx.beginPath(); ctx.moveTo(x - 5, y - 5); ctx.lineTo(x + 5, y + 5); ctx.moveTo(x + 5, y - 5); ctx.lineTo(x - 5, y + 5); ctx.stroke(); }
  }
  /** series: [{ pts: [{t, v, mark}], color, dashed, gapMs (null = points only) }] */
  function chart(id, series, unit, emptyText) {
    const { ctx, w, h } = setupCanvas($(id));
    const s = session();
    if (!s) return emptyNote(ctx, w, h, "データ未読み込み");
    let vis = series.map(se => ({ ...se, pts: se.pts.filter(p => p.v !== null && Number.isFinite(p.v) && inWin(p.t)) }));
    const vals = vis.flatMap(se => se.pts.map(p => p.v));
    if (!vals.length) { emptyNote(ctx, w, h, emptyText); const sc = scales(w, h, 0, 1); verticals(ctx, w, h, sc); return; }
    let yMin = Math.min(...vals), yMax = Math.max(...vals);
    if (yMax - yMin < 1e-6) { yMin -= 1; yMax += 1; }
    const pad = (yMax - yMin) * 0.1; yMin -= pad; yMax += pad;
    const sc = scales(w, h, yMin, yMax);
    axes(ctx, w, h, sc, yMin, yMax, unit);
    verticals(ctx, w, h, sc);
    // Dense series (20 Hz sensors) are drawn as a per-pixel min/max envelope; the data is untouched.
    vis = vis.map(se => (se.downsample && se.pts.length > 2 * w ? { ...se, pts: envelope(se.pts, sc) } : se));
    for (const se of vis) {
      if (se.gapMs) {
        ctx.save(); ctx.strokeStyle = se.color; ctx.lineWidth = se.noMarks ? 1.5 : 2; if (se.dash || se.dashed) ctx.setLineDash(se.dash || [6, 4]);
        for (let i = 1; i < se.pts.length; i++) {
          const a = se.pts[i - 1], b = se.pts[i];
          if (b.t - a.t > se.gapMs) continue;
          ctx.beginPath(); ctx.moveTo(sc.sx(a.t), sc.sy(a.v)); ctx.lineTo(sc.sx(b.t), sc.sy(b.v)); ctx.stroke();
        }
        ctx.restore();
      }
      if (!se.noMarks) for (const p of se.pts) mark(ctx, p.mark || "dot", sc.sx(p.t), sc.sy(p.v), se.color);
    }
    shadeFuture(ctx, w, h, sc);
  }
  function envelope(pts, sc) {
    const out = [];
    let col = null, lo = null, hi = null;
    const flush = () => { if (lo) { if (lo.t <= hi.t) out.push(lo, hi); else out.push(hi, lo); } };
    for (const p of pts) {
      const c = Math.round(sc.sx(p.t));
      if (c !== col) { flush(); col = c; lo = hi = p; continue; }
      if (p.v < lo.v) lo = p;
      if (p.v > hi.v) hi = p;
    }
    flush();
    return out;
  }

  function renderCharts() {
    const s = session();
    const sv = s ? s.serving : [];
    chart("chSpeed", [{ pts: (s ? s.gps : []).map(p => ({ t: p.t, v: p.kmh })), color: css("--gps"), gapMs: K.GPS_GAP_MS }], "km/h",
      "速度の値がありません（測位なし、または速度なし）");
    chart("chRsrp", [{ pts: sv.map(c => ({ t: c.t, v: c.rsrp })), color: css("--rsrp"), gapMs: K.SIGNAL_GAP_MS }], "dBm",
      s && !s.serving.length ? "在圏セル（registered=true）の値がありません" : "この時間帯に RSRP がありません");
    chart("chQual", [
      { pts: sv.map(c => ({ t: c.t, v: c.rsrq })), color: css("--rsrq"), gapMs: K.SIGNAL_GAP_MS },
      { pts: sv.map(c => ({ t: c.t, v: c.snr, mark: "hollow" })), color: css("--snr"), gapMs: K.SIGNAL_GAP_MS, dashed: true },
    ], "dB", "RSRQ・SINR/RSSNR の値がありません（端末が返していない可能性）");
    const pm = { ok: "dot", http_error: "tri", timeout: "cross", error: "cross" };
    const probes = s ? s.probes : [];
    chart("chHttp", [
      { pts: probes.filter(p => p.status === "ok").map(p => ({ t: p.t, v: p.ms, mark: "dot" })), color: css("--http"), gapMs: null },
      { pts: probes.filter(p => p.status !== "ok").map(p => ({ t: p.t, v: p.ms ?? 0, mark: pm[p.status] || "cross" })), color: css("--bad"), gapMs: null },
    ], "ms", "HTTP 応答時間の記録がありません");
    renderLane();
  }
  function renderLane() {
    const { ctx, w, h } = setupCanvas($("chLane"));
    const s = session();
    if (!s) return emptyNote(ctx, w, h, "データ未読み込み");
    const sc = scales(w, h, 0, 1);
    axes(ctx, w, h, { ...sc, sy: () => -100 }, 0, 1, "");
    verticals(ctx, w, h, sc);
    const vis = s.changes.filter(c => inWin(c.t));
    if (!vis.length) { emptyNote(ctx, w, h - 12, K.cellDiagnosis(s).key === "no_serving" ? "在圏セルを取得できず（判定できません）" : "この時間帯にセルイベントはありません"); return; }
    const y = (h - PAD.b) / 2 + 4;
    for (const c of vis) {
      const x = sc.sx(c.t);
      if (c.status === "switched") {
        ctx.beginPath(); ctx.moveTo(x, y - 8); ctx.lineTo(x + 8, y); ctx.lineTo(x, y + 8); ctx.lineTo(x - 8, y); ctx.closePath(); ctx.fillStyle = css("--change"); ctx.fill();
        ctx.fillStyle = css("--fg"); ctx.font = "11px system-ui"; ctx.fillText(cellName(s, c.to) ?? "", x + 10, y + 4);
      } else {
        ctx.beginPath(); ctx.arc(x, y, 6, 0, Math.PI * 2); ctx.lineWidth = 2; ctx.strokeStyle = c.status === "baseline" ? css("--serving") : css("--muted"); ctx.stroke();
        if (c.status === "baseline") { ctx.fillStyle = css("--muted"); ctx.font = "11px system-ui"; ctx.fillText(`基準 ${cellName(s, c.to) ?? ""}`, x + 9, y + 4); }
      }
    }
    shadeFuture(ctx, w, h, sc);
  }

  // ---------------------------------------------------------------------------------------------
  // Auxiliary sensors (CSV only; BLE does not carry sensor values yet)
  function renderSensors() {
    if (!$("sensorsPanel").open) return;
    const s = session();
    const note = $("sensorNote");
    if (st.view === "ble") {
      note.textContent = "BLE ライブでは未対応です（加速度・気圧は CSV でだけ表示します）。";
      chart("chAccel", [], "m/s²", "BLE ライブでは未対応（CSV のみ）");
      chart("chPressure", [], "hPa", "BLE ライブでは未対応（CSV のみ）");
      return;
    }
    const rate = k => (s && Number.isFinite(s.sensorRates[k]) ? `${s.sensorRates[k].toFixed(1)} Hz` : "不明");
    note.textContent = s ? `記録頻度（この CSV の実測）: 加速度 ${rate("accel")}・気圧 ${rate("pressure")}。目標値（加速度 20 Hz・気圧 1 Hz）とは端末によって異なります。センサーの変化と通信の変化の因果関係は示しません。` : "";
    const acc = s ? s.accel : [];
    const unavailable = k => s && s.sensorStatus[k] === "unavailable";
    chart("chAccel", [
      { pts: acc.map(a => ({ t: a.t, v: a.x })), color: css("--gps"), gapMs: 1000, noMarks: true, downsample: true },
      { pts: acc.map(a => ({ t: a.t, v: a.y })), color: css("--rsrq"), gapMs: 1000, noMarks: true, downsample: true, dash: [6, 3] },
      { pts: acc.map(a => ({ t: a.t, v: a.z })), color: css("--snr"), gapMs: 1000, noMarks: true, downsample: true, dash: [2, 3] },
    ], "m/s²", unavailable("accel") ? "この端末では未取得（加速度センサーなし）" : "加速度の記録がありません");
    chart("chPressure", [{ pts: (s ? s.pressure : []).map(p => ({ t: p.t, v: p.v })), color: css("--http"), gapMs: 5000, downsample: true }],
      "hPa", unavailable("pressure") ? "この端末では未取得（気圧センサーなし）" : "気圧の記録がありません");
  }

  // ---------------------------------------------------------------------------------------------
  // Route schematic and timetable (only when the page config has a route)
  function planMarkers() {
    const s = session();
    if (!cfg.route || !s) return [];
    const ref = windowRange()[1];
    return cfg.route.stations.filter(x => x.hh !== null).map(x => ({ t: K.jstTimeOnDay(ref, x.hh, x.mm), label: `${x.name} ${pad2(x.hh)}:${pad2(x.mm)}` }));
  }
  function renderRoute() {
    if (!cfg.route) return;
    const { ctx, w, h } = setupCanvas($("routeCanvas"));
    const stations = cfg.route.stations;
    const x = i => 70 + i * (w - 140) / (stations.length - 1), y = h / 2 - 2;
    ctx.strokeStyle = css("--gps"); ctx.lineWidth = 5; ctx.lineCap = "round";
    ctx.beginPath(); ctx.moveTo(x(0), y); ctx.lineTo(x(stations.length - 1), y); ctx.stroke();
    stations.forEach((stn, i) => {
      ctx.beginPath(); ctx.arc(x(i), y, 7, 0, Math.PI * 2); ctx.fillStyle = css("--panel"); ctx.fill(); ctx.lineWidth = 3; ctx.strokeStyle = css("--fg"); ctx.stroke();
      ctx.textAlign = "center";
      ctx.fillStyle = css("--fg"); ctx.font = "bold 14px system-ui"; ctx.fillText(stn.name, x(i), y - 13);
      ctx.fillStyle = css("--plan"); ctx.font = "12px system-ui"; ctx.fillText(`予定 ${pad2(stn.hh)}:${pad2(stn.mm)}${stn.kind}`, x(i), y + 24);
    });
    ctx.textAlign = "left";
    const s = session();
    // Scheduled position from the timetable (not a measurement).
    const planT = s ? focusT() : null;
    let note = "駅は均等配置の概略図で、距離の縮尺はありません。予定時刻は京王電鉄の公式時刻表（土休日）の掲載値で、乗車日に確認してください。";
    if (planT !== null) {
      const ts = stations.map(stn => K.jstTimeOnDay(planT, stn.hh, stn.mm));
      const i = ts.findIndex((t, k) => k < ts.length - 1 && planT >= t && planT <= ts[k + 1]);
      if (i >= 0) {
        const px = x(i) + (planT - ts[i]) / (ts[i + 1] - ts[i]) * (x(i + 1) - x(i));
        ctx.beginPath(); ctx.arc(px, y, 9, 0, Math.PI * 2); ctx.setLineDash([3, 3]); ctx.lineWidth = 2.5; ctx.strokeStyle = css("--plan"); ctx.stroke(); ctx.setLineDash([]);
        ctx.fillStyle = css("--plan"); ctx.font = "11px system-ui"; ctx.fillText("予定位置", px + 12, y - 6);
        note += " 点線の円は、選択中の時刻から時刻表で計算した予定位置で、実測位置ではありません。";
      }
    }
    note += ` GPS の実測位置は地図で確認してください（概略図には投影しません）。ルート外の判定は、主要駅を結んだ概略線から ${cfg.route.offRouteKm} km 以上離れているかどうかだけで行い、乗車中かどうかは判定しません。`;
    $("routeNote").textContent = note;
    $("markerList").textContent = st.markers.length ? st.markers.map(m => `${K.jstClock(m.t, false)} ${m.label}`).join("・") : "（メモなし。ページを閉じると消えます）";

    // Where the real GPS position is relative to the planned route. Never claims "on board".
    const status = $("routeStatus");
    const posT = st.view === "ble" ? speedNow() : focusT();
    const p = s ? lastAt(s.gps, posT) : null;
    if (!p || posT - p.t > 10000) {
      status.textContent = "GPS 位置なし（予定ルートとの位置関係は不明）";
      status.className = "chip routestatus";
    } else {
      const km = K.distanceToPolylineKm(p.lat, p.lon, cfg.route.waypoints);
      const off = km >= cfg.route.offRouteKm;
      // The flat approximation is only meaningful nearby; far away just say "100 km or more".
      const dist = km >= 100 ? "100 km 以上" : `約 ${km.toFixed(km < 10 ? 1 : 0)} km`;
      status.textContent = off ? `ルート外で動作確認中（予定ルートから${dist}）` : "予定ルート付近（乗車中かどうかは判定しません）";
      status.className = `chip routestatus ${off ? "off" : "near"}`;
    }
  }

  // ---------------------------------------------------------------------------------------------
  function render() {
    renderHeader(); renderReplay(); renderRoute(); renderCards(); renderCharacter(); renderHistory(); drawMap(); renderCharts(); renderSensors(); renderSelection();
    const s = session();
    if (st.view === "csv" && s) {
      const [a, b] = windowRange();
      $("rangeLabel").textContent = `${K.jstClock(a)} 〜 ${K.jstClock(b)} JST`;
    }
  }
  function select(t) { st.selectedT = t; clampCursor(); render(); }
  function setView(v) {
    pause();
    if (st.view === "csv") st.csvT = st.selectedT;
    st.view = v; st.map.view = null; st.map.userMoved = false;
    // The CSV cursor is restored when coming back; BLE keeps its live behaviour (no cursor).
    st.selectedT = v === "csv" ? st.csvT : null;
    if (v === "csv" && st.selectedT === null && st.csv) st.selectedT = windowRange()[0];
    clampCursor();
    render();
  }

  // ---------------------------------------------------------------------------------------------
  // CSV replay: one cursor (st.selectedT) inside the visible range, advanced by real elapsed time
  // multiplied by the speed, so uneven event density still plays in recorded time.
  function clampCursor() {
    if (st.view !== "csv" || st.selectedT === null) return;
    const [a, b] = windowRange();
    st.selectedT = Math.min(Math.max(st.selectedT, a), b);
  }
  function setCursor(t) { st.selectedT = t; clampCursor(); render(); }
  function play() {
    if (st.view !== "csv" || !st.csv) return;
    const [a, b] = windowRange();
    if (st.selectedT === null || st.selectedT >= b) st.selectedT = a;
    st.play.playing = true;
    st.play.lastReal = performance.now();
    st.play.timer = setTimeout(playStep, PLAY_TICK_MS);
    render();
  }
  function pause() {
    st.play.playing = false;
    clearTimeout(st.play.timer);
    st.play.timer = null;
    if (cfg && $("playToggle")) renderReplay();
  }
  function playStep() {
    if (!st.play.playing) return;
    const now = performance.now();
    advance((now - st.play.lastReal) * st.play.speed);
    st.play.lastReal = now;
    if (st.play.playing) st.play.timer = setTimeout(playStep, PLAY_TICK_MS);
  }
  /** Moves the cursor by [ms] of recorded time; stops at the end of the range. */
  function advance(ms) {
    const [a, b] = windowRange();
    const t = (st.selectedT ?? a) + ms;
    if (t >= b) { st.selectedT = b; pause(); } else st.selectedT = Math.max(a, t);
    render();
  }
  function renderReplay() {
    const csv = st.view === "csv" && !!st.csv;
    $("replay").classList.toggle("hidden", !csv);
    if (!csv) return;
    const [a, b] = windowRange();
    const t = st.selectedT ?? a;
    $("cursor").value = String(Math.round((t - a) / (b - a) * 10000));
    $("cursorLabel").textContent = `${K.jstClock(t)} JST（開始から ${fmtElapsed(t - st.csv.t0)}）`;
    const toggle = $("playToggle");
    toggle.textContent = st.play.playing ? "⏸ 一時停止" : "▶ 再生";
    toggle.setAttribute("aria-label", st.play.playing ? "一時停止" : "再生");
    toggle.setAttribute("aria-pressed", String(st.play.playing));
  }

  // ---------------------------------------------------------------------------------------------
  // CSV loading
  /** Blob.text() where available, FileReader otherwise (older browsers). */
  function readText(file) {
    if (typeof file.text === "function") return file.text();
    return new Promise((resolve, reject) => {
      const r = new FileReader();
      r.onload = () => resolve(String(r.result));
      r.onerror = () => reject(r.error);
      r.readAsText(file, "utf-8");
    });
  }
  async function readFile(file) {
    if (!file) return;
    let text;
    try { text = await readText(file); } catch { message([["bad", "ファイルを読み込めませんでした。"]]); return; }
    const s = K.buildCsvSession(text);
    if (s.error) {
      const extra = [];
      if (s.brokenRows) extra.push(["warn", `列数の合わない行・壊れた行: ${s.brokenRows} 行`]);
      if (s.badTimeRows) extra.push(["warn", `時刻（time_utc）が読めない行: ${s.badTimeRows} 行`]);
      message([["bad", s.error], ...extra]);
      return;
    }
    st.csv = s; st.range = [0, 1]; $("rangeStart").value = 0; $("rangeEnd").value = 1000;
    const msgs = [["", `CSV を読み込みました: ${s.rows.toLocaleString()} 行。`]];
    if (s.brokenRows) msgs.push(["warn", `列数の合わない行・壊れた行を ${s.brokenRows} 行読み飛ばしました。`]);
    if (s.badTimeRows) msgs.push(["warn", `時刻が読めない行を ${s.badTimeRows} 行読み飛ばしました（推測で配置しません）。`]);
    if (!s.gps.length) msgs.push(["warn", "GPS の測位データがありません。地図と速度は表示できません。"]);
    if (!s.hasCellColumns || !s.counts.cell) msgs.push(["warn", "セル情報（cell 行）がありません。"]);
    if (s.unknownEvents.length) msgs.push(["", `未対応のイベント種別は集計のみ: ${s.unknownEvents.join(", ")}`]);
    message(msgs);
    pause();
    st.csvT = null;              // a new CSV starts at the beginning of its range
    if (st.view === "csv") st.view = "none";
    setView("csv");
  }

  // ---------------------------------------------------------------------------------------------
  // BLE (Web Bluetooth). The browser is the central; values are only shown on this page.
  function bleUi(state, { connected = false, canReconnect = false } = {}) {
    st.ble.state = state; st.ble.connected = connected;
    $("bleDisconnect").classList.toggle("hidden", !connected);
    $("bleReconnect").classList.toggle("hidden", !canReconnect);
    $("bleConnect").disabled = connected || !bleSupported();
    renderHeader();
  }
  const bleSupported = () => !!(window.isSecureContext && navigator.bluetooth);
  function bleOnValue(event) {
    const dv = event.target.value;
    const body = st.ble.reasm.accept(new Uint8Array(dv.buffer, dv.byteOffset, dv.byteLength));
    if (!body) return;
    const p = K.bleDecode(body);
    if (!p) return;
    const first = !st.live.last;
    if (!st.live.ingest(p, Date.now())) return;
    if (first && st.view !== "csv") setView("ble");
    else if (st.view === "ble") render();
    else renderHeader();
  }
  async function bleOpen() {
    st.ble.userDisconnect = false;
    st.ble.reasm = new K.BleReassembler();
    try {
      bleUi("接続中…");
      const server = await st.ble.device.gatt.connect();
      bleUi("サービスを確認中…");
      const service = await server.getPrimaryService(K.BLE.SERVICE_UUID);
      st.ble.char = await service.getCharacteristic(K.BLE.CHAR_UUID);
      st.ble.char.addEventListener("characteristicvaluechanged", bleOnValue);
      bleUi("通知を開始中…", { connected: true });
      await st.ble.char.startNotifications();
      bleUi("受信中", { connected: true });
    } catch (e) {
      bleUi(e && e.name === "NotFoundError" ? "サービスが見つかりません（アプリの共有がオフ、または別の端末）" : "接続できませんでした", { canReconnect: true });
    }
  }
  async function bleConnect() {
    try {
      st.ble.device = await navigator.bluetooth.requestDevice({ filters: [{ services: [K.BLE.SERVICE_UUID] }] });
    } catch (e) {
      bleUi(e && e.name === "NotFoundError" ? "未接続（選択がキャンセルされました）" : "未接続（端末を選べませんでした。Bluetooth がオフの可能性）");
      return;
    }
    // A new device starts a new live history; an earlier device's values are not mixed in.
    st.live.reset();
    st.ble.device.addEventListener("gattserverdisconnected", () => {
      if (st.ble.char) st.ble.char.removeEventListener("characteristicvaluechanged", bleOnValue);
      st.ble.char = null;
      bleUi(st.ble.userDisconnect ? "切断しました" : "切断されました（自動では再接続しません）", { canReconnect: true });
      render();
    });
    await bleOpen();
  }

  // ---------------------------------------------------------------------------------------------
  // Wiring
  function wire() {
    $("file").addEventListener("change", e => readFile(e.target.files[0]));
    const drop = $("drop");
    drop.addEventListener("dragover", e => { e.preventDefault(); drop.classList.add("over"); });
    drop.addEventListener("dragleave", () => drop.classList.remove("over"));
    drop.addEventListener("drop", e => { e.preventDefault(); drop.classList.remove("over"); readFile(e.dataTransfer.files[0]); });
    window.addEventListener("dragover", e => e.preventDefault());
    window.addEventListener("drop", e => e.preventDefault());
    $("viewCsv").addEventListener("click", () => setView("csv"));
    $("viewBle").addEventListener("click", () => setView("ble"));

    if (bleSupported()) {
      $("bleConnect").addEventListener("click", bleConnect);
      $("bleReconnect").addEventListener("click", () => (st.ble.device ? bleOpen() : bleConnect()));
      $("bleDisconnect").addEventListener("click", () => { st.ble.userDisconnect = true; if (st.ble.device?.gatt.connected) st.ble.device.gatt.disconnect(); });
    } else {
      $("bleConnect").disabled = true;
      st.ble.state = "非対応（HTTPS の Chrome / Edge など Web Bluetooth 対応ブラウザが必要）";
    }

    const onRange = function () {
      let a = Number($("rangeStart").value) / 1000, b = Number($("rangeEnd").value) / 1000;
      if (b - a < 0.001) { if (this === $("rangeStart")) a = b - 0.001; else b = a + 0.001; }
      st.range = [Math.max(0, a), Math.min(1, b)];
      st.map.view = null;
      clampCursor();             // a cursor outside the new range moves to the nearest end
      render();
    };
    $("rangeStart").addEventListener("input", onRange);
    $("rangeEnd").addEventListener("input", onRange);
    $("rangeReset").addEventListener("click", () => { st.range = [0, 1]; $("rangeStart").value = 0; $("rangeEnd").value = 1000; st.map.view = null; clampCursor(); render(); });

    // Replay controls (CSV only).
    $("playToggle").addEventListener("click", () => (st.play.playing ? pause() : play()));
    $("playStart").addEventListener("click", () => { pause(); setCursor(windowRange()[0]); });
    $("playEnd").addEventListener("click", () => { pause(); setCursor(windowRange()[1]); });
    $("playSpeed").addEventListener("input", e => { st.play.speed = Number(e.target.value) || 1; });
    $("cursor").addEventListener("input", e => {
      const [a, b] = windowRange();
      setCursor(a + Number(e.target.value) / 10000 * (b - a));
    });
    document.addEventListener("keydown", e => {
      if (st.view !== "csv" || !st.csv || e.altKey || e.ctrlKey || e.metaKey) return;
      // Leave keys alone while a control has focus (buttons, sliders, selects, inputs).
      const el = e.target;
      if (el && (el.isContentEditable || /^(BUTTON|INPUT|SELECT|TEXTAREA|SUMMARY|A)$/.test(el.tagName))) return;
      if (e.key === " " || e.code === "Space") { e.preventDefault(); st.play.playing ? pause() : play(); }
      else if (e.key === "ArrowLeft" || e.key === "ArrowRight") {
        e.preventDefault();
        const step = (e.shiftKey ? 10000 : 1000) * (e.key === "ArrowLeft" ? -1 : 1);
        setCursor((st.selectedT ?? windowRange()[0]) + step);
      }
    });
    $("liveWindow").addEventListener("input", e => { st.liveWindowMs = Number(e.target.value); render(); });
    for (const id of ["showMap", "showAcc"]) $(id).addEventListener("input", () => drawMap());
    $("fit").addEventListener("click", () => { st.map.view = null; st.map.userMoved = false; drawMap(); });
    $("history").addEventListener("click", e => { const tr = e.target.closest("tr[data-t]"); if (tr) select(Number(tr.dataset.t)); });
    for (const id of ["chSpeed", "chRsrp", "chQual", "chHttp", "chLane"]) {
      $(id).addEventListener("click", e => { if (!session()) return; const sc = scales($(id).clientWidth, $(id).clientHeight, 0, 1); select(sc.tx(e.offsetX)); });
    }
    if (cfg.route) {
      $("addMarker").addEventListener("click", () => {
        const s = session(); if (!s) return;
        const t = st.selectedT ?? (st.view === "ble" && st.live.last ? st.live.last.payload.t : null);
        if (t === null) { message([["warn", "マーカーを置く時刻を、グラフや地図をクリックして選んでください。"]]); return; }
        st.markers.push({ t, label: $("markerLabel").value }); st.markers.sort((a, b) => a.t - b.t); render();
      });
    }
    $("sensorsPanel").addEventListener("toggle", renderSensors);
    for (const id of ["chAccel", "chPressure"]) {
      $(id).addEventListener("click", e => { if (!session()) return; const sc = scales($(id).clientWidth, $(id).clientHeight, 0, 1); select(sc.tx(e.offsetX)); });
    }
    wireMap();
    let resize = null;
    window.addEventListener("resize", () => { clearTimeout(resize); resize = setTimeout(() => { st.map.view = null; render(); }, 120); });
    // Clock, BLE staleness and card ages move even without new data.
    setInterval(() => {
      $("clock").textContent = `${K.jstClock(Date.now())} JST`;
      if (st.view === "ble") { renderHeader(); renderCards(); renderCharacter(); renderRoute(); drawMap(); } else renderHeader();
    }, 1000);
  }

  function wireMap() {
    const c = $("map"), pointers = new Map();
    let moved = false, pinch = null;
    c.addEventListener("pointerdown", e => { c.setPointerCapture(e.pointerId); pointers.set(e.pointerId, { x: e.offsetX, y: e.offsetY }); moved = false; });
    c.addEventListener("pointermove", e => {
      if (!st.map.view || !pointers.has(e.pointerId)) return;
      const prev = pointers.get(e.pointerId); pointers.set(e.pointerId, { x: e.offsetX, y: e.offsetY });
      if (pointers.size === 2) {
        const [a, b] = [...pointers.values()], d = Math.hypot(a.x - b.x, a.y - b.y);
        if (pinch) zoomAt((a.x + b.x) / 2, (a.y + b.y) / 2, d / pinch);
        pinch = d; moved = true; return;
      }
      const dx = e.offsetX - prev.x, dy = e.offsetY - prev.y;
      if (Math.abs(dx) + Math.abs(dy) > 1) { moved = true; st.map.userMoved = true; }
      st.map.view.cx -= dx / st.map.view.scale; st.map.view.cy -= dy / st.map.view.scale; drawMap();
    });
    const end = e => {
      pointers.delete(e.pointerId); if (pointers.size < 2) pinch = null;
      if (!moved && e.type === "pointerup") pick(e.offsetX, e.offsetY);
    };
    c.addEventListener("pointerup", end); c.addEventListener("pointercancel", end);
    c.addEventListener("wheel", e => { if (!st.map.view) return; e.preventDefault(); zoomAt(e.offsetX, e.offsetY, Math.exp(-e.deltaY * 0.0015)); }, { passive: false });
    function zoomAt(px, py, f) {
      const v = st.map.view, w = c.clientWidth, h = c.clientHeight;
      const wx = v.cx + (px - w / 2) / v.scale, wy = v.cy + (py - h / 2) / v.scale;
      v.scale = Math.min(Math.max(v.scale * f, 0.5), 2 ** 19 * 2);
      v.cx = wx - (px - w / 2) / v.scale; v.cy = wy - (py - h / 2) / v.scale;
      st.map.userMoved = true; drawMap();
    }
    function pick(px, py) {
      const s = session(); if (!s || !st.map.view) return;
      let best = null, bd = 14;
      for (const p of s.gps) {
        if (!inWin(p.t)) continue;
        const [x, y] = toScreen(p, c.clientWidth, c.clientHeight), d = Math.hypot(x - px, y - py);
        if (d < bd) { bd = d; best = p; }
      }
      if (best) select(best.t);
    }
  }

  function start(config) {
    cfg = config;
    if (cfg.layout === "onescreen") document.body.classList.add("onescreen");
    buildLayout();
    loadSprite();
    wire();
    $("clock").textContent = `${K.jstClock(Date.now())} JST`;
    bleUi(bleSupported() ? "未接続" : st.ble.state);
    message([["", "CSV を開くか、Android アプリの「BLE でブラウザへ共有」をオンにして「BLE接続」を押してください。サンプルデータは表示しません。"]]);
    render();
  }

  return { start, _state: st, _advance: advance };
})();
