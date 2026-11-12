package com.financeautomation.data.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DatabaseMigrationTest {

    @Test
    fun testMigration7to8_matchesSchema8Json() {
        val schemaFile = File("schemas/com.financeautomation.data.local.FinanceDatabase/8.json")
        val altFile = File("app/schemas/com.financeautomation.data.local.FinanceDatabase/8.json")
        val targetFile = if (schemaFile.exists()) schemaFile else altFile
        assertTrue("Schema 8.json must exist", targetFile.exists())

        val json = Json { ignoreUnknownKeys = true }
        val root = json.parseToJsonElement(targetFile.readText()).jsonObject
        val entities = root["database"]!!.jsonObject["entities"]!!.jsonArray

        val payablesEntity = entities.find { it.jsonObject["tableName"]?.jsonPrimitive?.content == "payables" }?.jsonObject
        assertNotNull("payables table must exist in schema 8.json", payablesEntity)

        val createSql = payablesEntity!!["createSql"]!!.jsonPrimitive.content
        assertTrue(createSql.contains("`uuid` TEXT NOT NULL"))
        assertTrue(createSql.contains("`dateIncurredSerial` INTEGER"))
        assertTrue(createSql.contains("`timeSerial` REAL"))
        assertTrue(createSql.contains("`paidBy` TEXT NOT NULL"))
        assertTrue(createSql.contains("`whatFor` TEXT NOT NULL"))
        assertTrue(createSql.contains("`amountIOwe` REAL NOT NULL"))
        assertTrue(createSql.contains("`dateRepaidSerial` INTEGER"))
        assertTrue(createSql.contains("`repaymentStatus` TEXT NOT NULL"))
        assertTrue(createSql.contains("`tabName` TEXT NOT NULL"))
        assertTrue(createSql.contains("`sheetRowIndex` INTEGER"))
        assertTrue(createSql.contains("`updatedAt` INTEGER NOT NULL"))
        assertTrue(createSql.contains("PRIMARY KEY(`uuid`)"))

        val indices = payablesEntity["indices"]!!.jsonArray
        assertEquals(3, indices.size)

        val indexNames = indices.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        assertTrue(indexNames.contains("index_payables_uuid"))
        assertTrue(indexNames.contains("index_payables_paidBy"))
        assertTrue(indexNames.contains("index_payables_repaymentStatus"))
    }
}
