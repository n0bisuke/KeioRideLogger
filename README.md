# Keio Ride Logger

京王ライナーなどの乗車中に、Android 端末の位置・携帯網・モーションセンサー情報を記録し、あとから分析できる CSV を出力する Android アプリです。記録データは端末内で扱い、外部サーバーには送信しません。例外は応答時間の計測（オフにできます）で、Google の接続確認用 URL に中身のない GET を送ります。位置・セル情報・端末情報は送りません。

## MVP

- Kotlin + Jetpack Compose、Android 16（API 36）実機を対象
- 画面上で計測を開始・停止し、状態と取得可能な最新値を表示
- GPS: 緯度、経度、速度、高度、水平精度（目安 1 秒）
- 携帯網: Cell ID、LTE/NR の種別、RSRP、取得できる場合は周波数帯
- 加速度・ジャイロ（目安 20 Hz）、気圧（目安 1 Hz）、磁気（目安 5 Hz）
- 各サンプルを種別ごとのイベントとして、取得時刻付きで記録
- Android Storage Access Framework（SAF）で CSV を保存
- 画面表示中の計測を MVP とする。バックグラウンド計測、地図、クラウド同期は対象外

端末や SIM、通信事業者、Android の権限・省電力設定により、位置やセル情報を取得できない場合があります。センサー未搭載または値が取得できない項目は、アプリ全体を止めず、その項目を利用不可として扱います。セル情報の変化は観測イベントであり、実際のハンドオーバーを完全に表すものではありません。GPS 高度と気圧は別々の値として保存します。

## 開発環境

Android CLI、SDK、Gradle、Claude Code は Docker/OrbStack 内で使用します。Android SDK・Gradle のビルドキャッシュを Mac ホストへ bind mount しないでください。プロジェクトソースを共有する既存の bind mount は維持し、キャッシュはコンテナ内または Docker volume に置きます。

リポジトリには既存の `Dockerfile` と `compose.yaml` を使ってください。サービス名、作業ディレクトリ、SDK の配置は構成に依存するため、以下の `android` は実際の Compose サービス名に読み替えます。コンテナ内でプロジェクトは `/workspace/android-app` にある想定です。

現在の `compose.yaml` は `image: android-kotlin:latest` を参照し、`build:` を持たないため、初回（または `Dockerfile` 変更時）はイメージを先にビルドします。

```sh
# リポジトリルート（Mac）
docker build --platform linux/amd64 -t android-kotlin:latest .
```

```sh
# リポジトリルート（Mac）
docker compose up -d
docker compose exec android bash
```

コンテナ内でビルドします。

```sh
cd /workspace/android-app
./gradlew assembleDebug
mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk dist/app-debug.apk
```

APK はリポジトリ共有領域の `android-app/dist/app-debug.apk`（Compose のマウント先がリポジトリルートの場合）に置きます。実際の Gradle module 名や出力先が異なる場合は既存構成に合わせてください。APK の取り出しに Mac 側の Android SDK や Gradle を使わないでください。

## 実機へのインストール

ADB の導入・USB 接続は Mac ホスト側で行います。Android 端末で開発者向けオプションと USB デバッグを有効にし、接続確認します。

```sh
adb devices
adb install -r android-app/dist/app-debug.apk
```

端末に表示される権限要求を許可してアプリを開き、計測開始、計測停止、CSV 保存を確認します。位置・セル情報はユーザーが権限を許可した場合だけ利用します。必要最小限の権限を実行時に説明し、拒否時もセンサー等の取得可能な計測を継続してください。

## コンテナとキャッシュの削除

終了は `docker compose down`。この操作はコンテナを停止・削除しますが、通常はイメージ、named volume、APK、ソースを削除しません。開発環境を完全に消す場合は、まず compose 設定で対象 volume とマウントを確認してください。`docker compose down -v` はこの Compose プロジェクトの named volume（SDK/Gradle キャッシュを保存している場合はそれも）を削除します。現在の `compose.yaml` では Claude Code の設定・認証を保持する `claude-config` volume も削除対象に含まれます。イメージは `docker image rm <対象イメージ>` で個別に削除します。ソースや `dist/app-debug.apk` は別途、必要性を確認してから削除してください。

