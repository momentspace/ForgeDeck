(() => {
  'use strict';
  const root = document.getElementById('fd-app');
  if (!root) return;
  const main = root.querySelector('#fd-main');
  const dialog = root.querySelector('#fd-dialog');
  const dialogContent = root.querySelector('#fd-dialog-content');
  const esc = value => String(value ?? '').replace(/[&<>"']/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
  const icon = name => `<i data-lucide="${name}" aria-hidden="true"></i>`;
  const button = (action, label, cls = 'fd-secondary', attrs = '') => `<button class="${cls}" data-action="${action}" ${attrs}>${label}</button>`;
  const badge = (label, tone = '', glyph = '') => `<span class="fd-badge ${tone}">${glyph ? icon(glyph) : ''}${esc(label)}</span>`;
  const repos = [
    {id:'ForgeDeck',owner:'momentspace',desc:'迷わず使える GitHub クライアント',lang:'Kotlin',pin:true},
    {id:'Metis',owner:'momentspace',desc:'情報を集めて、整理して、使う',lang:'TypeScript',pin:true},
    {id:'vistract-work',owner:'momentspace',desc:'画像から、必要な情報だけを抽出',lang:'Kotlin',pin:false},
    {id:'ai-development-tools',owner:'momentspace',desc:'どこからでも開発を続ける道具',lang:'Shell',pin:false}
  ];
  const tasks = [
    {id:142,type:'pr',repo:'vistract-work',title:'接続先をアプリから変更できるようにする',lane:'mine',tag:'レビュー依頼',tone:'purple',reason:'あなたにレビューが依頼されています',author:'demo-dev',body:'設定画面からサーバー URL を変更できるようにしました。\n\n・入力チェックを追加\n・変更後は接続を確認\n・以前の設定は保持',checks:'pass',approved:false,link:120,comments:[{who:'demo-dev',text:'URL の保存とエラー表示を確認してもらえますか？'}],branch:'feature/server-url',diff:'settings/ServerConfig.kt'},
    {id:128,type:'issue',repo:'ForgeDeck',title:'リポジトリをすぐ開けるホームを作る',lane:'mine',tag:'あなたの担当',tone:'mint',reason:'あなたが担当者です · 関連PRのCIが失敗',author:'momentspace',body:'ピン留めしたリポジトリを、ホームから1タップで開けるようにする。\n\n受入条件\n・owner / repo が分かる\n・ピン留めと最近使った項目を表示\n・検索結果からすぐ開ける',link:144,comments:[]},
    {id:144,type:'pr',repo:'ForgeDeck',title:'ピン留めと最近使ったリポジトリを追加',lane:'mine',tag:'CI失敗',tone:'red',reason:'あなたのPRで、テストが1件失敗しています',author:'momentspace',body:'ホーム画面のリポジトリ一覧を実装しました。\n\nCloses #128\n\n並び順のテストが失敗しているため、修正してからレビューを依頼します。',checks:'fail',approved:false,link:128,comments:[],branch:'feature/repository-home',diff:'ui/RepositoryHome.kt'},
    {id:146,type:'pr',repo:'Metis',title:'Telegramの入力を保存する処理を整理',lane:'ready',tag:'マージ候補',tone:'mint',reason:'チェック成功 · 承認あり · 競合なし（サンプル）',author:'demo-dev',body:'Telegram の入力処理を整理しました。\n\n・重複保存を防止\n・失敗した入力を再実行可能に\n・既存テストは成功',checks:'pass',approved:true,comments:[{who:'momentspace',text:'確認しました。受入条件を満たしています。'}],branch:'refactor/telegram-input',diff:'src/telegram.ts'},
    {id:131,type:'issue',repo:'ai-development-tools',title:'外出先から開発を再開する手順を整理',lane:'waiting',tag:'返答待ち',tone:'amber',reason:'質問への返答を待っています · 更新から3日（サンプル）',author:'momentspace',body:'スマホから開発状況を確認し、必要な作業へ戻れるように手順をまとめます。',comments:[{who:'momentspace',text:'接続先を切り替える手順も、このIssueに含めますか？'}]}
  ];
  let nextId = 200;
  let recent = ['vistract-work','ForgeDeck','Metis'];
  const notifications = [{id:142,reason:'レビューを依頼されました',read:false},{id:144,reason:'CIが失敗しました',read:false},{id:131,reason:'あなたがコメントしました',read:true}];
  const initialFiles = {
    'README.md':'# ForgeDeck\n\nリポジトリに迷わず入れる GitHub クライアント。\n\n## 大切にすること\n\n- リポジトリをすぐ開く\n- ファイルの場所を見失わない\n- Issue / PR の次の一手が分かる\n\n## 開発\n\nKotlin + Jetpack Compose\n',
    'app/build.gradle.kts':'plugins {\n    id("com.android.application")\n    kotlin("android")\n}\n\nandroid {\n    namespace = "dev.forgedeck.app"\n}\n',
    'app/src/MainActivity.kt':'package dev.forgedeck.app\n\nimport androidx.activity.ComponentActivity\n\nclass MainActivity : ComponentActivity() {\n    // ここから画面を表示する\n}\n',
    'docs/CONCEPT.md':'# コンセプト\n\nどこにいるか、何ができるか、次に何をするかが分かる。\n\nWiki / Projects は初期スコープ外です。\n',
    '.gitignore':'.gradle/\nbuild/\nlocal.properties\n',
    'settings.gradle.kts':'rootProject.name = "ForgeDeck"\ninclude(":app")\n'
  };
  const branches = {};
  repos.forEach(repo => { branches[repo.id] = {main:{...initialFiles},develop:{...initialFiles,'docs/NOTES.md':'# 開発メモ\n\nこのブランチはサンプルです。\n'}}; });
  let state = {page:'repos',repo:'ForgeDeck',branch:'main',path:'',file:'README.md',repoTab:'code',query:'',repoFilter:'all',deckLane:'mine',deckRepo:'all',task:142,detailTab:'overview'};
  const history = [];
  let draft = null;
  let modalHandler = null;
  let previousFocus = null;
  const repo = () => repos.find(r => r.id === state.repo);
  const files = () => branches[state.repo][state.branch];
  const task = () => tasks.find(t => t.id === state.task);
  const title = (name,subtitle,action = '') => `<div class="fd-title-row"><div><h1>${esc(name)}</h1>${subtitle ? `<p class="fd-title-sub">${esc(subtitle)}</p>` : ''}</div>${action}</div>`;
  const scope = () => `<div class="fd-scope">${button('back',icon('chevron-left'),'fd-icon fd-back','aria-label="戻る"')}${button('switch-repo',esc(repo().owner)+' / '+esc(state.repo),'fd-context')}${button('switch-repo',icon('chevrons-up-down'),'fd-icon','aria-label="リポジトリを切り替える"')}</div>`;
  const empty = text => `<div class="fd-empty">${icon('inbox')}<p>${esc(text)}</p></div>`;
  function icons() { if (typeof lucide !== 'undefined') lucide.createIcons({attrs:{width:20,height:20}}); }
  function toast(text) { const el = root.querySelector('#fd-toast'); el.textContent = text; el.classList.add('visible'); }
  function closeModal() { dialog.close(); modalHandler = null; if (previousFocus?.isConnected) previousFocus.focus(); }
  function modal(heading,content,onAction) {
    previousFocus = document.activeElement;
    dialogContent.innerHTML = `<div class="fd-modal-head"><h2>${esc(heading)}</h2>${button('close-modal',icon('x'),'fd-icon','aria-label="閉じる"')}</div>${content}`;
    modalHandler = onAction;
    if (!dialog.open) dialog.showModal();
    icons();
  }
  function guard(change) {
    if (draft && (draft.operation !== 'edit' || draft.text !== draft.original)) {
      modal('編集を終了しますか？','<p class="fd-modal-text">まだ保存していない変更があります。</p><div class="fd-actions">'+button('keep-edit','編集を続ける')+button('discard-edit','変更を破棄','fd-secondary fd-danger')+'</div>', action => { if(action === 'keep-edit') closeModal(); if(action === 'discard-edit'){ draft = null; closeModal(); change(); } });
    } else { draft = null; change(); }
  }
  function navigate(patch,push = true) { guard(() => { if(push) history.push({...state}); state = {...state,...patch}; render(); }); }
  function back() { guard(() => { state = history.pop() || {...state,page:'repos',path:''}; render(); }); }
  function repoCard(r) { return `<div class="fd-repo-card"><button class="fd-repo-open" data-action="open-repo" data-repo="${esc(r.id)}"><span class="fd-repo-symbol">${icon('folder-git-2')}</span><span class="fd-repo-copy"><span class="fd-owner">${esc(r.owner)} /</span><span class="fd-repo-name" style="display:block">${esc(r.id)}</span><span class="fd-repo-desc" style="display:block">${esc(r.desc)}</span><span class="fd-meta"><span>${esc(r.lang)}</span><span>${icon('git-pull-request')}${tasks.filter(t=>t.repo===r.id&&t.type==='pr'&&!t.closed).length} PR</span><span>${icon('circle-dot')}${tasks.filter(t=>t.repo===r.id&&t.type==='issue'&&!t.closed).length} Issue</span></span></span></button><button class="fd-pin fd-icon" data-action="pin" data-repo="${esc(r.id)}" aria-pressed="${r.pin}" aria-label="${esc(r.id)}を${r.pin?'ピン留めから外す':'ピン留めする'}">${icon('pin')}</button></div>`; }
  function repositoryResults() {
    const list = repos.filter(r => `${r.owner}/${r.id}`.toLowerCase().includes(state.query.toLowerCase()) && (state.repoFilter !== 'pinned' || r.pin));
    if(!list.length) return empty('一致するリポジトリがありません');
    if(state.query || state.repoFilter==='pinned') return list.map(repoCard).join('');
    return `<div class="fd-section-head"><h2>ピン留め</h2><small>${repos.filter(r=>r.pin).length} リポジトリ</small></div>`+list.filter(r=>r.pin).map(repoCard).join('')+`<div class="fd-section-head"><h2>最近使った</h2></div>`+recent.map(id=>`<button class="fd-recent" data-action="open-repo" data-repo="${esc(id)}">${icon('folder-git-2')}<span><span class="fd-owner" style="display:block">momentspace /</span>${esc(id)}</span>${icon('chevron-right')}</button>`).join('')+`<div class="fd-section-head"><h2>すべてのリポジトリ</h2></div>`+list.filter(r=>!r.pin).map(repoCard).join('');
  }
  function repositoriesPage() { return title('リポジトリ','いつもの場所へ、すぐに。')+`<label class="fd-search-wrap">${icon('search')}<input id="fd-repo-search" aria-label="リポジトリを検索" placeholder="名前・オーナーで検索" value="${esc(state.query)}" autocomplete="off"></label><div class="fd-chips">${button('repo-filter','すべて','fd-chip',`data-filter="all" aria-pressed="${state.repoFilter==='all'}"`)}${button('repo-filter',icon('pin')+' ピン留め','fd-chip',`data-filter="pinned" aria-pressed="${state.repoFilter==='pinned'}"`)}</div><div id="fd-repository-results">${repositoryResults()}</div>`; }
  function breadcrumbs(includeFile = false) {
    const parts = state.path ? state.path.split('/') : [];
    return `<div class="fd-breadcrumbs" aria-label="ファイルの場所">${button('directory','root','',`data-path=""`)}${parts.map((part,index)=>`<span>/</span>${button('directory',esc(part),'',`data-path="${esc(parts.slice(0,index+1).join('/'))}"`)}`).join('')}${includeFile ? '<span>/</span><span>'+esc(state.file.split('/').pop())+'</span>' : ''}</div>`;
  }
  const branchControl = () => button('branch',icon('git-branch')+`<span>${esc(state.branch)}</span>`+icon('chevron-down'),'fd-branch');
  function fileList() {
    const prefix = state.path ? state.path+'/' : '';
    const entries = new Map();
    Object.keys(files()).filter(path=>path.startsWith(prefix)).forEach(path=>{ const relative=path.slice(prefix.length); const name=relative.split('/')[0]; entries.set(name,relative.includes('/')?'directory':'file'); });
    const sorted = [...entries].sort((a,b)=>(a[1]===b[1]?a[0].localeCompare(b[0]):a[1]==='directory'?-1:1));
    return sorted.length ? `<div class="fd-file-list">${sorted.map(([name,type])=>{const path=prefix+name;return `<div class="fd-file-row"><button class="fd-file-open" data-action="${type==='directory'?'directory':'open-file'}" data-path="${esc(path)}">${icon(type==='directory'?'folder':'file-text')}<span>${esc(name)}</span></button>${type==='file'?button('file-menu',icon('ellipsis'),'fd-icon fd-file-more',`data-path="${esc(path)}" aria-label="${esc(name)}の操作"`):''}</div>`;}).join('')}</div>` : empty('このフォルダにはファイルがありません');
  }
  function taskCard(t) {
    const relation = t.link ? tasks.find(x=>x.id===t.link&&x.repo===t.repo) : null;
    return `<article class="fd-task"><button class="fd-task-open" data-action="open-task" data-id="${t.id}"><span class="fd-task-top">${badge(t.closed?'完了':t.tag,t.closed?'':t.tone)}<span>${esc(t.repo)} #${t.id}</span></span><span class="fd-task-title">${icon(t.type==='pr'?'git-pull-request':'circle-dot')}<span>${esc(t.title)}</span></span><span class="fd-task-reason">${esc(t.reason)}</span></button>${relation?`<div class="fd-task-link">${icon('link')}関連 ${relation.type==='pr'?'PR':'Issue'} #${relation.id} · ${esc(relation.tag)}</div>`:''}</article>`;
  }
  function repositoryPage() {
    const tabs = `<div class="fd-segments" aria-label="リポジトリ内の表示">${['code','issues','prs'].map(tab=>button('repo-tab',{code:'ファイル',issues:'Issue',prs:'PR'}[tab],'',`data-tab="${tab}" aria-pressed="${state.repoTab===tab}"`)).join('')}</div>`;
    if(state.repoTab!=='code') return scope()+tabs+`<div class="fd-section-head"><h2>${state.repoTab==='issues'?'Issue':'Pull Request'}</h2>${state.repoTab==='issues'?button('new-issue','作成','fd-text-button'):''}</div>`+(tasks.filter(t=>t.repo===state.repo&&t.type===(state.repoTab==='issues'?'issue':'pr')).map(taskCard).join('')||empty('まだ項目がありません'));
    return scope()+tabs+`<div class="fd-toolbar">${branchControl()}${button('add-file',icon('plus')+' 追加','fd-secondary')}</div>`+breadcrumbs()+fileList()+`<div class="fd-callout"><strong>場所が見える、操作が分かる。</strong>ファイル名で開く。横のメニューから名前変更・削除。編集は差分を確認してPRへ。</div>`;
  }
  function markdown(text) { return text.split('\n\n').map(block=>block.startsWith('# ')?`<h2>${esc(block.slice(2))}</h2>`:block.startsWith('## ')?`<h3>${esc(block.slice(3))}</h3>`:`<p>${esc(block).replace(/\n/g,'<br>')}</p>`).join(''); }
  function filePage() { const content=files()[state.file] ?? ''; return scope()+`<div class="fd-toolbar">${branchControl()}${button('edit-file',icon('pencil')+' 編集','fd-primary')}</div>`+breadcrumbs(true)+`<div class="fd-file-content">${state.file.endsWith('.md')?markdown(content):`<pre class="fd-code">${esc(content)}</pre>`}</div><div class="fd-actions">${button('file-menu',icon('ellipsis')+' ファイル操作','fd-secondary',`data-path="${esc(state.file)}"`)}</div>`; }
  function editPage() { return scope()+`<div class="fd-eyebrow">ファイルを編集</div><h2 style="overflow-wrap:anywhere">${esc(draft.newPath)}</h2><p class="fd-title-sub">${esc(state.branch)} · 変更はまだ保存されていません</p><label class="fd-label" for="fd-editor">内容</label><textarea id="fd-editor" class="fd-editor" spellcheck="false">${esc(draft.text)}</textarea><div class="fd-actions">${button('cancel-edit','キャンセル')}${button('review-edit',icon('git-compare-arrows')+' 変更を確認','fd-primary')}</div>`; }
  function diff(oldText,newText) {
    const oldLines=oldText.split('\n'), newLines=newText.split('\n');
    let prefix=0,suffix=0;
    while(prefix<oldLines.length && prefix<newLines.length && oldLines[prefix]===newLines[prefix]) prefix++;
    while(suffix<oldLines.length-prefix && suffix<newLines.length-prefix && oldLines[oldLines.length-1-suffix]===newLines[newLines.length-1-suffix]) suffix++;
    const context=oldLines.slice(Math.max(0,prefix-2),prefix).map(line=>'  '+esc(line));
    const removed=oldLines.slice(prefix,oldLines.length-suffix).map(line=>`<span class="del">− ${esc(line)}</span>`);
    const added=newLines.slice(prefix,newLines.length-suffix).map(line=>`<span class="add">+ ${esc(line)}</span>`);
    return `<div class="fd-diff">${[...context,...removed,...added,...newLines.slice(newLines.length-suffix,newLines.length-suffix+2).map(line=>'  '+esc(line))].join('\n')}</div>`;
  }
  function reviewPage() { return scope()+`<div class="fd-eyebrow">変更を確認</div><h2 style="overflow-wrap:anywhere">${esc(draft.newPath || draft.oldPath)}</h2>${draft.operation==='rename'?`<p class="fd-title-sub">${esc(draft.oldPath)} から名前変更</p>`:''}${diff(draft.original,draft.text)}<label class="fd-label" for="fd-save-branch">新しいブランチ</label><input id="fd-save-branch" class="fd-field" value="${esc(draft.targetBranch)}"><label class="fd-label" for="fd-save-title">PRのタイトル</label><input id="fd-save-title" class="fd-field" value="${esc(draft.title)}"><div id="fd-save-error" class="fd-error" role="alert"></div><div class="fd-callout">${esc(state.branch)} への変更を提案します。保存すると、別ブランチとPRを作成します。</div><div class="fd-actions">${button('return-edit','編集へ戻る','fd-secondary',draft.operation==='delete'?'disabled':'')}${button('save-pr','PRを作成','fd-primary')}</div>`; }
  function deckPage() {
    const labels={mine:'自分の番',waiting:'待ち',ready:'マージ候補',all:'すべて'};
    const visible=tasks.filter(t=>!t.closed&&(state.deckRepo==='all'||t.repo===state.deckRepo));
    const list=visible.filter(t=>state.deckLane==='all'||t.lane===state.deckLane);
    return `<div class="fd-eyebrow">ACTION DECK</div>`+title('次の一手','IssueとPRを、やることから。',button('deck-help',icon('circle-help'),'fd-icon','aria-label="分類の根拠"'))+`<label class="fd-label" for="fd-deck-repo">リポジトリ</label><select id="fd-deck-repo" class="fd-field fd-repo-filter"><option value="all">すべてのリポジトリ</option>${repos.map(r=>`<option value="${esc(r.id)}" ${state.deckRepo===r.id?'selected':''}>${esc(r.owner+'/'+r.id)}</option>`).join('')}</select><div class="fd-chips">${Object.entries(labels).map(([key,label])=>button('deck-lane',esc(label)+(key==='all'?'':` <span>${visible.filter(t=>t.lane===key).length}</span>`),'fd-chip',`data-lane="${key}" aria-pressed="${state.deckLane===key}"`)).join('')}</div><div class="fd-lane-head">${icon(state.deckLane==='ready'?'git-merge':state.deckLane==='waiting'?'clock-3':'zap')}${esc(labels[state.deckLane])}<span class="fd-count">${list.length}</span></div>${list.length?list.map(taskCard).join(''):empty('ここで対応する項目はありません')}`;
  }
  function overview(t) {
    const linked=t.link?tasks.find(x=>x.id===t.link&&x.repo===t.repo):null;
    return `<div class="fd-callout"><strong>${esc(t.closed?'完了しました':t.tag)}</strong>${esc(t.reason)}</div><div class="fd-detail-body">${esc(t.body)}</div>${linked?`<div class="fd-section-head"><h2>つながっている作業</h2></div>${taskCard(linked)}`:''}${t.type==='pr'?`<div class="fd-check">${icon(t.approved?'badge-check':'user-round')}<div>${t.approved?'レビュー承認済み':'レビュー承認はまだありません'}<span>${t.approved?'momentspace が承認（サンプル）':'承認状況を確認してから次の操作へ'}</span></div></div><div class="fd-check">${icon('git-branch')}<div>${esc(t.base || 'main')} に変更を提案<span>${esc(t.branch)} → ${esc(t.base || 'main')}</span></div></div>`:''}<div class="fd-section-head"><h2>コメント</h2><small>${t.comments.length}</small></div>${t.comments.map(c=>`<div class="fd-comment"><span class="fd-person">${esc(c.who.slice(0,1))}</span><div><small>${esc(c.who)}</small><p>${esc(c.text)}</p></div></div>`).join('')}${button('comment',icon('message-square')+' コメントする','fd-secondary fd-full')}`;
  }
  function checks(t) {
    if(t.checks==='unknown') return `<div class="fd-callout"><strong>チェックは未確認です</strong>成功と推定しません。実アプリではGitHubから最新状態を取得します。</div>`;
    return ['build / Android','test / Unit tests','lint / Kotlin'].map((name,index)=>{const failed=t.checks==='fail'&&index===1;return `<div class="fd-check ${failed?'failed':''}">${icon(failed?'circle-x':'circle-check')}<div>${esc(name)}<span>${failed?'失敗 · 並び順のテストが不一致（サンプル）':'成功（サンプル）'}</span></div>${failed?'<pre class="fd-diff">Expected: pinned first\nActual: alphabetical</pre>':''}`;}).join('')+`<div class="fd-callout">デモのCI状態です。マージ前には、必須チェック・権限・最新のコミットを再確認します。</div>`;
  }
  function taskDetailPage() {
    const t=task();
    const tabs=t.type==='pr'?`<div class="fd-segments">${['overview','checks','diff'].map(tab=>button('detail-tab',{overview:'概要',checks:'チェック',diff:'差分'}[tab],'',`data-tab="${tab}" aria-pressed="${state.detailTab===tab}"`)).join('')}</div>`:'';
    let body=overview(t);
    if(state.detailTab==='checks') body=checks(t);
    if(state.detailTab==='diff') body=`<div class="fd-section-head"><h2>変更ファイル</h2><small>1 ファイル（デモ）</small></div><p style="font-size:14px;overflow-wrap:anywhere">${esc(t.diff || 'README.md')}</p>${t.demoDiff || diff('val serverUrl = DEFAULT_URL\nconnect(serverUrl)','val serverUrl = preferences.serverUrl\nrequire(serverUrl.startsWith("https://"))\nconnect(serverUrl)')}`;
    return scope()+`<div class="fd-row">${badge(t.closed?(t.merged?'マージ済み':'完了'):t.type==='pr'?'Pull Request':'Issue',t.closed?'':t.type==='pr'?'purple':'mint',t.type==='pr'?'git-pull-request':'circle-dot')}<small>#${t.id} · ${esc(t.author)}</small></div><div class="fd-detail-heading">${esc(t.title)}</div>${tabs}${body}${!t.closed?`<div class="fd-actions">${t.type==='pr'?button('review-pr',t.author==='momentspace'?'自分のPRは承認不可':'レビューを書く','fd-secondary',t.author==='momentspace'?'disabled':'')+button('merge-pr','マージを確認','fd-primary',t.lane!=='ready'?'disabled aria-label="マージ候補ではないため操作できません"':''):button('close-issue','Issueを閉じる','fd-secondary')}</div>`:''}`;
  }
  function inboxPage() { return title('受信箱','あなたに届いた更新。',button('read-all','すべて既読','fd-text-button'))+notifications.map(n=>{const t=tasks.find(t=>t.id===n.id);return `<button class="fd-notification ${n.read?'read':''}" data-action="notification" data-id="${n.id}">${icon(t.type==='pr'?'git-pull-request':'circle-dot')}<div><small>${esc(t.repo)} #${t.id}</small><strong>${esc(t.title)}</strong><span class="fd-title-sub">${esc(n.reason)}${n.read?' · 既読':' · 未読'}</span></div></button>`;}).join(''); }
  function render() {
    const renderers={repos:repositoriesPage,repo:repositoryPage,file:filePage,edit:editPage,save:reviewPage,deck:deckPage,detail:taskDetailPage,inbox:inboxPage};
    main.innerHTML = renderers[state.page]();
    const active=['deck','detail'].includes(state.page)?'deck':state.page==='inbox'?'inbox':'repos';
    root.querySelectorAll('.fd-nav button').forEach(b=>{ if(b.dataset.page===active) b.setAttribute('aria-current','page'); else b.removeAttribute('aria-current'); });
    const unread=notifications.filter(n=>!n.read).length;
    root.querySelector('#fd-unread').textContent=unread || '';
    root.querySelector('#fd-unread').hidden=!unread;
    root.querySelector('#fd-toast').classList.remove('visible');
    icons();
  }
  function openRepo(id) {
    recent = [id,...recent.filter(r=>r!==id)].slice(0,3);
    navigate({page:'repo',repo:id,path:'',repoTab:'code',branch:branches[id][state.branch]?state.branch:'main'});
  }
  function openTask(id) { const selected=tasks.find(t=>t.id===id); navigate({page:'detail',task:id,repo:selected.repo,branch:'main',detailTab:'overview'}); }
  function startDraft(operation,oldPath,newPath,text) {
    history.push({...state});
    draft={operation,oldPath,newPath,original:oldPath?files()[oldPath]:'',text,targetBranch:'forgedeck/change-'+nextId,title:({edit:'更新: ',new:'追加: ',rename:'名前変更: ',delete:'削除: '})[operation]+(newPath||oldPath)};
    state.page=operation==='delete'?'save':'edit';
    render();
  }
  function validPath(path) { return path && !path.startsWith('/') && !path.split('/').some(p=>!p||p==='.'||p==='..') && !/[\\\u0000-\u001f]/.test(path); }
  function fileMenu(path) {
    modal(path,button('menu-edit',icon('pencil')+' 編集','fd-menu-item')+button('menu-rename',icon('text-cursor-input')+' 名前・場所を変更','fd-menu-item')+button('menu-delete',icon('trash-2')+' 削除を提案','fd-menu-item fd-danger'),action=>{
      if(action==='menu-edit'){ closeModal(); navigate({page:'file',file:path,path:path.includes('/')?path.slice(0,path.lastIndexOf('/')):''}); startDraft('edit',path,path,files()[path]); }
      if(action==='menu-rename') modal('名前・場所を変更',`<label class="fd-label" for="fd-new-path">新しいパス</label><input id="fd-new-path" class="fd-field" value="${esc(path)}"><div id="fd-modal-error" class="fd-error" role="alert"></div><div class="fd-actions">${button('do-rename','差分を確認','fd-primary')}</div>`,inner=>{ if(inner==='do-rename'){const next=dialog.querySelector('#fd-new-path').value.trim();if(!validPath(next)||next===path||Object.hasOwn(files(),next)){dialog.querySelector('#fd-modal-error').textContent='未使用の相対パスを入力してください。';return;}closeModal();startDraft('rename',path,next,files()[path]);state.page='save';render();}});
      if(action==='menu-delete') modal('ファイルの削除を提案',`<p class="fd-modal-text">${esc(path)} を削除する差分を確認します。まだ削除は実行しません。</p><div class="fd-actions">${button('do-delete','削除の差分を確認','fd-secondary fd-danger')}</div>`,inner=>{if(inner==='do-delete'){closeModal();startDraft('delete',path,'','');}});
    });
  }
  function addFile() {
    modal('ファイルを追加',`<label class="fd-label" for="fd-add-path">ファイルのパス</label><input id="fd-add-path" class="fd-field" placeholder="docs/guide.md" value="${esc(state.path?state.path+'/':'')}"><div id="fd-modal-error" class="fd-error" role="alert"></div><div class="fd-actions">${button('do-new-file','新しく書く','fd-primary')}${button('upload-file','テキストを選ぶ')}</div><input id="fd-upload" type="file" accept=".txt,.md,.kt,.kts,.json,.yml,.yaml,.css,.html,.js,.ts,.sh" hidden><p class="fd-modal-text" style="margin-top:12px">デモは1MB以下のUTF-8テキストに対応しています。</p>`,action=>{
      const path=dialog.querySelector('#fd-add-path').value.trim();
      if(action==='do-new-file'){if(!validPath(path)||Object.hasOwn(files(),path)){dialog.querySelector('#fd-modal-error').textContent='未使用の相対パスを入力してください。';return;}closeModal();startDraft('new','',path,'');}
      if(action==='upload-file') dialog.querySelector('#fd-upload').click();
    });
    dialog.querySelector('#fd-upload').addEventListener('change', async event=>{
      const file=event.target.files[0]; if(!file) return;
      if(file.size>1024*1024){dialog.querySelector('#fd-modal-error').textContent='1MB以下のテキストを選んでください。';return;}
      const path=dialog.querySelector('#fd-add-path').value.trim() || (state.path?state.path+'/':'')+file.name;
      if(!validPath(path)||Object.hasOwn(files(),path)){dialog.querySelector('#fd-modal-error').textContent='未使用の相対パスを入力してください。';return;}
      const text=await file.text();if(text.includes('\u0000')||text.includes('\ufffd')){dialog.querySelector('#fd-modal-error').textContent='UTF-8テキストとして読み取れません。';return;}
      closeModal();startDraft('new','',path,text);
    });
  }
  function savePR() {
    const target=main.querySelector('#fd-save-branch').value.trim(), prTitle=main.querySelector('#fd-save-title').value.trim();
    if(!/^[a-zA-Z0-9][a-zA-Z0-9_/-]*$/.test(target)||target.includes('..')||target.includes('//')||target.endsWith('/')||Object.hasOwn(branches[state.repo],target)||!prTitle){main.querySelector('#fd-save-error').textContent='未使用のブランチ名とPRタイトルを入力してください。';return;}
    const next={...files()};if(draft.operation==='rename'||draft.operation==='delete') delete next[draft.oldPath];if(draft.operation!=='delete') next[draft.newPath]=draft.text;
    branches[state.repo][target]=next;
    const id=nextId++;
    tasks.unshift({id,type:'pr',repo:state.repo,title:prTitle,lane:'mine',tag:'チェック未確認',tone:'amber',reason:'作成したPRのチェックとレビューを確認してください',author:'momentspace',body:`${draft.operation} による ${draft.newPath||draft.oldPath} の変更。\n\nこのPRはデモです。GitHubへの送信はありません。`,checks:'unknown',approved:false,comments:[],branch:target,base:state.branch,diff:draft.newPath||draft.oldPath,demoDiff:diff(draft.original,draft.text)});
    draft=null;history.push({...state,page:'repo',repoTab:'code'});state={...state,page:'detail',task:id,detailTab:'overview'};render();toast('PR #'+id+' を作成しました（デモ）');
  }
  function newIssue() {
    modal('Issueを作成',`<p class="fd-modal-text">${esc(repo().owner+'/'+state.repo)}</p><label class="fd-label" for="fd-issue-title">タイトル</label><input id="fd-issue-title" class="fd-field" placeholder="何を解決したい？"><label class="fd-label" for="fd-issue-body">内容</label><textarea id="fd-issue-body" class="fd-field" rows="4" placeholder="現状と、どうなったらよいか"></textarea><div id="fd-modal-error" class="fd-error" role="alert"></div><div class="fd-actions">${button('submit-issue','Issueを作成','fd-primary')}</div>`,action=>{if(action==='submit-issue'){const heading=dialog.querySelector('#fd-issue-title').value.trim(), body=dialog.querySelector('#fd-issue-body').value.trim();if(!heading){dialog.querySelector('#fd-modal-error').textContent='タイトルを入力してください。';return;}const id=nextId++;tasks.unshift({id,type:'issue',repo:state.repo,title:heading,body,lane:'mine',tag:'あなたの担当',tone:'mint',reason:'あなたが作成・担当しています（デモ）',author:'momentspace',comments:[]});closeModal();openTask(id);toast('Issueを作成しました（デモ）');}});
  }
  const actions = {
    home:()=>navigate({page:'repos'}), back,
    nav:el=>navigate({page:el.dataset.page},true),
    appearance:()=>{root.style.colorScheme=root.style.colorScheme==='dark'?'light':'dark';toast(root.style.colorScheme==='dark'?'ダーク表示にしました':'ライト表示にしました');},
    account:()=>modal('momentspace','<p class="fd-modal-text">サンプルアカウントです。ログイン情報は使っていません。実アプリでは権限確認とログアウトをここに置きます。</p>'),
    'open-repo':el=>openRepo(el.dataset.repo),
    pin:el=>{const r=repos.find(r=>r.id===el.dataset.repo);r.pin=!r.pin;render();toast(r.pin?'ピン留めしました':'ピン留めを外しました');},
    'repo-filter':el=>{state.repoFilter=el.dataset.filter;render();},
    'repo-tab':el=>{state.repoTab=el.dataset.tab;state.path='';render();},
    directory:el=>navigate({page:'repo',repoTab:'code',path:el.dataset.path}),
    'open-file':el=>navigate({page:'file',file:el.dataset.path}),
    'file-menu':el=>fileMenu(el.dataset.path),
    'edit-file':()=>startDraft('edit',state.file,state.file,files()[state.file]),
    'cancel-edit':back,
    'review-edit':()=>{if(draft.text===draft.original&&draft.operation==='edit'){toast('変更はありません');return;}state.page='save';render();},
    'return-edit':()=>{draft.targetBranch=main.querySelector('#fd-save-branch').value;draft.title=main.querySelector('#fd-save-title').value;state.page='edit';render();},
    'save-pr':savePR, 'add-file':addFile, 'new-issue':newIssue,
    branch:()=>modal('ブランチを選ぶ',Object.keys(branches[state.repo]).map(name=>button('select-branch',icon('git-branch')+' '+esc(name),'fd-menu-item',`data-branch="${esc(name)}"`)).join(''),(action,el)=>{if(action==='select-branch'){const chosen=el.dataset.branch;closeModal();navigate({branch:chosen,path:'',page:'repo',repoTab:'code'});}}),
    'switch-repo':()=>modal('リポジトリを切り替える',repos.map(r=>button('select-repo',icon('folder-git-2')+' '+esc(r.owner+'/'+r.id),'fd-menu-item',`data-repo="${esc(r.id)}"`)).join(''),(action,el)=>{if(action==='select-repo'){closeModal();openRepo(el.dataset.repo);}}),
    'deck-lane':el=>{state.deckLane=el.dataset.lane;render();},
    'deck-help':()=>modal('なぜ、この分類？','<p class="fd-modal-text">自分の番：レビュー依頼・担当Issue・自分のPRのCI失敗／未確認。\n\n待ち：自分の質問やPRに対する返答／レビュー待ち。\n\nマージ候補：必須チェック、承認、競合、権限が確認できたPR。未確認を成功扱いしません。\n\n関連作業は明示されたリンクで結びます。AIによる推測や自動マージは使いません。</p>'),
    'open-task':el=>openTask(Number(el.dataset.id)),
    'detail-tab':el=>{state.detailTab=el.dataset.tab;render();},
    comment:()=>modal('コメントする','<label class="fd-label" for="fd-comment-text">内容</label><textarea id="fd-comment-text" class="fd-field" rows="4" placeholder="コメントを入力"></textarea><div id="fd-modal-error" class="fd-error" role="alert"></div><div class="fd-actions">'+button('submit-comment','コメントを追加','fd-primary')+'</div>',action=>{if(action==='submit-comment'){const text=dialog.querySelector('#fd-comment-text').value.trim();if(!text){dialog.querySelector('#fd-modal-error').textContent='内容を入力してください。';return;}task().comments.push({who:'momentspace',text});closeModal();render();toast('コメントを追加しました（デモ）');}}),
    'review-pr':()=>{if(task().author==='momentspace')return;modal('レビューを書く','<p class="fd-modal-text">差分を確認して、レビュー結果を選んでください。</p><label class="fd-label" for="fd-review-text">コメント</label><textarea id="fd-review-text" class="fd-field" rows="3"></textarea><div class="fd-actions">'+button('approve-review','承認','fd-primary')+button('request-changes','修正を依頼')+'</div>',action=>{if(!['approve-review','request-changes'].includes(action))return;const t=task();const text=dialog.querySelector('#fd-review-text').value.trim();t.comments.push({who:'momentspace',text:(action==='approve-review'?'承認しました。':'修正を依頼しました。')+(text?'\n'+text:'')});t.approved=action==='approve-review';if(t.approved&&t.checks==='pass'){t.lane='ready';t.tag='マージ候補';t.tone='mint';t.reason='チェック成功 · 承認あり · 競合なし（サンプル）';}else if(!t.approved){t.lane='waiting';t.tag='修正待ち';t.tone='amber';t.reason='作者に修正を依頼しました';}closeModal();render();toast('レビューを記録しました（デモ）');});},
    'merge-pr':()=>{const t=task();if(t.lane!=='ready')return;modal('PRのマージを確認',`<p class="fd-modal-text">${esc(t.repo)} #${t.id}\n${esc(t.branch)} → ${esc(t.base || 'main')}\n\nSquash merge でまとめます。この操作はデモです。</p><div class="fd-callout"><strong>実アプリでは送信直前に再確認</strong>必須チェック・承認・競合・権限・head SHA が変わった場合は中断します。</div><div class="fd-actions">${button('confirm-merge','マージする','fd-primary')}</div>`,action=>{if(action==='confirm-merge'){t.closed=true;t.merged=true;t.reason='マージしました（デモ）';closeModal();render();toast('PRをマージしました（デモ）');}});},
    'close-issue':()=>modal('Issueを閉じますか？','<p class="fd-modal-text">完了として閉じます。この操作はデモです。</p><div class="fd-actions">'+button('confirm-close','完了として閉じる','fd-primary')+'</div>',action=>{if(action==='confirm-close'){task().closed=true;task().reason='完了として閉じました（デモ）';closeModal();render();}}),
    notification:el=>{notifications.find(n=>n.id===Number(el.dataset.id)).read=true;openTask(Number(el.dataset.id));},
    'read-all':()=>{notifications.forEach(n=>n.read=true);render();toast('すべて既読にしました（デモ）');}
  };
  root.addEventListener('click',event=>{
    const el=event.target.closest('button[data-action]');if(!el||el.disabled)return;
    const action=el.dataset.action;
    if(action==='close-modal'){closeModal();return;}
    if(dialog.open && dialog.contains(el)){if(modalHandler)modalHandler(action,el);return;}
    actions[action]?.(el);
  });
  root.addEventListener('input',event=>{
    if(event.target.id==='fd-repo-search'){state.query=event.target.value;main.querySelector('#fd-repository-results').innerHTML=repositoryResults();icons();}
    if(event.target.id==='fd-editor'&&draft)draft.text=event.target.value;
  });
  root.addEventListener('change',event=>{if(event.target.id==='fd-deck-repo'){state.deckRepo=event.target.value;render();}});
  dialog.addEventListener('cancel',event=>{event.preventDefault();closeModal();});
  render();
})();
