// LT demo page for the Keio Liner "Mt.TAKAO 3". Timetable values are the published plan, not
// measurements; check the official timetable on the day (https://www.keio.co.jp/zasekishitei/index_mt.html).
"use strict";
KRLApp.start({
  title: "Mt.TAKAO 3号｜ネットワーク観測",
  subtitle: "京王ライナー Mt.TAKAO 3号（土休日）乗車中の通信状態。予定は公式時刻表、軌跡と通信値は実測です",
  otherPage: { href: "../", label: "← 移動モニターへ" },
  travelModes: null,
  showDistance: false,
  route: {
    stations: [
      { name: "新宿", hh: 11, mm: 0, kind: "発" },
      { name: "明大前", hh: 11, mm: 6, kind: "" },
      { name: "高尾山口", hh: 11, mm: 45, kind: "着" },
    ],
    memoLabels: ["乗車", "新宿発", "明大前", "高尾山口着", "トンネル", "その他"],
  },
});
