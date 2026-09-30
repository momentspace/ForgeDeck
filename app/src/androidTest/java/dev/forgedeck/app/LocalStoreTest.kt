package dev.forgedeck.app

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class LocalStoreTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun uncacheableResponseCannotReuseOlderBodyWithNewValidator() {
        val store = LocalStore(context)
        store.logout()
        store.saveToken("test", "sample")
        store.cachePut("test-url", "older-body")
        store.validatorPut("test-url", "older-etag", "")
        store.cachePut("test-url", "x".repeat(600_001))
        store.validatorPut("test-url", "newer-etag", "")
        assertNull(store.cacheGet("test-url"))
        assertNull(store.validatorGet("test-url"))
        store.logout()
    }

    @Test
    fun tokenIsEncryptedAndLogoutRemovesAccountData() {
        val store = LocalStore(context)
        store.logout()
        val secret = "only-a-local-test-token"
        store.saveToken(secret, "sample")
        assertEquals(secret, store.token())
        val packed =
            context.getSharedPreferences("vault", Context.MODE_PRIVATE).getString("token", "")!!
        assertFalse(packed.contains(secret))
        store.toggle("pins", "sample/ForgeDeck")
        store.cachePut("test-url", "private-cache")
        store.logout()
        assertEquals("", store.token())
        assertEquals("", store.login)
        assertTrue(store.pins().isEmpty())
        assertNull(store.cacheGet("test-url"))
    }

    @Test
    fun changedBaseDoesNotDiscardDraft() {
        val store = LocalStore(context)
        store.logout()
        store.saveToken("test", "sample")
        val editor =
            Editor(
                "sample/ForgeDeck",
                "master",
                "README.md",
                "README.md",
                "old-sha",
                "Original",
                "My draft",
                target = "forgedeck/test",
                title = "Update",
            )
        store.draft(editor)
        val restored =
            store.restore(
                editor.copy(
                    originalSha = "new-sha",
                    original = "Changed by another user",
                    text = "Changed by another user",
                )
            )
        assertEquals("My draft", restored.text)
        assertEquals("old-sha", restored.originalSha)
        assertEquals("Original", restored.original)
        store.logout()
    }

    @Test
    fun expiredCredentialsDoNotEraseDrafts() {
        val store = LocalStore(context)
        store.logout()
        store.saveToken("test", "sample")
        val e =
            Editor(
                "sample/ForgeDeck",
                "master",
                "README.md",
                "README.md",
                "sha",
                "Original",
                "Draft",
                target = "forgedeck/test",
                title = "Update",
            )
        store.draft(e)
        store.expire()
        assertEquals("", store.token())
        assertEquals("sample", store.login)
        assertEquals("Draft", store.restore(e.copy(text = "Original")).text)
        store.logout()
    }
    @Test
    fun postJournalAndFormDraftSurviveRecreationAndExpiry() {
        val store = LocalStore(context)
        store.logout()
        store.saveToken("test", "sample")
        val key = formKey("comment", "sample/ForgeDeck", 1)
        val pending = PendingPost("b32476c2-7b05-49b1-8b52-cf9d65ba50fb", "comment", "sample/ForgeDeck", 1, false, "repos/sample/ForgeDeck/issues/1/comments", "{}", "sample")
        store.formDraft(key, "My comment draft")
        store.pendingPost(pending)
        val recreated = LocalStore(context)
        assertEquals("My comment draft", recreated.formDraft(key))
        assertEquals(pending, recreated.pendingPost())
        recreated.expire()
        assertEquals(pending, LocalStore(context).pendingPost())
        assertEquals("My comment draft", LocalStore(context).formDraft(key))
        recreated.logout()
        recreated.saveToken("another-account", "other")
        assertNull(recreated.formDraft(key))
        assertNull(recreated.pendingPost())
        recreated.logout()
    }

}
