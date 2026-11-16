package com.financeautomation.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Architecture guard test enforcing clean architecture boundaries:
 * The domain layer must not import data, presentation, or Android framework classes.
 */
class DomainArchitectureGuardTest {

    private val forbiddenPrefixes = listOf(
        "import com.financeautomation.data.",
        "import com.financeautomation.presentation.",
        "import android."
    )

    @Test
    fun testDomainLayerHasNoForbiddenImports() {
        val domainDir = File("app/src/main/java/com/financeautomation/domain")
        val altDir = File("src/main/java/com/financeautomation/domain")
        val targetDir = if (domainDir.exists()) domainDir else altDir
        assertTrue("Domain directory must exist", targetDir.exists() && targetDir.isDirectory)

        val violations = mutableListOf<String>()

        targetDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val lines = file.readLines()
                lines.forEachIndexed { index, line ->
                    val trimmed = line.trim()
                    for (forbidden in forbiddenPrefixes) {
                        if (trimmed.startsWith(forbidden)) {
                            violations.add("${file.name}:${index + 1}: $trimmed")
                        }
                    }
                }
            }

        assertTrue(
            "Domain layer contains forbidden imports:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }
}
