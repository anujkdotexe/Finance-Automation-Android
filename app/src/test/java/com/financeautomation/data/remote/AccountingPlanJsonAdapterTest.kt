package com.financeautomation.data.remote

import com.financeautomation.domain.model.AccountingPlan
import com.financeautomation.domain.model.CellEdit
import com.financeautomation.domain.model.NewRow
import com.financeautomation.domain.model.SettledRef
import com.financeautomation.domain.model.TableType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountingPlanJsonAdapterTest {

    /**
     * Legacy JSON sample formatted identically to org.json output produced by commit fbc6a8f.
     * Verifies that existing plans stored in sync_queue.planJson survive app updates seamlessly.
     */
    private val legacyJsonSample = """
        {
          "excess": 12.5,
          "unmatched": false,
          "message": "Split expense with partial settlement",
          "edits": [
            {
              "tab": "Nov26",
              "row": 5,
              "column": "E",
              "value": "Settled"
            },
            {
              "tab": "Nov26",
              "row": 6,
              "column": "F",
              "value": null
            }
          ],
          "appends": [
            {
              "table": "DEBIT",
              "tab": "Nov26",
              "uuid": "txn_test12345678",
              "cells": [
                "11/11/2026",
                "15:30:00",
                "Dinner",
                300.0,
                "Food & Drink",
                "SBI",
                100.0,
                200.0,
                "Alice, Bob",
                "Pending",
                null
              ]
            }
          ],
          "settled": [
            {
              "tab": "Nov26",
              "row": 4,
              "uuid": "txn_prev12345678",
              "applied": 50.0,
              "remaining": 0.0
            },
            {
              "tab": "Nov26",
              "row": 7,
              "uuid": null,
              "applied": 25.0,
              "remaining": 10.0
            }
          ]
        }
    """.trimIndent()

    @Test
    fun testParseLegacyOrgJsonFormat() {
        val plan = AccountingPlanJsonAdapter.fromJson(legacyJsonSample)

        assertEquals(12.5, plan.excess, 0.001)
        assertFalse(plan.unmatched)
        assertEquals("Split expense with partial settlement", plan.message)

        // Edits check
        assertEquals(2, plan.edits.size)
        assertEquals("Nov26", plan.edits[0].tab)
        assertEquals(5, plan.edits[0].row)
        assertEquals("E", plan.edits[0].column)
        assertEquals("Settled", plan.edits[0].value)

        assertEquals("Nov26", plan.edits[1].tab)
        assertEquals(6, plan.edits[1].row)
        assertEquals("F", plan.edits[1].column)
        assertNull(plan.edits[1].value)

        // Appends check
        assertEquals(1, plan.appends.size)
        val append = plan.appends[0]
        assertEquals(TableType.DEBIT, append.table)
        assertEquals("Nov26", append.tab)
        assertEquals("txn_test12345678", append.uuid)
        assertEquals(11, append.cells.size)
        assertEquals("Dinner", append.cells[2])
        assertEquals("Alice, Bob", append.cells[8])
        assertNull(append.cells[10])

        // Settled check
        assertEquals(2, plan.settled.size)
        assertEquals("Nov26", plan.settled[0].tab)
        assertEquals(4, plan.settled[0].row)
        assertEquals("txn_prev12345678", plan.settled[0].uuid)
        assertEquals(50.0, plan.settled[0].applied, 0.001)
        assertEquals(0.0, plan.settled[0].remaining, 0.001)

        assertEquals("Nov26", plan.settled[1].tab)
        assertEquals(7, plan.settled[1].row)
        assertNull(plan.settled[1].uuid)
        assertEquals(25.0, plan.settled[1].applied, 0.001)
        assertEquals(10.0, plan.settled[1].remaining, 0.001)
    }

    @Test
    fun testRoundTripSerialization() {
        val original = AccountingPlan(
            edits = listOf(
                CellEdit("Nov26", 10, "K", "Verified"),
                CellEdit("Nov26", 11, "L", null)
            ),
            appends = listOf(
                NewRow(
                    table = TableType.DEBIT,
                    tab = "Nov26",
                    uuid = "txn_roundtrip_999",
                    cells = listOf("11/11/2026", 150.0, "Lunch", null, true)
                )
            ),
            settled = listOf(
                SettledRef("Nov26", 8, "txn_settle_111", 75.0, 0.0),
                SettledRef("Nov26", 9, null, 25.0, 50.0)
            ),
            excess = 5.0,
            unmatched = true,
            message = "Round trip verification"
        )

        val jsonString = AccountingPlanJsonAdapter.toJson(original)
        val deserialized = AccountingPlanJsonAdapter.fromJson(jsonString)

        assertEquals(original.excess, deserialized.excess, 0.001)
        assertEquals(original.unmatched, deserialized.unmatched)
        assertEquals(original.message, deserialized.message)
        assertEquals(original.edits.size, deserialized.edits.size)
        assertEquals(original.edits[0].value, deserialized.edits[0].value)
        assertNull(deserialized.edits[1].value)
        assertEquals(original.appends.size, deserialized.appends.size)
        assertEquals(original.appends[0].uuid, deserialized.appends[0].uuid)
        assertEquals(original.settled.size, deserialized.settled.size)
        assertEquals(original.settled[0].uuid, deserialized.settled[0].uuid)
        assertNull(deserialized.settled[1].uuid)
    }

    @Test
    fun testRoundTripStringCellsPreserveTypes() {
        val originalPlan = AccountingPlan(
            appends = listOf(
                NewRow(
                    table = TableType.DEBIT,
                    tab = "Nov26",
                    uuid = "txn_numeric_strings",
                    cells = listOf("42", "007", "1e3", "true", "Infinity", 42, 3.14, true, false, null)
                )
            ),
            edits = listOf(
                CellEdit("Nov26", 5, "D", "42"),
                CellEdit("Nov26", 6, "D", "true"),
                CellEdit("Nov26", 7, "D", "Infinity")
            ),
            message = "Preserve string types"
        )

        val json = AccountingPlanJsonAdapter.toJson(originalPlan)
        val deserialized = AccountingPlanJsonAdapter.fromJson(json)

        val cells = deserialized.appends[0].cells
        assertEquals("42", cells[0])
        assertEquals("007", cells[1])
        assertEquals("1e3", cells[2])
        assertEquals("true", cells[3])
        assertEquals("Infinity", cells[4])
        assertEquals(42, cells[5])
        assertEquals(3.14, cells[6])
        assertEquals(true, cells[7])
        assertEquals(false, cells[8])
        assertNull(cells[9])

        assertEquals("42", deserialized.edits[0].value)
        assertEquals("true", deserialized.edits[1].value)
        assertEquals("Infinity", deserialized.edits[2].value)
    }
}
