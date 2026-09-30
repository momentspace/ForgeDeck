package dev.forgedeck.app

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Seeded UI only: these tests never perform authenticated GitHub writes. */
class NativeScreensTest {
    @get:Rule val compose=createComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun render(state:UiState,fontScale:Float=1f) {
        val store=LocalStore(context);store.logout()
        val vm=ForgeViewModel(store)
        compose.setContent {
            val density=LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density,fontScale)) { ForgeApp(vm,state) }
        }
    }
    private fun capture(name:String) {
        compose.waitForIdle()
        val dir=File(context.getExternalFilesDir(null),"screenshots").apply{mkdirs()}
        File(dir,"$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun loginDoesNotAskForGitHubPassword() {
        render(UiState())
        compose.onNodeWithText("GitHubトークン").assertIsDisplayed()
        compose.onNodeWithText("アカウントを確認して接続").assertIsNotEnabled()
        capture("01-login")
    }
    @Test fun repoOwnerAndPinnedRepositoryStayVisible() {
        render(UiState(login="sample",repos=listOf(Repo("sample/ForgeDeck","迷わず次の一手へ","Kotlin"),Repo("sample/Metis","個人AI基盤","TypeScript")),pins=setOf("sample/ForgeDeck")))
        compose.onNodeWithText("ピン留め",substring=false).assertIsDisplayed()
        compose.onAllNodesWithText("ForgeDeck",substring=false).assertCountEquals(2)
        capture("02-repositories")
    }
    @Test fun deckCollapsesLinkedIssueIntoPrWork() {
        val issue=Item("sample/ForgeDeck",12,"ファイル操作を整える","","sample",false,"open","",assignees=listOf("sample"))
        val pr=Item("sample/ForgeDeck",25,"ファイル編集とPR作成","","sample",true,"open","")
        val detail=Detail(pr,related=listOf(issue),requested=listOf("reviewer"))
        render(UiState(login="sample",route=Route(Page.DECK),tab=Page.DECK,items=listOf(issue,pr),deckDetails=mapOf(pr.key to detail),theme="dark"))
        compose.onNodeWithText("待ち 1").performClick()
        compose.onNodeWithText("依頼したレビューを待っています").assertIsDisplayed()
        compose.onNodeWithText("完了する作業",substring=true).assertIsDisplayed()
        capture("03-deck")
    }
    @Test fun fileBranchAndPathRemainExplicitWithLargeText() {
        render(UiState(login="sample",route=Route(Page.FILE,"sample/ForgeDeck","feature/files","src/App.kt"),selectedRepo=Repo("sample/ForgeDeck",canPush=true),document=Document("src/App.kt","sha","fun main() {\n    println(\"ForgeDeck\")\n}",55)),fontScale=1.6f)
        compose.onNodeWithText("feature/files").assertIsDisplayed()
        compose.onNodeWithText("src/App.kt").assertIsDisplayed()
        compose.onNodeWithText("編集",substring=false).assertIsDisplayed()
        capture("04-file-large-text")
    }
    @Test fun editorShowsDestinationBeforeSaving() {
        val editor=Editor("sample/ForgeDeck","master","README.md","README.md","sha","# ForgeDeck","",operation="delete",target="forgedeck/change-sample",title="READMEを整理")
        render(UiState(login="sample",route=Route(Page.EDITOR,editor.repo,editor.base,editor.oldPath),editor=editor))
        compose.onNodeWithText("新しいブランチ").performScrollTo().assertIsDisplayed()
        capture("05-editor-diff")
    }
}
