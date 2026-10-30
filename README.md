# Keio Ride Logger

京王ライナーなどの乗車中に、Android 端末の位置・携帯網・モーションセンサー情報を記録し、あとから分析できる CSV を出力する Android アプリです。記録データは端末内で扱い、外部サーバーには送信しません。

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
| `event` | `session_start` / `session_stop` / `sensor_status` / `sensor_rate` / `accel` / `gyro` / `mag` / `pressure` / `gps` / `gps_status` / `cell` / `cell_poll` / `cell_change` / `cell_status` / `display_info` |
| `status` | 状態イベントの値（`unavailable`、`permission_denied`、`provider_disabled`、`empty` など） |
| `lat` `lon` `speed_mps` `altitude_m` `h_accuracy_m` `provider` | GPS（`gps` 行）。高度は GPS 高度で、気圧とは別 |
| `x` `y` `z` `accuracy` | 加速度 [m/s²]・ジャイロ [rad/s]・磁気 [µT] と、センサーの精度区分 |
| `pressure_hpa` | 気圧 [hPa]（`pressure` 行） |
| `rat` `override_rat` | セルの方式（LTE / NR など）と、`display_info` 行の表示種別（`NR_NSA` など） |
| `cell_id` `pci` `tac` `arfcn` `bands` `rsrp_dbm` | セル情報。`bands` は `;` 区切り |
| `registered` | 在圏セルなら `true`、周辺セルなら `false` |
| `age_ms` | セル情報が取得されてから記録されるまでの経過時間 |
| `note` | 補足。`cell` / `cell_poll` 行は取得経路 `src=request|cached|listener`、`sensor_status` 行はセンサーの `min_delay_us` など、`sensor_rate` 行は端末からの配信頻度（`received_hz`）と記録頻度（`recorded_hz`） |

取得間隔の目安は、加速度・ジャイロ 20 Hz、磁気 5 Hz、気圧 1 Hz、GPS 1 秒、セル情報 2 秒です。`cell_change` は、在圏セルの組み合わせ（方式、Cell ID、PCI、ARFCN）が前回と変わったときに出す「観測したセル変更」で、ハンドオーバーそのものを示すものではありません。

セル情報は3つの経路から取ります。`request` はデータ通信の回線に紐づけた `requestCellInfoUpdate()`、`cached` は全モデム分の `allCellInfo`（キャッシュ）、`listener` は `CellInfoListener`（Android 12 以降）です。`cell` 行は `request` 経路のセルをすべて記録し、ほかの経路は在圏セルだけを記録します。`cell_poll` 行は応答ごとの件数（総数、在圏数、方式別）と状態（`ok` / `empty` / `no_response` / `error`）で、セル ID などの識別子は含みません。セッション開始時の `cell_status` 行には、回線・モデムの状態を真偽値と件数だけで残します。
