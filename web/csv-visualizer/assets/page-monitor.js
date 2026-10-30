// Top page: everyday movement monitor (walks, drives, ...). No route or station assumptions.
"use strict";
KRLApp.start({
  title: "移動モニター｜ネットワーク観測",
  subtitle: "散歩・ドライブなどの移動中に、Android の Keio Ride Logger が記録した通信状態を表示します（CSV またはBLE ライブ）",
  otherPage: { href: "keio-mttakao3/", label: "Mt.TAKAO 3号 LT デモへ →" },
  travelModes: ["未設定", "徒歩", "自転車", "車", "電車", "その他"],
  showDistance: true,
  assetBase: "",
  route: null,
});
