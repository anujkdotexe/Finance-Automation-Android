package com.financeautomation.domain.usecase

import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.Inflow
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ExportMonthCsvUseCaseTest {

    private fun transactionRepo(list: List<Transaction> = emptyList()): TransactionRepository =
        java.lang.reflect.Proxy.newProxyInstance(
            TransactionRepository::class.java.classLoader,
            arrayOf(TransactionRepository::class.java)
        ) { _, method, _ ->
            if (method.name == "getTransactions") flowOf(list) else throw UnsupportedOperationException(method.name)
        } as TransactionRepository

    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val useCase = ExportMonthCsvUseCase(
        transactionRepository = transactionRepo(),
        inflowRepository = object : com.financeautomation.domain.repository.InflowRepository {
            override fun getInflowsByRange(start: Long, end: Long): Flow<List<Inflow>> = flowOf(emptyList())
            override fun getAllInflows(): Flow<List<Inflow>> = flowOf(emptyList())
            override suspend fun getInflowById(id: String): Inflow? = null
            override suspend fun insertInflow(inflow: Inflow) {}
            override suspend fun updateStatus(id: String, status: String) {}
            override suspend fun voidInflow(id: String) = com.financeautomation.core.model.Result.Success(Unit)
        }
    )

    private fun ts(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0, s: Int = 0): Long =
        ZonedDateTime.of(y, m, d, h, min, s, 0, kolkata).toInstant().toEpochMilli()

    private fun txn(
        id: String,
        at: Long,
        merchant: String = "Cafe",
        category: String = "Food & Drink",
        total: Double = 120.0,
        share: Double = 60.0,
        owed: Double = 60.0,
        status: TransactionStatus = TransactionStatus.PENDING,
        companions: String = "Asha"
    ) = Transaction(
        id = id, timestamp = at, dateSerial = 0, timeSerial = 0.0, account = "SBI", merchant = merchant,
        category = category, totalAmount = total, companions = companions, myShare = share, owedToMe = owed,
        status = status
    )

    private fun inflow(id: String, at: Long, status: String = "CONFIRMED", note: String = "Salary") =
        Inflow(id, at, 50000.0, "SBI", "Income / Salary", "Employer", note, status = status)

    private fun lines(csv: String) = csv.trimEnd().split("\n")

    @Test
    fun headerAndRowUseTheDocumentedColumns() {
        val csv = useCase.build(2026, 11, listOf(txn("a", ts(2026, 11, 5, 9, 30, 15))), emptyList())
        val l = lines(csv)
        assertEquals("Date,Time,Description,Category,Account,Amount,My share,Owed to me,Status,Companions", l[0])
        assertEquals("2026-11-05,09:30:15,Cafe,Food & Drink,SBI,120.00,60.00,60.00,Pending,Asha", l[1])
    }

    @Test
    fun incomeIsWrittenAsASecondSectionAfterOneBlankLine() {
        val csv = useCase.build(2026, 11, listOf(txn("a", ts(2026, 11, 5))), listOf(inflow("i", ts(2026, 11, 6))))
        val l = lines(csv)
        assertEquals("", l[2])
        assertEquals("Income and refunds", l[3])
        assertEquals("Date,Time,Source,Reason,Type,Account,Amount", l[4])
        assertEquals("2026-11-06,12:00:00,Employer,Salary,Income / Salary,SBI,50000.00", l[5])
    }

    @Test
    fun commasQuotesAndNewlinesAreEscaped() {
        val csv = useCase.build(
            2026, 11,
            listOf(txn("a", ts(2026, 11, 5), merchant = "Dinner, \"Chez\" Bob\nlate")),
            emptyList()
        )
        assertTrue(csv, csv.contains("\"Dinner, \"\"Chez\"\" Bob\nlate\""))
    }

    @Test
    fun formulaInjectionIsGuarded() {
        for (bad in listOf("=SUM(A1)", "+1", "-1", "@cmd", "\tx", " =1+1")) {
            val guarded = ExportMonthCsvUseCase.guard(bad)
            assertTrue(bad, guarded.startsWith("'"))
        }
        assertEquals("Cafe", ExportMonthCsvUseCase.guard("Cafe"))
        assertEquals("", ExportMonthCsvUseCase.guard(""))
        val csv = useCase.build(2026, 11, listOf(txn("a", ts(2026, 11, 5), merchant = "=HYPERLINK(\"x\")")), emptyList())
        assertTrue(csv, csv.contains("\"'=HYPERLINK(\"\"x\"\")\""))
        val income = useCase.build(2026, 11, emptyList(), listOf(inflow("i", ts(2026, 11, 6), note = "@bad")))
        assertTrue(income, income.contains(",'@bad,"))
    }

    @Test
    fun numbersAreNotGuardedOrQuoted() {
        val csv = useCase.build(2026, 11, listOf(txn("a", ts(2026, 11, 5), total = 1234.5, share = 0.0, owed = 0.0)), emptyList())
        assertTrue(csv, csv.contains(",1234.50,0.00,0.00,"))
    }

    @Test
    fun monthBoundariesFollowAsiaKolkata() {
        val lastSecondOfOctober = ts(2026, 10, 31, 23, 59, 59)
        val firstSecondOfNovember = ts(2026, 11, 1, 0, 0, 0)
        val lastSecondOfNovember = ts(2026, 11, 30, 23, 59, 59)
        val firstSecondOfDecember = ts(2026, 12, 1, 0, 0, 0)
        val csv = useCase.build(
            2026, 11,
            listOf(
                txn("oct", lastSecondOfOctober, merchant = "Oct"),
                txn("nov1", firstSecondOfNovember, merchant = "NovFirst"),
                txn("nov30", lastSecondOfNovember, merchant = "NovLast"),
                txn("dec", firstSecondOfDecember, merchant = "Dec")
            ),
            emptyList()
        )
        assertFalse(csv.contains(",Oct,"))
        assertTrue(csv.contains("2026-11-01,00:00:00,NovFirst,"))
        assertTrue(csv.contains("2026-11-30,23:59:59,NovLast,"))
        assertFalse(csv.contains(",Dec,"))
    }

    @Test
    fun aTimestampJustBeforeKolkataMidnightInUtcStillBelongsToTheLocalDay() {
        // 2026-11-01 00:00 IST is 2026-10-31 18:30 UTC
        val utcInstant = ZonedDateTime.of(2026, 10, 31, 18, 30, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        val csv = useCase.build(2026, 11, listOf(txn("a", utcInstant, merchant = "Edge")), emptyList())
        assertTrue(csv, csv.contains("2026-11-01,00:00:00,Edge,"))
    }

    @Test
    fun voidRowsAreExcludedFromBothSections() {
        val csv = useCase.build(
            2026, 11,
            listOf(
                txn("keep", ts(2026, 11, 5), merchant = "Keep"),
                txn("void", ts(2026, 11, 6), merchant = "[Void] Gone", status = TransactionStatus.VOID)
            ),
            listOf(inflow("i1", ts(2026, 11, 7), note = "Kept"), inflow("i2", ts(2026, 11, 8), status = "VOID", note = "Voided"))
        )
        assertTrue(csv.contains("Keep"))
        assertFalse(csv.contains("Gone"))
        assertTrue(csv.contains("Kept"))
        assertFalse(csv.contains("Voided"))
    }

    @Test
    fun rowsAreOrderedOldestFirst() {
        val csv = useCase.build(
            2026, 11,
            listOf(txn("late", ts(2026, 11, 20), merchant = "Late"), txn("early", ts(2026, 11, 2), merchant = "Early")),
            emptyList()
        )
        assertTrue(csv.indexOf("Early") < csv.indexOf("Late"))
    }

    @Test
    fun emptyMonthStillHasBothHeaders() {
        val csv = useCase.build(2026, 11, emptyList(), emptyList())
        val l = lines(csv)
        assertEquals("Date,Time,Description,Category,Account,Amount,My share,Owed to me,Status,Companions", l[0])
        assertTrue(csv.contains("Income and refunds"))
    }

    @Test
    fun invokeReadsFromTheRepositories() = runBlocking {
        val repoUseCase = ExportMonthCsvUseCase(
            transactionRepository = transactionRepo(
                listOf(txn("a", ts(2026, 11, 5), merchant = "FromRepo"))
            ),
            inflowRepository = object : com.financeautomation.domain.repository.InflowRepository {
                override fun getInflowsByRange(start: Long, end: Long): Flow<List<Inflow>> = flowOf(emptyList())
                override fun getAllInflows(): Flow<List<Inflow>> = flowOf(emptyList())
                override suspend fun getInflowById(id: String): Inflow? = null
                override suspend fun insertInflow(inflow: Inflow) {}
                override suspend fun updateStatus(id: String, status: String) {}
                override suspend fun voidInflow(id: String) = com.financeautomation.core.model.Result.Success(Unit)
            }
        )
        assertTrue(repoUseCase(2026, 11).contains("FromRepo"))
    }
}
