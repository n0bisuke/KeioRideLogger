# Keio Ride Logger 用語集

LTのスライドやデモで使う、アプリ画面・CSV・Android APIの用語を短く説明します。値は端末や通信事業者が返した範囲で記録され、取得できない項目は空欄になることがあります。

## 携帯ネットワーク

| 用語 | 簡単な説明 |
|---|---|
| **セル** | 携帯電話網の無線エリアを構成する単位。日常会話の「基地局」と同じ意味で使われることがありますが、1つの基地局設備が複数のセルを持つことがあります。 |
| **Cell ID / CI / NCI** | セルを識別する番号。LTEではCI、5G NRではNCIと呼ばれる識別値を使います。番号だけで基地局の住所が分かるわけではありません。 |
| **PCI** | Physical Cell ID。無線上でセルを見分けるための番号です。近くのセル間で再利用されるため、PCIだけでは全国で一意なセルになりません。 |
| **TAC** | Tracking Area Code。端末の位置登録に使う追跡エリアの番号です。基地局ひとつの位置を示す番号ではありません。 |
| **MCC / MNC** | MCCは国コード、MNCは携帯網の事業者コードです。Cell IDなどと組み合わせてネットワークを識別します。SIMのブランド名と、実際に観測したセルの網が常に同じとは限りません。 |
| **ARFCN / EARFCN / NRARFCN** | 無線チャネル番号です。LTEではEARFCN、5G NRではNRARFCNなどと呼ばれます。周波数そのもの（MHz）ではなく、方式・番号と対応表から周波数帯を判断します。 |
| **周波数帯 / Band** | 電波を送受信する周波数の区分。端末やモデムが返さない場合は取得できません。 |
| **RAT** | Radio Access Technology。LTE、NR（5G）、WCDMAなどの無線方式です。画面の「LTE/NR」はこの方式の観測を表します。 |
| **在圏セル / registered cell** | Android APIが端末の登録先として報告したセル。アプリでは切り替わり判定の基準にします。APIの報告は更新制限を受けるため、実際の無線網内の処理を完全に記録するものではありません。 |
| **周辺セル / neighbor cell** | 端末周辺で観測されたセル。在圏セルとは限らず、接続先や切り替え先と断定できません。 |
| **セル変更候補** | アプリが在圏セルの識別値の変化を観測したイベントです。最初に観測したセルは基準値として扱い、切り替わりに数えません。「ハンドオーバー確定」と同じ意味ではありません。 |
| **ハンドオーバー** | 通信中に接続先のセルを切り替え、通信を継続させるネットワーク制御です。このアプリのセル変更候補だけでは、ハンドオーバーが起きたことを確定できません。 |
| **LTE / NR** | LTEは4G系、NR（New Radio）は5Gの無線方式です。5G NSAではLTEをアンカーにNRを併用することがあります。画面の表示種別と、個々のCellInfoの方式は区別して見ます。 |

## 電波品質と通信応答

| 用語 | 簡単な説明 |
|---|---|
| **RSRP** | 基準信号の受信電力。単位はdBmで、一般に負の値です。0に近いほど受信電力が強い傾向ですが、これだけで通信速度や体感品質は決まりません。 |
| **RSRQ** | 基準信号の受信品質を表す値。単位はdB。電力だけでなく干渉などの影響も受けます。 |
| **RSSNR / SINR** | 信号と雑音・干渉の比を表す品質指標。通常dBで示します。LTEとNRではAPI上の指標名が異なるため、CSV列名も分けています。 |
| **HTTPプローブ** | 決められたWebサーバーへ小さなHTTPリクエストを送り、応答を確認する計測です。位置やCell IDを送る計測ではありませんが、接続先サーバーにはリクエストが届きます。 |
| **HTTP応答時間 / `duration_ms`** | プローブ開始から応答までの所要時間。現在の実装では新しい接続を使うため、DNS・TCP・TLS・HTTP応答などを含む総時間です。電波の遅延だけを測る「ping」とは異なります。 |
| **HTTP 204** | HTTPの成功応答のひとつで、本文を返さず処理が成功したことを表します。204が返っても、一般的な大容量通信の速度を測ったことにはなりません。 |
| **既定ネットワーク / transport** | Androidがその時点で標準の通信に使うネットワークと、その種類（携帯網、Wi-Fiなど）です。アプリのプローブや他アプリの通信経路が常に同じとは限りません。 |
| **validated** | Androidがネットワークのインターネット接続を確認できた状態を示します。特定のWebサービスが必ず使えるという保証ではありません。 |

## 位置・時刻・センサー

| 用語 | 簡単な説明 |
|---|---|
| **GPS速度** | 位置測位から端末が受け取る速度の推定値です。列車の公式速度計ではなく、屋内・トンネル・測位精度低下時は欠測や古い値になることがあります。 |
| **水平精度 / `h_accuracy_m`** | GPS位置の水平誤差の目安（メートル）。値が大きいほど位置の不確かさが大きいことを表します。 |
| **UTC / `time_utc`** | CSVに保存する基準時刻。日本の予定時刻と比較するときはJSTへ変換します。 |
| **経過リアルタイム / `elapsed_realtime_ns`** | 端末起動後からの単調増加時刻。端末の時計合わせで時刻が前後しても、イベント間の順序や間隔を調べるのに使います。単位はナノ秒です。 |
| **加速度** | 端末センサーのX/Y/Z軸方向の加速度。端末の向きや手持ちの揺れも含み、列車の進行方向の加速度とそのまま同一視できません。 |
| **気圧** | 端末の気圧センサーが測った気圧です。GPS高度とは別の値で、気圧だけから標高を断定しません。 |

## このプロジェクトでの注意

- 現行CSVにはCell ID、PCI、TAC、ARFCN/NRARFCN、バンドなどがありますが、**MCC/MNCはまだ出力していません**。基地局位置データベースと照合する前に、追加記録できるか確認が必要です。
- AndroidのCellInfoは部分的な値やキャッシュを返すことがあり、更新頻度も保証されません。取得できない値を0や推測値で埋めません。
- 公開用の画面・CSVでは、正確なGPS、Cell ID、加入者情報を出さないでください。セル変更は「観測候補」と表現します。

## 参考資料

- [Android `CellInfo`: registered状態とセル情報の時刻](https://developer.android.com/reference/android/telephony/CellInfo)
- [Android `CellIdentityLte`: CI、PCI、TAC、EARFCN、MCC/MNC](https://developer.android.com/reference/android/telephony/CellIdentityLte)
- [Android `CellIdentityNr`: NCI、PCI、TAC、NRARFCN](https://developer.android.com/reference/android/telephony/CellIdentityNr)
- [Android `CellSignalStrengthNr`: SS-RSRP、SS-RSRQ、SS-SINR](https://developer.android.com/reference/android/telephony/CellSignalStrengthNr)
- [Android `TelephonyManager`: CellInfo更新は制限され、常に新しい値が保証されない](https://developer.android.com/reference/android/telephony/TelephonyManager)
- [OpenCelliD: セル位置照合に必要な識別子](https://docs.opencellid.org/docs/getting-started/first-lookup)
