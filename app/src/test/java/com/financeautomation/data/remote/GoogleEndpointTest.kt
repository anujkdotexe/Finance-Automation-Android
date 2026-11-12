package com.financeautomation.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

class GoogleEndpointTest {

    private val endpointLiteral = Regex("\"(drive|sheets)\\.[A-Za-z.]+\"")

    @Test
    fun everyEndpointConstantIsClassifiedExactlyOnce() {
        val declared = GoogleEndpoint::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.get(null) as String }
            .toSet()

        assertEquals("Every endpoint constant must be in BODY_ALLOWED or SUMMARY_ONLY", declared, GoogleEndpoint.ALL)
        assertTrue(
            "An endpoint cannot be both body-allowed and summary-only",
            GoogleEndpoint.BODY_ALLOWED.intersect(GoogleEndpoint.SUMMARY_ONLY).isEmpty()
        )
    }

    @Test
    fun policyFollowsTheClassification() {
        for (id in GoogleEndpoint.BODY_ALLOWED) {
            assertTrue("$id should allow a body", RawHttpErrorStore.isStructureOrProvisioningCall(id))
        }
        for (id in GoogleEndpoint.SUMMARY_ONLY) {
            assertFalse("$id must be summary only", RawHttpErrorStore.isStructureOrProvisioningCall(id))
        }
        assertFalse(RawHttpErrorStore.isStructureOrProvisioningCall("drive.files.search"))
        assertFalse(RawHttpErrorStore.isStructureOrProvisioningCall(""))
    }

    @Test
    fun transactionRowEndpointsNeverStoreABody() {
        val transactionRowCalls = listOf(
            GoogleEndpoint.SHEETS_VALUES_APPEND,
            GoogleEndpoint.SHEETS_VALUES_UPDATE,
            GoogleEndpoint.SHEETS_VALUES_BATCH_UPDATE
        )
        for (id in transactionRowCalls) {
            assertFalse("$id carries transaction rows", RawHttpErrorStore.isStructureOrProvisioningCall(id))
        }
    }

    @Test
    fun noCallSiteUsesAnInlineEndpointString() {
        val sourceRoot = File("src/main/java")
        if (!sourceRoot.exists()) return // not run from the module directory

        val offenders = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "GoogleEndpoint.kt" }
            .filter { file -> endpointLiteral.containsMatchIn(file.readText()) }
            .map { it.name }
            .toList()

        assertTrue("Inline endpoint strings found (use GoogleEndpoint constants): $offenders", offenders.isEmpty())
    }
}
