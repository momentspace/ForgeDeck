@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.forgedeck.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity:ComponentActivity(){
    private lateinit var vm:ForgeViewModel
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        val store=LocalStore(applicationContext)
        vm=ViewModelProvider(this,object:ViewModelProvider.Factory{
            @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T=ForgeViewModel(store) as T
        })[ForgeViewModel::class.java]
        setContent{ForgeApp(vm)}
        intent?.dataString?.let(vm::deepLink)
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);intent.dataString?.let(vm::deepLink)}
}
@Composable fun ForgeApp(vm:ForgeViewModel){
    val s by vm.ui.collectAsStateWithLifecycle()
    val dark=when(s.theme){"dark"->true;"light"->false;else->isSystemInDarkTheme()}
    val colors=if(dark)darkColorScheme(primary=Color(0xFF78EDC5),background=Color(0xFF10161E),surface=Color(0xFF19212C),onSurface=Color(0xFFEDF3FA),onPrimary=Color(0xFF06241D))else lightColorScheme(primary=Color(0xFF006A51),background=Color(0xFFF6F8FC),surface=Color.White,onSurface=Color(0xFF172331))
    MaterialTheme(colorScheme=colors){
        var modal by rememberSaveable{mutableStateOf("")}
        var confirmation by remember{mutableStateOf<Pair<String,()->Unit>?>(null)}
        var deferred by remember{mutableStateOf<(() -> Unit)?>(null)}
        fun move(action:()->Unit){if(s.writing)return;if(s.route.page==Page.EDITOR&&s.editor?.dirty==true)deferred=action else action()}
        BackHandler(s.route.page !in setOf(Page.REPOS,Page.DECK,Page.INBOX)){move(vm::back)}
        val context=LocalContext.current
        val scope=rememberCoroutineScope()
        fun web(url:String){
            val uri=Uri.parse(url);if(uri.scheme!="https"||uri.host.isNullOrBlank()||uri.userInfo!=null)return
            val browser=Intent(Intent.ACTION_VIEW,Uri.parse("https://example.com")).resolveActivity(context.packageManager)
            val intent=Intent(Intent.ACTION_VIEW,uri).addCategory(Intent.CATEGORY_BROWSABLE)
            if(browser!=null&&browser.packageName!=context.packageName&&browser.packageName!="android")intent.setPackage(browser.packageName)
            runCatching{context.startActivity(intent)}.onFailure{vm.report("ブラウザで開けませんでした")}
        }
        val upload=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)scope.launch{
            try{
                val (name,text)=withContext(Dispatchers.IO){
                    val name=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{c->if(c.moveToFirst())c.getString(0)else"upload.txt"} ?: "upload.txt"
                    val out=java.io.ByteArrayOutputStream()
                    context.contentResolver.openInputStream(uri)?.use{input->val buffer=ByteArray(8192);while(true){val count=input.read(buffer);if(count<0)break;check(out.size()+count<=1_000_000){"1MB以下のUTF-8テキストを選んでください"};out.write(buffer,0,count)}} ?: error("ファイルを読めません")
                    val text=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString()
                    check(!text.contains('\u0000')){"バイナリはアップロードできません"};name to text
                };vm.beginEdit("new",name,text)
            }catch(e:Exception){vm.report(e.message ?: "ファイルを読めません")}
        }}
        val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")){uri->if(uri!=null)scope.launch{try{withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri)?.use{it.write(s.document?.text.orEmpty().toByteArray(Charsets.UTF_8))} ?: error("保存先を開けません")}}catch(e:Exception){vm.report("ファイルを保存できません")}}}
        val holder=rememberSaveableStateHolder()
        Scaffold(topBar={Column{
            TopAppBar(title={Text("ForgeDeck",fontWeight=FontWeight.SemiBold)},navigationIcon={if(s.route.page !in setOf(Page.REPOS,Page.DECK,Page.INBOX))IconButton(onClick={move(vm::back)},enabled=!s.writing){Icon(Icons.AutoMirrored.Filled.ArrowBack,"戻る")}},actions={
                if(s.login.isNotEmpty())IconButton(onClick=vm::refresh,enabled=!s.busy&&s.route.page!=Page.EDITOR){Icon(Icons.Default.Refresh,"更新")}
                IconButton(onClick={modal="account"},enabled=!s.writing){Icon(Icons.Default.AccountCircle,"アカウントと外観")}
            })
            if(s.route.repo.isNotEmpty())Text(s.route.repo,Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=6.dp),color=colors.primary)
            if(s.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        }},bottomBar={if(s.login.isNotEmpty())NavigationBar{
            listOf(Page.REPOS,Page.DECK,Page.INBOX).forEach{page->NavigationBarItem(selected=s.tab==page,onClick={move{vm.home(page)}},enabled=!s.writing,icon={Icon(when(page){Page.REPOS->Icons.Default.Folder;Page.DECK->Icons.Default.Dashboard;else->Icons.Default.Inbox},null)},label={Text(when(page){Page.REPOS->"リポジトリ";Page.DECK->"Deck";else->"受信箱"})})}
        }}){padding->Column(Modifier.fillMaxSize().padding(padding)){
            if(s.error.isNotBlank())Message(s.error,true,vm::clearError)
            if(s.notice.isNotBlank())Message(s.notice,false,vm::clearError)
            s.cacheTime?.let{Text("キャッシュ · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it))} · 書込みは接続時のみ",Modifier.padding(12.dp),style=MaterialTheme.typography.bodySmall)}
            s.pending?.let{p->Card(Modifier.padding(12.dp)){Column(Modifier.padding(12.dp)){Text("${p.repo} · ${p.head} は保存済み");Text("PRは未完了です。重複を確認してから作成します。");Button(onClick=vm::retryPending,enabled=!s.busy){Text("PRを確認・作成")}}}}
            Box(Modifier.weight(1f).fillMaxWidth()){
                if(s.login.isEmpty())LoginScreen(s,vm,::web)else holder.SaveableStateProvider(s.route.toString()){
                    when(s.route.page){
                        Page.REPOS->ReposScreen(s,vm)
                        Page.FILES->FilesScreen(s,vm,{vm.loadBranches();modal="branches"},{modal=it},{upload.launch(arrayOf("text/*","application/json","application/xml"))})
                        Page.FILE->FileScreen(s,vm,{vm.loadBranches();modal="branches"},{save.launch(s.document?.path?.substringAfterLast('/') ?: "file.txt")},::web)
                        Page.EDITOR->EditorScreen(s,vm){confirmation="${s.editor?.repo}\n${s.editor?.base} → ${s.editor?.target}\n変更を新しいブランチへ保存してPRを作成します。" to vm::saveEditor}
                        Page.ITEM->DetailScreen(s,vm,{modal=it},{label,action->confirmation=label to action},::web)
                        Page.DECK->DeckScreen(s,vm)
                        Page.INBOX->InboxScreen(s,vm){confirmation="GitHub上のすべての通知を既読にします。" to vm::allRead}
                    }
                }
            }
        }}
        if(deferred!=null)AlertDialog(onDismissRequest={deferred=null},title={Text("編集を終了しますか？")},text={Text("下書きは端末に保存しています。破棄する場合は編集画面の「下書きを破棄」を使ってください。")},confirmButton={TextButton(onClick={val action=deferred;deferred=null;action?.invoke()}){Text("下書きを残して移動")}},dismissButton={TextButton(onClick={deferred=null}){Text("編集を続ける")}})
        confirmation?.let{(label,action)->AlertDialog(onDismissRequest={if(!s.writing)confirmation=null},title={Text("操作を確認")},text={Text(label)},confirmButton={TextButton(onClick={confirmation=null;action()},enabled=!s.busy){Text("実行する")}},dismissButton={TextButton(onClick={confirmation=null}){Text("キャンセル")}})}
        when(modal){
            "account"->AlertDialog(onDismissRequest={modal=""},title={Text(s.login.ifEmpty{"アカウント"})},text={Column{Text("トークンはKeystoreの鍵で暗号化して保存します。外部AIには送信しません。");Text("外観",Modifier.padding(top=12.dp));listOf("system" to "端末に合わせる","light" to "ライト","dark" to "ダーク").forEach{(value,label)->Row(Modifier.clickable{vm.theme(value)}.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){RadioButton(s.theme==value,{vm.theme(value)});Text(label)}}}},confirmButton={TextButton(onClick={modal=""}){Text("閉じる")}},dismissButton={if(s.login.isNotEmpty())TextButton(onClick={modal="";confirmation="認証情報、下書き、キャッシュ、ピン留めを端末から削除します。" to vm::logout}){Text("ログアウト")}})
            "branches"->AlertDialog(onDismissRequest={modal=""},title={Text("ブランチを選ぶ")},text={LazyColumn{items(s.branches){branch->TextButton(onClick={modal="";vm.chooseBranch(branch)},modifier=Modifier.fillMaxWidth()){Text(branch)}};if(s.branchesMore)item{TextButton(onClick={vm.loadBranches(true)},enabled=!s.busy){Text("さらに取得")}}}},confirmButton={TextButton(onClick={modal=""}){Text("閉じる")}})
            "new-issue","edit-issue","new-pr","comment","review"->ActionForm(modal,s,vm){modal=""}
        }
    }
}
@Composable fun Message(text:String,error:Boolean,dismiss:()->Unit){Surface(color=if(error)MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer){Row(Modifier.fillMaxWidth().padding(10.dp),verticalAlignment=Alignment.CenterVertically){Text(text,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall);IconButton(onClick=dismiss){Icon(Icons.Default.Close,"閉じる")}}}}
@Composable fun Badge(text:String,error:Boolean=false){Surface(color=if(error)MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,shape=RoundedCornerShape(8.dp)){Text(text,Modifier.padding(horizontal=8.dp,vertical=4.dp),style=MaterialTheme.typography.labelMedium)}}
@Composable fun Empty(text:String){Column(Modifier.fillMaxWidth().padding(30.dp),horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.Default.Inbox,null);Text(text,Modifier.padding(top=12.dp))}}
@Composable fun LoginScreen(s:UiState,vm:ForgeViewModel,web:(String)->Unit){
    var token by remember{mutableStateOf("")};var client by rememberSaveable{mutableStateOf("")}
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
        Text("いつものリポジトリへ。",style=MaterialTheme.typography.headlineMedium);Text("GitHub.comの本人のアカウントを接続します。トークンはパスワードと同じように扱ってください。")
        OutlinedTextField(token,{token=it},label={Text("GitHubトークン")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!s.busy)
        Button(onClick={vm.login(token);token=""},enabled=token.isNotBlank()&&!s.busy,modifier=Modifier.fillMaxWidth()){Text("アカウントを確認して接続")}
        TextButton(onClick={web("https://github.com/settings/personal-access-tokens/new")}){Text("トークンを発行する")}
        Text("選んだrepoにContents・Issues・Pull requestsの読取り／書込み、Checks・Commit statuses・Metadataの読取りを付けます。通知・Organization・ルールの取得は認証方式で制限されます。使えない機能は理由を表示します。",style=MaterialTheme.typography.bodySmall)
        HorizontalDivider();Text("登録済みGitHub Appで接続",style=MaterialTheme.typography.titleMedium)
        OutlinedTextField(client,{client=it},label={Text("GitHub AppのClient ID")},modifier=Modifier.fillMaxWidth(),enabled=s.deviceCode.isEmpty())
        Text("Device Flowを有効にしたAppが必要です。client secretは不要。失効したら再認証します。",style=MaterialTheme.typography.bodySmall)
        if(s.deviceCode.isEmpty())OutlinedButton(onClick={vm.startDeviceFlow(client)},enabled=client.isNotBlank()&&!s.busy){Text("ブラウザで認証")}
        else{SelectionContainer{Text(s.deviceCode,style=MaterialTheme.typography.headlineMedium)};Button(onClick={web(s.deviceUrl)}){Text("GitHubでコードを入力")};TextButton(onClick=vm::cancelDeviceFlow){Text("認証を中止")}}
    }
}
@Composable fun ReposScreen(s:UiState,vm:ForgeViewModel){
    var query by rememberSaveable{mutableStateOf("")}
    val found=s.repos.filter{it.fullName.contains(query,true)};val pinned=found.filter{it.fullName in s.pins};val recent=s.recent.mapNotNull{name->found.find{it.fullName==name&&name !in s.pins}}
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        item{Text("リポジトリ",style=MaterialTheme.typography.headlineLarge)}
        item{OutlinedTextField(query,{query=it},label={Text("取得済みの名前・オーナーで検索")},singleLine=true,modifier=Modifier.fillMaxWidth())}
        item{FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(onClick={vm.searchRepo(query)},enabled=!s.busy&&query.isNotBlank()){Text("GitHubでも検索")};if(s.repoQuery.isNotBlank())TextButton(onClick={query="";vm.searchRepo("")}){Text("自分の一覧へ")}}}
        if(pinned.isNotEmpty()){item{Text("ピン留め",style=MaterialTheme.typography.titleMedium)};items(pinned,key={"pin-${it.fullName}"}){RepoCard(it,s,vm)}}
        if(recent.isNotEmpty()){item{Text("最近使った",style=MaterialTheme.typography.titleMedium)};items(recent,key={"recent-${it.fullName}"}){RepoCard(it,s,vm)}}
        item{Text("取得済み ${s.repos.size}件${if(s.more)" · 続きあり"else""}",style=MaterialTheme.typography.bodySmall)}
        items(found.filter{it !in pinned&&it !in recent},key={it.fullName}){RepoCard(it,s,vm)}
        if(found.isEmpty()&&!s.busy)item{Empty("該当するリポジトリがありません。権限と取得済み範囲も確認してください。")}
        if(s.more)item{OutlinedButton(onClick=vm::more,enabled=!s.busy,modifier=Modifier.fillMaxWidth()){Text("さらに取得")}}
    }
}
@Composable fun RepoCard(r:Repo,s:UiState,vm:ForgeViewModel){Card(onClick={vm.openRepo(r.fullName)},modifier=Modifier.fillMaxWidth(),enabled=!s.writing){Row(Modifier.padding(16.dp),verticalAlignment=Alignment.Top){Icon(Icons.Default.Folder,null,tint=MaterialTheme.colorScheme.primary);Column(Modifier.weight(1f).padding(horizontal=12.dp)){Text(r.owner,style=MaterialTheme.typography.bodySmall);Text(r.name,style=MaterialTheme.typography.titleLarge);if(r.description.isNotEmpty())Text(r.description);FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){if(r.private)Badge("Private");if(r.language.isNotEmpty())Text(r.language,style=MaterialTheme.typography.bodySmall)}};IconButton(onClick={vm.pin(r.fullName)}){Icon(Icons.Default.PushPin,if(r.fullName in s.pins)"ピン留めを外す"else"ピン留め",tint=if(r.fullName in s.pins)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)}}}}
@Composable fun FilesScreen(s:UiState,vm:ForgeViewModel,branches:()->Unit,form:(String)->Unit,upload:()->Unit){
    var search by rememberSaveable{mutableStateOf("")}
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(onClick=branches,enabled=!s.busy){Text(s.route.branch)};if(s.selectedRepo?.canPush==true){TextButton(onClick={vm.beginEdit("new")},enabled=!s.busy){Text("新規ファイル")};TextButton(onClick=upload,enabled=!s.busy){Text("アップロード")}}}
        item{FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("files" to "ファイル","issue" to "Issue","pr" to "PR").forEach{(key,label)->FilterChip(selected=s.repoKind==key,onClick={vm.repoKind(key)},label={Text(label)},enabled=!s.busy)}}}
        if(s.repoKind=="files"){
            item{FlowRow{TextButton(onClick={vm.directory("")}){Text("root")};s.route.path.split('/').filter{it.isNotEmpty()}.forEachIndexed{i,p->TextButton(onClick={vm.directory(s.route.path.split('/').take(i+1).joinToString("/"))}){Text("/ $p")}}}
            items(s.entries.sortedWith(compareBy<Entry>{it.type!="dir"}.thenBy{it.name}),key={it.path}){entry->Card(onClick={if(entry.type=="dir")vm.directory(entry.path)else vm.openFile(entry.path)},modifier=Modifier.fillMaxWidth()){Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){Icon(if(entry.type=="dir")Icons.Default.Folder else Icons.Default.Description,null,tint=MaterialTheme.colorScheme.primary);Text(entry.name,Modifier.weight(1f).padding(start=12.dp));if(entry.type!="dir")Text("${entry.size} B",style=MaterialTheme.typography.bodySmall)}}}
            if(s.entries.isEmpty()&&!s.busy)item{Empty("ファイルがないか、空のリポジトリです。")}
        }else{
            item{FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(selected=s.itemState=="open",onClick={vm.itemState("open")},label={Text("Open")});FilterChip(selected=s.itemState=="closed",onClick={vm.itemState("closed")},label={Text("Closed")});Button(onClick={form(if(s.repoKind=="pr")"new-pr"else"new-issue")},enabled=!s.busy){Text("作成")}}}
            item{OutlinedTextField(search,{search=it},label={Text("取得済みのタイトル・ラベル・担当で絞る")},modifier=Modifier.fillMaxWidth())}
            items(s.items.filter{"${it.title} ${it.labels.joinToString()} ${it.assignees.joinToString()}".contains(search,true)},key={it.key}){ItemCard(it,vm)}
            if(s.items.isEmpty()&&!s.busy)item{Empty("該当する項目がありません")}
            if(s.more)item{OutlinedButton(onClick=vm::more,enabled=!s.busy){Text("さらに取得")}}
        }
    }
}
@Composable fun FileScreen(s:UiState,vm:ForgeViewModel,branches:()->Unit,save:()->Unit,web:(String)->Unit){val d=s.document
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        item{OutlinedButton(onClick=branches,enabled=!s.busy){Text(s.route.branch)}}
        item{Text(s.route.path,style=MaterialTheme.typography.titleMedium)}
        if(d!=null){if(d.text==null)item{Text(d.note)}else{
            item{FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){if(s.selectedRepo?.canPush!=false){Button(onClick={vm.beginEdit()},enabled=!s.busy){Text("編集")};OutlinedButton(onClick={vm.beginEdit("rename")},enabled=!s.busy){Text("名前・場所を変更")};TextButton(onClick={vm.beginEdit("delete")},enabled=!s.busy){Text("削除を提案")}};TextButton(onClick=save){Text("端末へ保存")}}}
            if(d.text.lines().size>2000)item{Text("表示は先頭2000行です。端末へ保存すると全文を読めます。")}
            items(d.text.lines().take(2000).withIndex().toList(),key={it.index}){line->SelectionContainer{Text(line.value.ifEmpty{" "},fontFamily=if(d.path.endsWith(".md"))FontFamily.Default else FontFamily.Monospace,fontSize=16.sp,style=if(d.path.endsWith(".md")&&line.value.startsWith("# "))MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge)}}
        };item{TextButton(onClick={web("https://github.com/${s.route.repo}")}){Text("GitHubで開く")}}}
    }
}
@Composable fun EditorScreen(s:UiState,vm:ForgeViewModel,save:()->Unit){val e=s.editor ?: return;var preview by rememberSaveable{mutableStateOf(e.operation=="delete")}
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        item{Text("${e.base} · ${e.oldPath.ifEmpty{e.newPath}}");Text(if(preview)"変更を確認"else"ファイルを編集",style=MaterialTheme.typography.headlineMedium)}
        if(e.operation!="delete")item{OutlinedTextField(e.newPath,{value->vm.editor{it.copy(newPath=value)}},label={Text("ファイルの相対パス")},modifier=Modifier.fillMaxWidth(),enabled=!s.writing)}
        if(!preview&&e.operation!="delete")item{OutlinedTextField(e.text,{value->vm.editor{it.copy(text=value)}},label={Text("内容 · 下書きは自動保存")},modifier=Modifier.fillMaxWidth().heightIn(min=280.dp,max=500.dp),textStyle=MaterialTheme.typography.bodyLarge.copy(fontFamily=FontFamily.Monospace),enabled=!s.writing)}
        if(preview){if(e.operation=="rename")item{Text("${e.oldPath} → ${e.newPath}")};val lines=textDiff(e.original,e.text);if(lines.size>2000)item{Text("差分表示は先頭2000行です")};items(lines.take(2000).withIndex().toList(),key={it.index}){(_,line)->DiffRow(line)}
            item{OutlinedTextField(e.target,{value->vm.editor{it.copy(target=value)}},label={Text("新しいブランチ")},modifier=Modifier.fillMaxWidth(),enabled=!s.writing)}
            item{OutlinedTextField(e.title,{value->vm.editor{it.copy(title=value)}},label={Text("コミット・PRタイトル")},modifier=Modifier.fillMaxWidth(),enabled=!s.writing)}
            item{Text("${e.repo}\n${e.base}への変更を提案します。元ブランチは直接変更しません。")}
        }
        item{FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){if(e.operation!="delete")OutlinedButton(onClick={preview=!preview},enabled=!s.busy){Text(if(preview)"編集へ戻る"else"差分を確認")};if(preview)Button(onClick=save,enabled=!s.busy&&e.dirty&&e.title.isNotBlank()&&validBranch(e.target)&&(e.operation=="delete"||validPath(e.newPath))){Text("保存してPRを作成")}}}
        if(e.oldPath.isNotBlank())item{TextButton(onClick=vm::rebaseEditor,enabled=!s.busy){Text("最新のファイルと差分を確認")}}
        item{TextButton(onClick=vm::discardEditor,enabled=!s.busy){Text("下書きを破棄して戻る")}}
    }
}
@Composable fun DiffRow(line:DiffLine,onLine:((Int,String)->Unit)?=null){val color=when(line.kind){'+'->MaterialTheme.colorScheme.primaryContainer;'-'->MaterialTheme.colorScheme.errorContainer;else->MaterialTheme.colorScheme.surface};Row(Modifier.fillMaxWidth().background(color).padding(4.dp),verticalAlignment=Alignment.Top){if(onLine!=null&&(line.left!=null||line.right!=null))TextButton(onClick={onLine(line.right ?: line.left!!,if(line.right!=null)"RIGHT"else"LEFT")},modifier=Modifier.widthIn(min=48.dp)){Text("${line.right ?: line.left}",fontFamily=FontFamily.Monospace)};SelectionContainer{Text("${line.kind} ${line.text.removePrefix("${line.kind}")}",fontFamily=FontFamily.Monospace,fontSize=14.sp)}}}
@Composable fun ItemCard(item:Item,vm:ForgeViewModel,reason:String=""){Card(onClick={vm.openItem(item)},modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text("${item.repo} #${item.number}",style=MaterialTheme.typography.bodySmall);FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){Badge(if(item.isPr)"PR"else"Issue");Badge(item.state);item.labels.take(4).forEach{Badge(it)}};Text(item.title,style=MaterialTheme.typography.titleMedium);Text(reason.ifEmpty{item.updated},style=MaterialTheme.typography.bodyMedium)}}}
@Composable fun DeckScreen(s:UiState,vm:ForgeViewModel){var lane by rememberSaveable{mutableStateOf(Lane.MINE)};var repo by rememberSaveable{mutableStateOf("")};var picker by remember{mutableStateOf(false)};val values=s.items.filter{repo.isBlank()||it.repo==repo}.map{it to assess(it,s.login,s.deckDetails[it.key],it.key in s.waiting)}
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{Text("次の一手",style=MaterialTheme.typography.headlineLarge);Text("IssueとPRを、やることから。")}
        item{Box{OutlinedButton(onClick={picker=true}){Text(repo.ifEmpty{"すべてのリポジトリ"})};DropdownMenu(picker,{picker=false}){DropdownMenuItem(text={Text("すべて")},onClick={repo="";picker=false});s.items.map{it.repo}.distinct().forEach{name->DropdownMenuItem(text={Text(name)},onClick={repo=name;picker=false})}}}}
        item{FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){Lane.entries.forEach{l->FilterChip(selected=lane==l,onClick={lane=l},label={Text(l.label+if(l==Lane.ALL)""else" ${values.count{it.second.lane==l}}")})}}}
        items(values.filter{lane==Lane.ALL||it.second.lane==lane},key={it.first.key}){(item,assessment)->ItemCard(item,vm,assessment.reason)}
        if(values.none{lane==Lane.ALL||it.second.lane==lane}&&!s.busy)item{Empty("この分類で対応する項目はありません。未確認は「すべて」から開けます。")}
        if(s.more)item{OutlinedButton(onClick=vm::more,enabled=!s.busy){Text("さらに取得")}}
    }
}
@Composable fun DetailScreen(s:UiState,vm:ForgeViewModel,form:(String)->Unit,confirm:(String,()->Unit)->Unit,web:(String)->Unit){val d=s.detail ?: return;val item=d.item;var tab by rememberSaveable{mutableStateOf("overview")};var inline by remember{mutableStateOf<Triple<String,Int,String>?>(null)}
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){Badge(if(item.isPr)"Pull Request"else"Issue");Badge(item.state);if(d.draft)Badge("Draft")};Text(item.title,style=MaterialTheme.typography.headlineMedium);Text("#${item.number} · ${item.author}")}
        item{Text(assess(item,s.login,d,item.key in s.waiting).reason);FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){TextButton(onClick={vm.wait(item)}){Text(if(item.key in s.waiting)"待ちを解除"else"待ちに置く")};TextButton(onClick={form("edit-issue")},enabled=!s.busy){Text("本文・担当を編集")};TextButton(onClick={web(item.url.ifEmpty{"https://github.com/${item.repo}"})}){Text("GitHubで開く")}}}
        if(item.isPr)item{FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("overview" to "概要","checks" to "チェック","diff" to "差分").forEach{(key,label)->FilterChip(selected=tab==key,onClick={tab=key},label={Text(label)})}}}
        when(tab){
            "overview"->{item{SelectionContainer{Text(item.body.ifEmpty{"本文がありません"})}};if(item.isPr)item{Text("${d.headBranch} → ${d.baseBranch}\n${d.headSha}",style=MaterialTheme.typography.bodySmall)}
                if(d.related.isNotEmpty()){item{Text("つながっている作業",style=MaterialTheme.typography.titleMedium)};items(d.related,key={it.key}){ItemCard(it,vm,if(item.isPr)"このPRから完了するIssue"else"明示的に参照された作業")}}
                item{Text("コメント",style=MaterialTheme.typography.titleMedium)};items(d.comments.withIndex().toList(),key={it.index}){(_,c)->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp)){Text(c.author,style=MaterialTheme.typography.labelLarge);if(c.path.isNotBlank())Text("${c.path}:${c.line}",style=MaterialTheme.typography.bodySmall);SelectionContainer{Text(c.body)}}}}
                item{OutlinedButton(onClick={form("comment")},enabled=!s.busy){Text("コメントする")}}
            }
            "checks"->{item{Text("レビュー条件: ${d.reviewDecision}\nマージ状態: ${d.mergeState}\n競合: ${d.mergeable?.let{if(it)"なし"else"あり"} ?: "未確認"}")};if(!d.checksKnown||d.checks.isEmpty())item{Text("チェックは未確認、または登録されていません。成功とは扱いません。")};items(d.checks.withIndex().toList(),key={it.index}){(_,c)->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){Text(c.name);Badge(c.state,c.state in setOf("failure","error","timed_out","cancelled","action_required"));if(c.url.isNotBlank())TextButton(onClick={web(c.url)}){Text("詳細・ログを開く")}}}}}
            "diff"->items(d.files,key={it.path}){f->Column{Text(f.path,style=MaterialTheme.typography.titleMedium);Text("${f.status} · +${f.additions} / −${f.deletions}",style=MaterialTheme.typography.bodySmall);if(f.patch==null)Text("バイナリまたは省略された差分です。GitHubで確認してください。")else{val lines=patchLines(f.patch);if(lines.size>1000)Text("表示は先頭1000行です");lines.take(1000).forEach{line->DiffRow(line){number,side->inline=Triple(f.path,number,side)}}}}}
        }
        if(d.partial.isNotEmpty())item{Text(d.partial.joinToString("\n"),color=MaterialTheme.colorScheme.error)}
        item{FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){if(item.isPr)OutlinedButton(onClick={form("review")},enabled=!s.busy){Text("レビューを書く")};if(item.isPr)Button(onClick={confirm("${item.repo} #${item.number}\n${d.headBranch} → ${d.baseBranch}\nSquash merge\n送信直前に条件とコミットを再確認します。"){vm.merge("squash")}},enabled=!s.busy&&d.safeCandidate){Text("マージを確認")};OutlinedButton(onClick={confirm("${item.repo} #${item.number}を${if(item.state=="open")"閉じます"else"再開します"}"){vm.state(if(item.state=="open")"closed"else"open")}},enabled=!s.busy){Text(if(item.state=="open")"閉じる"else"再開")}}}
    }
    inline?.let{(path,line,side)->var body by rememberSaveable(path,line,side){mutableStateOf("")};var submitted by remember{mutableStateOf(false)};LaunchedEffect(s.busy,s.error){if(submitted&&!s.busy&&s.error.isEmpty()&&s.notice.isNotEmpty())inline=null};AlertDialog(onDismissRequest={if(!s.writing)inline=null},title={Text("差分にコメント")},text={Column{Text("$path:$line ($side)");OutlinedTextField(body,{body=it},label={Text("コメント")},enabled=!s.writing);if(s.error.isNotEmpty())Text(s.error,color=MaterialTheme.colorScheme.error)}},confirmButton={TextButton(onClick={submitted=true;vm.inline(path,line,side,body)},enabled=body.isNotBlank()&&!s.busy){Text("送信")}},dismissButton={TextButton(onClick={inline=null},enabled=!s.writing){Text("キャンセル")}})}
}
@Composable fun InboxScreen(s:UiState,vm:ForgeViewModel,allRead:()->Unit){LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){item{Text("受信箱",style=MaterialTheme.typography.headlineLarge);TextButton(onClick=allRead,enabled=!s.busy){Text("すべて既読")}};items(s.notifications,key={it.id}){n->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp)){Text("${n.repo} · ${if(n.unread)"未読"else"既読"}",style=MaterialTheme.typography.bodySmall);Text(n.title,style=MaterialTheme.typography.titleMedium);Text(n.reason);FlowRow{if(n.kind in setOf("Issue","PullRequest")&&n.number>0)TextButton(onClick={vm.notification(n)},enabled=!s.busy){Text("開く")};if(n.unread)TextButton(onClick={vm.read(n.id)},enabled=!s.busy){Text("既読にする")}}}}};if(s.notifications.isEmpty()&&!s.busy)item{Empty("通知がありません。権限不足は上に理由を表示します。")};if(s.more)item{OutlinedButton(onClick=vm::more,enabled=!s.busy){Text("さらに取得")}}}}
@Composable fun ActionForm(action:String,s:UiState,vm:ForgeViewModel,close:()->Unit){
    val item=s.detail?.item
    var title by rememberSaveable(action){mutableStateOf(if(action=="edit-issue")item?.title.orEmpty()else"")};var body by rememberSaveable(action){mutableStateOf(if(action=="edit-issue")item?.body.orEmpty()else"")}
    var labels by rememberSaveable(action){mutableStateOf(if(action=="edit-issue")item?.labels?.joinToString().orEmpty()else"")};var assignees by rememberSaveable(action){mutableStateOf(if(action=="edit-issue")item?.assignees?.joinToString().orEmpty()else"")}
    var base by rememberSaveable(action){mutableStateOf(s.route.branch)};var head by rememberSaveable(action){mutableStateOf("")};var submitted by remember{mutableStateOf(false)}
    LaunchedEffect(s.busy,s.error,s.notice){if(submitted&&!s.busy&&s.error.isEmpty()&&s.notice.isNotEmpty())close()}
    val heading=when(action){"new-issue"->"Issueを作成";"edit-issue"->"本文・担当を編集";"new-pr"->"PRを作成";"review"->"レビューを書く";else->"コメントする"}
    AlertDialog(onDismissRequest={if(!s.writing)close()},title={Text(heading)},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text(s.route.repo)
        if(action in setOf("new-issue","edit-issue","new-pr"))OutlinedTextField(title,{title=it},label={Text("タイトル")},enabled=!s.writing)
        if(action=="new-pr"){OutlinedTextField(base,{base=it},label={Text("baseブランチ")},enabled=!s.writing);OutlinedTextField(head,{head=it},label={Text("headブランチ · 同じrepo")},enabled=!s.writing)}
        OutlinedTextField(body,{body=it},label={Text(if(action in setOf("comment","review"))"コメント"else"本文")},modifier=Modifier.heightIn(min=140.dp),enabled=!s.writing)
        if(action in setOf("new-issue","edit-issue")){OutlinedTextField(labels,{labels=it},label={Text("ラベル · カンマ区切り")},enabled=!s.writing);OutlinedTextField(assignees,{assignees=it},label={Text("担当 · GitHubユーザー名")},enabled=!s.writing)}
        if(action=="review"&&item?.author==s.login)Text("自分のPRには承認・修正依頼を送れません。コメントは送れます。")
        if(s.error.isNotEmpty())Text(s.error,color=MaterialTheme.colorScheme.error)
    }},confirmButton={FlowRow{
        if(action=="review"){
            TextButton(onClick={submitted=true;vm.review("APPROVE",body)},enabled=!s.busy&&item?.author!=s.login){Text("承認")};TextButton(onClick={submitted=true;vm.review("REQUEST_CHANGES",body)},enabled=!s.busy&&body.isNotBlank()&&item?.author!=s.login){Text("修正依頼")};TextButton(onClick={submitted=true;vm.review("COMMENT",body)},enabled=!s.busy&&body.isNotBlank()){Text("コメント")}
        }else TextButton(onClick={submitted=true;when(action){"comment"->vm.comment(body);"new-pr"->vm.newPr(base,head,title,body);else->vm.issue(title,body,labels.split(',').map{it.trim()}.filter{it.isNotEmpty()},assignees.split(',').map{it.trim()}.filter{it.isNotEmpty()},if(action=="edit-issue")item?.number ?: 0 else 0)}},enabled=!s.busy&&if(action=="comment")body.isNotBlank()else title.isNotBlank()){Text("送信")}
    }},dismissButton={TextButton(onClick=close,enabled=!s.writing){Text("キャンセル")}})
}
