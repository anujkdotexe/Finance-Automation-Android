package com.financeautomation.data.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** D-1, D-2, D-3: storage must never fall back to memory, must be retriable, and resets must be visible. */
class StorageAvailabilityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun passphraseFailureTwiceThrowsStorageUnavailableInsteadOfReturningAFallback() {
        var calls = 0
        try {
            PassphraseAccess.obtain(provider = { calls++; throw IllegalStateException("keystore") }, pause = {})
            fail("expected StorageUnavailableException")
        } catch (e: StorageUnavailableException) {
            assertEquals(2, calls)
        }
    }

    @Test
    fun passphraseRecoversOnTheRetry() {
        var calls = 0
        val result = PassphraseAccess.obtain(
            provider = { if (calls++ == 0) throw IllegalStateException("transient") else byteArrayOf(1, 2) },
            pause = {}
        )
        assertEquals(2, result.size)
        assertEquals(2, calls)
    }

    @Test
    fun failedCreationIsNotCachedAndTheNextAccessRetries() = runBlocking {
        var calls = 0
        val provider = RetriableProvider<String> {
            if (calls++ == 0) throw StorageUnavailableException("down") else "db"
        }
        try {
            provider.get()
            fail("first access must fail")
        } catch (e: StorageUnavailableException) {
            // expected
        }
        assertEquals("db", provider.get())
        assertEquals("db", provider.get())
        assertEquals("success is cached", 2, calls)
    }

    @Test
    fun onlyTheNewestTwoUnreadableFilesAreKept() {
        val dir = tmp.newFolder("dbs")
        listOf(100L, 200L, 300L, 400L).forEach { File(dir, "finance_automation.db.unreadable_$it").writeText("x") }
        File(dir, "finance_automation.db").writeText("live")
        File(dir, "other.txt").writeText("keep")

        val deleted = UnreadableDatabaseRetention.prune(dir, "finance_automation.db", keep = 2)

        assertEquals(2, deleted)
        val left = dir.list()!!.toSet()
        assertTrue(left.contains("finance_automation.db.unreadable_400"))
        assertTrue(left.contains("finance_automation.db.unreadable_300"))
        assertFalse(left.contains("finance_automation.db.unreadable_100"))
        assertTrue(left.contains("finance_automation.db"))
        assertTrue(left.contains("other.txt"))
    }

    @Test
    fun resetNoticeIsShownOnceThenCleared() {
        val notice = StorageResetNotice(File(tmp.newFolder("files"), "storage_reset_notice"))
        assertFalse(notice.consume())
        notice.mark()
        assertTrue(notice.consume())
        assertFalse("second read must be empty", notice.consume())
    }
}
