// Reference geometry for known underground / tunnel sections near the Mt.TAKAO 3 route (LT page only).
// No official machine-readable portal coordinates were found (checked 2026-10-30). The official sources
// give the project sections as station-to-station ranges, so each shape here joins public station
// coordinates (approximate) and is NOT a tunnel portal or the exact track. A section whose position
// could not be pinned down from the sources has autoDetect: false and is shown as reference only.
"use strict";
window.KRL_TUNNEL_SECTIONS = [
  {
    id: "keio-chofu-underground",
    kind: "underground",
    label: "地下化区間（資料）",
    name: "京王線 調布駅付近の地下化区間（柴崎〜西調布）",
    short: "調布付近・京王線",
    line: "京王線",
    between: "柴崎〜西調布",
    // 柴崎, 国領, 布田, 調布, 西調布 (station representative points).
    coords: [[35.65417, 139.56667], [35.65028, 139.55861], [35.649917, 139.551389], [35.651861, 139.544389], [35.657056, 139.530222]],
    geometry: "station-interval",
    accuracy: "approximate",
    accuracyNote: "駅の代表座標を結んだ折れ線（概略）。坑口（入口・出口）の位置ではなく、実際の線路から数百 m ずれることがあります。両端の柴崎・西調布は事業区間の端で、地下の部分はその間の一部です。",
    method: "東京都建設局が公表する事業区間（駅間）を、公開されている各駅の代表座標で結んだ",
    toleranceM: 300,
    autoDetect: true,
    sources: [
      { title: "東京都建設局 連続立体交差事業 完了パンフレット（京王線 柴崎駅〜西調布駅間・相模原線 調布駅〜京王多摩川駅間）", url: "https://www.kensetsu.metro.tokyo.lg.jp/road/pamphlet/lib/renritsukanryo" },
      { title: "京王電鉄 立体交差化（2012年8月 調布駅付近を地下化）", url: "https://www.keio.co.jp/train/safety/security_feature/railway_crossing.html" },
      { title: "駅の代表座標: Wikipedia 各駅記事の座標欄", url: "https://ja.wikipedia.org/wiki/調布駅" },
    ],
    checked: "2026-10-30",
  },
  {
    id: "sagamihara-chofu-underground",
    kind: "underground",
    label: "地下化区間（資料）",
    name: "相模原線 調布駅付近の地下化区間（調布〜京王多摩川）",
    short: "調布付近・相模原線",
    line: "相模原線（Mt.TAKAO 3号の経路外）",
    between: "調布〜京王多摩川",
    // 調布, 京王多摩川.
    coords: [[35.651861, 139.544389], [35.644806, 139.537028]],
    geometry: "station-interval",
    accuracy: "approximate",
    accuracyNote: "駅の代表座標を直線で結んだ概略。坑口の位置ではなく、実際の線路とはずれます。",
    method: "東京都建設局が公表する事業区間（駅間）を、公開されている各駅の代表座標で結んだ",
    toleranceM: 300,
    autoDetect: true,
    sources: [
      { title: "東京都建設局 連続立体交差事業 完了パンフレット", url: "https://www.kensetsu.metro.tokyo.lg.jp/road/pamphlet/lib/renritsukanryo" },
      { title: "2012年の事業者発表（調布〜京王多摩川の駅間トンネルで携帯サービス開始）", url: "https://www.kddi.com/corporate/news_release/2012/0816/" },
      { title: "駅の代表座標: Wikipedia 各駅記事の座標欄", url: "https://ja.wikipedia.org/wiki/京王多摩川駅" },
    ],
    checked: "2026-10-30",
  },
  {
    id: "takao-first-tunnel",
    kind: "tunnel",
    label: "トンネル（資料）",
    name: "高尾線 高尾第一トンネル（高尾〜高尾山口間）",
    short: "高尾第一トンネル",
    line: "高尾線",
    between: "高尾〜高尾山口",
    // 高尾, 高尾山口: the station range that contains the tunnel, not the tunnel itself.
    coords: [[35.64167, 139.28167], [35.632222, 139.269917]],
    geometry: "station-interval",
    accuracy: "unset",
    accuracyNote: "トンネルの位置と長さは資料から確定できていません。線は高尾駅と高尾山口駅を結んだ駅間の範囲で、トンネルの形ではありません。自動判定には使いません。",
    method: "資料で駅間にトンネルがあることだけを確認。位置は未設定",
    toleranceM: 300,
    autoDetect: false,
    sources: [
      { title: "京王電鉄 安全報告書（高尾線 高尾山口〜高尾駅間「高尾第一トンネル出口付近」）", url: "https://www.keio.co.jp/assets/pdf/train/safety/security_feature/keio_2009.pdf" },
    ],
    checked: "2026-10-30",
  },
];