## 端末で確認する項目

- 位置情報・電話状態の権限を許可／拒否したときの動作
- 計測開始・停止を繰り返したとき、重複記録やクラッシュがない
- 利用可能なセンサーと未搭載センサーが正しく表示される
- GPS を失っても他の取得可能なデータが記録される
- CSV を SAF で選んだ場所に保存でき、時刻・データ種別・値を確認できる
- 位置情報やセル情報がネットワーク送信されない

## CSV の考え方

センサーごとに取得間隔が異なるため、共通の時刻列を持つイベント形式を基本とします。各行に ISO 8601 UTC 時刻（ミリ秒精度）、イベント種別、取得値を含め、GPS・セル情報・各センサーの値を混同しないでください。値が利用できない項目は空欄または明示的な状態で表し、`0` を欠損値として扱わないこと。

### 実装済みの CSV 列

1 行目はヘッダー、文字コードは UTF-8（BOM なし）、改行は LF です。カンマ・引用符・改行を含む値はダブルクォートで囲みます。

| 列 | 内容 |
|---|---|
| `time_utc` | 取得時刻（ISO 8601 UTC、ミリ秒）。センサー・セルは `elapsed_realtime_ns` から換算、GPS は測位時刻 |
| `elapsed_realtime_ns` | 端末起動からの単調時刻（プラットフォームがサンプルに付けた値） |
| `event` | `session_start` / `session_stop` / `sensor_status` / `sensor_rate` / `accel` / `gyro` / `mag` / `pressure` / `gps` / `gps_status` / `cell` / `cell_poll` / `cell_change` / `cell_status` / `display_info` / `signal_strength` / `network` / `http_probe` |
| `status` | 状態イベントの値（`unavailable`、`permission_denied`、`provider_disabled`、`empty` など） |
| `lat` `lon` `speed_mps` `altitude_m` `h_accuracy_m` `provider` | GPS（`gps` 行）。高度は GPS 高度で、気圧とは別 |
| `x` `y` `z` `accuracy` | 加速度 [m/s²]・ジャイロ [rad/s]・磁気 [µT] と、センサーの精度区分 |
| `pressure_hpa` | 気圧 [hPa]（`pressure` 行） |
| `rat` `override_rat` | セルの方式（LTE / NR など）と、`display_info` 行の表示種別（`NR_NSA` など） |
| `cell_id` `pci` `tac` `arfcn` `bands` | セル情報。`bands` は `;` 区切り |
| `rsrp_dbm` | LTE は RSRP、NR は SS-RSRP [dBm] |
| `lte_rsrq_db` `lte_rssnr_db` `lte_rssi_dbm` `lte_cqi` | LTE の RSRQ [dB]・RSSNR [dB]・RSSI [dBm]・CQI。LTE の行だけに入る |
| `nr_ss_rsrq_db` `nr_ss_sinr_db` `nr_csi_rsrp_dbm` `nr_csi_rsrq_db` `nr_csi_sinr_db` | NR の SS-RSRQ [dB]・SS-SINR [dB]・CSI-RSRP [dBm]・CSI-RSRQ [dB]・CSI-SINR [dB]。NR の行だけに入る |
| `registered` | 在圏セルなら `true`、周辺セルなら `false` |
| `age_ms` | セル情報が取得されてから記録されるまでの経過時間 |
| `transport` `validated` | 既定ネットワークの経路（`cellular` / `wifi` / `vpn` など、複数は `+` 区切り）と、インターネット接続の確認済み（`NET_CAPABILITY_VALIDATED`）かどうか。`network` 行と `http_probe` 行に入る |
| `http_status` `duration_ms` | 応答時間計測の HTTP ステータスと所要時間 [ms]（`http_probe` 行） |
| `note` | 補足。`cell` / `cell_poll` 行は取得経路 `src=request|cached|listener`、`sensor_status` 行はセンサーの `min_delay_us` など、`sensor_rate` 行は端末からの配信頻度（`received_hz`）と記録頻度（`recorded_hz`） |

