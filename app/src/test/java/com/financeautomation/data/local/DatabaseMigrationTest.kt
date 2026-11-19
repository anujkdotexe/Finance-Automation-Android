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

    @Test
    fun testMigration12to13_matchesSchema13Json() {
        assertEquals(12, DatabaseMigrations.MIGRATION_12_13.startVersion)
        assertEquals(13, DatabaseMigrations.MIGRATION_12_13.endVersion)
        assertTrue(DatabaseMigrations.ALL_MIGRATIONS.contains(DatabaseMigrations.MIGRATION_12_13))

        val root = loadSchema(13)
        val database = root["database"]!!.jsonObject
        assertEquals("13", database["version"]!!.jsonPrimitive.content)
        val entities = database["entities"]!!.jsonArray

        val executed = mutableListOf<String>()
        val db = java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(androidx.sqlite.db.SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL") executed.add(args!![0] as String)
            null
        } as androidx.sqlite.db.SupportSQLiteDatabase
        DatabaseMigrations.MIGRATION_12_13.migrate(db)

        val expected = mutableListOf<String>()
        val entity = entities.find { it.jsonObject["tableName"]?.jsonPrimitive?.content == "inflows" }?.jsonObject
        assertNotNull("inflows must exist in schema 13.json", entity)
        expected.add(entity!!["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", "inflows"))
        entity["indices"]?.jsonArray?.forEach { idx ->
            expected.add(idx.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", "inflows"))
        }

        assertEquals(expected.map(::normalizeSql).sorted(), executed.map(::normalizeSql).sorted())

        val inflowSql = expected.filter { it.contains("inflows") }
        assertTrue(inflowSql.any { it.contains("`index_inflows_timestamp`") })
        assertTrue(inflowSql.any { it.contains("`index_inflows_inflowType`") })
        assertTrue(inflowSql.any { it.contains("`index_inflows_account`") })
        assertTrue(inflowSql.any { it.contains("`index_inflows_status`") })
    }

    @Test
    fun testSchema13KeepsEverySchema12Table() {
        fun tables(root: kotlinx.serialization.json.JsonObject) =
            root["database"]!!.jsonObject["entities"]!!.jsonArray.map { it.jsonObject["tableName"]!!.jsonPrimitive.content }.toSet()
        val v12 = tables(loadSchema(12))
        val v13 = tables(loadSchema(13))
        assertTrue(v13.containsAll(v12))
        assertEquals(setOf("inflows"), v13 - v12)
    }

    @Test
    fun testMigration13to14_matchesSchema14Json() {
        assertEquals(13, DatabaseMigrations.MIGRATION_13_14.startVersion)
        assertEquals(14, DatabaseMigrations.MIGRATION_13_14.endVersion)
        assertTrue(DatabaseMigrations.ALL_MIGRATIONS.contains(DatabaseMigrations.MIGRATION_13_14))

        val root = loadSchema(14)
        val database = root["database"]!!.jsonObject
        assertEquals("14", database["version"]!!.jsonPrimitive.content)
        val entities = database["entities"]!!.jsonArray

        val executed = mutableListOf<String>()
        val db = java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(androidx.sqlite.db.SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL") executed.add(args!![0] as String)
            null
        } as androidx.sqlite.db.SupportSQLiteDatabase
        DatabaseMigrations.MIGRATION_13_14.migrate(db)

        val entity = entities.find { it.jsonObject["tableName"]?.jsonPrimitive?.content == "budgets" }?.jsonObject
        assertNotNull("budgets must exist in schema 14.json", entity)
        val expected = mutableListOf(entity!!["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", "budgets"))
        entity["indices"]?.jsonArray?.forEach { idx ->
            expected.add(idx.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", "budgets"))
        }
        assertEquals(expected.map(::normalizeSql).sorted(), executed.map(::normalizeSql).sorted())

        val columns = entity["fields"]!!.jsonArray.map { it.jsonObject["columnName"]!!.jsonPrimitive.content }
        assertEquals(listOf("category", "monthlyAmount", "updatedAt", "dirty", "deleted"), columns)
        assertEquals("category", entity["primaryKey"]!!.jsonObject["columnNames"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun testSchema14KeepsEverySchema13Table() {
        fun tables(root: kotlinx.serialization.json.JsonObject) =
            root["database"]!!.jsonObject["entities"]!!.jsonArray.map { it.jsonObject["tableName"]!!.jsonPrimitive.content }.toSet()
        val v13 = tables(loadSchema(13))
        val v14 = tables(loadSchema(14))
        assertTrue(v14.containsAll(v13))
        assertEquals(setOf("budgets"), v14 - v13)
    }

    @Test
    fun testMigration14to15_addsNoteColumnToTransactionsPendingCapturesAndSubscriptions() {
        assertEquals(14, DatabaseMigrations.MIGRATION_14_15.startVersion)
        assertEquals(15, DatabaseMigrations.MIGRATION_14_15.endVersion)
        assertTrue(DatabaseMigrations.ALL_MIGRATIONS.contains(DatabaseMigrations.MIGRATION_14_15))

        val executed = mutableListOf<String>()
        val db = java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(androidx.sqlite.db.SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL") executed.add(args!![0] as String)
            null
        } as androidx.sqlite.db.SupportSQLiteDatabase

        DatabaseMigrations.MIGRATION_14_15.migrate(db)

        assertEquals(3, executed.size)
        assertTrue(executed.any { it.contains("ALTER TABLE `transactions` ADD COLUMN `note`") })
        assertTrue(executed.any { it.contains("ALTER TABLE `pending_captures` ADD COLUMN `note`") })
        assertTrue(executed.any { it.contains("ALTER TABLE `subscriptions` ADD COLUMN `note`") })
    }

    @Test
    fun testSchema16KeepsEverySchema15Table() {
        fun tables(root: kotlinx.serialization.json.JsonObject) =
            root["database"]!!.jsonObject["entities"]!!.jsonArray.map { it.jsonObject["tableName"]!!.jsonPrimitive.content }.toSet()
        val v15 = tables(loadSchema(15))
        val v16 = tables(loadSchema(16))
        assertTrue(v16.containsAll(v15))
        assertEquals(emptySet<String>(), v16 - v15)
    }

    @Test
    fun testDatabaseDeclaresVersion16() {
        val source = java.io.File("src/main/java/com/financeautomation/data/local/FinanceDatabase.kt").readText()
        assertTrue(source.contains("version = 16"))
    }

    @Test
    fun testMigration15to16_addsTripDatesAndClosedSubscriptionDate() {
        assertEquals(15, DatabaseMigrations.MIGRATION_15_16.startVersion)
        assertEquals(16, DatabaseMigrations.MIGRATION_15_16.endVersion)
        assertTrue(DatabaseMigrations.ALL_MIGRATIONS.contains(DatabaseMigrations.MIGRATION_15_16))

        val executed = mutableListOf<String>()
        val db = java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(androidx.sqlite.db.SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL") executed.add(args!![0] as String)
            null
        } as androidx.sqlite.db.SupportSQLiteDatabase

        DatabaseMigrations.MIGRATION_15_16.migrate(db)

        assertEquals(6, executed.size)
        assertTrue(executed.any { it.contains("ALTER TABLE `trip_tags` ADD COLUMN `startDateEpochDay`") })
        assertTrue(executed.any { it.contains("ALTER TABLE `trip_tags` ADD COLUMN `endDateEpochDay`") })
        assertTrue(executed.any { it.contains("ALTER TABLE `subscriptions` ADD COLUMN `closedDateEpochDay`") })
        assertTrue(executed.any { it.contains("UPDATE trip_tags") && it.contains("startDateEpochDay") })
        assertTrue(executed.any { it.contains("UPDATE trip_tags") && it.contains("endDateEpochDay") })
        assertTrue(executed.any { it.contains("UPDATE subscriptions") && it.contains("closedDateEpochDay") })
    }

    @Test
    fun testMigration15to16_realRowsDataTransformation() {
        val hasJdbc = try {
            Class.forName("org.sqlite.JDBC")
            true
        } catch (_: Throwable) {
            false
        }

        if (hasJdbc) {
            val conn = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:")
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE trip_tags (id TEXT PRIMARY KEY, name TEXT, isActive INTEGER, isArchived INTEGER, createdAt INTEGER, updatedAt INTEGER, dirty INTEGER)")
                stmt.execute("CREATE TABLE transactions (uuid TEXT PRIMARY KEY, tag TEXT, dateSerial INTEGER)")
                stmt.execute("CREATE TABLE subscriptions (id TEXT PRIMARY KEY, name TEXT, active INTEGER, createdAt INTEGER, updatedAt INTEGER, dirty INTEGER, note TEXT)")

                // 1. Active trip with transactions
                stmt.execute("INSERT INTO trip_tags VALUES ('tag_goa', 'Goa 2026', 1, 0, 1731974400000, 1731974400000, 0)")
                stmt.execute("INSERT INTO transactions VALUES ('tx_1', 'Goa 2026', 46330)")
                stmt.execute("INSERT INTO transactions VALUES ('tx_2', 'Goa 2026', 46335)")

                // 2. Ended trip with transactions
                stmt.execute("INSERT INTO trip_tags VALUES ('tag_delhi', 'Delhi 2026', 0, 0, 1731000000000, 1731500000000, 0)")
                stmt.execute("INSERT INTO transactions VALUES ('tx_3', 'Delhi 2026', 46300)")
                stmt.execute("INSERT INTO transactions VALUES ('tx_4', 'Delhi 2026', 46310)")

                // 3. Trip with no transactions
                stmt.execute("INSERT INTO trip_tags VALUES ('tag_kerala', 'Kerala 2026', 1, 0, 1728000000000, 1728000000000, 0)")

                // 4. Inactive/cancelled subscription
                stmt.execute("INSERT INTO subscriptions VALUES ('sub_gym', 'Gym', 0, 1720000000000, 1725000000000, 0, 'Cancelled')")
                // 5. Active subscription
                stmt.execute("INSERT INTO subscriptions VALUES ('sub_netflix', 'Netflix', 1, 1720000000000, 1725000000000, 0, 'Active')")
            }

            val db = java.lang.reflect.Proxy.newProxyInstance(
                javaClass.classLoader, arrayOf(androidx.sqlite.db.SupportSQLiteDatabase::class.java)
            ) { _, method, args ->
                if (method.name == "execSQL") {
                    conn.createStatement().use { it.execute(args!![0] as String) }
                }
                null
            } as androidx.sqlite.db.SupportSQLiteDatabase

            DatabaseMigrations.MIGRATION_15_16.migrate(db)

            conn.createStatement().use { stmt ->
                // Check Active trip Goa
                stmt.executeQuery("SELECT startDateEpochDay, endDateEpochDay FROM trip_tags WHERE id = 'tag_goa'").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(46330 - 25569, rs.getInt(1))
                    rs.getInt(2)
                    assertTrue("endDateEpochDay must be null for active trip", rs.wasNull())
                }

                // Check Ended trip Delhi
                stmt.executeQuery("SELECT startDateEpochDay, endDateEpochDay FROM trip_tags WHERE id = 'tag_delhi'").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(46300 - 25569, rs.getInt(1))
                    assertEquals(46310 - 25569, rs.getInt(2))
                }

                // Check Trip with no transactions (Kerala)
                stmt.executeQuery("SELECT startDateEpochDay, endDateEpochDay FROM trip_tags WHERE id = 'tag_kerala'").use { rs ->
                    assertTrue(rs.next())
                    assertEquals((1728000000000L / 86400000L).toInt(), rs.getInt(1))
                    rs.getInt(2)
                    assertTrue("endDateEpochDay must be null for trip with no transactions", rs.wasNull())
                }

                // Check Inactive subscription
                stmt.executeQuery("SELECT closedDateEpochDay FROM subscriptions WHERE id = 'sub_gym'").use { rs ->
                    assertTrue(rs.next())
                    assertEquals((1725000000000L / 86400000L).toInt(), rs.getInt(1))
                }

                // Check Active subscription
                stmt.executeQuery("SELECT closedDateEpochDay FROM subscriptions WHERE id = 'sub_netflix'").use { rs ->
                    assertTrue(rs.next())
                    rs.getInt(1)
                    assertTrue("closedDateEpochDay must be null for active subscription", rs.wasNull())
                }
            }
            conn.close()
        }
    }
}
