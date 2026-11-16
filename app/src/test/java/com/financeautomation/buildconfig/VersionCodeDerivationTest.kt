package com.financeautomation.buildconfig

import com.financeautomation.core.utils.VersionCodeDerivation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VersionCodeDerivationTest {

    @Test
    fun testParseCommitCount_parsesValidOutputCorrectly() {
        assertEquals(42, VersionCodeDerivation.parseCommitCount("42"))
        assertEquals(105, VersionCodeDerivation.parseCommitCount("105\n"))
        assertEquals(1, VersionCodeDerivation.parseCommitCount("   1   \n"))
    }

    @Test
    fun testParseCommitCount_fallsBackOnInvalidOrEmptyInput() {
        assertEquals(1, VersionCodeDerivation.parseCommitCount(null))
        assertEquals(1, VersionCodeDerivation.parseCommitCount(""))
        assertEquals(1, VersionCodeDerivation.parseCommitCount("   \n\t"))
        assertEquals(1, VersionCodeDerivation.parseCommitCount("not_a_number"))
        assertEquals(5, VersionCodeDerivation.parseCommitCount("invalid", fallback = 5))
        assertEquals(1, VersionCodeDerivation.parseCommitCount("0")) // 0 is invalid versionCode in Android
        assertEquals(1, VersionCodeDerivation.parseCommitCount("-12"))
    }

    @Test
    fun testDeriveFromGit_runsRealGitProcessAndReturnsPositiveCount() {
        val count = VersionCodeDerivation.deriveFromGit(File("."))
        assertTrue("Version code derived from git must be at least 1, got $count", count >= 1)
    }
}
