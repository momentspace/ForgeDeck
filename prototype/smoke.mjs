// Optional DOM-only interaction checks. This does not verify rendering or Android.
// npm install --prefix /tmp/forgedeck-qa happy-dom --ignore-scripts
// node prototype/smoke.mjs /tmp/forgedeck-qa/node_modules/happy-dom/lib/index.js
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

const { Window } = await import(pathToFileURL(process.argv[2]).href);
const w = new Window({ url: 'https://forgedeck.test/' });
w.document.write(await readFile(new URL('shell.html', import.meta.url), 'utf8'));
const d = w.document;
const modal = d.querySelector('#fd-dialog');
// happy-dom doesn't implement native dialog focus trapping.
modal.showModal = () => { modal.open = true; };
modal.close = () => { modal.open = false; };
w.eval(await readFile(new URL('app.js', import.meta.url), 'utf8'));
const q = selector => { const el=d.querySelector(selector); assert.ok(el,selector); return el; };
const click = selector => q(selector).click();
const input = (selector,value) => {const el=q(selector);el.value=value;el.dispatchEvent(new w.Event('input',{bubbles:true}));};
const text = () => d.querySelector('#fd-main').textContent;

assert.match(text(),/ピン留め/);
input('#fd-repo-search','Metis');
assert.equal(d.querySelectorAll('.fd-repo-card').length,1);
input('#fd-repo-search','not-existing');
assert.match(text(),/一致するリポジトリがありません/);
input('#fd-repo-search','');
click('[data-action="open-repo"][data-repo="ForgeDeck"]');
click('[data-action="open-file"][data-path="README.md"]');
assert.match(text(),/root.*README.md/s);
click('[data-action="edit-file"]');
const original=q('#fd-editor').value;
const changed=original+'\n追加のテスト行 <img src=x onerror=alert(1)>\n';
input('#fd-editor',changed);
click('[data-action="nav"][data-page="deck"]');
assert.ok(modal.open);
click('[data-action="keep-edit"]');
assert.equal(q('#fd-editor').value,changed);
click('[data-action="review-edit"]');
assert.match(text(),/追加のテスト行/);
assert.equal(d.querySelectorAll('img').length,0);
input('#fd-save-branch','main');
click('[data-action="save-pr"]');
assert.match(q('#fd-save-error').textContent,/未使用/);
input('#fd-save-branch','forgedeck/smoke-change');
click('[data-action="save-pr"]');
assert.match(text(),/チェック未確認/);
assert.ok(q('[data-action="merge-pr"]').disabled);
assert.ok(q('[data-action="review-pr"]').disabled);

click('[data-action="nav"][data-page="repos"]');
click('[data-action="open-repo"][data-repo="ForgeDeck"]');
click('[data-action="open-file"][data-path="README.md"]');
assert.doesNotMatch(text(),/追加のテスト行/); // Original branch is untouched.
click('[data-action="branch"]');
click('[data-action="select-branch"][data-branch="forgedeck/smoke-change"]');
click('[data-action="open-file"][data-path="README.md"]');
assert.match(text(),/追加のテスト行/);
assert.equal(d.querySelectorAll('img').length,0);

click('[data-action="nav"][data-page="deck"]');
click('[data-action="open-task"][data-id="142"]');
click('[data-action="detail-tab"][data-tab="diff"]');
assert.match(text(),/preferences.serverUrl/);
click('[data-action="review-pr"]');
click('[data-action="approve-review"]');
assert.ok(!q('[data-action="merge-pr"]').disabled);
click('[data-action="merge-pr"]');
assert.ok(modal.open);
assert.match(modal.textContent,/Squash merge/);
click('[data-action="confirm-merge"]');
assert.match(text(),/マージ済み/);

click('[data-action="nav"][data-page="deck"]');
click('[data-action="open-task"][data-id="144"]');
assert.ok(q('[data-action="review-pr"]').disabled);
assert.ok(q('[data-action="merge-pr"]').disabled);

click('[data-action="nav"][data-page="repos"]');
click('[data-action="open-repo"][data-repo="ForgeDeck"]');
click('[data-action="repo-tab"][data-tab="issues"]');
click('[data-action="new-issue"]');
input('#fd-issue-title','<img src=x onerror=alert(1)> 新しいIssue');
input('#fd-issue-body','本文 <script>alert(1)</script>');
click('[data-action="submit-issue"]');
assert.match(text(),/新しいIssue/);
assert.equal(d.querySelectorAll('img,script').length,0);
click('[data-action="comment"]');
input('#fd-comment-text','入力を保持してコメントできます');
click('[data-action="submit-comment"]');
assert.match(text(),/入力を保持してコメントできます/);

click('[data-action="nav"][data-page="inbox"]');
click('[data-action="read-all"]');
assert.ok(q('#fd-unread').hidden);
console.log('PASS: search, empty state, draft preservation, escaped input, branch isolation, PR checks/review/merge confirmation, self-review prevention, issue/comment, unread state.');
await w.happyDOM.close();
