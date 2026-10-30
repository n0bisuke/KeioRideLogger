// LT demo page for the Keio Liner "Mt.TAKAO 3". Timetable values are the published plan, not
// measurements; check the official timetable on the day (https://www.keio.co.jp/zasekishitei/index_mt.html).
"use strict";
KRLApp.start({
  title: "Mt.TAKAO 3号｜ネットワーク観測",
  subtitle: "京王ライナー Mt.TAKAO 3号のネットワーク観測デモ。予定ルートと端末の実測を区別して表示します",
  otherPage: { href: "../", label: "← 移動モニターへ" },
  travelModes: null,
  showDistance: false,
  layout: "onescreen",
  assetBase: "../",
  route: {
    stations: [
      { name: "高尾山口", hh: 11, mm: 45, kind: "着" },
      { name: "明大前", hh: 11, mm: 6, kind: "" },
      { name: "新宿", hh: 11, mm: 0, kind: "発" },
    ],
    // Public station locations (approximate, WGS84) along the Keio Line / Takao Line, joined into a
    // rough polyline. Used only for the "off the planned route" notice; positions are never snapped.
    waypoints: [
      [35.6896, 139.7006], // 新宿
      [35.6687, 139.6503], // 明大前
      [35.6518, 139.5444], // 調布
      [35.6720, 139.4796], // 府中
      [35.6509, 139.4467], // 聖蹟桜ヶ丘
      [35.6466, 139.3584], // 北野
      [35.6420, 139.2826], // 高尾
      [35.6324, 139.2697], // 高尾山口
    ],
    offRouteKm: 2,
    memoLabels: ["乗車", "新宿発", "明大前", "高尾山口着", "トンネル", "その他"],
  },
});
