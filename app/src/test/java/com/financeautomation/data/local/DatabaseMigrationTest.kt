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

    @Test
    fun testMigration8to9_matchesSchema9Json() {
        // Assert migration registration
        assertEquals(8, DatabaseMigrations.MIGRATION_8_9.startVersion)
        assertEquals(9, DatabaseMigrations.MIGRATION_8_9.endVersion)
        assertTrue(DatabaseMigrations.ALL_MIGRATIONS.contains(DatabaseMigrations.MIGRATION_8_9))

        val schemaFile = File("schemas/com.financeautomation.data.local.FinanceDatabase/9.json")
        val altFile = File("app/schemas/com.financeautomation.data.local.FinanceDatabase/9.json")
        val targetFile = if (schemaFile.exists()) schemaFile else altFile
        assertTrue("Schema 9.json must exist", targetFile.exists())

        val json = Json { ignoreUnknownKeys = true }
        val root = json.parseToJsonElement(targetFile.readText()).jsonObject
        val entities = root["database"]!!.jsonObject["entities"]!!.jsonArray

        val pendingCapturesEntity = entities.find { it.jsonObject["tableName"]?.jsonPrimitive?.content == "pending_captures" }?.jsonObject
        assertNotNull("pending_captures table must exist in schema 9.json", pendingCapturesEntity)

        val fields = pendingCapturesEntity!!["fields"]!!.jsonArray
        val hintField = fields.find { it.jsonObject["columnName"]?.jsonPrimitive?.content == "detectedAccountHint" }?.jsonObject
        assertNotNull("detectedAccountHint column must exist in schema 9.json pending_captures table", hintField)
        assertEquals("TEXT", hintField!!["affinity"]?.jsonPrimitive?.content)
        assertEquals("false", hintField["notNull"]?.jsonPrimitive?.content)

        val createSql = pendingCapturesEntity["createSql"]!!.jsonPrimitive.content
        assertTrue(createSql.contains("`detectedAccountHint` TEXT"))
    }

    @Test
    fun testMigration9to10_matchesSchema10Json() {
        assertEquals(9, DatabaseMigrations.MIGRATION_9_10.startVersion)
        assertEquals(10, DatabaseMigrations.MIGRATION_9_10.endVersion)
        assertTrue(DatabaseMigrations.ALL_MIGRATIONS.contains(DatabaseMigrations.MIGRATION_9_10))

        val schemaFile = File("schemas/com.financeautomation.data.local.FinanceDatabase/10.json")
        val altFile = File("app/schemas/com.financeautomation.data.local.FinanceDatabase/10.json")
        val targetFile = if (schemaFile.exists()) schemaFile else altFile
        assertTrue("Schema 10.json must exist", targetFile.exists())

        val json = Json { ignoreUnknownKeys = true }
        val root = json.parseToJsonElement(targetFile.readText()).jsonObject
        val entities = root["database"]!!.jsonObject["entities"]!!.jsonArray

        val transactionsEntity = entities.find { it.jsonObject["tableName"]?.jsonPrimitive?.content == "transactions" }?.jsonObject
        assertNotNull("transactions table must exist in schema 10.json", transactionsEntity)

        val fields = transactionsEntity!!["fields"]!!.jsonArray
        val dupField = fields.find { it.jsonObject["columnName"]?.jsonPrimitive?.content == "duplicateOfId" }?.jsonObject
        assertNotNull("duplicateOfId column must exist in schema 10.json transactions table", dupField)
        assertEquals("TEXT", dupField!!["affinity"]?.jsonPrimitive?.content)
        assertEquals("false", dupField["notNull"]?.jsonPrimitive?.content)

        val createSql = transactionsEntity["createSql"]!!.jsonPrimitive.content
        assertTrue(createSql.contains("`duplicateOfId` TEXT"))
    }

    @Test
    fun testMigration10to11_matchesSchema11Json() {
        assertEquals(10, DatabaseMigrations.MIGRATION_10_11.startVersion)
        assertEquals(11, DatabaseMigrations.MIGRATION_10_11.endVersion)
        assertTrue(DatabaseMigrations.ALL_MIGRATIONS.contains(DatabaseMigrations.MIGRATION_10_11))

        val schemaFile = File("schemas/com.financeautomation.data.local.FinanceDatabase/11.json")
        val altFile = File("app/schemas/com.financeautomation.data.local.FinanceDatabase/11.json")
        val targetFile = if (schemaFile.exists()) schemaFile else altFile
        assertTrue("Schema 11.json must exist", targetFile.exists())

        val json = Json { ignoreUnknownKeys = true }
        val root = json.parseToJsonElement(targetFile.readText()).jsonObject
        val entities = root["database"]!!.jsonObject["entities"]!!.jsonArray

        val syncQueueEntity = entities.find { it.jsonObject["tableName"]?.jsonPrimitive?.content == "sync_queue" }?.jsonObject
        assertNotNull("sync_queue table must exist in schema 11.json", syncQueueEntity)

        val fields = syncQueueEntity!!["fields"]!!.jsonArray
        val kindField = fields.find { it.jsonObject["columnName"]?.jsonPrimitive?.content == "lastErrorKind" }?.jsonObject
        assertNotNull("lastErrorKind column must exist in schema 11.json sync_queue table", kindField)
        assertEquals("TEXT", kindField!!["affinity"]?.jsonPrimitive?.content)
        assertEquals("false", kindField["notNull"]?.jsonPrimitive?.content)

        val detailField = fields.find { it.jsonObject["columnName"]?.jsonPrimitive?.content == "lastErrorDetail" }?.jsonObject
        assertNotNull("lastErrorDetail column must exist in schema 11.json sync_queue table", detailField)
        assertEquals("TEXT", detailField!!["affinity"]?.jsonPrimitive?.content)
        assertEquals("false", detailField["notNull"]?.jsonPrimitive?.content)

        val createSql = syncQueueEntity["createSql"]!!.jsonPrimitive.content
        assertTrue(createSql.contains("`lastErrorKind` TEXT"))
        assertTrue(createSql.contains("`lastErrorDetail` TEXT"))
    }
}
