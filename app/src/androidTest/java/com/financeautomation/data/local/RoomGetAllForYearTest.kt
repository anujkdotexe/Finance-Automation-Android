package com.financeautomation.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.financeautomation.core.utils.DateSerialUtils
import com.financeautomation.data.local.dao.InflowDao
import com.financeautomation.data.local.dao.PayableDao
import com.financeautomation.data.local.dao.TransactionDao
import com.financeautomation.data.local.entity.InflowEntity
import com.financeautomation.data.local.entity.PayableEntity
import com.financeautomation.data.local.entity.TransactionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Instrumented test verifying real SQLite query semantics for getAllForYear
 * across TransactionDao, InflowDao, and PayableDao using an in-memory Room database.
 */
@RunWith(AndroidJUnit4::class)
class RoomGetAllForYearTest {

    private lateinit var db: FinanceDatabase
    private lateinit var transactionDao: TransactionDao
    private lateinit var inflowDao: InflowDao
    private lateinit var payableDao: PayableDao

    private val zone = ZoneId.of("Asia/Kolkata")
    private val year = 2026

    private val startMillis = ZonedDateTime.of(year, 1, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val endMillis = ZonedDateTime.of(year + 1, 1, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val startSerial = DateSerialUtils.toDateSerial(LocalDate.of(year, 1, 1))
    private val endSerial = DateSerialUtils.toDateSerial(LocalDate.of(year + 1, 1, 1))

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, FinanceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        transactionDao = db.transactionDao()
        inflowDao = db.inflowDao()
        payableDao = db.payableDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun transactionDao_getAllForYear_boundaryAndExclusions() = runBlocking {
        val boundaryStart = createTxn("t_start", startMillis, status = "CONFIRMED", reviewState = "CONFIRMED")
        val beforeStart = createTxn("t_before", startMillis - 1, status = "CONFIRMED", reviewState = "CONFIRMED")
        val midYear = createTxn("t_mid", startMillis + 100_000, status = "CONFIRMED", reviewState = "CONFIRMED")
        val atEnd = createTxn("t_end", endMillis, status = "CONFIRMED", reviewState = "CONFIRMED")
        val voidRow = createTxn("t_void", startMillis + 200_000, status = "Void", reviewState = "CONFIRMED")
        val needsReviewRow = createTxn("t_review", startMillis + 300_000, status = "CONFIRMED", reviewState = "NEEDS_REVIEW")

        transactionDao.insertAll(listOf(boundaryStart, beforeStart, midYear, atEnd, voidRow, needsReviewRow))

        val results = transactionDao.getAllForYear(startMillis, endMillis)
        val ids = results.map { it.id }

        assertTrue("1 Jan 00:00 boundary transaction must be included", ids.contains("t_start"))
        assertTrue("Mid-year transaction must be included", ids.contains("t_mid"))
        assertEquals("Boundary-excluded, void, and needs-review transactions must be omitted", 2, results.size)
    }

    @Test
    fun inflowDao_getAllForYear_boundaryChecks() = runBlocking {
        val inStart = InflowEntity(id = "in_start", timestamp = startMillis, account = "HDFC", amount = 1000.0, inflowType = "Income / Salary", source = "Company", note = "Start")
        val inBefore = InflowEntity(id = "in_before", timestamp = startMillis - 1, account = "HDFC", amount = 1000.0, inflowType = "Income / Salary", source = "Company", note = "Before")
        val inMid = InflowEntity(id = "in_mid", timestamp = startMillis + 50_000, account = "HDFC", amount = 1000.0, inflowType = "Income / Salary", source = "Company", note = "Mid")
        val inEnd = InflowEntity(id = "in_end", timestamp = endMillis, account = "HDFC", amount = 1000.0, inflowType = "Income / Salary", source = "Company", note = "End")

        inflowDao.insertAll(listOf(inStart, inBefore, inMid, inEnd))

        val results = inflowDao.getAllForYear(startMillis, endMillis)
        val ids = results.map { it.id }

        assertTrue("1 Jan 00:00 boundary inflow must be included", ids.contains("in_start"))
        assertTrue("Mid-year inflow must be included", ids.contains("in_mid"))
        assertEquals("Boundary-excluded inflows must be omitted", 2, results.size)
    }

    @Test
    fun payableDao_getAllForYear_boundaryAndNullDateIncurred() = runBlocking {
        val pStart = createPayable("p_start", dateSerial = startSerial, updatedAt = startMillis, status = "Pending")
        val pBefore = createPayable("p_before", dateSerial = startSerial - 1, updatedAt = startMillis, status = "Pending")
        val pEnd = createPayable("p_end", dateSerial = endSerial, updatedAt = endMillis, status = "Pending")
        val pVoid = createPayable("p_void", dateSerial = startSerial + 5, updatedAt = startMillis, status = "VOID")
        val pNullInside = createPayable("p_null_in", dateSerial = null, updatedAt = startMillis + 10_000, status = "Pending")
        val pNullOutside = createPayable("p_null_out", dateSerial = null, updatedAt = startMillis - 1000, status = "Pending")

        payableDao.upsertAll(listOf(pStart, pBefore, pEnd, pVoid, pNullInside, pNullOutside))

        val results = payableDao.getAllForYear(startSerial, endSerial, startMillis, endMillis)
        val ids = results.map { it.uuid }

        assertTrue("Start date payable must be included", ids.contains("p_start"))
        assertTrue("Null-date payable with updatedAt inside year must be included", ids.contains("p_null_in"))
        assertEquals("Void, out-of-boundary, and null-date with updatedAt outside year must be omitted", 2, results.size)
    }

    private fun createTxn(id: String, timestamp: Long, status: String, reviewState: String) =
        TransactionEntity(
            id = id,
            timestamp = timestamp,
            dateSerial = DateSerialUtils.toDateSerial(LocalDate.of(2026, 6, 1)),
            timeSerial = 0.5,
            account = "SBI",
            merchant = "Store",
            totalAmount = 100.0,
            myShare = 100.0,
            owedToMe = 0.0,
            category = "Food & Drink",
            sourcePackage = "com.google.android.apps.messaging",
            status = status,
            syncState = "SYNCED",
            reviewState = reviewState
        )

    private fun createPayable(uuid: String, dateSerial: Int?, updatedAt: Long, status: String) =
        PayableEntity(
            uuid = uuid,
            dateIncurredSerial = dateSerial,
            timeSerial = 0.5,
            paidBy = "Alice",
            whatFor = "Dinner",
            amountIOwe = 50.0,
            dateRepaidSerial = null,
            repaymentStatus = status,
            tabName = "Nov26",
            sheetRowIndex = null,
            updatedAt = updatedAt
        )
}
