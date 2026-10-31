// Keio Ride Logger dashboard UI shared by the movement monitor (/) and the Mt.TAKAO 3 LT page.
// Pages differ only by the config passed to KRLApp.start(). Data (CSV or BLE) stays in memory:
// nothing is uploaded, stored, or logged. Only OpenStreetMap tiles are fetched, when the map is on.
"use strict";

const KRLApp = (() => {
  const K = KRL;
  const STALE_MS = 5000;
  const PLAY_TICK_MS = 200;      // replay redraw interval (wall clock)
  const PLAY_SPEEDS = [1, 2, 5, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100];
  const LOW_ACC_M = 30;
  const OSM_URL = "https://tile.openstreetmap.org";

  let cfg = null;
  const st = {
    csv: null,
    live: new K.LiveStore(),
    view: "none",                // "none" | "csv" | "ble"
    demo: false,
    debug: false,
    range: [0, 1],               // CSV window (fractions)
    liveWindowMs: 10 * 60 * 1000,
    selectedT: null,
    csvT: null,                  // CSV cursor kept while another view is shown
    // anchorReal/anchorT: wall clock and cursor when playback (re)started; lastT: cursor after the last step.
    play: { playing: false, speed: 1, timer: null, anchorReal: 0, anchorT: 0, lastT: null },
    dashboard: { overview: true },
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

  // Theme: dark (default) or light. Only the choice is stored; no measurement data.
  const THEME_KEY = "krl.theme.v1";
  const isLight = () => document.documentElement.dataset.theme === "light";
  function loadTheme() {
    try { return localStorage.getItem(THEME_KEY) === "light" ? "light" : "dark"; } catch (_) { return "dark"; }
  }
  function setTheme(theme, save) {
    document.documentElement.dataset.theme = theme;
    const b = $("themeToggle");
    if (b) {
      b.textContent = theme === "light" ? "ダーク表示" : "ライト表示";
      b.setAttribute("aria-pressed", String(theme === "light"));
      b.setAttribute("aria-label", theme === "light" ? "ダーク表示に切り替え" : "ライト表示に切り替え");
    }
    if (save) try { localStorage.setItem(THEME_KEY, theme); } catch (_) { /* not saved */ }
  }

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
<header class="top" id="topHeader">
  <div class="title"><h1>${esc(cfg.title)}</h1><p class="sub">${esc(cfg.subtitle)}</p></div>
  <div class="status">
    <span class="badge" id="modeBadge">データ未読み込み</span>
    <span class="badge demo-badge hidden" id="demoBadge">DEMO · 架空データ</span>
    <span class="badge" id="bleBadge">BLE 未接続</span>
    <span class="dashboard-switch" role="group" aria-label="ダッシュボード表示">
      <button id="overviewMode" class="active" type="button" aria-pressed="true">一覧</button>
      <button id="detailMode" type="button" aria-pressed="false">詳細</button>
    </span>
    <button id="themeToggle" type="button" class="themetoggle" aria-pressed="false" title="画面の配色を切り替えます（配色だけを保存します）">ライト表示</button>
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
    <div class="routehead"><h2 id="h-route">走行ルートと時刻表 <span class="tag">概略・縮尺なし・時刻は公式時刻表の予定</span></h2>
      <span class="chip routestatus" id="routeStatus">GPS 位置なし</span>
      <span class="routelegend small"><i class="lg-plan" aria-hidden="true"></i>予定位置（時刻表）<i class="lg-gps" aria-hidden="true"></i>GPS 推定位置: <span id="routeGps" class="nodata">表示なし</span><i class="lg-passed" aria-hidden="true"></i>通過済み（GPS）</span>
      <span class="routelegend small pinlegend"><i class="pin-lowacc" aria-hidden="true"></i>GPS精度低下<i class="pin-gap" aria-hidden="true"></i>GPS更新なし<i class="pin-near" aria-hidden="true"></i>トンネル区間付近<i class="pin-hint" aria-hidden="true"></i>トンネルかも？<span class="muted">（ピンをクリックで詳細）</span></span>
      ${tunnels().length ? `<span class="chip tunnelstatus" id="tunnelStatus" role="status">判定材料なし</span>` : ""}
      <span class="markerrow"><label>実測メモ <select id="markerLabel">${cfg.route.memoLabels.map(l => `<option>${esc(l)}</option>`).join("")}</select></label><button id="addMarker" type="button">選択中の時刻に追加</button><span id="markerList" class="muted small"></span></span></div>
    <div class="routewrap"><canvas id="routeCanvas" class="route-canvas" aria-label="ルート概略図。観測イベントのピンの一覧は下の「観測イベントの一覧」にあります"></canvas>
      <div class="pinpop hidden" id="pinPop" role="dialog" aria-label="観測イベントの詳細"><button type="button" id="pinPopClose" aria-label="閉じる">×</button><div id="pinPopBody"></div></div></div>
    ${tunnels().length ? `<p class="routenote" id="tunnelReasons"></p>` : ""}
    <details class="routenote"><summary>この図について</summary><span id="routeNote"></span></details>
    <details class="routenote" id="pinListBox"><summary>観測イベントの一覧（<span id="pinCount">0</span> 件・新しい順）</summary><ol id="pinList" class="pinlist"></ol></details>
    ${tunnels().length ? `<details class="routenote" id="tunnelInfo"><summary>トンネル・地下化区間のデータ（資料・概略）</summary>${tunnelInfoHtml()}</details>` : ""}</section>` : ""}
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
      ${tunnels().length ? `<p class="legend">紫の帯＝地下化区間（資料・駅を結んだ概略）・茶の破線＝高尾第一トンネルを含む駅間（トンネル位置は未設定）。どちらもこのアプリが地図の上に重ねている参考表示で、OpenStreetMap の地図記号（細い鉄道線など）ではありません。地下区間の正確な坑口位置や現在の GPS 位置を示すものでもありません。</p>` : ""}
    </section>
    <section class="side" aria-label="現在のネットワーク指標">
      <div class="panel character"><canvas id="charCanvas" class="sprite" aria-label="速度キャラクター"></canvas>
        <div><b id="charLabel">—</b><div id="charSub" class="small muted"></div><div class="small muted">GPS 推定速度に応じた演出です。乗り物を自動判定するものではなく、GPS 速度は車両の速度計でもありません。</div></div></div>
      <div class="cards" id="cards"></div>
      <div class="panel history-panel"><h2>セル変更の履歴</h2><p class="help">「接続アンテナエリアの変化（推定）」は、記録された在圏セルの識別情報の変化から推定したものです。物理的な電波塔やアンテナの切り替えを確定するものではありません。</p><p class="muted small" id="cellDiag"></p><div class="history"><table id="history"></table></div></div>
      ${cfg.travelModes ? `<div class="panel small"><label>移動手段メモ（表示用・自動判定しません） <select id="travelMode">${cfg.travelModes.map(m => `<option>${esc(m)}</option>`).join("")}</select></label></div>` : ""}
    </section>
  </div>
  <section class="panel timeseries" aria-labelledby="h-ts">
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
      <label>速度 <select id="playSpeed">${PLAY_SPEEDS.map(v => `<option value="${v}"${v === 1 ? " selected" : ""}>${v} 倍</option>`).join("")}</select></label>
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
      <div><h3>受信電波の強さ（RSRP）[dBm]</h3><canvas id="chRsrp" class="chart"></canvas></div>
      <div><h3>受信電波の品質（RSRQ・SINR/RSSNR）[dB]</h3><canvas id="chQual" class="chart"></canvas><p class="small muted">実線・● RSRQ　破線・○ SINR/RSSNR</p></div>
      <div><h3>HTTP 応答時間 [ms]（DNS・TCP・TLS・HTTP を含む総所要時間）</h3><canvas id="chHttp" class="chart"></canvas><p class="small muted">● 成功　▲ HTTP エラー　× タイムアウト・失敗</p></div>
    </div>
    <h3>セル変更の時系列</h3><canvas id="chLane" class="chart lane"></canvas>
    <p class="small muted">◆ 接続アンテナエリアの変化（推定、縦の破線）　○ 基準（最初の在圏セル、数えない）${cfg.route ? "　点線＝時刻表の予定（公式掲載時刻）　実線＝実測メモ" : ""}。値のない区間は線でつながず、0 で埋めません。${tunnels().length ? "<br>電波・HTTP・セル変更のグラフの薄い紫＝トンネル区間付近（GPS 位置からの推定）・薄い橙＝トンネルかも？（GPS 欠測中に携帯網の観測が途切れた・低下した時間の推定）・緑の点線＝GPS 測位復帰。いずれも推定の境界で、実測の入口・出口ではありません。" : ""}</p>
  </section>
  <section class="panel track3d-panel" id="track3dPanel" aria-labelledby="h-3d">
    <h2 id="h-3d">GPS 軌跡の 3D 表示 <span class="tag">緯度・経度・GPS 高度・地形ではありません</span></h2>
    <div class="toolbar">
      <label>高さの強調 <select id="t3dExag"><option value="1">×1（実寸）</option><option value="3">×3</option><option value="10" selected>×10</option><option value="30">×30</option></select></label>
      <button id="t3dReset" type="button">向きを戻す</button>
      <span class="muted" id="t3dStatus"></span>
    </div>
    <canvas id="track3d" class="track3d" tabindex="0" aria-label="GPS 軌跡の 3D 表示。ドラッグまたは矢印キーで回転できます"></canvas>
    <p class="small muted">CSV の緯度・経度と <code>altitude_m</code>（GPS 高度）だけで軌跡を立体的に描きます。地形・建物・標高データは描かず、外部のデータも取得しません。高さは見やすさのため強調しています（初期値 ×10）。GPS の高さは水平位置より誤差が大きく、測量の標高ではありません。高度が空欄の点は描かず、空欄や 5 秒を超える欠測の前後は線でつなぎません。下の格子は「この区間で最も低い GPS 高度」の平面で、地面ではありません。ドラッグ（またはフォーカスして矢印キー）で回転、点をクリックするとその時刻を選びます。再生位置より後の軌跡は薄く表示します。</p>
  </section>
  <section class="panel altitude-panel" id="altitudePanel" aria-label="GPS 高度">
    <h2>GPS 高度 [m]</h2>
    <canvas id="chAlt" class="chart"></canvas>
    <p class="small muted">CSV の <code>altitude_m</code>（GPS の測位結果）をそのまま描きます。平滑化・補間はせず、空欄の区間は線でつなぎません。GPS の高さは水平位置より誤差が大きく、地形図や測量の標高ではありません。気圧からは換算しません。CSV に垂直方向の精度はないため、誤差の幅は示していません。○ は再生位置（選択時刻）の値です。</p>
  </section>
  <details class="panel sensors" id="sensorsPanel"><summary><h2>補助センサー（加速度・気圧）</h2></summary>
    <p class="small muted" id="sensorNote"></p>
    <div class="charts">
      <div><h3>端末の加速度 [m/s²]（重力を含む端末座標系の x・y・z。列車の進行方向とは限らない）</h3><canvas id="chAccel" class="chart"></canvas><p class="small muted">実線 x　破線 y　点線 z。描画のときだけ 1 ピクセル幅ごとの最小・最大に間引きます（データは間引きません）</p></div>
      <div><h3>気圧 [hPa]（補助観測。標高には換算しません）</h3><canvas id="chPressure" class="chart"></canvas><p class="small muted">車内の空調やセンサーの個体差でも変わります</p></div>
    </div>
  </details>
  <section class="panel" id="selectionPanel"><h2>選択中の時刻</h2><div id="selection" class="muted">地図の点、グラフ、または履歴をクリックしてください。</div></section>
</main>
<div class="floatchar hidden" id="floatChar" role="group" tabindex="0" aria-roledescription="移動できるキャラクター"
  aria-label="速度キャラクター。ドラッグ、またはフォーカスして矢印キーで移動できます">
  <div class="floatbar"><span class="grip" aria-hidden="true">⋮⋮</span>
    <button id="floatHide" type="button" aria-label="キャラクターをたたむ" title="たたむ">×</button></div>
  <canvas id="floatCanvas" class="floatsprite" aria-hidden="true"></canvas>
  <div class="floatcap"><b id="floatLabel">—</b><span id="floatSub"></span>
    <span class="floatnote">GPS 推定速度に応じた演出で、乗り物の判定ではありません</span></div>
  <div class="floatgrip" id="floatGrip" role="button" tabindex="0" aria-describedby="floatGripHelp"
    aria-label="キャラクターの大きさを変える" title="ドラッグで大きさを変更（ダブルクリックで元の大きさ）"></div>
  <span id="floatGripHelp" class="hidden">左下のつまみをドラッグして幅と高さを変えます。右上の角は動きません。フォーカスして矢印キーでも変えられます（左右で幅、上下で高さ、Shift で大きく）。ダブルクリックか Enter で元の大きさに戻ります。</span>
</div>
<button id="floatShow" class="floatshow hidden" type="button" aria-label="キャラクターを表示">キャラクターを表示</button>`;
  }

  // ---------------------------------------------------------------------------------------------
  // Messages and header
  function message(list) {
    $("messages").innerHTML = list.map(([kind, text]) => `<p class="msg ${kind}">${esc(text)}</p>`).join("");
  }

  function renderHeader() {
    const badge = $("modeBadge");
    badge.textContent = st.debug ? "表示: DEBUG データ" : st.demo ? "表示: デモデータ" : st.view === "csv" ? "表示: CSV（計測済みデータ）" : st.view === "ble" ? "表示: BLE ライブ" : "データ未読み込み";
    badge.className = `badge ${st.view}`;
    $("demoBadge").classList.toggle("hidden", !st.demo);
    $("demoBadge").textContent = st.debug ? "DEBUG · 架空データ" : "DEMO · 架空データ";
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

    const notTakenAlt = ble => `<span class="nodata">${ble ? "未取得（BLE では送信しない）" : "未取得"}</span>`;
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
    if (cfg.showDistance) cards.push(card("距離（参考）", distanceText(s, t), `精度 ${LOW_ACC_M} m 以内・欠測を除く測位点から`, "distance"));
    // GPS altitude of the same fix as the speed: blank stays "未取得", never 0 m, never from pressure.
    cards.push(card("GPS 高度",
      gpsFresh ? (fmt(gps.alt, 1, " m") ?? notTakenAlt(live)) : `<span class="nodata">${gps ? "GPS 更新なし" : "GPS 未測位"}</span>`,
      "GPS 由来で誤差が大きい値です。気圧からは換算しません", `altitude${gps && !gpsFresh ? " dim" : ""}`));
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
    $("charSub").textContent = !s ? "" : band ? `GPS 速度 ${state.kmh.toFixed(1)} km/h（直近 5 秒の中央値）` : "GPS 速度 不明（0 km/h ではありません）";
    // The floating character repeats the state, speed and caveat so it can stand alone.
    $("floatLabel").textContent = $("charLabel").textContent;
    $("floatSub").textContent = !s ? "" : band ? `${state.kmh.toFixed(1)} km/h` : "速度不明（0 km/h ではない）";
    // While the floating one is shown, the card keeps only its text so the sprite is not shown twice.
    const floating = !float.hidden;
    $("charCanvas").classList.toggle("hidden", floating);
    if (!floating) drawSprite($("charCanvas"), band ? state.band : null);
    else drawSprite($("floatCanvas"), band ? state.band : null);
  }
  /** Draws frame [band] (null = dimmed stop frame) with nearest-neighbour scaling and kept aspect ratio. */
  function drawSprite(c, band) {
    const { ctx, w, h } = setupCanvas(c);
    if (!sprite) { ctx.fillStyle = css("--muted"); ctx.font = "12px system-ui"; ctx.textAlign = "center"; ctx.fillText(band !== null ? K.SPEED_BANDS[band].label : "—", w / 2, h / 2); ctx.textAlign = "left"; return; }
    const f = K.spriteFrame(sprite.naturalWidth || sprite.width, sprite.naturalHeight || sprite.height, band ?? 0);
    const scale = Math.min(w / f.w, h / f.h), dw = f.w * scale, dh = f.h * scale;
    ctx.imageSmoothingEnabled = false;
    ctx.globalAlpha = band !== null ? 1 : 0.25;
    ctx.drawImage(sprite, f.x, f.y, f.w, f.h, (w - dw) / 2, (h - dh) / 2, dw, dh);
    ctx.globalAlpha = 1;
  }

  // ---------------------------------------------------------------------------------------------
  // Floating character: dragged anywhere and resized from its lower-left grip by the user, kept inside
  // the viewport. Only its position, size and shown/hidden state go to localStorage (shared by both
  // pages); no GPS/CSV/BLE data.
  const FLOAT_KEY = "krl.floatingCharacter.v1";
  // custom: the user placed it (or a position was restored); otherwise it follows the default spot.
  // w/h: user size in CSS px (null = the CSS default size).
  const float = { hidden: false, x: null, y: null, drag: null, custom: false, w: null, h: null, resize: null };
  const FLOAT_MIN = { w: 120, h: 170 }, FLOAT_MAX = { w: 640, h: 760 };
  function loadFloat() {
    try {
      const v = JSON.parse(localStorage.getItem(FLOAT_KEY) || "null");
      if (v && typeof v === "object") {
        float.hidden = v.hidden === true;
        if (Number.isFinite(v.w) && Number.isFinite(v.h)) { float.w = v.w; float.h = v.h; }
        if (Number.isFinite(v.x) && Number.isFinite(v.y)) { float.x = v.x; float.y = v.y; float.custom = true; }
      }
    } catch (_) { /* unavailable or corrupt storage: use the defaults */ }
  }
  function saveFloat() {
    // The default spot is not stored, so it keeps following the layout until the user moves the box.
    const pos = float.custom ? { x: float.x, y: float.y } : { x: null, y: null };
    try { localStorage.setItem(FLOAT_KEY, JSON.stringify({ ...pos, w: float.w, h: float.h, hidden: float.hidden })); } catch (_) { /* not saved */ }
  }
  /** Size of the floating box; CSS sizes as a fallback when layout is unavailable. */
  function floatSize() {
    const el = $("floatChar");
    return { w: el.offsetWidth || float.w || 150, h: el.offsetHeight || float.h || 190 };
  }
  /**
   * Places the box at (x, y), clamped so it stays fully inside the viewport. null = the default spot:
   * the lower-left corner of the map (scale bar is top-left, attribution bottom-right) when the map is
   * on screen and large enough, else the bottom right of the window.
   */
  function placeFloat(x, y) {
    const el = $("floatChar"), { w, h } = floatSize(), m = 8;
    const vw = window.innerWidth || document.documentElement.clientWidth, vh = window.innerHeight || document.documentElement.clientHeight;
    if (x === null || y === null) {
      const r = $("map") ? $("map").getBoundingClientRect() : null;
      if (r && r.width > w * 2.5 && r.height > h + 24 && r.bottom <= vh && r.top >= 0) { x = r.left + 12; y = r.bottom - h - 12; }
      else { x = vw - w - 16; y = vh - h - 16; }
    }
    float.x = Math.round(Math.max(m, Math.min(x, vw - w - m)));
    float.y = Math.round(Math.max(m, Math.min(y, vh - h - m)));
    el.style.left = `${float.x}px`; el.style.top = `${float.y}px`;
  }
  function showFloat(show) {
    float.hidden = !show;
    $("floatChar").classList.toggle("hidden", !show);
    $("floatShow").classList.toggle("hidden", show);
    if (show) placeFloat(float.custom ? float.x : null, float.custom ? float.y : null);
    saveFloat();
    renderCharacter();
  }
  /**
   * Applies a user size (null = CSS default), limited to [FLOAT_MIN, FLOAT_MAX] and to the viewport
   * (8 px margin each side, so narrow screens get a smaller maximum). [right] keeps the top-right corner.
   */
  function sizeFloat(w, h, right = null) {
    const el = $("floatChar");
    const vw = window.innerWidth || document.documentElement.clientWidth, vh = window.innerHeight || document.documentElement.clientHeight;
    if (w === null || h === null) { float.w = float.h = null; }
    else {
      const maxW = Math.max(FLOAT_MIN.w, Math.min(FLOAT_MAX.w, vw - 16)), maxH = Math.max(FLOAT_MIN.h, Math.min(FLOAT_MAX.h, vh - 16));
      float.w = Math.round(Math.max(FLOAT_MIN.w, Math.min(w, maxW)));
      float.h = Math.round(Math.max(FLOAT_MIN.h, Math.min(h, maxH)));
    }
    el.classList.toggle("sized", float.w !== null);
    el.style.width = float.w !== null ? `${float.w}px` : "";
    el.style.height = float.h !== null ? `${float.h}px` : "";
    if (right !== null && float.x !== null) placeFloat(right - floatSize().w, float.y);
    renderCharacter();
  }
  /**
   * Lower-left grip: dragging it changes width and height while the top-right corner stays put (like a
   * window corner). Its own pointer capture and stopPropagation keep the window drag, map and charts out.
   */
  function wireFloatGrip() {
    const g = $("floatGrip");
    g.addEventListener("pointerdown", e => {
      if (e.button !== 0) return;
      e.preventDefault(); e.stopPropagation();
      const { w, h } = floatSize();
      if (float.x === null) placeFloat(null, null);
      float.resize = { id: e.pointerId, x: e.clientX, y: e.clientY, w, h, right: float.x + w };
      $("floatChar").classList.add("resizing");
      if (g.setPointerCapture) try { g.setPointerCapture(e.pointerId); } catch (_) { /* synthetic pointer */ }
    });
    g.addEventListener("pointermove", e => {
      const r = float.resize;
      if (!r || e.pointerId !== r.id) return;
      e.preventDefault(); e.stopPropagation();
      sizeFloat(r.w + (r.x - e.clientX), r.h + (e.clientY - r.y), r.right);
      float.custom = true;
    });
    const end = e => {
      if (!float.resize || e.pointerId !== float.resize.id) return;
      e.stopPropagation();
      float.resize = null;
      $("floatChar").classList.remove("resizing");
      saveFloat();
    };
    g.addEventListener("pointerup", end);
    g.addEventListener("pointercancel", end);
    const reset = () => { const { w } = floatSize(), right = float.x + w; sizeFloat(null, null, right); saveFloat(); };
    g.addEventListener("dblclick", e => { e.stopPropagation(); reset(); });
    // Keys: Left/Right = wider/narrower (the left edge moves), Up/Down = shorter/taller, Shift = x5, Enter = reset.
    g.addEventListener("keydown", e => {
      if (e.key === "Enter" || e.key === " ") { e.preventDefault(); e.stopPropagation(); reset(); return; }
      const d = { ArrowLeft: [1, 0], ArrowRight: [-1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] }[e.key];
      if (!d) return;
      e.preventDefault(); e.stopPropagation();
      const step = e.shiftKey ? 50 : 10, { w, h } = floatSize();
      if (float.x === null) placeFloat(null, null);
      sizeFloat(w + d[0] * step, h + d[1] * step, float.x + w);
      float.custom = true;
      saveFloat();
    });
  }
  function wireFloat() {
    const el = $("floatChar");
    loadFloat();
    sizeFloat(float.w, float.h);
    wireFloatGrip();
    el.classList.toggle("hidden", float.hidden);
    $("floatShow").classList.toggle("hidden", !float.hidden);
    if (!float.hidden) placeFloat(float.custom ? float.x : null, float.custom ? float.y : null);
    $("floatHide").addEventListener("click", () => { showFloat(false); $("floatShow").focus(); });
    $("floatShow").addEventListener("click", () => { showFloat(true); el.focus(); });
    // Pointer Events cover mouse, touch and pen. Capturing the pointer and stopping propagation keeps
    // the map, charts and page from seeing the drag.
    el.addEventListener("pointerdown", e => {
      if (e.button !== 0 || e.target.closest("button")) return;
      e.preventDefault(); e.stopPropagation();
      float.drag = { id: e.pointerId, dx: e.clientX - float.x, dy: e.clientY - float.y };
      el.classList.add("dragging");
      if (el.setPointerCapture) try { el.setPointerCapture(e.pointerId); } catch (_) { /* synthetic pointer */ }
    });
    el.addEventListener("pointermove", e => {
      if (!float.drag || e.pointerId !== float.drag.id) return;
      e.preventDefault(); e.stopPropagation();
      placeFloat(e.clientX - float.drag.dx, e.clientY - float.drag.dy);
      float.custom = true;
    });
    const end = e => {
      if (!float.drag || e.pointerId !== float.drag.id) return;
      e.stopPropagation();
      float.drag = null;
      el.classList.remove("dragging");
      saveFloat();
    };
    el.addEventListener("pointerup", end);
    el.addEventListener("pointercancel", end);
    for (const t of ["click", "wheel", "dblclick"]) el.addEventListener(t, e => e.stopPropagation());
    // Arrow keys move it while it has focus (Shift = larger steps); they do not reach the replay keys.
    el.addEventListener("keydown", e => {
      const d = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] }[e.key];
      if (!d || e.target !== el) return;
      e.preventDefault(); e.stopPropagation();
      const step = e.shiftKey ? 50 : 10;
      placeFloat(float.x + d[0] * step, float.y + d[1] * step);
      float.custom = true;
      saveFloat();
    });
    window.addEventListener("resize", () => {
      if (float.w !== null) sizeFloat(float.w, float.h);
      if (!float.hidden) placeFloat(float.custom ? float.x : null, float.custom ? float.y : null);
    });
  }

  // ---------------------------------------------------------------------------------------------
  // Cell change history
  function renderHistory() {
    const s = session();
    const table = $("history");
    if (!s) { table.innerHTML = ""; return; }
    const allRows = s.changes.filter(c => inWin(c.t)).slice().reverse();
    const rows = allRows;
    const diag = $("cellDiag");
    if (diag) {
      const suffix = ` 全${allRows.length}件`;
      diag.textContent = K.cellDiagnosis(s).text + suffix + (s.legacyChanges ? "（旧形式の cell_change: 最初の行を基準として扱っています）" : "");
    }
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
    drawSections(ctx, w, h, mapOn || isLight());
    // Casing makes the line readable on light tiles and on the dark no-map background.
    // Light ground (map tiles, or the light theme without tiles) uses the dark-on-light stroke set.
    const lightBg = mapOn || isLight();
    const core = mapOn ? "#c2185b" : lightBg ? "#00838f" : "#4dd0e1", casing = lightBg ? "#ffffff" : "#0b1015", edge = mapOn ? "#3a0020" : lightBg ? "#004d55" : "#e6eaee";
    const lowAcc = p => p.acc === null || p.acc > LOW_ACC_M;
    if ($("showAcc").checked) {
      for (const p of pts) {
        if (p.acc === null) continue;
        const r = p.acc / K.merc.metersPerPx0(p.lat) * st.map.view.scale;
        if (r < 1.5 || r > 4000) continue;
        const [x, y] = toScreen(p, w, h);
        ctx.beginPath(); ctx.arc(x, y, r, 0, Math.PI * 2);
        ctx.fillStyle = mapOn ? "rgba(194,24,91,0.10)" : lightBg ? "rgba(0,131,143,0.10)" : "rgba(77,208,225,0.10)"; ctx.fill();
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
        ctx.beginPath(); ctx.arc(x, y, 13, 0, Math.PI * 2); ctx.lineWidth = 2.5; ctx.strokeStyle = lightBg ? "#000" : "#fff"; ctx.stroke();
      }
    }
    // Scale bar (top-left, away from the attribution).
    const mpp = K.merc.metersPerPx0((last ?? pts[pts.length - 1]).lat) / st.map.view.scale;
    const nice = [5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000];
    const m = nice.find(v => v / mpp >= 70) || nice[nice.length - 1];
    ctx.fillStyle = lightBg ? "rgba(255,255,255,.85)" : "rgba(0,0,0,.5)"; ctx.fillRect(6, 6, m / mpp + 16, 30);
    ctx.strokeStyle = lightBg ? "#222" : "#eee"; ctx.lineWidth = 2;
    ctx.beginPath(); ctx.moveTo(14, 28); ctx.lineTo(14 + m / mpp, 28); ctx.stroke();
    ctx.fillStyle = lightBg ? "#222" : "#eee"; ctx.font = "12px system-ui"; ctx.fillText(m >= 1000 ? `${m / 1000} km` : `${m} m`, 14, 22);
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
    const shortWindow = spanS <= 120;
    $("axisTag").textContent = shortWindow ? "JST・経過" : "JST";
    for (let t = Math.ceil(x0 / 1000 / stepS) * stepS * 1000; t <= x1; t += stepS * 1000) {
      const x = sc.sx(t); ctx.beginPath(); ctx.moveTo(x, PAD.t); ctx.lineTo(x, h - PAD.b); ctx.stroke();
      const elapsedS = Math.max(0, Math.round((t - x0) / 1000));
      const label = shortWindow ? `${Math.floor(elapsedS / 60)}:${pad2(elapsedS % 60)}` : K.jstClock(t, false);
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
    ctx.fillStyle = css("--shade");
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
    let vis = series.map(se => ({ ...se, pts: validPts(se) }));
    const vals = vis.flatMap(se => se.pts.map(p => p.v));
    if (!vals.length) { emptyNote(ctx, w, h, emptyText); const sc = scales(w, h, 0, 1); verticals(ctx, w, h, sc); return; }
    let yMin = Math.min(...vals), yMax = Math.max(...vals);
    if (yMax - yMin < 1e-6) { yMin -= 1; yMax += 1; }
    const pad = (yMax - yMin) * 0.1; yMin -= pad; yMax += pad;
    const sc = scales(w, h, yMin, yMax);
    axes(ctx, w, h, sc, yMin, yMax, unit);
    if (TUNNEL_CHARTS.has(id)) tunnelBands(ctx, w, h, sc);
    verticals(ctx, w, h, sc);
    // Dense series (20 Hz sensors) are drawn as a per-pixel min/max envelope; the data is untouched.
    vis = vis.map(se => (se.downsample && se.pts.length > 2 * w ? { ...se, pts: envelope(se.pts, sc) } : se));
    for (const se of vis) {
      if (se.gapMs) {
        ctx.save(); ctx.strokeStyle = se.color; ctx.lineWidth = se.noMarks ? 1.5 : 2; if (se.dash || se.dashed) ctx.setLineDash(se.dash || [6, 4]);
        for (let i = 1; i < se.pts.length; i++) {
          const a = se.pts[i - 1], b = se.pts[i];
          if (b.t - a.t > se.gapMs || b.brk) continue;
          ctx.beginPath(); ctx.moveTo(sc.sx(a.t), sc.sy(a.v)); ctx.lineTo(sc.sx(b.t), sc.sy(b.v)); ctx.stroke();
        }
        ctx.restore();
      }
      if (!se.noMarks) for (const p of se.pts) mark(ctx, p.mark || "dot", sc.sx(p.t), sc.sy(p.v), se.color);
      if (se.cursorValue) cursorValue(ctx, w, sc, se, unit);
    }
    shadeFuture(ctx, w, h, sc);
  }
  /**
   * Points inside the window with a finite value. With [breakOnMissing], a point that follows a
   * missing value starts a new line, so a blank in the record is never bridged.
   */
  function validPts(se) {
    const out = [];
    let missing = false;
    for (const p of se.pts) {
      if (!inWin(p.t)) continue;
      if (p.v === null || !Number.isFinite(p.v)) { missing = true; continue; }
      out.push(missing && se.breakOnMissing ? { ...p, brk: true } : p);
      missing = false;
    }
    return out;
  }
  /** Rings the last point at or before the focus time and labels its value (the cursor's value). */
  function cursorValue(ctx, w, sc, se, unit) {
    const t = focusT();
    let p = null;
    for (const q of se.pts) if (q.t <= t) p = q; else break;
    if (!p) return;
    const x = sc.sx(p.t), y = sc.sy(p.v);
    ctx.save();
    ctx.strokeStyle = css("--fg"); ctx.lineWidth = 2;
    ctx.beginPath(); ctx.arc(x, y, 5, 0, Math.PI * 2); ctx.stroke();
    ctx.fillStyle = css("--fg"); ctx.font = "11px system-ui";
    const label = `${p.v.toFixed(1)} ${unit}`;
    ctx.textAlign = x > w - 70 ? "right" : "left";
    ctx.fillText(label, x + (ctx.textAlign === "right" ? -8 : 8), Math.max(12, y - 6));
    ctx.restore();
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
    chart("chAlt", [{ pts: (s ? s.gps : []).map(p => ({ t: p.t, v: p.alt })), color: css("--gps"), gapMs: K.GPS_GAP_MS, breakOnMissing: true, cursorValue: true }], "m",
      st.view === "ble" ? "BLE ライブでは GPS 高度を受信しません（CSV のみ）" : "GPS 高度の値がありません（空欄、または列のない古い CSV）");
    renderLane();
  }
  function renderLane() {
    const { ctx, w, h } = setupCanvas($("chLane"));
    const s = session();
    if (!s) return emptyNote(ctx, w, h, "データ未読み込み");
    const sc = scales(w, h, 0, 1);
    axes(ctx, w, h, { ...sc, sy: () => -100 }, 0, 1, "");
    tunnelBands(ctx, w, h, sc);
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
  // GPS track in 3D (latitude, longitude, GPS altitude only). Orthographic view rotated by the user;
  // no terrain, no external data. Missing altitude is never drawn as 0 and breaks the line.
  const t3d = { yaw: -0.6, pitch: 0.55, drag: null, screen: [] };
  const T3D_DEFAULT = { yaw: -0.6, pitch: 0.55 };
  function renderTrack3d() {
    const canvas = $("track3d");
    if (!canvas) return;
    const { ctx, w, h } = setupCanvas(canvas);
    const status = $("t3dStatus");
    t3d.screen = [];
    if (!w || !h) return;
    const s = session();
    if (!s) { status.textContent = ""; return emptyNote(ctx, w, h, "データ未読み込み"); }
    if (st.view === "ble") { status.textContent = ""; return emptyNote(ctx, w, h, "BLE ライブでは GPS 高度を受信しないため 3D 表示できません（CSV のみ）"); }
    const all = s.gps.filter(p => inWin(p.t));
    const pts = all.filter(p => p.alt !== null && Number.isFinite(p.alt));
    status.textContent = all.length ? `高度あり ${pts.length} 点${all.length > pts.length ? `・高度なし ${all.length - pts.length} 点（描きません）` : ""}` : "";
    if (pts.length < 2) return emptyNote(ctx, w, h, all.length ? "GPS 高度の値が足りません（空欄、または列のない古い CSV）" : "この時間帯に GPS の測位がありません");
    const exag = Number($("t3dExag").value) || 1;
    const k = Math.PI / 180, R = 6371008.8;
    const lat0 = pts.reduce((a, p) => a + p.lat, 0) / pts.length, lon0 = pts.reduce((a, p) => a + p.lon, 0) / pts.length;
    const cos0 = Math.cos(lat0 * k);
    const altMin = Math.min(...pts.map(p => p.alt)), altMax = Math.max(...pts.map(p => p.alt));
    const local = p => ({ e: (p.lon - lon0) * k * R * cos0, n: (p.lat - lat0) * k * R, u: (p.alt - altMin) * exag });
    const L = pts.map(local);
    const span = Math.max(50, ...L.map(q => Math.abs(q.e)), ...L.map(q => Math.abs(q.n))) * 2;
    const height = (altMax - altMin) * exag;
    const cy0 = Math.cos(t3d.yaw), sy0 = Math.sin(t3d.yaw), cp = Math.cos(t3d.pitch), sp = Math.sin(t3d.pitch);
    // Fit the rotated bounding box (horizontal square plus the height) into the canvas.
    const scale = Math.min((w - 40) / (span * 1.42), (h - 40) / (span * 1.42 * sp + height * cp + 1e-6));
    const proj = q => {
      const x = q.e * cy0 - q.n * sy0, depth = q.e * sy0 + q.n * cy0;
      return [w / 2 + x * scale, h / 2 + (height / 2 - q.u) * cp * scale - depth * sp * scale];
    };
    // Reference plane at the lowest GPS altitude in the window (not the ground), with a north arrow.
    ctx.save();
    ctx.strokeStyle = css("--line"); ctx.lineWidth = 1;
    const half = span / 2, step = span / 6;
    for (let g = -half; g <= half + 1e-6; g += step) {
      let [a, b] = [proj({ e: g, n: -half, u: 0 }), proj({ e: g, n: half, u: 0 })];
      ctx.beginPath(); ctx.moveTo(...a); ctx.lineTo(...b); ctx.stroke();
      [a, b] = [proj({ e: -half, n: g, u: 0 }), proj({ e: half, n: g, u: 0 })];
      ctx.beginPath(); ctx.moveTo(...a); ctx.lineTo(...b); ctx.stroke();
    }
    const [nx, ny] = proj({ e: 0, n: half * 1.08, u: 0 });
    ctx.fillStyle = css("--muted"); ctx.font = "bold 12px system-ui"; ctx.textAlign = "center"; ctx.fillText("北", nx, ny); ctx.textAlign = "left";
    const cursor = st.view === "csv" ? st.selectedT : null;
    const isPast = t => cursor === null || t <= cursor;
    // Faint drop lines from some points to the plane make the height readable.
    const every = Math.max(1, Math.ceil(pts.length / 60));
    ctx.strokeStyle = css("--muted"); ctx.globalAlpha = 0.35;
    for (let i = 0; i < pts.length; i += every) {
      if (!isPast(pts[i].t)) continue;
      const [ax, ay] = proj(L[i]), [bx, by] = proj({ ...L[i], u: 0 });
      ctx.beginPath(); ctx.moveTo(ax, ay); ctx.lineTo(bx, by); ctx.stroke();
    }
    ctx.globalAlpha = 1;
    // Line breaks: a GPS gap over 5 s, or any blank altitude between two drawn points.
    const idx = new Map(all.map((p, i) => [p, i]));
    const scr = L.map(proj);
    ctx.lineCap = "round"; ctx.lineJoin = "round";
    for (let i = 1; i < pts.length; i++) {
      const a = pts[i - 1], b = pts[i];
      if (b.t - a.t > K.GPS_GAP_MS || idx.get(b) - idx.get(a) !== 1) continue;
      const past = isPast(b.t);
      ctx.globalAlpha = past ? 1 : 0.3; ctx.strokeStyle = css("--gps"); ctx.lineWidth = past ? 3 : 2;
      ctx.beginPath(); ctx.moveTo(...scr[i - 1]); ctx.lineTo(...scr[i]); ctx.stroke();
    }
    ctx.globalAlpha = 1;
    pts.forEach((p, i) => t3d.screen.push({ t: p.t, x: scr[i][0], y: scr[i][1] }));
    // Position at the cursor / focus time: the last fix with altitude at or before it.
    const t = focusT();
    let cur = -1;
    for (let i = 0; i < pts.length; i++) if (pts[i].t <= t) cur = i; else break;
    if (cur >= 0) {
      const [x, y] = scr[cur], stale = t - pts[cur].t > STALE_MS;
      ctx.beginPath(); ctx.arc(x, y, 8, 0, Math.PI * 2); ctx.lineWidth = 3; ctx.strokeStyle = stale ? css("--muted") : css("--bad"); ctx.stroke();
      ctx.fillStyle = css("--fg"); ctx.font = "11px system-ui";
      ctx.fillText(`${pts[cur].alt.toFixed(1)} m${stale ? "（古い値）" : ""}`, Math.min(x + 11, w - 90), Math.max(12, y - 8));
    }
    ctx.fillStyle = css("--muted"); ctx.font = "11px system-ui";
    ctx.fillText(`GPS 高度 ${altMin.toFixed(1)}〜${altMax.toFixed(1)} m・高さ ×${exag} 強調・格子 1 マス約 ${Math.round(step)} m`, 8, h - 8);
    ctx.restore();
  }
  function wireTrack3d() {
    const c = $("track3d");
    if (!c) return;
    let moved = false;
    const rotate = (dyaw, dpitch) => {
      t3d.yaw += dyaw;
      t3d.pitch = Math.min(1.5, Math.max(0.05, t3d.pitch + dpitch));
      renderTrack3d();
    };
    c.addEventListener("pointerdown", e => {
      if (e.button !== 0) return;
      t3d.drag = { id: e.pointerId, x: e.clientX, y: e.clientY }; moved = false;
      if (c.setPointerCapture) try { c.setPointerCapture(e.pointerId); } catch (_) { /* synthetic pointer */ }
    });
    c.addEventListener("pointermove", e => {
      if (!t3d.drag || e.pointerId !== t3d.drag.id) return;
      const dx = e.clientX - t3d.drag.x, dy = e.clientY - t3d.drag.y;
      if (Math.abs(dx) + Math.abs(dy) > 2) moved = true;
      t3d.drag.x = e.clientX; t3d.drag.y = e.clientY;
      rotate(dx * 0.01, dy * 0.01);
    });
    const end = e => {
      if (!t3d.drag || e.pointerId !== t3d.drag.id) return;
      t3d.drag = null;
      if (moved || e.type !== "pointerup") return;
      // A click (no drag) selects the nearest drawn point within 14 px.
      let best = null, bd = 14;
      for (const q of t3d.screen) { const d = Math.hypot(q.x - e.offsetX, q.y - e.offsetY); if (d < bd) { bd = d; best = q; } }
      if (best) select(best.t);
    };
    c.addEventListener("pointerup", end); c.addEventListener("pointercancel", end);
    c.addEventListener("keydown", e => {
      const d = { ArrowLeft: [-0.1, 0], ArrowRight: [0.1, 0], ArrowUp: [0, -0.08], ArrowDown: [0, 0.08] }[e.key];
      if (!d) return;
      e.preventDefault(); e.stopPropagation();       // rotating must not move the replay cursor
      rotate(...d);
    });
    $("t3dExag").addEventListener("input", renderTrack3d);
    $("t3dReset").addEventListener("click", () => { Object.assign(t3d, T3D_DEFAULT); renderTrack3d(); });
  }

  // ---------------------------------------------------------------------------------------------
  // Route schematic and timetable (only when the page config has a route)
  function planMarkers() {
    const s = session();
    if (!cfg.route || !s) return [];
    const ref = windowRange()[1];
    return cfg.route.stations.filter(x => x.hh !== null)
      .map(x => ({ t: K.jstTimeOnDay(ref, x.hh, x.mm), label: `${x.name} ${pad2(x.hh)}:${pad2(x.mm)}` }))
      .sort((a, b) => a.t - b.t);
  }
  // ---------------------------------------------------------------------------------------------
  // Tunnel reference sections and hints (LT page). Three layers kept apart: reference shapes from
  // public sources, GPS proximity, and "トンネルかも？" from a GPS gap plus a cellular sign.
  const tunnels = () => (cfg.route && cfg.route.tunnels) || [];
  function tunnelInfoHtml() {
    const acc = { approximate: "概略（駅を結んだ線）", unset: "位置未設定" };
    return "<ul>" + tunnels().map(sec => `<li><b>${esc(sec.label)} ${esc(sec.name)}</b>（${esc(sec.line)}・${esc(sec.between)}）<br>` +
      `位置の精度: ${esc(acc[sec.accuracy] ?? sec.accuracy)}。${esc(sec.accuracyNote)}<br>決め方: ${esc(sec.method)}。` +
      `自動判定: ${sec.autoDetect ? `あり（概略線から ${sec.toleranceM} m 以内を「付近」）` : "未設定（表示のみ）"}。確認日: ${esc(sec.checked)}<br>出典: ` +
      sec.sources.map(x => `<a href="${esc(x.url)}" target="_blank" rel="noopener">${esc(x.title)}</a>`).join("、") + "</li>").join("") +
      "</ul><p>公式の機械可読な入口・出口の座標データは見つかっていません。帯は資料の駅間を示す参考範囲で、トンネルの坑口や実際の線路ではありません。GPS の点は線路へ補正しません。区間付近にいることは乗車中であることを意味しません。</p>";
  }
  const tunnelCache = new WeakMap();
  /** Chart bands for the session; recomputed when the session grows (BLE). */
  function tunnelIv(s) {
    const key = `${s.gps.length}|${s.serving.length}|${s.probes.length}|${s.t1}`;
    const hit = tunnelCache.get(s);
    if (hit && hit.key === key) return hit.iv;
    const iv = K.tunnelIntervals(s, tunnels());
    tunnelCache.set(s, { key, iv });
    return iv;
  }
  const TUNNEL_CHARTS = new Set(["chRsrp", "chQual", "chHttp"]);
  /** Faint estimated bands under the data: near (GPS), maybe-tunnel (GPS gap + cell sign), GPS recovery ticks. */
  function tunnelBands(ctx, w, h, sc) {
    const s = session();
    if (!s || !tunnels().length) return;
    const iv = tunnelIv(s), [x0, x1] = windowRange();
    const band = (a, b, fill, label) => {
      if (b < x0 || a > x1) return;
      const xa = Math.max(sc.sx(Math.max(a, x0)), PAD.l), xb = Math.min(sc.sx(Math.min(b, x1)), w - PAD.r);
      ctx.fillStyle = fill; ctx.fillRect(xa, PAD.t, Math.max(2, xb - xa), h - PAD.t - PAD.b);
      if (xb - xa > 40) { ctx.fillStyle = css("--muted"); ctx.font = "10px system-ui"; ctx.fillText(label, xa + 3, h - PAD.b - 4); }
    };
    ctx.save();
    for (const b of iv.near) band(b.a, b.b, "rgba(149,117,205,0.16)", "区間付近（推定）");
    for (const b of iv.hint) band(b.a, b.b, "rgba(255,179,71,0.16)", "トンネルかも？（推定）");
    for (const r of iv.recovery) {
      if (!inWin(r.t)) continue;
      const x = sc.sx(r.t);
      ctx.strokeStyle = css("--serving"); ctx.lineWidth = 1.2; ctx.setLineDash([1, 3]);
      ctx.beginPath(); ctx.moveTo(x, PAD.t); ctx.lineTo(x, h - PAD.b); ctx.stroke();
      ctx.setLineDash([]); ctx.fillStyle = css("--serving"); ctx.font = "10px system-ui"; ctx.fillText("GPS 測位復帰", x + 3, PAD.t + 20);
    }
    ctx.restore();
  }
  /** Reference shapes on the GPS map ([lightBg] = tiles or light theme): solid band = underground (approx.), dashed = tunnel range, position unset. */
  function drawSections(ctx, w, h, lightBg) {
    for (const sec of tunnels()) {
      const pts = sec.coords.map(([lat, lon]) => toScreen({ lat, lon }, w, h));
      const under = sec.kind === "underground";
      ctx.save();
      ctx.lineCap = "round"; ctx.lineJoin = "round";
      ctx.strokeStyle = under ? (lightBg ? "rgba(103,58,183,0.45)" : "rgba(149,117,205,0.55)") : (lightBg ? "rgba(121,85,72,0.8)" : "rgba(188,170,164,0.8)");
      ctx.lineWidth = under ? 16 : 4;
      if (!under) ctx.setLineDash([10, 7]);
      ctx.beginPath(); pts.forEach(([x, y], i) => (i ? ctx.lineTo(x, y) : ctx.moveTo(x, y))); ctx.stroke();
      const [mx, my] = pts[Math.floor((pts.length - 1) / 2)];
      if (mx > -50 && mx < w + 50 && my > -50 && my < h + 50) {
        const text = under ? `${sec.label}・概略` : `${sec.label}を含む駅間・位置未設定`;
        ctx.setLineDash([]); ctx.font = "11px system-ui";
        const tw = ctx.measureText(text).width;
        ctx.fillStyle = lightBg ? "rgba(255,255,255,.85)" : "rgba(0,0,0,.6)"; ctx.fillRect(mx + 10, my - 22, tw + 8, 16);
        ctx.fillStyle = lightBg ? "#311b92" : "#e1d5f5"; ctx.fillText(text, mx + 14, my - 10);
      }
      ctx.restore();
    }
  }
  /** Status chip and the facts behind it. Never says "in a tunnel" or "no signal". */
  function renderTunnel() {
    const chip = $("tunnelStatus");
    if (!chip) return;
    const s = session(), reasons = $("tunnelReasons");
    let text = "判定材料なし", cls = "", why = "";
    if (s && st.view === "ble" && liveStale()) {
      text = "BLE 受信停止中（判定を保留）";
      why = "BLE の受信が止まっているため、GPS・携帯網の欠測とは区別して判定を止めています。表示中の値は古い値です。";
    } else if (s) {
      const t = st.view === "ble" ? speedNow() : focusT();
      const r = K.tunnelStateAt(s, t, tunnels());
      const rec = r.recovered ? `GPS 測位復帰 ${K.jstClock(r.recovered.t)}（欠測 ${Math.round(r.recovered.gapMs / 1000)} 秒）` : "";
      if (r.hint) {
        text = `トンネルかも？（${r.approach.section.short}）`; cls = "maybe";
        why = `観測事実: ${r.reasons.join("・")}。直前の GPS は ${K.jstClock(r.approach.t)} に${r.approach.section.label}の付近。センサーからの推測で、トンネル内・圏外とは断定しません。`;
      } else if (r.near) {
        text = r.near.lowAcc ? "トンネル区間付近（GPS精度低下）" : `トンネル区間付近（${r.near.section.short}）`; cls = "near";
        why = `GPS 位置が${r.near.section.label}「${r.near.section.name}」の概略線から約 ${Math.round(r.near.distM)} m（許容 ${r.near.section.toleranceM} m）。区間内にいる・乗車中であるとは判定しません。${rec ? ` ${rec}。` : ""}`;
      } else if (r.approach) {
        text = "GPS 更新なし（区間付近の後）";
        why = `観測事実: ${r.reasons.join("・")}。同じ時刻の在圏セル・RSRP には低下が見えないため「トンネルかも？」は出していません。GPS が止まる理由は屋内・端末の置き場所などもあります。`;
      } else if (rec) {
        text = "GPS 測位復帰"; cls = "near"; why = `${rec}。電波や HTTP の回復は各グラフの実測で確認してください（通過が原因とは断定しません）。`;
      } else if (r.gps === "fresh") {
        text = "トンネル区間外";
        why = "GPS 位置は自動判定できる区間（調布付近の地下化区間）から離れています。高尾第一トンネルは位置未設定のため自動判定しません。";
      } else if (r.gps === "gap") {
        text = "GPS 更新なし（区間付近の記録なし）";
        why = "区間に近づいた位置の記録がないため、トンネルかどうかは判定しません。";
      } else why = "GPS の記録がないため、区間との位置関係は判定できません。";
    }
    chip.textContent = text;
    chip.className = `chip tunnelstatus ${cls}`;
    chip.title = why;
    if (reasons) reasons.textContent = why;
  }

  /** Theme-dependent colours of the route schematic (the dark one is the LT "night map" look). */
  function routePalette() {
    return isLight()
      ? { bg: ["#eaf4fb", "#dfeef8", "#e7f1f9"], wave: "rgba(30, 90, 130, .12)", glow: "rgba(0, 150, 200, .25)", glowShadow: "#4fc3e8", rail: "#0a8fb8",
        ring: "rgba(10, 143, 184, .15)", stnFill: "#ffffff", stnEnd: "#0a8fb8", stnMid: "#27495c", name: "#10202c", time: "#3d5566", dir: "#4a6577",
        plan: "#d9730d", planRing: "#7a3d00", planText: "#7a3d00", gpsEdge: "#00838f", gpsBack: "rgba(255,255,255,.95)", gpsText: "#00616a",
        passed: "#9aa7b2", pinEdge: "#ffffff", pin: { lowacc: "#b38600", gap: "#5f6b75", near: "#5e35b1", hint: "#d9730d" } }
      : { bg: ["#071522", "#0b1b2b", "#081321"], wave: "rgba(62, 119, 151, .12)", glow: "rgba(18, 201, 242, .36)", glowShadow: "#12c9f2", rail: "#23d7fa",
        ring: "rgba(35, 215, 250, .13)", stnFill: "#071522", stnEnd: "#32e3ff", stnMid: "#d6f6ff", name: "#f2f7fb", time: "#a9bdcc", dir: "#8da8ba",
        plan: "#ffb347", planRing: "#fff0cf", planText: "#fff0cf", gpsEdge: "#5ef2d0", gpsBack: "rgba(7,21,34,.92)", gpsText: "#b8fff0",
        passed: "#5d6b78", pinEdge: "#071522", pin: { lowacc: "#f2c94c", gap: "#c4ced6", near: "#b39ddb", hint: "#ffb347" } };
  }
  /**
   * A fix projected onto the schematic: { from, to, u, distKm, alongKm } between two schematic stations,
   * or { reason } when it is inaccurate (> 30 m or unknown), off the route (>= offRouteKm) or cannot be
   * placed. Freshness is checked by the callers. Cached per fix object.
   */
  const projCache = new WeakMap();
  function projectFix(p) {
    if (projCache.has(p)) return projCache.get(p);
    let out;
    if (p.acc === null || p.acc > LOW_ACC_M) out = { reason: `GPS 精度不足（${p.acc === null ? "精度不明" : `${p.acc.toFixed(0)} m`}）` };
    else {
      const pr = K.projectToPolyline(p.lat, p.lon, cfg.route.waypoints);
      const stations = cfg.route.stations;
      if (pr.distKm >= cfg.route.offRouteKm) out = { reason: "ルート外（ルート図には表示しません）" };
      else if (!stations.every(x => Number.isInteger(x.wp) && x.wp < pr.cumKm.length)) out = { reason: "GPS 位置をルート図に表示できません" };
      else {
        // Station i sits at schematic index i; find the pair of stations whose route distance brackets the fix.
        const at = stations.map((x, i) => ({ i, km: pr.cumKm[x.wp] })).sort((a, b) => a.km - b.km);
        const seg = at.slice(0, -1).findIndex((a, k) => pr.alongKm >= a.km && pr.alongKm <= at[k + 1].km);
        if (seg < 0) out = { reason: "GPS 位置をルート図に表示できません" };
        else {
          const a = at[seg], b = at[seg + 1];
          out = { from: a.i, to: b.i, u: (pr.alongKm - a.km) / (b.km - a.km), distKm: pr.distKm, alongKm: pr.alongKm };
        }
      }
    }
    projCache.set(p, out);
    return out;
  }
  /** The latest fix at or before [t] (any age) that projects onto the schematic, or null. Never a later fix. */
  function lastProjected(s, t, maxAgeMs = Infinity) {
    let lo = 0, hi = s.gps.length - 1, i = -1;
    while (lo <= hi) { const m = (lo + hi) >> 1; if (s.gps[m].t <= t) { i = m; lo = m + 1; } else hi = m - 1; }
    for (; i >= 0 && t - s.gps[i].t <= maxAgeMs; i--) {
      const pr = projectFix(s.gps[i]);
      if (!pr.reason) return { p: s.gps[i], ...pr };
    }
    return null;
  }
  /**
   * Where the GPS fix lies on the schematic, or why it is not shown. Uses only a fresh (<= 5 s), accurate
   * (<= 30 m) fix at or before [t] that is within offRouteKm of the planned polyline; the fix is projected
   * onto that rough line for the schematic only (the map keeps the raw position).
   */
  function routeGpsEstimate(s, t) {
    if (!s) return { reason: "データなし" };
    if (st.view === "ble" && liveStale()) return { reason: "BLE 受信停止中（古い位置は動かしません）" };
    const p = lastAt(s.gps, t);
    if (!p) return { reason: "GPS 位置なし" };
    if (t - p.t > STALE_MS) return { reason: `GPS 更新なし（最後の測位 ${K.jstClock(p.t)}）` };
    const pr = projectFix(p);
    if (pr.reason) return pr;
    return { p, ...pr, facing: routeFacing(s, p, pr.alongKm) };
  }
  /**
   * Observed events for the route pins, in time order: GPS accuracy drop (start of a low-accuracy run),
   * GPS gap (5 s after the last fix), tunnel-section proximity and "トンネルかも？" (start of each band,
   * from the existing tunnel judgement). Each keeps the fix used for its place (or none). Cached per session.
   */
  const eventCache = new WeakMap();
  function routeEvents(s) {
    const key = `${s.gps.length}|${s.serving.length}|${s.probes.length}|${s.t1}`;
    const hit = eventCache.get(s);
    if (hit && hit.key === key) return hit.ev;
    const ev = [];
    const placeAt = t => lastProjected(s, t, 60000);
    for (let i = 0; i < s.gps.length; i++) {
      const p = s.gps[i], prev = s.gps[i - 1];
      const low = p.acc === null || p.acc > LOW_ACC_M;
      const prevLow = prev && prev.t >= p.t - K.GPS_GAP_MS && (prev.acc === null || prev.acc > LOW_ACC_M);
      if (low && !prevLow) {
        ev.push({ t: p.t, kind: "lowacc", why: `水平精度 ${p.acc === null ? "不明" : `${p.acc.toFixed(0)} m`}（${LOW_ACC_M} m を超える）`, at: placeAt(p.t), estimated: true });
      }
      if (prev && p.t - prev.t > K.GPS_GAP_MS) ev.push({ t: prev.t + K.GPS_GAP_MS, kind: "gap", lastFix: prev.t, end: p.t, at: placeAt(prev.t), estimated: true });
    }
    const last = s.gps[s.gps.length - 1];
    if (last) ev.push({ t: last.t + K.GPS_GAP_MS, kind: "gap", lastFix: last.t, end: null, at: placeAt(last.t), estimated: true, open: true });
    if (tunnels().length) {
      const iv = tunnelIv(s);
      for (const b of iv.near) {
        const p = s.gps.find(q => q.t === b.a), own = p ? projectFix(p) : { reason: "x" };
        ev.push({ t: b.a, kind: "near", why: `${b.section.label}「${b.section.name}」の概略線の近く（GPS 位置からの推定${b.lowAcc ? "・精度低下を含む" : ""}）`,
          at: own.reason ? placeAt(b.a) : { p, ...own }, estimated: !!own.reason });
      }
      for (const b of iv.hint) {
        const r = K.tunnelStateAt(s, b.a, tunnels());
        ev.push({ t: b.a, kind: "hint", why: `${r.reasons.join("・")}（直前の GPS が区間付近。トンネル内とは断定しません）`, at: placeAt(b.a), estimated: true });
      }
    }
    ev.sort((a, b) => a.t - b.t);
    eventCache.set(s, { key, ev });
    return ev;
  }
  const PIN_KINDS = {
    lowacc: { label: "GPS精度低下", shape: "tri" },
    gap: { label: "GPS更新なし", shape: "ring" },
    near: { label: "トンネル区間付近", shape: "diamond" },
    hint: { label: "トンネルかも？", shape: "square" },
  };
  /** Pins observed by time [t] (nothing later), with display text that never reveals later data. */
  function routePinsAt(s, t) {
    if (!s) return [];
    return routeEvents(s).filter(e => e.t <= t && !(e.open && st.view === "csv" && e.t > s.t1)).map(e => {
      let why = e.why;
      if (e.kind === "gap") {
        const ongoing = e.end === null || e.end > t;
        why = `最後の測位 ${K.jstClock(e.lastFix)} から 5 秒を超えて更新なし（${ongoing ? `継続中・${Math.round((t - e.lastFix) / 1000)} 秒` : `欠測 ${Math.round((e.end - e.lastFix) / 1000)} 秒・${K.jstClock(e.end)} に測位復帰`}）`;
      }
      const at = e.at && e.at.p.t <= t ? e.at : null;
      const where = !at ? "位置なし（推定の根拠がないため、ルート上には置きません）"
        : `${e.estimated ? "位置は推定: " : ""}${K.jstClock(at.p.t)} の測位（精度 ${at.p.acc.toFixed(0)} m）を概略線へ投影`;
      return { ...e, why, at, where };
    });
  }
  /**
   * Which way the GPS marker faces on the schematic: compares the route distance of this fix with an
   * accurate fix 5-60 s earlier. Waypoints run 新宿 -> 高尾山口 and the schematic puts 高尾山口 on the left,
   * so moving along the line means "left". Unknown or less than 30 m of progress = left (this demo's direction).
   */
  function routeFacing(s, p, alongKm) {
    let i = s.gps.indexOf(p);
    for (i--; i >= 0; i--) {
      const q = s.gps[i];
      if (p.t - q.t < 5000) continue;
      if (p.t - q.t > 60000) break;
      if (q.acc === null || q.acc > LOW_ACC_M) continue;
      const d = alongKm - (projectFix(q).alongKm ?? K.projectToPolyline(q.lat, q.lon, cfg.route.waypoints).alongKm);
      return d <= -0.03 ? "right" : "left";
    }
    return "left";
  }
  let routePinHits = [];
  /** Text list of the observed pins (detail mode); each item selects its time. */
  function renderPinList(pins) {
    const ol = $("pinList");
    if (!ol) return;
    $("pinCount").textContent = String(pins.length);
    ol.innerHTML = pins.length ? pins.slice().reverse().map(p => `<li><button type="button" class="linkish" data-t="${p.t}">${K.jstClock(p.t)}</button> <b>${esc(PIN_KINDS[p.kind].label)}</b>: ${esc(p.why)}。${esc(p.where)}</li>`).join("")
      : "<li class=\"muted\">この時刻までの観測イベントはありません</li>";
  }
  /** Popover with the time, evidence and position basis of the pins under a click on the route canvas. */
  function showPinPop(hit, px, py) {
    const pop = $("pinPop");
    if (!hit) { pop.classList.add("hidden"); return; }
    $("pinPopBody").innerHTML = hit.pins.map(p => `<p><b>${K.jstClock(p.t)} ${esc(PIN_KINDS[p.kind].label)}</b><br>根拠: ${esc(p.why)}<br>${esc(p.where)}</p>`).join("");
    pop.classList.remove("hidden");
    const c = $("routeCanvas"), box = c.parentElement.getBoundingClientRect(), r = c.getBoundingClientRect();
    pop.style.left = `${Math.max(4, Math.min(r.left - box.left + px + 8, box.width - 300))}px`;
    pop.style.top = `${r.top - box.top + py + 10}px`;
  }
  function renderRoute() {
    if (!cfg.route) return;
    const { ctx, w, h } = setupCanvas($("routeCanvas"));
    const stations = cfg.route.stations, pal = routePalette();
    const narrow = w < 640, roomy = h >= 90;
    const padX = Math.min(82, Math.max(40, w * 0.055));
    const x = i => padX + i * (w - padX * 2) / (stations.length - 1);
    // Rows: station labels on top, the rail in the middle, direction labels at the bottom corners.
    const y = roomy ? h * 0.56 : h * 0.52;
    const labelY = Math.max(13, y - (roomy ? 22 : 14));
    // The rail overview is intentionally schematic. Give it a subtle night-map treatment without
    // implying that the line, cell regions, or GPS position are a geographic measurement.
    ctx.save();
    const bg = ctx.createLinearGradient(0, 0, w, h);
    bg.addColorStop(0, pal.bg[0]); bg.addColorStop(0.55, pal.bg[1]); bg.addColorStop(1, pal.bg[2]);
    ctx.fillStyle = bg; ctx.fillRect(0, 0, w, h);
    ctx.strokeStyle = pal.wave; ctx.lineWidth = 1;
    for (let row = 0; row < 4; row++) {
      const yy = 15 + row * (h - 30) / 3;
      ctx.beginPath(); ctx.moveTo(0, yy);
      for (let xx = 0; xx <= w; xx += 24) ctx.lineTo(xx, yy + Math.sin(xx / 92 + row) * 4);
      ctx.stroke();
    }
    // Cubic segments between neighbouring stations; markers are placed on the same curves.
    const amp = roomy ? 1 : 0.6;
    const curves = stations.slice(0, -1).map((_, i) => {
      const x0 = x(i), x1 = x(i + 1), dx = x1 - x0, up = i % 2 === 0 ? [-17, 14] : [-14, 13];
      return [[x0, y], [x0 + dx * (i ? .35 : .36), y + up[0] * amp], [x1 - dx * (i ? .28 : .25), y + up[1] * amp], [x1, y]];
    });
    const bez = (c, u) => [0, 1].map(k => (1 - u) ** 3 * c[0][k] + 3 * (1 - u) ** 2 * u * c[1][k] + 3 * (1 - u) * u ** 2 * c[2][k] + u ** 3 * c[3][k]);
    /** Point on the rail between schematic stations [i] and [j] at fraction [u] of the way from i. */
    const railPoint = (i, j, u) => {
      const lo = Math.min(i, j), c = curves[lo];
      return bez(c, i < j ? u : 1 - u);
    };
    const rail = () => { ctx.beginPath(); ctx.moveTo(x(0), y); for (const c of curves) ctx.bezierCurveTo(c[1][0], c[1][1], c[2][0], c[2][1], c[3][0], c[3][1]); ctx.stroke(); };
    ctx.shadowColor = pal.glowShadow; ctx.shadowBlur = 16;
    ctx.strokeStyle = pal.glow; ctx.lineWidth = 12; ctx.lineCap = "round"; rail(); ctx.shadowBlur = 0;
    ctx.strokeStyle = pal.rail; ctx.lineWidth = 5; rail();
    const s = session();
    const posT = st.view === "ble" ? speedNow() : focusT();
    // Passed part of the line (grey): from the departure station (earliest timetable time) to the latest
    // fix that projects onto the schematic, at or before the cursor / live time. It stops when GPS stops.
    const progress = s ? lastProjected(s, posT) : null;
    if (progress) {
      const startIdx = stations.map((stn, i) => ({ i, m: stn.hh * 60 + stn.mm })).sort((a, b) => a.m - b.m)[0].i;
      const c = Math.min(progress.from, progress.to), tc = progress.from < progress.to ? progress.u : 1 - progress.u;
      // de Casteljau split of a cubic at [t]: [left part, right part].
      const split = (q, t) => {
        const L = (a, b) => [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t];
        const ab = L(q[0], q[1]), bc = L(q[1], q[2]), cd = L(q[2], q[3]), abc = L(ab, bc), bcd = L(bc, cd), m = L(abc, bcd);
        return [[q[0], ab, abc, m], [m, bcd, cd, q[3]]];
      };
      const parts = startIdx > c
        ? [split(curves[c], tc)[1], ...curves.slice(c + 1, startIdx)]
        : [...curves.slice(startIdx, c), split(curves[c], tc)[0]];
      ctx.strokeStyle = pal.passed; ctx.lineWidth = 6;
      for (const q of parts) { ctx.beginPath(); ctx.moveTo(...q[0]); ctx.bezierCurveTo(...q[1], ...q[2], ...q[3]); ctx.stroke(); }
    }
    // One label per station ("name 予定 hh:mm着"), kept inside the canvas and shrunk if neighbours touch.
    const labelText = stn => `${stn.name} ${narrow ? "" : "予定 "}${pad2(stn.hh)}:${pad2(stn.mm)}${stn.kind}`;
    let size = roomy ? 14 : 12;
    const widths = () => stations.map(stn => { ctx.font = `bold ${size}px system-ui`; return ctx.measureText(labelText(stn)).width; });
    let tw = widths();
    const lefts = () => tw.map((wd, i) => Math.min(Math.max(x(i) - wd / 2, 4), w - 4 - wd));
    const overlaps = l => l.some((lx, i) => i > 0 && lx < l[i - 1] + tw[i - 1] + 8);
    while (size > 9 && overlaps(lefts())) { size--; tw = widths(); }
    const lx = lefts();
    stations.forEach((stn, i) => {
      const endpoint = i === 0 || i === stations.length - 1;
      if (endpoint) { ctx.beginPath(); ctx.arc(x(i), y, 13, 0, Math.PI * 2); ctx.fillStyle = pal.ring; ctx.fill(); }
      ctx.beginPath(); ctx.arc(x(i), y, endpoint ? 8 : 6, 0, Math.PI * 2);
      ctx.fillStyle = pal.stnFill; ctx.fill(); ctx.lineWidth = endpoint ? 3 : 2.5; ctx.strokeStyle = endpoint ? pal.stnEnd : pal.stnMid; ctx.stroke();
      ctx.textAlign = "left"; ctx.font = `bold ${size}px system-ui`;
      const name = `${stn.name} `;
      ctx.fillStyle = pal.name; ctx.fillText(name, lx[i], labelY);
      const nw = ctx.measureText(name).width;
      ctx.font = `${size - 1}px system-ui`; ctx.fillStyle = pal.time;
      ctx.fillText(labelText(stn).slice(name.length), lx[i] + nw, labelY);
    });
    ctx.font = `${roomy ? 11 : 10}px system-ui`; ctx.fillStyle = pal.dir;
    ctx.textAlign = "left"; ctx.fillText(narrow ? "← 高尾方面" : "← 高尾・高尾山口方面", 6, h - 5);
    ctx.textAlign = "right"; ctx.fillText(narrow ? "新宿方面 →" : "新宿・都心方面 →", w - 6, h - 5);
    ctx.textAlign = "left";
    const est = routeGpsEstimate(s, posT);
    const gpsAt = est.reason ? null : railPoint(est.from, est.to, est.u);
    // Scheduled position from the timetable (not a measurement): amber circle with a dashed ring.
    const planT = s ? focusT() : null;
    let note = "駅は均等配置の概略図で、距離の縮尺はありません。予定時刻は京王電鉄の公式時刻表（土休日）の掲載値で、乗車日に確認してください。";
    if (planT !== null) {
      const ordered = stations.map((stn, i) => ({ t: K.jstTimeOnDay(planT, stn.hh, stn.mm), i })).sort((a, b) => a.t - b.t);
      const k = ordered.slice(0, -1).findIndex((a, n) => planT >= a.t && planT <= ordered[n + 1].t);
      if (k >= 0) {
        const a = ordered[k], b = ordered[k + 1];
        const [px, py] = railPoint(a.i, b.i, (planT - a.t) / (b.t - a.t));
        ctx.shadowColor = pal.plan; ctx.shadowBlur = 12;
        ctx.beginPath(); ctx.arc(px, py, 8, 0, Math.PI * 2); ctx.fillStyle = pal.plan; ctx.fill(); ctx.shadowBlur = 0;
        ctx.beginPath(); ctx.arc(px, py, 12, 0, Math.PI * 2); ctx.setLineDash([3, 3]); ctx.lineWidth = 2; ctx.strokeStyle = pal.planRing; ctx.stroke(); ctx.setLineDash([]);
        // The text label is left to the legend when the GPS marker is close (its tag sits below it too).
        if (roomy && (!gpsAt || Math.abs(gpsAt[0] - px) > 70)) {
          ctx.font = "bold 11px system-ui"; ctx.fillStyle = pal.planText; ctx.textAlign = "center";
          ctx.fillText("予定位置", Math.min(Math.max(px, 30), w - 30), py + 27); ctx.textAlign = "left";
        }
        note += " 琥珀色の点線の円は、選択中の時刻から時刻表で計算した予定位置で、実測位置ではありません。";
      }
    }
    // Event pins, accumulated in time order up to the cursor / live time. Near pins stagger into three
    // rows; when all rows are taken the pin is folded into its neighbour's "+n" count. Kept on the rail
    // band so the canvas never grows.
    const pins = routePinsAt(s, posT);
    routePinHits = [];
    const rowY = [0, roomy ? -13 : -9, roomy ? 13 : 9], lastX = [-Infinity, -Infinity, -Infinity], folded = new Map();
    const placed = [];
    for (const pin of pins) {
      if (!pin.at) continue;
      const [px, py] = railPoint(pin.at.from, pin.at.to, pin.at.u);
      let row = lastX.findIndex(v => Math.abs(px - v) >= 11);
      if (row < 0) { const host = placed[placed.length - 1]; folded.set(host, (folded.get(host) || 1) + 1); host.more.push(pin); continue; }
      lastX[row] = px;
      placed.push({ pin, x: px, y: py + rowY[row], more: [] });
    }
    for (const q of placed) {
      const k = PIN_KINDS[q.pin.kind], col = pal.pin[q.pin.kind], r = roomy ? 5.5 : 4.5;
      ctx.lineWidth = 1.5; ctx.strokeStyle = pal.pinEdge; ctx.fillStyle = col;
      ctx.beginPath();
      if (k.shape === "tri") { ctx.moveTo(q.x, q.y - r - 1); ctx.lineTo(q.x + r, q.y + r - 1); ctx.lineTo(q.x - r, q.y + r - 1); ctx.closePath(); ctx.fill(); ctx.stroke(); }
      else if (k.shape === "ring") { ctx.arc(q.x, q.y, r, 0, Math.PI * 2); ctx.fillStyle = pal.pinEdge; ctx.fill(); ctx.lineWidth = 2.5; ctx.strokeStyle = col; ctx.stroke(); }
      else if (k.shape === "diamond") { ctx.moveTo(q.x, q.y - r - 1); ctx.lineTo(q.x + r + 1, q.y); ctx.lineTo(q.x, q.y + r + 1); ctx.lineTo(q.x - r - 1, q.y); ctx.closePath(); ctx.fill(); ctx.stroke(); }
      else { ctx.rect(q.x - r, q.y - r, r * 2, r * 2); ctx.fill(); ctx.stroke(); }
      if (folded.has(q)) { ctx.font = "bold 9px system-ui"; ctx.fillStyle = pal.name; ctx.fillText(`+${folded.get(q) - 1}`, q.x + r + 2, q.y - r); }
      routePinHits.push({ x: q.x, y: q.y, pins: [q.pin, ...q.more] });
    }
    renderPinList(pins);
    // GPS estimate: speed-band sprite in a cyan-edged square with a "GPS" tag, never from a stale or far
    // fix. The sprite (drawn facing right) is mirrored to face the direction of travel; frame and text are not.
    if (gpsAt) {
      const [gx, gy] = gpsAt;
      const size = roomy ? 28 : 22, half = size / 2 + 4;
      ctx.fillStyle = pal.gpsBack; ctx.fillRect(gx - half, gy - half, half * 2, half * 2);
      ctx.lineWidth = 2.5; ctx.strokeStyle = pal.gpsEdge; ctx.strokeRect(gx - half, gy - half, half * 2, half * 2);
      const state = K.speedStateAt(s.gps, posT);
      if (sprite) {
        const f = K.spriteFrame(sprite.naturalWidth || sprite.width, sprite.naturalHeight || sprite.height, state.band ?? 0);
        ctx.save();
        ctx.imageSmoothingEnabled = false;
        ctx.translate(gx, gy);
        if (est.facing === "left") ctx.scale(-1, 1);
        ctx.drawImage(sprite, f.x, f.y, f.w, f.h, -size / 2, -size / 2, size, size);
        ctx.restore();
      } else { ctx.fillStyle = pal.gpsText; ctx.font = "bold 11px system-ui"; ctx.textAlign = "center"; ctx.fillText("GPS", gx, gy + 4); ctx.textAlign = "left"; }
      if (roomy) {
        // Small tag on the bottom edge of the frame: part of the marker, so it never lands on a station label.
        ctx.font = "bold 10px system-ui";
        const tag = "GPS推定", tw = ctx.measureText(tag).width + 8;
        ctx.fillStyle = pal.gpsEdge; ctx.fillRect(gx - tw / 2, gy + half - 2, tw, 13);
        ctx.fillStyle = pal.gpsBack; ctx.textAlign = "center"; ctx.fillText(tag, gx, gy + half + 8); ctx.textAlign = "left";
      }
    }
    const gpsLabel = $("routeGps");
    if (gpsLabel) {
      gpsLabel.textContent = est.reason ? `表示なし（${est.reason}）` : `${K.jstClock(est.p.t)} の測位・概略線から約 ${est.distKm.toFixed(est.distKm < 10 ? 1 : 0)} km`;
      gpsLabel.className = est.reason ? "nodata" : "";
    }
    note += " グレーの線は、新宿側から最新の有効な GPS 推定位置までの通過済み区間です（時刻表の予定では進めません。GPS が止まると境界も止まります）。ピンは、その時刻までに観測した GPS 精度低下・GPS 更新なし・トンネル区間付近・トンネルかも？で、後の時刻のものは出しません。欠測などの位置は直前の有効な測位からの推定で、根拠がなければ置きません。";
    note += ` 水色の枠の小さなキャラクターは「GPS 推定位置」で、新しい（5 秒以内）・精度 ${LOW_ACC_M} m 以内の測位を、主要駅を結んだ概略線へいちばん近い点で投影した位置です。概略線が粗いため駅との前後関係は目安で、乗車中かどうかは判定しません。GPS が古い・低精度・ルートから ${cfg.route.offRouteKm} km 以上離れているときは表示しません。GPS の実測位置は地図にそのまま描きます。ルート外の判定は、同じ概略線から ${cfg.route.offRouteKm} km 以上離れているかどうかだけで行います。`;
    $("routeNote").textContent = note;
    $("markerList").textContent = st.markers.length ? st.markers.map(m => `${K.jstClock(m.t, false)} ${m.label}`).join("・") : "（メモなし。ページを閉じると消えます）";

    // Where the real GPS position is relative to the planned route. Never claims "on board".
    const status = $("routeStatus");
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
    ctx.restore();
  }

  // ---------------------------------------------------------------------------------------------
  function render() {
    renderHeader(); renderReplay(); renderRoute(); renderTunnel(); renderCards(); renderCharacter(); renderHistory(); drawMap(); renderCharts(); renderTrack3d(); renderSensors(); renderSelection();
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

  function setDashboardMode(overview) {
    st.dashboard.overview = overview;
    document.body.classList.toggle("dashboard-overview", overview);
    $("overviewMode").setAttribute("aria-pressed", String(overview));
    $("detailMode").setAttribute("aria-pressed", String(!overview));
    $("overviewMode").classList.toggle("active", overview);
    $("detailMode").classList.toggle("active", !overview);
    // Refit the map to its new viewport while keeping the selected CSV time and range intact.
    st.map.view = null; st.map.userMoved = false;
    updateDashboardHeight();
    requestAnimationFrame(() => { render(); if (!float.hidden && !float.custom) placeFloat(null, null); });
  }

  function updateDashboardHeight() {
    if (!document.body.classList.contains("dashboard-overview")) return;
    const header = $("topHeader");
    if (header) document.documentElement.style.setProperty("--topbar-height", `${Math.ceil(header.getBoundingClientRect().height)}px`);
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
    anchorPlay(performance.now());
    st.play.timer = setTimeout(playStep, PLAY_TICK_MS);
    render();
  }
  function pause() {
    st.play.playing = false;
    clearTimeout(st.play.timer);
    st.play.timer = null;
    if (cfg && $("playToggle")) renderReplay();
  }
  function anchorPlay(now) {
    st.play.anchorReal = now;
    st.play.anchorT = st.selectedT;
    st.play.lastT = st.selectedT;
  }
  /**
   * The cursor is anchor + elapsed wall time x speed, computed from the anchor every step rather than
   * by adding per-tick deltas, so late timers at high speeds never accumulate drift. A seek by the user
   * while playing (slider, keys, clicks) moves the anchor to the new position.
   */
  function playStep() {
    if (!st.play.playing) return;
    const now = performance.now();
    if (st.selectedT !== st.play.lastT) anchorPlay(now);
    try {
      advanceTo(st.play.anchorT + (now - st.play.anchorReal) * st.play.speed);
      st.play.lastT = st.selectedT;
    } catch (error) {
      // A failed canvas redraw must not leave the UI claiming playback is active forever.
      st.play.playing = false;
      st.play.timer = null;
      console.error("CSV replay stopped after a rendering error", error);
      renderReplay();
      return;
    }
    if (st.play.playing) st.play.timer = setTimeout(playStep, PLAY_TICK_MS);
  }
  /** Moves the cursor to recorded time [t]; stops at the end of the range. */
  function advanceTo(t) {
    const [a, b] = windowRange();
    if (t >= b) { st.selectedT = b; pause(); } else st.selectedT = Math.max(a, t);
    render();
  }
  /** Moves the cursor by [ms] of recorded time (tests and key steps). */
  function advance(ms) {
    advanceTo((st.selectedT ?? windowRange()[0]) + ms);
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
    toggle.textContent = st.play.playing ? `⏸ 一時停止（${st.play.speed}倍）` : "▶ 再生";
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
  function loadDemoData(mode = "demo") {
    if (st.demo && st.csv && st.debug === (mode === "debug")) return;
    const isDebug = mode === "debug";
    // A page may swap in its own fictional #debug story (the LT page uses a tunnel pass).
    const s = K.buildCsvSession(KRL_DEMO_DATA.createCsv({ debug: isDebug, scenario: cfg.debugScenario ?? null }));
    if (s.error) { message([["bad", `デモデータを作れませんでした: ${s.error}`]]); return; }
    pause();
    st.csv = s; st.demo = true; st.debug = isDebug; st.range = [0, 1]; st.csvT = s.t0 + 2000;
    $("rangeStart").value = 0; $("rangeEnd").value = 1000;
    if (st.view === "csv") st.view = "none";
    message([["", `${st.debug ? (cfg.debugScenario === "tunnel" ? "DEBUG（調布付近を通過する架空シナリオ）" : "DEBUG") : "デモ"}データを表示中:${s.rows.toLocaleString()} 行（架空の位置・ネットワーク情報です）。CSV ファイルは読み込んでいません。`]]);
    setView("csv");
    const hash = st.debug ? "#debug" : "#demo";
    if (location.hash !== hash) history.replaceState(null, "", `${location.pathname}${location.search}${hash}`);
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
    st.csv = s; st.demo = false; st.debug = false; st.range = [0, 1]; $("rangeStart").value = 0; $("rangeEnd").value = 1000;
    if (["#demo", "#debug"].includes(location.hash)) history.replaceState(null, "", `${location.pathname}${location.search}`);
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
    if (location.hash === "#demo" || location.hash === "#debug") loadDemoData(location.hash.slice(1));
    window.addEventListener("hashchange", () => {
      if (location.hash === "#demo" || location.hash === "#debug") loadDemoData(location.hash.slice(1));
    });
    $("overviewMode").addEventListener("click", () => setDashboardMode(true));
    // Theme switch redraws in place: same data, cursor, range and layout; no reload.
    $("themeToggle").addEventListener("click", () => { setTheme(isLight() ? "dark" : "light", true); render(); });
    $("detailMode").addEventListener("click", () => setDashboardMode(false));
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
    $("playSpeed").addEventListener("input", e => {
      // Settle the position at the old speed, then continue from here at the new one.
      if (st.play.playing) {
        const now = performance.now();
        if (st.selectedT === st.play.lastT) advanceTo(st.play.anchorT + (now - st.play.anchorReal) * st.play.speed);
        if (st.play.playing) anchorPlay(now);
      }
      st.play.speed = Number(e.target.value) || 1;
      renderReplay();
    });
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
    for (const id of ["chSpeed", "chRsrp", "chQual", "chHttp", "chLane", "chAlt"]) {
      $(id).addEventListener("click", e => { if (!session()) return; const sc = scales($(id).clientWidth, $(id).clientHeight, 0, 1); select(sc.tx(e.offsetX)); });
    }
    if (cfg.route) {
      // Pins: click / tap opens the details of the nearest pin (within 10 px); elsewhere closes it.
      $("routeCanvas").addEventListener("click", e => {
        let best = null, bd = 10;
        for (const q of routePinHits) { const d = Math.hypot(q.x - e.offsetX, q.y - e.offsetY); if (d < bd) { bd = d; best = q; } }
        showPinPop(best, e.offsetX, e.offsetY);
      });
      $("pinPopClose").addEventListener("click", () => { showPinPop(null); $("routeCanvas").focus?.(); });
      document.addEventListener("keydown", e => { if (e.key === "Escape") showPinPop(null); });
      $("pinList").addEventListener("click", e => { const b = e.target.closest("button[data-t]"); if (b) select(Number(b.dataset.t)); });
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
      if (st.view === "ble") { renderHeader(); renderCards(); renderCharacter(); renderRoute(); renderTunnel(); drawMap(); } else renderHeader();
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
    setTheme(loadTheme(), false);
    buildLayout();
    setTheme(loadTheme(), false);
    document.body.classList.add("dashboard-overview");
    document.body.classList.toggle("has-route", !!cfg.route);
    loadSprite();
    wire();
    wireFloat();
    wireTrack3d();
    updateDashboardHeight();
    if (window.ResizeObserver) new ResizeObserver(updateDashboardHeight).observe($("topHeader"));
    $("clock").textContent = `${K.jstClock(Date.now())} JST`;
    bleUi(bleSupported() ? "未接続" : st.ble.state);
    if (location.hash !== "#demo" && location.hash !== "#debug") {
      message([["", "CSV を開くか、URL に #debug を付けると架空データで画面を確認できます。BLE ライブは Android アプリで共有をオンにして接続してください。"]]);
    }
    render();
  }

  return { start, _state: st, _advance: advance };
})();
