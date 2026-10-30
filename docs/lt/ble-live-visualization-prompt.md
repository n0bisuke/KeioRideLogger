# Claude Code指示: BLEライブ値を地図・グラフへ表示

以下は、BLE最小試作がWebブラウザで受信できるようになった後の追加作業です。関連TODOは[`docs/ROADMAP.md`](../ROADMAP.md)の「BLE受信値をブラウザの地図・グラフへライブ表示する」です。

```text
既存のKeio Ride Logger BLE受信表示を、リアルタイムの軌跡地図と時系列グラフに拡張してください。

## 現在確認できていること

- AndroidアプリからGitHub Pages上のWeb Bluetooth対応ブラウザへBLE通知が届き、約1秒ごとに画面が更新される。
- ブラウザには最終受信時刻、GPS位置/速度/精度、LTE在圏セルの方式・Cell ID・PCI・RSRP等、セル変更候補、ネットワーク経路、HTTP probe結果のカードが表示される。
- 実機スクリーンショットは`docs/lt/local-captures/README.md`を参照。原本画像は位置・Cell IDが見えるためGit管理外。
- 現状のBLE接続・通知・カード表示を作り直さず、この実装とプロトコルv1から継続する。作業前にgit diffと関連ソースを確認し、ユーザー変更を上書きしない。

## 表示要件

1. BLEパネル内にGPSの軌跡・現在位置・水平精度の円を描画する。CSVを読み込んでいなくてもBLEだけで動く。GPS欠測を(0,0)や0 km/hで描かず、位置が古い場合は最終位置をstaleとして示す。
2. 速度[km/h]、在圏セルRSRP[dBm]、利用可能なRSRQ/SNR[dB]、HTTP応答時間[ms]を別々の時系列グラフで表示する。測定周期が異なるので別々に描き、未計測区間を補間しない。RSSI/SNRの名称や単位は既存payloadの意味に合わせる。
3. `cellStatus`・セル識別情報・`switches`・`lastSwitch`を使い、在圏セルなし/周辺セルのみ/取得待ちを区別する。初回baselineはhandoverとして表示しない。
4. `probe.ageMs`が進む間は同じHTTP probe結果を新しい測定点として毎秒追加しない。`t - ageMs`から元のprobe時刻を求め、同じprobe sampleは一回だけプロットする。成功・HTTPエラー・タイムアウト/失敗は記号で区別する。
5. BLE frame sequence、payloadの端末時刻` t`、各値の`ageMs`を使って時刻順と新鮮さを扱う。古い/重複frameを新しい点として重ねない。受信停止、切断、再接続中は最終受信時刻を表示し、データが古いと明示する。欠測値は0へ変換しない。
6. BLEの履歴はJavaScriptメモリのみで保持する。最大保持時間と各系列の最大点数を決め、長時間接続でも増え続けないようにする。ページ更新/終了で破棄し、localStorage・sessionStorage・IndexedDBには書かない。
7. 既存のCSV解析/地図のstateとBLEライブstateを分離する。CSVを読み込んだ状態でもBLE受信でCSVの履歴や選択時刻を上書きしない。CSV未読込でもBLEパネルの地図・グラフは操作できる。
8. OSM背景地図を再利用するなら既存のタイル取得条件・帰属表示・開示文を保つ。表示中の地図タイル座標とIPはOSMへ送られるが、BLEの個別測位点、Cell ID、ペイロードは送らない。地図タイル取得失敗時も地図背景なしで軌跡・グラフを表示する。測位点を道路/線路にスナップしたように描かない。
9. 明るいタイル上で軌跡が読めるよう濃い線+明るい縁取りを使う。地図なし/暗い背景では反転した配色にする。文字サイズを拡大してもカード・地図・グラフが重ならない。
10. CSVビューア既存機能とBLE受信を混同させない。BLEでブラウザへ届いたデータは画面内表示だけにし、Fetch/XHR/WebSocket/Beacon等で送信しない。OSMタイル通信と、Android端末自身が実行するHTTP probe通信はBLE payload送信とは別であることを表示上・検証上で正確に扱う。

## 接続・故障時の実機確認

- GitHub Pages HTTPSの発表用PC/ブラウザとAndroid実機で、BLE接続→約1秒更新→明示切断→手動再接続を試す。
- 接続中にAndroid計測を停止、BLEスイッチをオフ、端末Bluetoothをオフ、ブラウザタブを閉じる各ケースで、Androidの記録/CSV保存を壊さず、Web側がstale/切断を表示することを確認する。
- 再接続後に新しいsequence/位置/信号が表示され、以前のsessionのBLE履歴が混ざらないことを確認する。ページreload後にBLEデータが復元されないことも見る。
- 可能なら短い実歩行テストでGPS軌跡が増えることを確認する。屋内で位置が止まったときは「0移動」と誤表示しない。
- DevTools Networkを記録し、BLE受信データが外へ送られていないことを確認する。画面表示中のOSMタイル要求とAndroidからのHTTP probeは分けて説明する。

## テスト/作業後

- frame/parserの不正値・重複・順不同・欠落、probe重複抑制、履歴上限、stale/欠測をテストする。依存追加や大規模なUI再構築は避ける。
- `docs/ROADMAP.md`・`docs/TEST_LOG.md`・`docs/lt/talk-outline.md`を結果に合わせて更新する。実機やブラウザで確認していないことは未確認とする。
- Android build/test/lintは親AGENTSに従いDocker/OrbStack内で実行する。実機データ、正確な位置、Cell IDをgit管理対象にしない。
```
