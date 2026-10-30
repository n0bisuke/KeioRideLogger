# Claude Code指示: BLEリアルタイム表示の最小試作

以下をリポジトリルートでClaude Codeに渡してください。関連TODOは[`docs/ROADMAP.md`](../ROADMAP.md)の「BLEで計測中データをブラウザへ送る最小デモを作る」です。

```text
Keio Ride LoggerのAndroid実機からBLEで計測中データをWeb Bluetooth対応ブラウザへ送り、ブラウザにリアルタイム表示する最小試作を実装してください。私はCodexに実装を依頼せず、あなた（Claude Code）が実装します。

## 作業前に確認

- リポジトリルート`AGENTS.md`、`android-app/AGENTS.md`、README、Dockerfile、compose.yaml、Gradle設定、Manifestを読む。
- `docs/ROADMAP.md`、`docs/DEVELOPMENT_STATUS.md`、`docs/TEST_LOG.md`、この指示書を読む。
- Androidの計測stateとデータ取得元（GPS、CellSnapshot/在圏セル、cell change、HTTP probe）および`web/csv-visualizer/`の構造を確認する。
- `git status`と現在の差分を確認し、ユーザーの未コミット変更を上書き・巻き戻ししない。既存CSV形式・取得周期・計測ロジックは変更しない。
- BLE権限、広告API、Web Bluetoothの現在のAPI要件を公式ドキュメントで確認する。Android 16/API 36実機を前提にしつつ、プロジェクトのminSdk 26との互換性を壊さない。

## 接続構成と範囲

1. AndroidアプリをBLE peripheralかつGATT serverとして動かし、ブラウザをcentral/clientにする。Android側は計測画面が表示され、計測中のユーザーがBLE共有を有効にした間だけ広告・通知する。バックグラウンド常時実行、クラウド中継、Node.jsブリッジ、USBケーブルは追加しない。
2. Web Bluetoothのデバイス選択はユーザーが押す「BLE接続」ボタンから開始する。HTTPSのGitHub Pagesで動くことを前提とし、非対応ブラウザでは対応ブラウザを案内する。
3. 既存の`web/csv-visualizer/`に小さなBLE受信・状態表示・デバッグ用値表示を追加する。現在のCSVアップロード/解析、地図、グラフの既存動作を壊さない。まず受信データを文字列/カードで見えるようにし、既存地図・グラフとの連携は最小限にする。
4. 送信は1〜2 Hz程度を初期値とし、既存stateの最新値を使う。候補項目はUTC時刻、sequence、GPS緯度経度・速度・水平精度、在圏セルのRAT・Cell ID・PCI・RSRP/利用可能な品質指標、観測セル変更候補、直近HTTP probeの結果と応答時間。未取得値はnull/欠落として表し、0に置き換えない。20 Hzセンサー生データとCSV全体は送らない。
5. BLEでブラウザへCell ID等を送ることをAndroid UIとWeb UIに明示する。接続はユーザーが選んだ端末に限定し、送信内容はブラウザ内で表示するだけにする。受信データをHTTP、WebSocket、分析サービスへ送らず、localStorage/IndexedDB等にも永続化しない。
6. ブラウザ切断時に自動的に第三者端末へ送信しない。ユーザー操作で再接続できるようにする。BLE広告非対応、Bluetooth無効、権限拒否、未接続、通知停止、stale状態をUIで区別する。BLEが失敗してもAndroid計測・CSV保存を継続する。

## GATTとペイロード

- アプリ専用の128-bit service UUIDとnotify characteristic UUIDを定義し、AndroidとWebのコードで同じ定数を使う。サービス/characteristicには用途とプロトコルバージョンを記載する。
- 形式は保守しやすく、時刻・sequence・欠測を明確に表現できるものにする。採用形式と最大サイズをこの指示書またはコードコメントに記録する。
- GATT通知サイズはMTUと端末/browser実装に依存する。Android側で広告・MTU・characteristic通知の実際の結果を確認する。1つの通知サイズに収まらない場合は、version、sequence、fragment index/countを含む分割/再構成を実装し、不完全・重複・順不同フレームを誤表示しない。最大フレームサイズを制限する。
- 接続がない時に通知キューを無制限に蓄積しない。古いライブ値は捨て、最新値を送る。通知の失敗は計測収集スレッドを止めない。
- Android端末でBLE peripheral広告機能が利用できるか起動時に判定し、非対応なら理由を表示する。必要最小限のAndroid権限だけをAPIレベルに応じて要求し、用途を説明して拒否時も通常計測を続ける。
- 広告停止、GATT server close、callback解除はBLE共有停止、計測停止、画面破棄時に適切に行う。重複広告やリソースリークを防ぐ。

## ブラウザ要件

- `navigator.bluetooth` が利用できない場合、接続操作を隠すか無効化し、ブラウザ要件を説明する。
- HTTPS上でサービスUUIDを使って候補を選択する。名前だけで無制限に全端末を選ばない。
- 接続、service/characteristic discovery、通知開始、切断、再接続の状態を表示する。接続前はユーザージェスチャーを必要とする標準フローに従う。
- sequenceと取得時刻で新旧を判定し、最終受信時刻を表示する。タイムアウト後は値を消すか古い表示と明記する。0速度とGPS欠測を区別する。
- GitHub PagesのページでDevTools Networkを確認し、BLE受信に伴う通信先が発生しないことを確認する（ページ本体/地図タイル等の既存通信とは区別する）。

## 検証

- Kotlinのプロトコルencode/decode等は既存テスト構成に沿って単体テストする。ブラウザ側もfixture等でフレーム解析・再構成・無効フレーム・stale判定を確認する。
- Android build/test/lintは親`AGENTS.md`に従ってDocker/OrbStack内で実行し、MacホストにAndroid SDK/Gradle cacheを置かない。
- 実機と実際の発表用ブラウザで、広告検出→ユーザー接続→通知受信→GPS/セル値更新→切断→再接続を確認する。BLE共有をオフにしても計測とCSV保存が続くことを確認する。
- 実機確認できない場合はビルド確認と混同せず未確認と記録する。
- `docs/ROADMAP.md`の完了状況、`docs/TEST_LOG.md`の日時・端末/ブラウザ・試験結果を更新する。正確なGPS座標、Cell IDをログやテストfixtureへ書かない。
- 変更後に`git diff --check`と`git status`を確認し、公開リポジトリへ秘密情報、実機CSV、端末固有ログが追加されていないことを確認する。
```
