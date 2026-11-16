package com.financeautomation.buildconfig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VersionCodeDerivationTest {

    @Test
    fun testBuildGradleDerivesVersionCodeFromGitCommitCount() {
        val buildGradle = File("build.gradle.kts")
        val appBuildGradle = File("app/build.gradle.kts")
        val targetFile = if (appBuildGradle.exists()) appBuildGradle else buildGradle
        assertTrue("app/build.gradle.kts must exist", targetFile.exists())

        val content = targetFile.readText()
        assertTrue("Must derive from git rev-list --count HEAD", content.contains("git") && content.contains("rev-list") && content.contains("--count"))
        assertTrue("Must have safe fallback to 1", content.contains("orElse(1)"))
        assertTrue("versionName must remain 1.0.0", content.contains("versionName = \"1.0.0\""))
        assertTrue("versionCode must use gitCommitCount", content.contains("versionCode = gitCommitCount"))
    }
}
