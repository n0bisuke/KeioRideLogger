# LT用 開発・デモ画像（ローカル保管）

会話で共有された企画参考画像、アプリ/Webの画面、実機デバッグ画像を保管します。実画面にはGPS位置、移動軌跡、Cell ID、時刻などが含まれる場合があります。画像ファイルは `.gitignore` でGit追跡対象外にしています。LT資料や公開リポジトリへ載せる場合は、必要な部分を隠した発表用コピーを別に作成し、原本画像は加工・上書きしないでください。

## Androidアプリ・実機計測

- `android-app-permission-screen-2101.png` — 21:01頃のKeio Ride Logger初期画面。停止状態、計測開始ボタン、位置情報・電話の状態に関する権限説明、GPS/携帯網カードを確認できる。公開前に端末状態を確認する。
- `android-session-screen-2108.png` — 21:08頃のAndroid画面。CSV保存後のイベント数、GPSと各センサーの取得状態を確認した記録。座標が表示されるため公開しない。
- `android-live-charts-2245.png` — 22:45頃のライブ可視化画面。GPS軌跡と速度・在圏セル信号品質・HTTP応答時間のグラフ。横軸ラベルが下端で切れている。
- `android-live-charts-2305.png` — 23:05頃の更新画面。在圏セルのRSRP/品質とHTTP応答時間のグラフ。画面下部にCell IDが見えるため公開しない。
- `android-map-layout-2306.png` — 23:06頃の背景地図表示。OSMタイル、GPS軌跡、精度範囲、現在位置を表示。地図と説明/凡例が重なり、軌跡線のコントラストが低い。実移動位置が分かるため公開しない。

## Webダッシュボード・CSVビューア

- `web-ble-live-reception-2321.png` — 23:21頃のGitHub Pages画面。AndroidからBLEで受信中（約1秒ごと）のGPS、LTE在圏セル指標、セル変更候補、通信経路、HTTP probeを表示。正確なGPS座標とCell IDが見えるため公開しない。
- `web-csv-selected-walk-segment-0050.png` — 00:50頃のCSVビューア。歩行計測の開始から約2分9秒の地点で、地図・速度・在圏セル指標・通信状態・セル変更履歴を同時に確認。実際の移動経路が分かるため公開しない。
- `web-csv-timeseries-0817-1047.png` — 00:56頃のCSVビューア。計測開始から約8分17秒〜10分47秒の速度・RSRP/品質・HTTP応答時間を表示。時間帯と移動状況が分かるため公開しない。
- `web-csv-map-full-reloaded-0103.png` — 01:03頃のCSV再読み込み後の全体表示。背景地図上の全軌跡と最新状態カードを確認。移動経路が分かるため公開しない。
- `web-csv-map-window-reloaded-0103.png` — 同じ再読み込み後に00:02:54〜00:05:46を選択した地図・時系列グラフ。GPS軌跡と携帯網指標を時間で照合できる。移動経路が分かるため公開しない。
- `csv-viewer-first-publish-2238.png` — GitHub Pages公開直後のCSVビューア。CSV読み込み前の初期表示。
- `csv-viewer-first-import-2238.png` — CSV読み込み後の初期画面。行数、地図軌跡、GPS速度グラフを確認。移動経路を含むため公開しない。
- `csv-viewer-map-no-basemap-2239.png` — 背景地図なしの概略軌跡表示。地理的な動きが推測できるため公開しない。
- `web-dashboard-overview-2352.png` — 23:52頃のBLEライブ受信中のWebダッシュボード全体画面。GPS位置、Cell ID、時刻が表示されるため公開しない。
- `web-dashboard-timeseries-2352.png` — 23:52頃のWebダッシュボード時系列グラフとセル変更履歴。実測時刻を含むため公開しない。
- `web-keio-debug-dashboard-0816.png` — 08:16頃の `/keio-mttakao3/#debug` 全体画面。架空デバッグ位置、予定ルート、GPS地図、時系列グラフを表示。駅名・時刻・方面ラベルの重なり、キャラクターの重なり、調布付近の参考区間を確認する資料。
- `web-keio-debug-dashboard-zoom-0815.png` — 08:15頃の同じデバッグ画面の拡大表示。ルート端のラベルとキャラクター、参考区間、下段グラフの配置を確認。画面の位置・ネットワーク値は架空データで、実測値として引用しない。
- `web-keio-route-endpoint-detail-0816.png` — 08:16頃の予定ルート左端の切り抜き。高尾山口、予定時刻、方面ラベルが狭い幅で重なる問題を示す。

