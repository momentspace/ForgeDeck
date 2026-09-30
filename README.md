# ForgeDeck

いつものリポジトリをすぐ開き、ファイルを扱い、Issue / PR の次の一手を見つけるAndroid GitHubクライアント。

**Android試用版（alpha）が利用できます。実アカウント・実機・配布署名の受入確認は未完了です。** 開発は [PR #19](https://github.com/momentspace/ForgeDeck/pull/19)、完成条件は [Epic #1](https://github.com/momentspace/ForgeDeck/issues/1) で管理します。Wiki / Projects / Enterprise / 複数アカウントは初期スコープ外です。

## Android版

[検証済み試用APK（ZIP）](https://github.com/momentspace/ForgeDeck/actions/runs/36688660640/artifacts/11084829391) を展開し、`app-debug.apk` をAndroidで開いてインストールしてください。GitHubへのログインが必要です。最新版は [Android CI](https://github.com/momentspace/ForgeDeck/actions/workflows/android.yml) の成功runから取得できます。


- リポジトリ一覧、ownerとrepo名、検索、ピン留め、最近使った場所。
- ブランチとパンくず、UTF-8テキスト・Markdown閲覧、端末への保存。
- ファイル作成・編集・アップロード・移動・削除の差分確認、新ブランチへの保存、PR作成。SHA競合検知、下書き、途中成功の復旧。
- Issueの作成・編集・担当・ラベル・コメント・close/reopen。
- PR作成、概要、差分、チェック、レビュー、行コメント、条件を再確認するSquash merge。
- **Action Deck**：自分の番 / 待ち / マージ候補。分類理由を表示し、APIで確認した完了関係だけを使ってIssueとPRをまとめる。
- 通知一覧・既読、repo / Issue / PRリンク、ライト・ダーク。

[インストール・権限・署名と更新](docs/INSTALL.md) / [実装と検証の状況](docs/IMPLEMENTATION.md)。

## 画面サンプル

[`prototype/index.html`](prototype/index.html) をダウンロードしてブラウザで開けます。単一ファイル、通信不要。データと操作結果はすべて架空です。

「ForgeDeck → README.md → 編集 → 変更を確認」、次に「Deck → レビュー依頼 → 差分」を試してください。

## 開発

Android 8.0以上（API 26）、compile / target SDK 35、JDK 17、Gradle 8.11.1、AGP 8.9.2、Kotlin 2.1.20、Compose BOM 2025.04.01。

Android Studioでこのディレクトリを開くか、SDK 35とJDK 17を用意して次を実行します。

```sh
./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest
```

APKは `app/build/outputs/apk/debug/app-debug.apk`。PRのActionsにもAPK、テスト・lint結果、ネイティブ画面の画像を保存します。デバッグAPKには本番用の署名鍵を含めません。署名なしのrelease APKはそのままインストールできません。

## 設計

- [コンセプト](docs/CONCEPT.md) / [完成までの計画](docs/ROADMAP.md) / [サンプルの操作](docs/PROTOTYPE.md)
- [ADR: Android](docs/adr/0001-android-architecture.md) / [ADR: 認証](docs/adr/0002-authentication.md) / [ADR: Deck](docs/adr/0003-action-deck.md)
- [依存ライブラリとライセンス](docs/THIRD_PARTY.md)

