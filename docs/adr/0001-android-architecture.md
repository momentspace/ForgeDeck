# ADR 0001: Android のUIとデータ層

状態: 提案。実装着手時に対応SDK・依存バージョンを確定する。

## 方針

Android版は Kotlin + Jetpack Compose。WebプロトタイプをWebViewに入れて製品にするのではなく、操作・情報構造をネイティブUIに移す。UI State / ViewModel / Repository を分け、Coroutines / Flow で状態を流す。画面がGitHub APIを直接呼ばない。

Roomは閲覧キャッシュと下書き、DataStoreはピン留め・設定、Android Keystoreは秘密を暗号化する鍵に使う。秘密は通常のDataStoreやログ、APK、Gitに含めない。具体的なライブラリ選定と対応OSは初期実装Issueで再確認する。

最初から多数のGradleモジュールに分割せず、`ui` / `data` / `domain` と機能別パッケージから始める。Deckのルールと書込みの確認ロジックはUIから独立させる。

GitHub RESTを基本とし、関連PR・レビュー等で必要な場合にGraphQLを使う。APIの境界でページング、キャッシュ、取得済み範囲、レート制限、401/403/404、ネットワーク例外を正規化する。書込みはユーザーの明示操作から実行し、成功確認前に成功表示しない。

## 根拠

Android公式はCompose、UIとデータ層の分離、Repository、単方向の状態更新を推奨している。これは当プロジェクトの採用提案であり、API対応バージョンの確認済み宣言ではない。

- https://developer.android.com/topic/architecture/recommendations (2026-09-30確認)
- https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api (2026-09-30確認)

## 検証

CIでlint、主要なルール／競合処理テスト、デバッグAPK。実機でログインからファイル閲覧、編集、Issueコメント、PR確認まで通す。画面ごとの実装をなぞるテストより、入力消失・誤分類・権限不足・競合の検証を優先する。
