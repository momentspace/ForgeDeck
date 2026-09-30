# ForgeDeck — 完成までの計画

作成日: 2026-09-30 JST。管理の入口は [Epic #1](https://github.com/momentspace/ForgeDeck/issues/1)。初期版は GitHub.com、単一アカウント、日本語、個人利用向けAndroid APK。Wiki / Projects は対象外。

## 実装順とゲート

| 段階 | 達成すること | 次へ進む条件 |
| --- | --- | --- |
| M0 | コンセプト・操作可能な画面サンプル | 情報構造と主要操作がレビュー可能 |
| M1 | Android基盤・認証実証・API層 | CIのAPKを起動でき、必要APIと安全な認証が実証済み |
| M2 | リポジトリとファイル操作 | 自repoを開き、変更を別ブランチとPRにできる |
| M3 | IssueとPRの基本機能 | 投稿・レビュー・確認付きマージが実データで通る |
| M4 | 行動別Deckと受信箱 | 分類理由・関連作業・未確認・通知が整合する |
| M5 | 実機品質と配布 | 主要フローの受入確認後に署名APKを配布 |

M0と後続のAndroid製造を区別する。依存は直列作業の待ち順であり、独立したUIや検証は可能な範囲で進められる。認証App登録、Organization承認、署名鍵、実機アカウントによる最終確認は環境固有の作業としてIssueに記録する。暦日ベースの完成日は、認証実証と最初の実機確認後に見積もる。

## 子Issue

| Issue | 作業 | 依存 |
| --- | --- | --- |
| [#2](https://github.com/momentspace/ForgeDeck/issues/2) | M0: コンセプトと操作可能な画面サンプルを作る | — |
| [#3](https://github.com/momentspace/ForgeDeck/issues/3) | M1: Androidの基盤とAPKビルドCIを用意する | #2 |
| [#4](https://github.com/momentspace/ForgeDeck/issues/4) | M1: GitHub認証・必要権限・通知APIの実証を行う | #3 |
| [#5](https://github.com/momentspace/ForgeDeck/issues/5) | M1: ログイン・権限確認・安全なトークン保存を実装する | #4 |
| [#6](https://github.com/momentspace/ForgeDeck/issues/6) | M1: GitHub API層・ページング・キャッシュ・失敗状態を作る | #5 |
| [#7](https://github.com/momentspace/ForgeDeck/issues/7) | M2: リポジトリ一覧・検索・ピン留め・最近使った場所を実装する | #6 |
| [#8](https://github.com/momentspace/ForgeDeck/issues/8) | M2: ブランチ・フォルダ・Markdown・コード閲覧を実装する | #7 |
| [#9](https://github.com/momentspace/ForgeDeck/issues/9) | M2: ファイル作成・編集・アップロード・移動・削除からPRを作る | #8 |
| [#10](https://github.com/momentspace/ForgeDeck/issues/10) | M3: Issueの一覧・作成・編集・コメント・状態変更を実装する | #6, #7 |
| [#11](https://github.com/momentspace/ForgeDeck/issues/11) | M3: PRの概要・差分・レビュー・CIを閲覧できるようにする | #8, #10 |
| [#12](https://github.com/momentspace/ForgeDeck/issues/12) | M3: PR作成・レビュー・安全なマージを実装する | #9, #11 |
| [#13](https://github.com/momentspace/ForgeDeck/issues/13) | M4: Issue/PRの次の一手と関連作業を分類する | #10, #11, #12 |
| [#14](https://github.com/momentspace/ForgeDeck/issues/14) | M4: 行動別Deckとつながった作業の画面を実装する | #13 |
| [#15](https://github.com/momentspace/ForgeDeck/issues/15) | M4: 通知の受信箱・既読操作・深いリンクを実装する | #4, #6, #10, #11 |
| [#16](https://github.com/momentspace/ForgeDeck/issues/16) | M5: 実機UX・アクセシビリティ・失敗時の品質を仕上げる | #14, #15 |
| [#17](https://github.com/momentspace/ForgeDeck/issues/17) | M5: 個人利用向けAndroid APKを配布して完成条件を満たす | #16 |

各Issueに受入条件がある。今回はM0のサンプルと計画を作成する。後続のIssueは未着手であり、計画の登録だけで完成扱いにしない。

## 製品の完成条件

- 実アカウントでログインし、許可された個人／Organizationのrepoを探して開ける。
- ファイルの閲覧・作成・編集・アップロード・移動・削除から、新ブランチとPRまで進める。
- Issueの作成・編集・コメント・状態変更、PRの差分・CI確認・レビュー・許可されたマージを行える。
- Deckの分類に根拠があり、unknown/部分取得を成功や全件と誤認させない。
- 関連Issue/PRと通知から必要な画面へ入り、戻ると文脈が復元する。
- 入力消失、二重投稿、競合の上書き、秘密の露出など重大問題がない。
- ライト/ダーク、文字拡大、TalkBack、小画面を実機で確認し、署名APKと使い方を配布する。

## リスクと対処

認証方式・通知API・Organization承認はM1で先に実証する。マージ権限やルールを取得できない場合は未確認表示にする。大量データはページングと取得範囲を表示する。ファイル書込みはSHAによる競合確認を行い、部分成功を再送で隠さない。

## 初期版の後に検討

Play公開、複数アカウント、Enterprise Server、即時push、AI支援は実利用で必要になったものから独立Issueを作る。Wiki / Projectsは今回の要求に従い後回し。

