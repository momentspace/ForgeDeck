package dev.forgedeck.app

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class LocalStoreTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun tokenIsEncryptedAndLogoutRemovesAccountData() {
        val store=LocalStore(context);store.logout()
        val secret="only-a-local-test-token"
        store.saveToken(secret,"sample")
        assertEquals(secret,store.token())
        val packed=context.getSharedPreferences("vault",Context.MODE_PRIVATE).getString("token","")!!
        assertFalse(packed.contains(secret))
        store.toggle("pins","sample/ForgeDeck");store.cachePut("test-url","private-cache")
        store.logout()
        assertEquals("",store.token());assertEquals("",store.login);assertTrue(store.pins().isEmpty());assertNull(store.cacheGet("test-url"))
    }
    @Test fun changedBaseDoesNotDiscardDraft() {
        val store=LocalStore(context);store.logout();store.saveToken("test","sample")
        val editor=Editor("sample/ForgeDeck","master","README.md","README.md","old-sha","Original","My draft",target="forgedeck/test",title="Update")
        store.draft(editor)
        val restored=store.restore(editor.copy(originalSha="new-sha",original="Changed by another user",text="Changed by another user"))
        assertEquals("My draft",restored.text);assertEquals("old-sha",restored.originalSha);assertEquals("Original",restored.original)
        store.logout()
    }
}
