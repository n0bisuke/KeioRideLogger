"use strict";
(() => {
  const $ = id => document.getElementById(id);
  const TOTAL = 36, stage = $("stage"), image = $("slideImage"), widget = $("liveWidget");
  const params = new URLSearchParams(location.search);
  let index = Math.max(1, Math.min(TOTAL, Number(params.get("n")) || 1));
  let metric = "speed", sprite = new Image(), last = null, reassembler = new KRL.BleReassembler(), gatt = null;
  const points = [], gpsHistory = [];
  const saved = (() => { try { return JSON.parse(localStorage.getItem("krl.slideWidget.v1") || "null"); } catch { return null; } })();
  if (saved && Number.isFinite(saved.x) && Number.isFinite(saved.y)) {
    widget.style.left = `${saved.x * 100}%`; widget.style.top = `${saved.y * 100}%`; widget.style.right = "auto";
    if (Number.isFinite(saved.w)) widget.style.width = `${saved.w * 100}%`;
    if (Number.isFinite(saved.h)) widget.style.height = `${saved.h * 100}%`;
  }
  function sourceSlide(index) {
    // Put the Mt.TAKAO 3 question and timetable before the experiment details.
    // Existing source order had that context after the four experiment slides.
    if (index === 4) return 7;
    if (index === 5) return 2;
    if (index >= 6 && index <= 9) return index - 3;
    if (index >= 10 && index <= 35) return index - 2;
    return null;
  }
  function showSlide() {
    if (index === 1) image.src = "../slide/deck/opening.png";
    else if (index === 2) image.src = "../slide/deck/self-intro.png";
    else if (index === 3) image.src = "../slide/deck/hook.png";
    else if (index === TOTAL) image.src = "../slide/deck/summary.png";
    else image.src = `../slide/deck/slide-${String(sourceSlide(index)).padStart(2, "0")}.png`;
    $("count").textContent = `${index} / ${TOTAL}`;
    const url = new URL(location.href); url.searchParams.set("n", index); history.replaceState(null, "", url);
  }
  function move(delta) { index = (index - 1 + delta + TOTAL) % TOTAL + 1; showSlide(); }
  $("prev").onclick = () => move(-1); $("next").onclick = () => move(1);
  document.addEventListener("keydown", e => {
    if (["ArrowRight", "PageDown", " "].includes(e.key)) { e.preventDefault(); move(1); }
    if (["ArrowLeft", "PageUp"].includes(e.key)) { e.preventDefault(); move(-1); }
    if (e.key.toLowerCase() === "f") toggleFull();
  });
  function toggleFull() { if (!document.fullscreenElement) stage.requestFullscreen?.(); else document.exitFullscreen?.(); }
  $("full").onclick = toggleFull;
  document.addEventListener("fullscreenchange", () => document.body.classList.toggle("fullscreen", !!document.fullscreenElement));

  function persistWidget() {
    const r = widget.getBoundingClientRect(), s = stage.getBoundingClientRect();
    try { localStorage.setItem("krl.slideWidget.v1", JSON.stringify({ x: r.left / s.width, y: r.top / s.height, w: r.width / s.width, h: r.height / s.height })); } catch { /* private mode */ }
  }
  let drag = null;
  $("widgetHandle").addEventListener("pointerdown", e => {
    if (e.target.closest("button")) return;
    const r = widget.getBoundingClientRect(); drag = { x: e.clientX-r.left, y: e.clientY-r.top };
    widget.setPointerCapture(e.pointerId);
  });
  $("widgetHandle").addEventListener("pointermove", e => {
    if (!drag) return;
    const s = stage.getBoundingClientRect();
    widget.style.left = `${Math.max(0, Math.min(s.width-widget.offsetWidth, e.clientX-s.left-drag.x))}px`;
    widget.style.top = `${Math.max(0, Math.min(s.height-widget.offsetHeight, e.clientY-s.top-drag.y))}px`; widget.style.right = "auto";
  });
  $("widgetHandle").addEventListener("pointerup", () => { if (drag) persistWidget(); drag = null; });
  // Native resize handle on the widget; persist the final dimensions when pointer interaction ends.
  $("resizeHandle").addEventListener("pointerdown", () => setTimeout(persistWidget, 150));
  if (window.ResizeObserver) {
    let resizeTimer;
    new ResizeObserver(() => { clearTimeout(resizeTimer); resizeTimer = setTimeout(persistWidget, 250); }).observe(widget);
  }
  window.addEventListener("resize", persistWidget);

  $("chartToggle").onclick = () => { metric = metric === "speed" ? "rsrp" : "speed"; $("chartToggle").textContent = metric === "speed" ? "速度グラフ" : "RSRPグラフ"; drawChart(); };
  sprite.src = "../assets/glider_speed_sheet.png";
  function update(payload) {
    last = payload;
    const t = Number.isFinite(payload.t) ? payload.t : Date.now();
    const gps = payload.gps, cell = payload.cell;
    if (gps && Number.isFinite(gps.lat) && Number.isFinite(gps.lon)) {
      const sampleT = t - (gps.ageMs || 0);
      const prev = gpsHistory[gpsHistory.length - 1];
      if (!prev || sampleT > prev.t) gpsHistory.push({ t: sampleT, kmh: gps.kmh, acc: gps.accM });
    }
    points.push({ t, speed: Number.isFinite(gps?.kmh) ? gps.kmh : null, rsrp: Number.isFinite(cell?.rsrp) ? cell.rsrp : null });
    while (points.length && t - points[0].t > 90000) points.shift();
    while (gpsHistory.length && t - gpsHistory[0].t > 60000) gpsHistory.shift();
    $("speedValue").textContent = Number.isFinite(gps?.kmh) ? `${gps.kmh.toFixed(1)} km/h` : "GPS待ち";
    const st = KRL.speedStateAt(gpsHistory, t);
    const band = st.band;
    $("mascotLabel").textContent = band === null ? KRL.SPEED_REASONS[st.reason] || "GPS待ち" : KRL.SPEED_BANDS[band].label;
    drawMascot(band);
    $("chartLabel").textContent = metric === "speed" ? "GPS速度 · 直近90秒" : "在圏セル RSRP · 直近90秒";
    drawChart();
  }
  function drawMascot(band) {
    const c=$("mascot"), dpr=devicePixelRatio||1, w=c.clientWidth, h=c.clientHeight;
    c.width=Math.max(1,Math.round(w*dpr)); c.height=Math.max(1,Math.round(h*dpr));
    const ctx=c.getContext("2d"); ctx.scale(dpr,dpr); ctx.clearRect(0,0,w,h);
    if (!sprite.complete || !sprite.naturalWidth) return;
    const f=KRL.spriteFrame(sprite.naturalWidth,sprite.naturalHeight,band??0), scale=Math.min((w-8)/f.w,(h-8)/f.h);
    ctx.imageSmoothingEnabled=false; ctx.globalAlpha=band===null?.35:1;
    ctx.drawImage(sprite,f.x,f.y,f.w,f.h,(w-f.w*scale)/2,(h-f.h*scale)/2,f.w*scale,f.h*scale); ctx.globalAlpha=1;
  }
  function drawChart() {
    const c=$("chart"), dpr=devicePixelRatio||1,w=c.clientWidth,h=c.clientHeight;
    c.width=Math.max(1,Math.round(w*dpr)); c.height=Math.max(1,Math.round(h*dpr));
    const x=c.getContext("2d"); x.scale(dpr,dpr); x.clearRect(0,0,w,h);
    x.strokeStyle="#617887"; x.lineWidth=1; x.beginPath(); x.moveTo(5,h-16); x.lineTo(w-3,h-16); x.moveTo(5,5); x.lineTo(5,h-16); x.stroke();
    const vals=points.map(p=>metric === "speed" ? p.speed : p.rsrp).filter(Number.isFinite); if(!vals.length){x.fillStyle="#b8c9d2";x.font="11px system-ui";x.fillText("データ受信待ち",10,h/2);return;}
    let min=Math.min(...vals),max=Math.max(...vals); if(max===min){min-=1;max+=1;}
    x.strokeStyle=metric==="speed"?"#47d7ee":"#5de09a";x.lineWidth=2;x.beginPath();
    let started=false;
    points.forEach((p,i)=>{const v=metric === "speed" ? p.speed : p.rsrp;if(!Number.isFinite(v)){started=false;return;}const px=8+i*(w-16)/Math.max(1,points.length-1),py=6+(max-v)*(h-27)/(max-min);if(started)x.lineTo(px,py);else x.moveTo(px,py);started=true;});x.stroke();
    x.fillStyle="#c7d7df";x.font="10px system-ui";x.fillText(`${max.toFixed(0)} ${metric==="speed"?"km/h":"dBm"}`,8,12);
  }
  async function connect() {
    if (!navigator.bluetooth) { $("status").textContent="Web Bluetooth対応のChrome/EdgeとHTTPSが必要です"; return; }
    try {
      $("status").textContent="Android端末を選択してください…";
      reassembler = new KRL.BleReassembler();
      const device=await navigator.bluetooth.requestDevice({filters:[{services:[KRL.BLE.SERVICE_UUID]}]});
      device.addEventListener("gattserverdisconnected",()=>{gatt=null;$("status").textContent="BLE切断 · 表示は最終受信値";});
      gatt=await device.gatt.connect();const service=await gatt.getPrimaryService(KRL.BLE.SERVICE_UUID);const ch=await service.getCharacteristic(KRL.BLE.CHAR_UUID);
      ch.addEventListener("characteristicvaluechanged",e=>{
        const v=e.target.value, bytes=new Uint8Array(v.buffer,v.byteOffset,v.byteLength), body=reassembler.accept(bytes); if(!body)return;
        const p=KRL.bleDecode(body);if(p){update(p);$("status").textContent=`BLE受信中 · seq ${p.seq}`;}
      }); await ch.startNotifications();$("status").textContent="BLE接続 · データ待ち";
    } catch(e) { $("status").textContent=`BLE接続できません: ${e?.message||e}`; }
  }
  $("ble").onclick=connect;
  showSlide();drawChart();drawMascot(null);
})();
