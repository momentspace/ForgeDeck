# 依存ライブラリ

依存バージョンはGradleの宣言を正とします。画面サンプルのLucideライセンスは `prototype/vendor/LICENSE-lucide` に同梱しています。

| ライブラリ | 使用 | ライセンス / 公式ソース |
| --- | --- | --- |
| AndroidX / Jetpack Compose | Android UI、Lifecycle、テスト | Apache-2.0 / https://android.googlesource.com/platform/frameworks/support/ |
| Kotlin / Coroutines | 言語・非同期 | Apache-2.0 / https://github.com/JetBrains/kotlin / https://github.com/Kotlin/kotlinx.coroutines |
| OkHttp / MockWebServer | HTTP・APIテスト | Apache-2.0 / https://github.com/square/okhttp |
| CommonMark Java | Markdown解析 | BSD-2-Clause / https://github.com/commonmark/commonmark-java |
| JUnit 4 | 単体テスト | EPL-1.0 / https://github.com/junit-team/junit4 |
| JSON-java | JVMテスト用JSON | Public Domain / https://github.com/stleary/JSON-java |
| Lucide | 画面サンプルのアイコン | ISC / https://lucide.dev/license |
| ktfmt | 開発時の整形、APKには含まれない | Apache-2.0 / https://github.com/Kotlin/ktfmt |

実行時にリポジトリのコードやHTMLを評価する処理はありません。MarkdownはCommonMark ASTからネイティブTextへ変換し、HTMLは文字として表示します。外部画像を自動取得しません。GFM表・タスクリスト・構文ハイライト等は完全互換ではありません。
