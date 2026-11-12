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

    private fun loadSchema(version: Int): kotlinx.serialization.json.JsonObject {
        val rel = "schemas/com.financeautomation.data.local.FinanceDatabase/$version.json"
        val file = File(rel).takeIf { it.exists() } ?: File("app/$rel")
        assertTrue("Schema $version.json must exist", file.exists())
        return Json { ignoreUnknownKeys = true }.parseToJsonElement(file.readText()).jsonObject
    }

    private fun normalizeSql(sql: String) = sql.replace(Regex("\\s+"), " ").replace("( ", "(").replace(" )", ")").trim()

    @Test
    fun testMigration11to12_matchesSchema12Json() {
        assertEquals(11, DatabaseMigrations.MIGRATION_11_12.startVersion)
        assertEquals(12, DatabaseMigrations.MIGRATION_11_12.endVersion)
        assertTrue(DatabaseMigrations.ALL_MIGRATIONS.contains(DatabaseMigrations.MIGRATION_11_12))

        val root = loadSchema(12)
        val database = root["database"]!!.jsonObject
        assertEquals("12", database["version"]!!.jsonPrimitive.content)
        val entities = database["entities"]!!.jsonArray

        // Run the migration against a recording database and compare each statement with schema 12.json
        val executed = mutableListOf<String>()
        val db = java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(androidx.sqlite.db.SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL") executed.add(args!![0] as String)
            null
        } as androidx.sqlite.db.SupportSQLiteDatabase
        DatabaseMigrations.MIGRATION_11_12.migrate(db)

        val expected = mutableListOf<String>()
        for (table in listOf("subscriptions", "subscription_cycles")) {
            val entity = entities.find { it.jsonObject["tableName"]?.jsonPrimitive?.content == table }?.jsonObject
            assertNotNull("$table must exist in schema 12.json", entity)
            expected.add(entity!!["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            entity["indices"]?.jsonArray?.forEach { idx ->
                expected.add(idx.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            }
        }
        assertEquals(expected.map(::normalizeSql).sorted(), executed.map(::normalizeSql).sorted())

        // The cycle table has the unique (subscriptionId, dueEpochDay) index and the subscriptionId index
        val cycleSql = expected.filter { it.contains("subscription_cycles") }
        assertTrue(cycleSql.any { it.startsWith("CREATE UNIQUE INDEX") && it.contains("`subscriptionId`, `dueEpochDay`") })
        assertTrue(cycleSql.any { it.startsWith("CREATE INDEX") && it.contains("(`subscriptionId`)") })
    }

    @Test
    fun testSchema12KeepsEverySchema11Table() {
        fun tables(root: kotlinx.serialization.json.JsonObject) =
            root["database"]!!.jsonObject["entities"]!!.jsonArray.map { it.jsonObject["tableName"]!!.jsonPrimitive.content }.toSet()
        val v11 = tables(loadSchema(11))
        val v12 = tables(loadSchema(12))
        assertTrue(v12.containsAll(v11))
        assertEquals(setOf("subscriptions", "subscription_cycles"), v12 - v11)
    }
}
