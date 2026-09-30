# インストール・アカウント接続・更新

## まず試す

1. [PR #19](https://github.com/momentspace/ForgeDeck/pull/19) のChecksから成功したAndroid runを開く。
2. `ForgeDeck-debug-apk` をダウンロード・展開し、`app-debug.apk` をAndroid 8.0以上の端末へ転送。
3. APKを開き、そのファイルを開くアプリのインストール許可をAndroidの画面で設定する。
4. ForgeDeckで本人のトークンを入力する。トークンをIssue、PR、チャットへ貼らない。

alphaは実アカウントの受入確認が未完了です。まず検証用repoを選び、読取り→Issueコメント→ファイル変更PRの順で確認します。既存の重要なブランチを直接書き換える操作はありません。

デバッグAPKの署名鍵はCI実行ごとに変わる場合があります。更新できないときはアンインストールが必要で、端末内の下書き・認証情報も消えます。継続利用には、下記の同じ個人用署名鍵で作るrelease APKを使ってください。

## 認証と必要権限

| 方式 | 使用条件 | 制限 |
| --- | --- | --- |
| fine-grained PAT | 対象repoを選ぶ。Contents / Issues / Pull requests: read & write。Checks / Commit statuses / Metadata: read | 通知APIは非対応。複数ownerやOrganizationにはそれぞれの制限・承認がある |
| GitHub App Device Flow | 本人が登録したAppのClient ID、Device Flow有効、対象repoへのインストールと上記権限 | client secretはアプリに不要。通知APIは非対応。期限切れ時は再認証。実Appでの検証は未完了 |
| classic PAT | 通知に `notifications`。private repo操作にも使うなら `repo`。OrganizationによってSSO承認が必要 | 選択repoだけに権限を絞れない。実アカウントの権限確認は未完了 |

GitHubの通知APIは、公式資料上classic PATのみ対応しています。非対応の認証でInboxを開くと、GitHubの通知をブラウザで確認する導線を表示します。通知のために無断で権限を増やすことはしません。

- [PATの管理](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens)
- [通知API](https://docs.github.com/en/rest/activity/notifications?apiVersion=2022-11-28)
- [GitHub Appユーザー認証](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/authenticating-with-a-github-app-on-behalf-of-a-user)

## 保存するもの

トークンはAndroid KeystoreのAES-GCM鍵で暗号化し、アプリ専用の領域に保存。ピン留め、最近使ったrepo、手動の待ち、下書き、途中のPR作成、閲覧キャッシュは同じ専用領域に保存します。これらは端末バックアップの対象外です。閲覧キャッシュと下書き本文はアプリ専用領域内の平文で、トークンと同じ暗号化はしていません。

ログアウトは認証・当該アカウントの設定・下書き・キャッシュを消去します。401による認証失効はトークンとキャッシュだけを消し、同じアカウントへの再接続に備えて下書きを残します。GitHub側のSettingsからPATまたはAppの承認を取り消せます。

アプリの通信先はGitHub APIと認証エンドポイントです。Markdownの外部画像やHTMLを自動実行・取得しません。リンクは明示操作でブラウザへ渡します。外部AIへの送信や分析用SDKはありません。

## 継続利用する署名付きAPK

署名鍵は同じAPKを更新するために必要です。**Gitへ追加せず、鍵とパスワードを本人が保管してください。**

JDKの `keytool` で個人用の鍵を作ります。パスワードは対話入力します。

```sh
keytool -genkeypair -keystore /your/private/path/forgedeck-release.jks \
  -alias forgedeck -keyalg RSA -keysize 3072 -validity 10000
```

GitHub Actionsで署名する場合、repositoryのSettings → Secrets and variables → Actionsに次を登録します。

| Secret | 値 |
| --- | --- |
| `FORGEDECK_KEYSTORE_BASE64` | JKSファイルをbase64化した内容 |
| `FORGEDECK_STORE_PASSWORD` | keystoreのパスワード |
| `FORGEDECK_KEY_ALIAS` | `forgedeck` |
| `FORGEDECK_KEY_PASSWORD` | 鍵のパスワード |

`Personal signed APK` を手動実行します。CIは鍵を一時復元し、releaseのlint・テスト・ビルドと署名検証を行い、`ForgeDeck-signed-release-apk` を保存します。終了時に一時の鍵を消します。公開Releaseへの自動投稿はありません。

ローカルでも `FORGEDECK_KEYSTORE_PATH`、`FORGEDECK_STORE_PASSWORD`、`FORGEDECK_KEY_ALIAS`、`FORGEDECK_KEY_PASSWORD` を環境変数に渡して `./gradlew :app:assembleRelease` で作れます。環境変数やパスワードをコマンド履歴・ログに残さない方法で設定してください。

署名鍵の登録と署名付きAPKの実証はまだ行っていません。鍵が異なるdebug版からrelease版への移行はアンインストールが必要です。同じ署名鍵とapplication IDのrelease版は、versionCodeを上げて更新します。
