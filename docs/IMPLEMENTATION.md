# Android実装・検証の状況

2026-09-30。開発PR: #19。これは実装内容と確認済み範囲の記録であり、全Issueの完了宣言ではありません。

## 実装

| 機能 | 実装内容 | 残る確認・制限 |
| --- | --- | --- |
| 認証 | PATの本人確認、Keystore暗号化、GitHub App Device Flow、401再認証 | 登録App・本人private repo・Organizationの実証が必要。更新secretは保持せず再認証する |
| API | REST / GraphQL、ページング、権限・API制限・競合、GETの通信失敗時キャッシュ | HTTPエラーで古いprivateデータへ戻さない。書込みを自動再送しない。ETag / Last-Modifiedと304再検証に対応 |
| リポジトリ | owner/name、ローカル・GitHub検索、ピン留め、最近使った場所 | GitHub検索最大1000件。取得済み範囲を表示 |
| ファイル | branch、階層、Markdown・コード、作成・編集・UTF-8アップロード・移動・削除・端末保存 | UTF-8・1MB以下。一覧API上限1000件。コード表示先頭2000行。画像・バイナリ編集は対象外 |
| 書込み | 最新baseと元SHA、mode保持、移動を一つのtree / commitにする、新ref→PR | 途中成功を端末に記録し、同じhead/baseのPRを確認して再開。権限と実アカウントでの受入は未確認 |
| Issue / PR | 作成、編集、ラベル・担当、コメント、状態、レビュー・行コメント、チェック、差分、Squash merge | コメント・差分等は先頭100件と部分取得表示。自分のPRへ承認不可。fork PR作成は未対応 |
| Deck | 根拠付きの分類、横断検索、手動待ち、明示的な完了関係をまとめる | 自動詳細確認は先頭12PR。未確認はマージ候補にしない。タイトル一致で関係を推定しない |
| Inbox | 通知と既読、202受付を完了扱いにしない、repo / Issue / PRリンク | 通知はclassic PATのみ。fine-grained PAT / GitHub Appはブラウザへ。通知pushは未実装 |
| UX | ライト・ダーク、入力下書き、回転時ViewModel保持、場所と保存先の明示 | Xiaomi 14・TalkBack・大量データ・本人主要フローは未確認 |
| 配布 | debug / unsigned release CI、個人用署名secretを使う別workflow | 本番用鍵の登録・署名実証・実機更新が必要 |

## マージ候補の基準

open PR、draftではない、push権限、競合なし、GitHubのmergeStateがCLEAN、レビューAPPROVED、最新headのチェックと必須/任意条件を全取得、チェックが一件以上で全件success / neutral / skipped、部分取得なし。送信前に全条件を再取得し、表示していたhead SHAとも比較してAPIへshaを渡します。

承認不要のPR、チェックのないPR、条件が取得できないPRは候補にしません。個人のレビューなしPRもGitHubで確認・マージしてください。これは既知の保守的な制限です。ルール未確認を成功へ読み替えません。

## 確認済み

- prototypeのDOM・操作テストとJavaScript構文確認。ブラウザでのモバイル描画は未確認。
- Android CI run [36682932162](https://github.com/momentspace/ForgeDeck/actions/runs/36682932162)：debug / releaseビルド、lint、単体テスト成功。debug APK artifactあり。
- SDK準備失敗と画面コードの構文エラーは修正済み。
- CI run [36687503237](https://github.com/momentspace/ForgeDeck/actions/runs/36687503237)では追加したキャッシュ・通知・マージ条件のテストとAndroidテスト、APKインストール・MainActivity起動まで成功。画面画像から320dp・文字200%でタイトルと下部ラベルのはみ出しを確認し、最終修正を再検証中。

単体テストは分類の優先度、未知条件の候補除外、明示的関係だけのグループ化、リンク / path / refの検証、diff行番号、認証ヘッダーをredirectへ送らないこと、403と古いキャッシュの分離、SHA競合、移動の原子的tree作成、PR重複回避、通知の202受付、Markdownの危険リンク無効化を対象にします。

Android端末上のテストはKeystore暗号化、ログアウト、元SHAが変わった下書き、認証失効での下書き保持と、ログイン・repo・Deck・ファイル・編集画面の表示を対象にします。これらのseeded UIテストは実アカウントのGitHub書込みを行いません。

## 完成に必要な受入

1. 本人の検証用repoでログイン→一覧→ファイル編集→差分→PR作成。
2. 別アカウントのPRへのレビュー依頼→差分→レビューと、条件変更時のマージ中断。
3. private repo / Organization / 通知 / 取消し / 期限切れの権限確認。
4. Xiaomi 14、320dp級、文字200%、TalkBack、回転・再起動、低速・オフライン・競合。
5. 本人が管理する同じ署名鍵でrelease APKを作り、インストールと更新。

#4 / #16 / #17に実証結果を記録し、満たされるまではEpic #1を閉じない。
