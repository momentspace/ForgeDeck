# ADR 0001: Android のUIとデータ層

状態: alpha実装へ採用。実アカウント・実機の受入は未完了。

## 方針

Android版は Kotlin + Jetpack Compose。WebプロトタイプをWebViewに入れて製品にするのではなく、操作・情報構造をネイティブUIに移す。UI State / ViewModel / Repository を分け、Coroutines / Flow で状態を流す。画面がGitHub APIを直接呼ばない。

閲覧キャッシュ、下書き、ピン留め、設定は初期版ではアプリ専用SharedPreferencesに保存する。閲覧キャッシュは一件600KB・合計約2MBを上限にする。下書き入力はUI状態を即時更新し、IOで遅延保存、画面を離れるときとonStopで確定する。トークンはAndroid KeystoreのAES-GCM鍵で暗号化する。秘密をログ・APK・Git・通常バックアップへ含めない。Room / DataStoreは大量データ計測を踏まえた後続の移行候補とし、現時点で使用済みとは記載しない。

最初から多数のGradleモジュールに分割せず、UI / ViewModel / Repository / API / LocalStore / 純粋なルールをファイル境界で分離する。Deckのルールと書込みの確認ロジックはUIから独立させる。

GitHub RESTを基本とし、関連PR・レビュー等で必要な場合にGraphQLを使う。APIの境界でページング、キャッシュ、取得済み範囲、レート制限、401/403/404、ネットワーク例外を正規化する。書込みはユーザーの明示操作から実行し、成功確認前に成功表示しない。

## 根拠

Android公式はCompose、UIとデータ層の分離、Repository、単方向の状態更新を推奨している。これは当プロジェクトの採用提案であり、API対応バージョンの確認済み宣言ではない。

- https://developer.android.com/topic/architecture/recommendations (2026-09-30確認)
- https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api (2026-09-30確認)

## 検証

CIでlint、主要なルール／競合処理テスト、デバッグAPK。実機でログインからファイル閲覧、編集、Issueコメント、PR確認まで通す。画面ごとの実装をなぞるテストより、入力消失・誤分類・権限不足・競合の検証を優先する。


## 固定バージョン

min SDK 26 / compile・target 35、AGP 8.9.2、Gradle 8.11.1、JDK 17、Kotlin 2.1.20、Compose BOM 2025.04.01。初期build・lint・unit testはCI成功。実装と制限は `docs/IMPLEMENTATION.md`。
