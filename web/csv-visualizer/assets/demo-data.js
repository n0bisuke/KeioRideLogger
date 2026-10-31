// Runtime-generated, explicitly synthetic demo data. No recorded coordinates or cell identifiers
// are included in the public site. The short session mirrors the shape of an outdoor walk log.
"use strict";
window.KRL_DEMO_DATA = (() => {
  const columns = [
    "time_utc", "elapsed_realtime_ns", "event", "status", "lat", "lon", "speed_mps", "altitude_m", "h_accuracy_m", "provider",
    "x", "y", "z", "accuracy", "pressure_hpa", "rat", "override_rat", "cell_id", "pci", "tac", "arfcn", "bands", "rsrp_dbm",
    "lte_rsrq_db", "lte_rssnr_db", "lte_rssi_dbm", "lte_cqi", "nr_ss_rsrq_db", "nr_ss_sinr_db", "nr_csi_rsrp_dbm", "nr_csi_rsrq_db",
    "nr_csi_sinr_db", "registered", "age_ms", "transport", "validated", "http_status", "duration_ms", "note",
  ];

  /** Row collector: add(seconds from [startIso], event, values); toCsv() sorts by time and quotes every field. */
  function rowBuilder(startIso) {
    const start = Date.parse(startIso), rows = [];
    const add = (sec, event, values = {}) => {
      const row = Object.fromEntries(columns.map(key => [key, ""]));
      Object.assign(row, values, {
        time_utc: new Date(start + Math.round(sec * 1000)).toISOString(),
        elapsed_realtime_ns: String(Math.round(sec * 1e9)), event,
      });
      rows.push(row);
    };
    const toCsv = () => {
      rows.sort((a, b) => Date.parse(a.time_utc) - Date.parse(b.time_utc));
      const quote = value => `"${String(value).replace(/"/g, '""')}"`;
      return [columns.join(","), ...rows.map(row => columns.map(key => quote(row[key])).join(","))].join("\n");
    };
    return { add, toCsv };
  }

  function createCsv({ debug = false, scenario = null } = {}) {
    if (debug && scenario === "tunnel") return createTunnelCsv();
    const { add, toCsv } = rowBuilder("2025-04-26T11:00:00+09:00");

    add(0, "session_start", { note: "demo=true;synthetic=true;location=false;phone_state=false" });
    [["accel", 50000, 20], ["gyro", 50000, 20], ["mag", 200000, 5], ["pressure", 1000000, 1]].forEach(([kind, period, hz], i) => {
      add(.01 + i * .01, "sensor_status", { status: "registered", note: `${kind};name=synthetic;requested_period_us=${period}` });
      add(.05 + i * .01, "sensor_rate", { status: "measured", note: `sensor=${kind};recorded_hz=${hz}` });
    });
    add(.02, "gps_status", { status: "active" });
    add(.03, "cell_status", { status: "registered" });
    add(.04, "network", { status: "available", transport: "cellular", validated: "true" });

    // A short, fictional low-speed walk around a fixed public demo point (not the recorded path).
    const gpsCount = 46, origin = { lat: 35.6815, lon: 139.7675 };
    for (let i = 0; i < gpsCount; i++) {
      const u = i / (gpsCount - 1), phase = u * Math.PI * 2;
      const speed = i % 3 === 0 ? 0 : i === 18 ? 1.181 : .04 + .48 * Math.sin(phase * 1.4) ** 2;
      // Fictional GPS altitude that drifts along the walk; #debug also has a short blank stretch to show the gap.
      const altitude = debug && i >= 30 && i <= 32 ? "" : (38 + 4 * u + 2.5 * Math.sin(phase * 1.5)).toFixed(1);
      add(u * 50, "gps", {
        lat: (origin.lat + .00015 * u + .00008 * Math.sin(phase)).toFixed(7),
        lon: (origin.lon + .00012 * Math.sin(phase * .8)).toFixed(7),
        speed_mps: speed.toFixed(3), altitude_m: altitude, h_accuracy_m: (2.6 + .7 * Math.abs(Math.sin(i))).toFixed(1), provider: "demo",
      });
    }

    const debugCells = [
      { suffix: "A", pci: "101" }, { suffix: "B", pci: "202" }, { suffix: "C", pci: "303" },
    ];
    const debugSequence = [0, 1, 2, 1, 0, 2, 1, 0, 2, 1];
    const cellFor = index => {
      const c = debugCells[index];
      return { rat: "LTE", cell_id: `DEMO-CELL-${c.suffix}`, pci: c.pci, tac: "DEMO", arfcn: "1500", bands: "DEMO", registered: "true" };
    };
    const initialCell = cellFor(0);
    add(.2, "cell_change", { ...initialCell, status: "baseline", note: "src=listener;switch_count=0;demo=true" });
    if (debug) {
      for (let i = 1; i < debugSequence.length; i++) {
        const from = cellFor(debugSequence[i - 1]), to = cellFor(debugSequence[i]);
        add(i * 5, "cell_change", { ...to, status: "switched", note: `src=listener;prev=${from.rat}/${from.cell_id}/${from.pci}/${from.arfcn};demo=true` });
      }
    }
    const unregisteredIndexes = new Set([0, 8, 16, 24, 32, 40, 48, 56, 64, 72, 80]);
    for (let i = 0; i < 90; i++) {
      const wave = Math.sin(i * .19);
      const registered = !unregisteredIndexes.has(i);
      const servingCell = debug ? cellFor(debugSequence[Math.min(debugSequence.length - 1, Math.floor(i / 9))]) : initialCell;
      const rsrp = i === 8 ? -44 : i === 38 ? -93 : Math.round(-59 + 8 * wave + 2 * Math.sin(i * .63));
      const rsrq = i === 13 ? -20 : i === 64 ? -3 : Math.round(-9 + 2 * Math.sin(i * .11));
      const snr = i === 21 ? 0 : Math.round(13 + 2 * Math.sin(i * .07 + 1));
      add(.3 + i * 50 / 90, "cell", {
        ...servingCell, cell_id: registered ? servingCell.cell_id : "DEMO-NEIGHBOR", registered: String(registered),
        rsrp_dbm: rsrp.toFixed(0), lte_rsrq_db: rsrq.toFixed(0), lte_rssnr_db: snr.toFixed(0),
        lte_rssi_dbm: "DEMO", lte_cqi: "DEMO", note: "src=listener;demo=true",
      });
    }
    [5, 18, 31, 46].forEach((sec, i) => add(sec, "http_probe", {
      status: "ok", duration_ms: [540, 613, 239, 420][i], http_status: "204", transport: "cellular",
    }));

    for (let i = 0; i < 1014; i++) {
      const p = i * .17;
      add(i * 50 / 1000, "accel", { x: (.08 * Math.sin(p) + .02).toFixed(3), y: (.04 * Math.cos(p)).toFixed(3), z: (9.8 + .12 * Math.sin(p * .7)).toFixed(3), accuracy: "3" });
    }
    for (let i = 0; i < 1013; i++) {
      const p = i * .17;
      add(i * 50 / 1000 + .001, "gyro", { x: (.03 * Math.sin(p)).toFixed(4), y: (.02 * Math.cos(p)).toFixed(4), z: (.01 * Math.sin(p * .4)).toFixed(4), accuracy: "3" });
    }
    for (let i = 0; i < 254; i++) {
      const p = i * .38;
      add(i * 200 / 1000 + .002, "mag", { x: (28 + 2 * Math.sin(p)).toFixed(2), y: (4 + 2 * Math.cos(p)).toFixed(2), z: (-39 + Math.sin(p * .3)).toFixed(2), accuracy: "3" });
    }
    for (let i = 0; i < 50; i++) add(i, "pressure", { pressure_hpa: (1013.2 + .08 * Math.sin(i * .2)).toFixed(2) });
    for (let i = 0; i < 79; i++) add(i * 50 / 79, "cell_poll", { status: "ok", note: "registered=1;demo=true" });
    for (let i = 0; i < 6; i++) add(i * 8 + 1, "signal_strength", { rsrp_dbm: (-60 + i).toFixed(0), note: "demo=true" });
    for (let i = 0; i < 3; i++) add(i * 20 + 2, "display_info", { status: "available", note: "demo=true" });
    add(50.9, "session_stop", { note: "demo=true" });

    return toCsv();
  }

  /**
   * /keio-mttakao3/#debug only: a fictional train-speed pass by the Chofu underground reference section.
   * The track joins public station points with made-up offsets (not a recording). Story: approach
   * (fresh GPS) -> near the section, accuracy drops -> GPS gap with RSRP lower, serving cell missing and
   * an HTTP timeout -> GPS gap while the cell is observed normally again -> GPS fix again (recovery).
   */
  function createTunnelCsv() {
    const { add, toCsv } = rowBuilder("2025-04-26T11:13:00+09:00");
    add(0, "session_start", { note: "demo=true;synthetic=true;scenario=tunnel" });
    [["accel", 50000, 20], ["pressure", 1000000, 1]].forEach(([kind, period, hz], i) => {
      add(.01 + i * .01, "sensor_status", { status: "registered", note: `${kind};name=synthetic;requested_period_us=${period}` });
      add(.05 + i * .01, "sensor_rate", { status: "measured", note: `sensor=${kind};recorded_hz=${hz}` });
    });
    add(.02, "gps_status", { status: "active" });
    add(.03, "cell_status", { status: "registered" });
    add(.04, "network", { status: "available", transport: "cellular", validated: "true" });

    // [sec, lat, lon] keyframes; no GPS between 110 s and 290 s.
    const legs = [
      [0, 35.6560, 139.5800], [76, 35.65417, 139.56667], [110, 35.6516, 139.5613],
      [290, 35.6578, 139.5280], [380, 35.6610, 139.5130],
    ];
    const at = sec => {
      const k = legs.findIndex(([t], i) => i + 1 < legs.length && sec >= t && sec <= legs[i + 1][0]);
      const [t0, a0, o0] = legs[k], [t1, a1, o1] = legs[k + 1], u = (sec - t0) / (t1 - t0);
      return [a0 + (a1 - a0) * u, o0 + (o1 - o0) * u];
    };
    for (let sec = 0; sec <= 380; sec++) {
      if (sec > 110 && sec < 290) continue;
      const [lat, lon] = at(sec);
      const acc = sec >= 100 && sec <= 110 ? 45 : sec >= 290 && sec < 296 ? 18 : 4 + Math.abs(Math.sin(sec));
      add(sec + .1, "gps", {
        lat: lat.toFixed(6), lon: lon.toFixed(6), speed_mps: (16.2 + .8 * Math.sin(sec / 9)).toFixed(3),
        // A short blank altitude stretch (330-332 s) keeps the missing-value view checkable here too.
        altitude_m: sec >= 330 && sec <= 332 ? "" : (44 + 3 * Math.sin(sec / 60)).toFixed(1), h_accuracy_m: acc.toFixed(1), provider: "demo",
      });
    }

    const cell = k => ({ rat: "LTE", cell_id: `DEMO-CELL-${k}`, pci: { A: "101", B: "202", C: "303" }[k], tac: "DEMO", arfcn: "1500", bands: "DEMO" });
    add(.2, "cell_change", { ...cell("A"), status: "baseline", note: "src=listener;switch_count=0;demo=true" });
    add(176, "cell_change", { ...cell("B"), status: "switched", note: "src=listener;prev=LTE/DEMO-CELL-A/101/1500;demo=true" });
    add(300, "cell_change", { ...cell("C"), status: "switched", note: "src=listener;prev=LTE/DEMO-CELL-B/202/1500;demo=true" });
    for (let sec = 0; sec <= 380; sec++) {
      if (sec > 150 && sec < 175) {
        // No serving cell reported for 25 s (only a neighbour row): "在圏セル更新なし".
        add(sec + .3, "cell", { ...cell("A"), cell_id: "DEMO-NEIGHBOR", registered: "false", rsrp_dbm: "-110", note: "src=listener;demo=true" });
        continue;
      }
      let rsrp = -84 + 2 * Math.sin(sec / 7), rsrq = -10 + Math.sin(sec / 11), snr = 12 + 2 * Math.sin(sec / 13);
      if (sec > 110 && sec <= 150) { const u = (sec - 110) / 40; rsrp = -86 - 16 * u; rsrq = -11 - 5 * u; snr = 10 - 8 * u; }
      else if (sec >= 175 && sec < 200) { rsrp = -95; rsrq = -14; snr = 4; }
      add(sec + .3, "cell", {
        ...cell(sec < 176 ? "A" : sec < 300 ? "B" : "C"), registered: "true",
        rsrp_dbm: rsrp.toFixed(0), lte_rsrq_db: rsrq.toFixed(0), lte_rssnr_db: snr.toFixed(0),
        lte_rssi_dbm: "DEMO", lte_cqi: "DEMO", note: "src=listener;demo=true",
      });
    }
    [[20, "ok", 410], [60, "ok", 455], [100, "ok", 520], [160, "timeout", 5000], [220, "ok", 690], [320, "ok", 430], [360, "ok", 400]]
      .forEach(([sec, status, ms]) => add(sec, "http_probe", {
        status, duration_ms: String(ms), http_status: status === "ok" ? "204" : "", transport: "cellular",
      }));
    for (let i = 0; i < 380 * 20; i++) {
      const p = i * .17;
      add(i / 20, "accel", { x: (.15 * Math.sin(p) + .02).toFixed(3), y: (.1 * Math.cos(p * .6)).toFixed(3), z: (9.8 + .2 * Math.sin(p * .7)).toFixed(3), accuracy: "3" });
    }
    for (let i = 0; i < 380; i++) add(i + .002, "pressure", { pressure_hpa: (1010.4 + .05 * Math.sin(i * .05)).toFixed(2) });
    add(381, "session_stop", { note: "demo=true" });
    return toCsv();
  }

  return { createCsv };
})();