## LT企画・可視化イメージ

- `iotlt-network-observation-concept-2117.png` — GPS速度とHTTP応答時間を時系列で見る、電車内ネットワーク計測の初期案。数値は架空のサンプル。
- `iotlt-cell-tracker-concept-2117.png` — Cell Trackerの初期UI案。Cell ID・受信強度・切替履歴を可視化するコンセプト。画面中の数値はサンプル。
- `iotlt-signal-map-concept-2117.png` — 京王線沿線の受信強度を駅や位置と関連づける地図表示のコンセプト。駅名・数値はサンプル表現。
- `iotlt-demo-outline-concept-2117.png` — 移動中の基地局リレーを観測するLT案と電車・基地局の概念図。実際の基地局位置や電波経路ではない。
- `keio-route-cell-design-2318.png` — 新宿〜高尾山口の走行ルートとセル観測を示すダッシュボード案。縮尺・セル境界・観測変更候補は説明用で、実際の位置や電波範囲を示さない。
- `mttakao3-schedule-reference-2258.png` — Mt.TAKAO 1号・3号の時刻表を比較した参考画面。3号の新宿11:00発、明大前11:06、高尾山口11:45着を計測時刻の検討に使った。乗車日は公式ページで再確認する。
- `rakuten-area-map-takaosanguchi-2312.png` — 楽天モバイルのサービスエリアマップで高尾山口駅を検索した画面。駅ピンは目的地で、基地局位置ではない。データ基準日は画像内の表示を参照。

## キャラクター参考画像

- `mucchan-character-reference-2138.png` — 高尾山のムササビキャラクター「ムッちゃん」を調べた際の参考画面。オリジナルの速度キャラクターを考えるきっかけ。公式キャラクターの画像・名称・意匠をアプリ素材へ流用しない。
- `keita-character-reference-0155.png` — けい太くんと家族のデザイン参考画像。速度キャラクターの列車ポーズを考える際の参考。
- `shingokun-character-reference-0155.png` — しんごくんの正面デザイン参考画像。速度キャラクターの列車ポーズを考える際の参考。
- `mucchan-speed-sprite-sheet-8-stage.png` — アプリ/Web共通の速度キャラクタースプライトシート。左上から停止、歩行、走行、自転車、車、最高速風の飛行、けい太くん風車両に乗るムッちゃん、しんごくん風車両に乗るムッちゃんの8コマ。画像原本はAndroidアプリ素材と同一で、このフォルダではローカル記録として保管。
- `keio-keita-family-profile-0831.png` — 京王電鉄公式「けい太くんプロフィール」の家族紹介画面。けい太くん、にこちゃん、しんごくん等のデザイン配色を確認する参考。
- `takao-mucchan-interview-0831.png` — 高尾山マガジン「VOL08 高尾山公認キャラ ムッちゃん」の記事画面。ムッちゃんのムササビ設定と飛膜の参考。公開スライドでは記事画像を転載せず、出典リンクを示す。

## GitHub Pages公開作業

- `github-pages-settings-2232.png` — GitHubリポジトリのPages設定画面。SourceをGitHub Actionsに設定した状態。
- `github-actions-failure-2233.png` — Pages初回デプロイ失敗画面。サイト設定未完了に関するエラーを確認した記録。

公開用コピーを作る場合も、画像内のGPS位置・Cell ID・時刻・個人情報を確認してください。背景地図タイルを使用する画面では、タイル提供元へIPアドレスと表示範囲が送られる点にも注意します。
