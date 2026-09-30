# ForgeDeck

リポジトリに迷わず入れて、ファイルを扱えて、Issue / PR の次の一手が分かる Android GitHub クライアント。

現在は **コンセプト・操作可能な画面サンプル・実装計画** の段階です。Android アプリと GitHub API 接続はまだ実装していません。

## 画面サンプル

[`prototype/index.html`](prototype/index.html) をダウンロードしてブラウザで開いてください。単一ファイル、インストール不要、通信不要です。表示される件数、Issue / PR、CI、ファイルはすべて架空のデモデータです。操作は画面内だけで完結し、GitHub を変更しません。再読込すると元に戻ります。

- **リポジトリ**：検索、ピン留め、最近使ったリポジトリ、常に見える owner / repo。
- **ファイル**：ブランチ、パンくず、閲覧・編集、変更差分、別ブランチと PR による保存体験。
- **Deck**：リポジトリを横断して「自分の番」「待ち」「マージ候補」を根拠付きで分類。Issue と関連 PR を一緒に確認。
- **Issue / PR**：本文、コメント、チェック、差分、確認画面。作成や操作もデモです。

最初は「ForgeDeck → README.md → 編集 → 変更を確認」、次に「Deck → レビューを頼まれた PR → 差分」を試してください。

## 設計と計画

- [プロダクトコンセプトと情報設計](docs/CONCEPT.md)
- [完成までの計画と受入条件](docs/ROADMAP.md)
- [画面サンプルの操作・確認結果](docs/PROTOTYPE.md)
- [ADR: Android とデータ層](docs/adr/0001-android-architecture.md)
- [ADR: 認証と権限](docs/adr/0002-authentication.md)
- [ADR: 次の一手の分類](docs/adr/0003-action-deck.md)

Wiki と Projects は初期スコープ外です。最初の完成目標は GitHub.com の個人利用向け APK。ストア公開と Enterprise Server は別フェーズです。
