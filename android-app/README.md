# Keio Ride Logger

京王ライナーなどの乗車中に、Android端末で取得できる位置・携帯網・モーションセンサーの値を記録し、CSVとして保存するアプリです。記録は端末内で扱い、外部サーバーへ送信しません。

## MVPの範囲

- Kotlin、Jetpack Compose。Android 16 / API 36を主な対象とします（minSdk 26）。
- 画面から計測を開始・停止し、経過時間、取得状態、最新値を表示します。
- GPS: 緯度、経度、速度、高度、水平精度（目安1秒）。
- 携帯網: Cell ID、LTE/NR等の種別、RSRP、取得できる場合は周波数情報。
- 加速度・ジャイロ（目安20 Hz）、気圧（目安1 Hz）、磁気（目安5 Hz）。
- 種別ごとのイベントを取得時刻付きで記録し、Storage Access Framework（SAF）でCSVの保存先を選びます。
- MVPでは画面表示中のみ計測します。バックグラウンド計測、地図、クラウド同期は対象外です。

センサーやセル情報の可用性は端末、SIM、通信事業者、権限、OS設定に依存します。未対応・取得不可でも他の計測を継続します。セルの変化は観測イベントであり、ハンドオーバーを完全に示すものではありません。GPS高度と気圧は別々に記録します。

## 開発環境（Docker / OrbStack）

Android CLI、SDK、Gradle、Claude Codeはリポジトリ既存のDocker環境内で使用します。SDK・GradleキャッシュをMacホストへbind mountしないでください。`compose.yaml`はリポジトリを`/workspace`へ共有し、作業ディレクトリを`/workspace/android-app`にします。ビルドキャッシュと出力はDocker named volumeです。

初回、またはDockerfileを変更した場合はリポジトリルートでイメージをビルドします。

```sh
docker build --platform linux/amd64 -t android-kotlin:latest .
docker compose up -d
docker compose exec android bash
```

コンテナ内でビルドし、共有領域へAPKをコピーします。

```sh
cd /workspace/android-app
./gradlew assembleDebug
mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk dist/app-debug.apk
```

APKはホスト側の`keiou-takao/android-app/dist/app-debug.apk`にあります。Macホスト側のAndroid SDKやGradleでビルドしないでください。

## 実機へのインストール

ADBはMacホスト側に導入し、Android端末で開発者向けオプションとUSBデバッグを有効にします。リポジトリルートから接続・インストールします。

```sh
adb devices
adb install -r android-app/dist/app-debug.apk
```

位置情報と電話状態の権限は用途を確認して許可してください。拒否しても許可された項目の計測は続けます。CSVはアプリ内で計測停止後にSAFから保存します。

## データ形式

CSVはセンサー周期の違いを保つイベント形式です。各行にはUTCのISO 8601時刻（ミリ秒精度）、イベント種別、該当イベントの値を記録します。該当しない値は空欄とし、ゼロと欠損を区別します。GPS高度と気圧を混ぜません。

## 終了とデータ削除

開発コンテナを停止するにはリポジトリルートで`docker compose down`を実行します。キャッシュやClaude Code設定も含めnamed volumeを削除する場合だけ、対象を確認してから`docker compose down -v`を使ってください。ソースやAPKは別途削除されません。APKや端末内の計測データを削除する前に必要なファイルを退避してください。

## 実機での確認項目

- 権限の許可・拒否それぞれで、利用可能な計測が継続する。
- 計測の開始・停止を繰り返しても、クラッシュや重複記録がない。
- 未搭載センサーと取得不能なGPS／セル情報が適切に表示される。
- SAFでCSVを保存でき、時刻・イベント・値・空欄を確認できる。
- 位置・セル情報がネットワーク送信されない。
