package dev.forgedeck.app

import org.junit.Assert.*
import org.junit.Test

class RulesTest {
    private val item = Item("owner/repo", 12, "Change", "", "someone", true, "open", "")
    private val ready =
        Detail(
            item,
            checks = listOf(Check("test", "success", required = true)),
            headSha = "head",
            mergeable = true,
            mergeState = "CLEAN",
            reviewDecision = "APPROVED",
            canPush = true,
            checksKnown = true,
        )

    @Test
    fun unknownConditionsNeverBecomeReady() {
        assertTrue(ready.safeCandidate)
        assertFalse(ready.copy(checksKnown = false).safeCandidate)
        assertFalse(
            ready.copy(checks = listOf(Check("test", "success", required = null))).safeCandidate
        )
        assertFalse(ready.copy(checks = emptyList()).safeCandidate)
        assertFalse(ready.copy(mergeable = null).safeCandidate)
        assertFalse(ready.copy(reviewDecision = "UNKNOWN").safeCandidate)
        assertFalse(ready.copy(mergeState = "BLOCKED").safeCandidate)
        assertFalse(ready.copy(canPush = false).safeCandidate)
        assertFalse(ready.copy(draft = true).safeCandidate)
        assertFalse(ready.copy(partial = listOf("checks partial")).safeCandidate)
        assertFalse(ready.copy(checks = listOf(Check("build", "pending"))).safeCandidate)
    }

    @Test
    fun reviewRequestTakesPrecedenceOverReady() {
        assertEquals(
            Lane.MINE,
            assess(item, "me", ready.copy(requested = listOf("me")), false).lane,
        )
    }

    @Test
    fun failedOwnPrIsActionEvenWhenManuallyWaiting() {
        val own = item.copy(author = "me")
        assertEquals(
            Lane.MINE,
            assess(
                    own,
                    "me",
                    ready.copy(item = own, checks = listOf(Check("test", "failure"))),
                    true,
                )
                .lane,
        )
    }

    @Test
    fun issueWaitingRequiresExplicitIntent() {
        val issue = item.copy(isPr = false, assignees = listOf("me"))
        assertEquals(Lane.MINE, assess(issue, "me", null, false).lane)
        assertEquals(Lane.WAITING, assess(issue, "me", null, true).lane)
    }

    @Test
    fun unsafePathsAndRefsAreRejected() {
        listOf("../x", "a/../x", "/x", "a//b", "a\\b").forEach { assertFalse(it, validPath(it)) }
        listOf(".secret", "a.lock", "a..b", "a@{b", "a b", "/a", "a/", "a//b", "a~b", "a:")
            .forEach { assertFalse(it, validBranch(it)) }
        assertTrue(validPath("docs/日本語.md"))
        assertTrue(validBranch("forgedeck/change-12"))
        assertFalse(validRepo("owner/../../repo"))
    }

    @Test
    fun deepLinksOnlyAcceptExactGitHubHostAndSupportedPaths() {
        assertEquals(
            LinkTarget("owner/repo", 12, true),
            githubTarget("https://github.com/owner/repo/pull/12"),
        )
        assertNull(githubTarget("https://github.com.evil.test/owner/repo/issues/1"))
        assertNull(githubTarget("https://secret@github.com/owner/repo/issues/1"))
        assertNull(githubTarget("http://github.com/owner/repo"))
        assertNull(githubTarget("https://github.com/owner/repo/issues/-1"))
        assertNull(githubTarget("https://github.com/owner/repo/tree/main"))
    }

    @Test
    fun patchNumbersRespectDeletedLines() {
        val lines = patchLines("@@ -4,2 +4,2 @@\n-old\n+new\n context")
        assertEquals(4, lines[1].left)
        assertNull(lines[1].right)
        assertEquals(4, lines[2].right)
        assertEquals(5, lines[3].left)
        assertEquals(5, lines[3].right)
    }

    @Test
    fun diffPreservesUnchangedTailAndAddedText() {
        val lines = textDiff("one\ntwo\nend", "one\nnew\nend")
        assertEquals(listOf(' ', '-', '+', ' '), lines.map { it.kind })
        assertEquals("new", lines[2].text)
    }

    @Test
    fun deckGroupsOnlyExplicitClosingRelations() {
        val issue =
            Item(
                "owner/repo",
                1,
                "Same title",
                "",
                "me",
                false,
                "open",
                "",
                assignees = listOf("me"),
            )
        val pr = Item("owner/repo", 2, "Same title", "", "me", true, "open", "")
        assertEquals(2, workGroups(listOf(issue, pr), emptyMap(), "me", emptySet()).size)
        val detail = Detail(pr, related = listOf(issue), requested = listOf("reviewer"))
        val groups = workGroups(listOf(issue, pr), mapOf(pr.key to detail), "me", emptySet())
        assertEquals(1, groups.size)
        assertEquals(pr, groups.single().primary)
        assertEquals(Lane.WAITING, groups.single().assessment.lane)
        val other = issue.copy(repo = "other/repo")
        assertEquals(
            2,
            workGroups(listOf(other, pr), mapOf(pr.key to detail), "me", emptySet()).size,
        )
    }

    @Test
    fun mergedPrIsDifferentFromClosedPr() {
        val item =
            parseItem(
                org.json.JSONObject(
                    "{\"number\":1,\"state\":\"closed\",\"merged\":true,\"head\":{}}"
                ),
                "owner/repo",
            )
        assertEquals("merged", item.state)
        assertFalse(Detail(item).safeCandidate)
    }
}
