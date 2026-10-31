# Sora Weather

Android向け天気アプリ（Kotlin + Jetpack Compose）。

## 機能
- 起動時に位置情報権限を確認し、許可された場合は端末の既知の位置情報から天気を取得
- 都市名検索による手動切り替え
- 現在の気温・体感温度・湿度・風速・降水量
- 1時間ごとの予報、7日間予報
- Open-Meteo Forecast API / Geocoding APIを利用（APIキー不要）

## GitHub Actions
mainブランチへのpush、またはActions画面の「Android CI」から手動実行するとDebug APKをビルドします。
成功時はActions実行ページのArtifactsから「Sora-Weather-debug-apk」をダウンロードできます。

## ローカルビルド
Gradle 8.13 / JDK 17で以下を実行してください。

```bash
gradle --no-daemon :app:assembleDebug
```

生成APK: `app/build/outputs/apk/debug/app-debug.apk`

## メモ
位置情報は端末に保存された最後の既知位置を利用します。GPSの新規測位を待つ実装ではありません。
位置情報が取得できない場合は、上部の検索アイコンから都市名を入力してください。