取得間隔の目安は、加速度・ジャイロ 20 Hz、磁気 5 Hz、気圧 1 Hz、GPS 1 秒、セル情報 2 秒、応答時間 10 秒です。値が取れなかった指標は空欄で、0 で埋めることはありません。

`cell_change` 行は在圏セル（`registered=true`）の切り替わり判定で、`status` は次のいずれかです。`baseline` は最初に得た在圏セルで、切り替わりには数えません。`switched` は在圏セルが変わったもので、方式・Cell ID・PCI・ARFCN のうち両方に値がある項目で比べます。`indeterminate` は比べられる識別子がなく、判定できなかったものです。`note` に累計の切り替わり回数と、直前のセルを残します。周辺セルの変化は数えません。Android の更新間隔に制限があるため、短い切り替わりは見逃すことがあり、ハンドオーバーの完全な記録ではありません。

`signal_strength` 行は TelephonyManager の SignalStrength（接続中の信号）です。NSA の NR 側のように、在圏セルとしては返らない信号も入ります。セル識別子はありません。

セル情報は3つの経路から取ります。`request` はデータ通信の回線に紐づけた `requestCellInfoUpdate()`、`cached` は全モデム分の `allCellInfo`（キャッシュ）、`listener` は `CellInfoListener`（Android 12 以降）です。`cell` 行は `request` 経路のセルをすべて記録し、ほかの経路は在圏セルだけを記録します。`cell_poll` 行は応答ごとの件数（総数、在圏数、方式別）と状態（`ok` / `empty` / `no_response` / `error`）で、セル ID などの識別子は含みません。セッション開始時の `cell_status` 行には、回線・モデムの状態を真偽値と件数だけで残します。

### 通信経路と応答時間

`network` 行は、アプリの既定ネットワークが変わったとき（`default_changed`）、経路やインターネット接続の確認状態が変わったとき（`capabilities`）、失われたとき（`lost`）に記録します。`note` の `net=` はセッション内の通し番号で、端末やネットワークの識別子ではありません。

`http_probe` 行は応答時間の計測です。仕様は次のとおりです。

- 接続先: `https://connectivitycheck.gstatic.com/generate_204`（Android 自身が接続確認に使う Google の URL。本文が空の 204 応答が返る）
- 間隔: 10 秒ごと。タイムアウトは DNS 解決から応答の受信まで含めて 5 秒
- 毎回新しく接続します（`Connection: close`、キャッシュなし、リダイレクトに従わない）。そのため `duration_ms` には DNS・TCP・TLS の時間が含まれます
- `status` は `ok` / `http_error` / `timeout` / `error` のいずれか。`error` のときは `note` に例外の種類を残します
- 送るのは中身のない GET だけです。位置・セル情報・端末情報は送りません。接続先からは送信元の IP アドレスとアクセスした時刻が見えます
- 計測開始前に画面のスイッチでオフにできます。オフのときは `session_start` 行の `note` に `probe=off` と残ります

### CSV ビューア

`web/csv-visualizer/index.html` をブラウザで開いて CSV を選ぶと、GPS 軌跡の概略図、速度と在圏セル RSRP の時系列グラフ、セル変更の時系列を表示します。軌跡・グラフ・セルイベントの表は選択した時刻で連動し、時間帯で絞り込めます。CSV はブラウザの中だけで読み込み、外部への通信は CSP で禁止しています。使い方・対応形式・確認項目は [`web/csv-visualizer/README.md`](web/csv-visualizer/README.md) を参照してください。

`tools/track-viewer.html` は、軌跡だけを表示する以前の簡易版です。

