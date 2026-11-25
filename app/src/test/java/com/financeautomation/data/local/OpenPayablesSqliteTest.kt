package com.financeautomation.data.local

import com.financeautomation.data.local.dao.PayableSql
import org.junit.Assert.assertEquals
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/** The open debt queries of PayableDao, run on real SQLite. */
class OpenPayablesSqliteTest {

    private fun withPayables(block: (Connection) -> Unit) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            c.createStatement().use { stmt ->
                stmt.execute(
                    "CREATE TABLE payables (uuid TEXT NOT NULL, dateIncurredSerial INTEGER, timeSerial REAL, " +
                        "paidBy TEXT NOT NULL, whatFor TEXT NOT NULL, amountIOwe REAL NOT NULL, " +
                        "dateRepaidSerial INTEGER, repaymentStatus TEXT NOT NULL, tabName TEXT NOT NULL, " +
                        "sheetRowIndex INTEGER, updatedAt INTEGER NOT NULL, PRIMARY KEY(uuid))"
                )
                listOf(
                    Triple("p_pending", 46001, "Pending"),
                    Triple("p_partial", 46003, "Partial"),
                    Triple("p_partial_lower", 46002, "partial"),
                    Triple("p_repaid", 46004, "Repaid"),
                    Triple("p_carried", 46005, "Carried Fwd"),
                    Triple("p_void", 46006, "Void")
                ).forEach { (uuid, date, status) ->
                    stmt.execute(
                        "INSERT INTO payables VALUES ('$uuid', $date, 0.5, 'Asha', 'Dinner', 100.0, NULL, " +
                            "'$status', 'Nov26', 4, 0)"
                    )
                }
            }
            block(c)
        }
    }

    private fun Connection.ids(sql: String): List<String> = createStatement().use { stmt ->
        val rs = stmt.executeQuery(sql)
        val out = mutableListOf<String>()
        while (rs.next()) out.add(rs.getString("uuid"))
        out
    }

    @Test
    fun aPartlyRepaidDebtIsStillOpen() = withPayables { c ->
        assertEquals(setOf("p_pending", "p_partial", "p_partial_lower"), c.ids(PayableSql.SELECT_OPEN).toSet())
    }

    @Test
    fun theObservedListIsNewestFirst() = withPayables { c ->
        assertEquals(listOf("p_partial", "p_partial_lower", "p_pending"), c.ids(PayableSql.SELECT_OPEN_NEWEST_FIRST))
    }
}
