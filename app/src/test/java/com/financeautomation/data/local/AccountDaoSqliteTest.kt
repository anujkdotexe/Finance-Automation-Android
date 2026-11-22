package com.financeautomation.data.local

import com.financeautomation.data.local.dao.AccountDao
import com.financeautomation.data.repository.AccountRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AccountDaoSqliteTest {

    @Test
    fun testMakeOnlyDefaultSqlStatement_onRealSqlite_updatesOnlyChangedRows() {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.use { c ->
            c.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE accounts (
                        id TEXT PRIMARY KEY NOT NULL,
                        name TEXT NOT NULL,
                        lastFourDigits TEXT,
                        isDefault INTEGER NOT NULL DEFAULT 0,
                        isArchived INTEGER NOT NULL DEFAULT 0,
                        displayOrder INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0,
                        dirty INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )

                // Initial state:
                // SBI is currently default (isDefault=1), dirty=0, updatedAt=1000
                // HDFC is not default (isDefault=0), dirty=0, updatedAt=1000
                // Cash is not default (isDefault=0), dirty=0, updatedAt=1000
                stmt.execute("INSERT INTO accounts (id, name, isDefault, dirty, updatedAt) VALUES ('acc_sbi', 'SBI', 1, 0, 1000)")
                stmt.execute("INSERT INTO accounts (id, name, isDefault, dirty, updatedAt) VALUES ('acc_hdfc', 'HDFC', 0, 0, 1000)")
                stmt.execute("INSERT INTO accounts (id, name, isDefault, dirty, updatedAt) VALUES ('acc_cash', 'Cash', 0, 0, 1000)")
            }

            // Exact statement from AccountDao.kt:
            // UPDATE accounts SET isDefault = CASE WHEN id = :id THEN 1 ELSE 0 END, dirty = 1, updatedAt = :updatedAt WHERE isDefault != CASE WHEN id = :id THEN 1 ELSE 0 END
            val sql = "UPDATE accounts SET isDefault = CASE WHEN id = ? THEN 1 ELSE 0 END, dirty = 1, updatedAt = ? WHERE isDefault != CASE WHEN id = ? THEN 1 ELSE 0 END"
            c.prepareStatement(sql).use { ps ->
                ps.setString(1, "acc_hdfc")
                ps.setLong(2, 5000L)
                ps.setString(3, "acc_hdfc")
                val rowsAffected = ps.executeUpdate()
                assertEquals("Exactly 2 rows must change (old default cleared, new default set)", 2, rowsAffected)
            }

            // Verify the accounts table
            c.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT id, isDefault, dirty, updatedAt FROM accounts ORDER BY id")
                val accounts = mutableMapOf<String, Triple<Int, Int, Long>>()
                while (rs.next()) {
                    accounts[rs.getString("id")] = Triple(
                        rs.getInt("isDefault"),
                        rs.getInt("dirty"),
                        rs.getLong("updatedAt")
                    )
                }

                // 1. Exactly one default account exists
                val defaultCount = accounts.values.count { it.first == 1 }
                assertEquals("Exactly one account must be default", 1, defaultCount)

                // 2. HDFC is now default, dirty = 1, updatedAt = 5000
                val hdfc = accounts["acc_hdfc"]!!
                assertEquals("HDFC must be default", 1, hdfc.first)
                assertEquals("HDFC must be marked dirty", 1, hdfc.second)
                assertEquals("HDFC updatedAt must be updated to 5000", 5000L, hdfc.third)

                // 3. SBI is no longer default, dirty = 1, updatedAt = 5000
                val sbi = accounts["acc_sbi"]!!
                assertEquals("SBI must not be default", 0, sbi.first)
                assertEquals("SBI must be marked dirty", 1, sbi.second)
                assertEquals("SBI updatedAt must be updated to 5000", 5000L, sbi.third)

                // 4. Cash was not default before and is not default now:
                // MUST REMAIN UNTOUCHED (dirty = 0, updatedAt = 1000)
                val cash = accounts["acc_cash"]!!
                assertEquals("Cash must remain non-default", 0, cash.first)
                assertEquals("Cash must NOT be marked dirty", 0, cash.second)
                assertEquals("Cash updatedAt must remain 1000", 1000L, cash.third)
            }

            // 5. Calling makeOnlyDefault again with the same default account ('acc_hdfc')
            // should affect 0 rows because isDefault already matches the target
            c.prepareStatement(sql).use { ps ->
                ps.setString(1, "acc_hdfc")
                ps.setLong(2, 9000L)
                ps.setString(3, "acc_hdfc")
                val rowsAffected = ps.executeUpdate()
                assertEquals("No rows should be updated when the account is already the sole default", 0, rowsAffected)
            }
        }
    }

    @Test
    fun testAccountRepositoryImpl_usesInjectedClock() = runBlocking {
        val fixedMillis = 1732276800000L
        val fixedClock = Clock.fixed(Instant.ofEpochMilli(fixedMillis), ZoneOffset.UTC)

        var passedId: String? = null
        var passedUpdatedAt: Long? = null

        val recordingDao = java.lang.reflect.Proxy.newProxyInstance(
            AccountDao::class.java.classLoader,
            arrayOf(AccountDao::class.java)
        ) { _, method, args ->
            if (method.name == "makeOnlyDefault") {
                passedId = args[0] as String
                passedUpdatedAt = args[1] as Long
            }
            null
        } as AccountDao

        val repo = AccountRepositoryImpl(
            accountDao = recordingDao,
            clock = fixedClock
        )

        repo.setDefaultAccount("acc_hdfc")

        assertEquals("acc_hdfc", passedId)
        assertEquals("makeOnlyDefault must receive timestamp from injected clock", fixedMillis, passedUpdatedAt)
    }
}
