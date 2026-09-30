# ADR 0002: 認証方式を権限と運用まで検証して選ぶ

状態: 提案／検証待ち。認証の実装前ゲート。

## 要求

Androidクライアントにclient secretを埋め込まない。ユーザーのGitHubパスワードをアプリに入力させない。アクセス先と権限を明示し、ログアウトで秘密と当該アカウントのキャッシュを消去する。トークンをログ・クラッシュ情報・通常バックアップへ出さない。

## 比較候補

1. GitHub Appのユーザー認証・Device Flowを第一候補として実証する。リポジトリ選択と細かな権限を使えるが、Organizationへのインストールや承認、トークン期限・更新方式まで含めて成立確認が必要。
2. OAuth AppのDevice Flowは代替候補。権限の広さとOrganizationの制限を比較する。
3. 個人利用の早期検証に限りfine-grained PATの手入力を補助導線にする。これだけで認証の完成とはしない。無制限classic PATを標準にしない。

公式資料でDevice Flowの存在は確認できる。一方、採用するApp種別でのrefresh token更新、ユーザー通知API、Organization承認、必要権限の組合せはまだ実証していない。**現時点で「バックエンド不要」「全APIが使える」と断定しない。** 登録と実証の結果をこのADRへ反映してから、認証UIと保存処理を製造する。

バックエンドが必要な方式を採る場合は、秘密管理と運用コストを含む独立Issueを追加する。今回の静的プロトタイプは認証情報を一切受け付けず通信もしない。

## 実証の最低条件

個人のprivate repoの一覧・ファイル読取り・Issueコメント・PR作成／レビュー、Organizationで許可されたrepo、通知一覧、取消し、期限切れ、再認証、ログアウト時の消去。使えないAPIを表で記録し、権限追加が必要な理由をUIに出す。

## 根拠

- https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps
- https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/authenticating-with-a-github-app-on-behalf-of-a-user

2026-09-30確認。GitHub Appのアクセスはユーザーの権限、Appの権限、インストール先の交差に制限される。上記資料の説明と当プロジェクトの方式選択は区別する。
