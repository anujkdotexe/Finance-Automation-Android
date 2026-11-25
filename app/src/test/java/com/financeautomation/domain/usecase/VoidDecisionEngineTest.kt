package com.financeautomation.domain.usecase

import com.financeautomation.domain.model.VoidDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Table test proving all combinations of the all-or-nothing void decision:
 * - Untouched debt -> VoidBoth
 * - Partly / fully repaid or offset debt -> Block
 * - Ambiguous matching candidate debts -> Block
 * - Ordinary expenses (including on account "Other" and near-misses) -> OrdinaryVoid (never blocked)
 */
class VoidDecisionEngineTest {

    private val fixedDateSerial = 46350
    private val fixedTimeSerial = 0.5

    @Test
    fun testDecisionTable() {
        data class TestCase(
            val name: String,
            val expenseTotal: Double,
            val expenseId: String,
            val merchant: String,
            val account: String,
            val exactLinked: PayableCandidate?,
            val allCandidates: List<PayableCandidate>,
            val expectedDecisionClass: Class<out VoidDecision>,
            val expectedBlockReason: VoidDecision.BlockReason? = null
        )

        val testCases = listOf(
            TestCase(
                name = "1. Untouched debt: Pending, amount equal to expense total -> VoidBoth",
                expenseTotal = 500.0,
                expenseId = "txn_untouched_1",
                merchant = "Paid by Alice",
                account = "Other",
                exactLinked = PayableCandidate(
                    uuid = "pay_untouched_1",
                    paidBy = "Alice",
                    whatFor = "Paid by Alice",
                    amountIOwe = 500.0,
                    repaymentStatus = "Pending",
                    dateIncurredSerial = fixedDateSerial,
                    timeSerial = fixedTimeSerial
                ),
                allCandidates = emptyList(),
                expectedDecisionClass = VoidDecision.VoidBoth::class.java
            ),
            TestCase(
                name = "2. Fully repaid debt: Status Settled -> Block(DEBT_PARTIALLY_OR_FULLY_REPAID)",
                expenseTotal = 500.0,
                expenseId = "txn_repaid_1",
                merchant = "Paid by Alice",
                account = "Other",
                exactLinked = PayableCandidate(
                    uuid = "pay_repaid_1",
                    paidBy = "Alice",
                    whatFor = "Paid by Alice",
                    amountIOwe = 500.0,
                    repaymentStatus = "Settled",
                    dateIncurredSerial = fixedDateSerial,
                    timeSerial = fixedTimeSerial
                ),
                allCandidates = emptyList(),
                expectedDecisionClass = VoidDecision.Block::class.java,
                expectedBlockReason = VoidDecision.BlockReason.DEBT_PARTIALLY_OR_FULLY_REPAID
            ),
            TestCase(
                name = "3. Status Repaid -> Block(DEBT_PARTIALLY_OR_FULLY_REPAID)",
                expenseTotal = 500.0,
                expenseId = "txn_repaid_2",
                merchant = "Paid by Alice",
                account = "Other",
                exactLinked = PayableCandidate(
                    uuid = "pay_repaid_2",
                    paidBy = "Alice",
                    whatFor = "Paid by Alice",
                    amountIOwe = 500.0,
                    repaymentStatus = "Repaid",
                    dateIncurredSerial = fixedDateSerial,
                    timeSerial = fixedTimeSerial
                ),
                allCandidates = emptyList(),
                expectedDecisionClass = VoidDecision.Block::class.java,
                expectedBlockReason = VoidDecision.BlockReason.DEBT_PARTIALLY_OR_FULLY_REPAID
            ),
            TestCase(
                name = "4. Partly repaid debt: Pending but amount reduced -> Block(DEBT_PARTIALLY_OR_FULLY_REPAID)",
                expenseTotal = 500.0,
                expenseId = "txn_partly_1",
                merchant = "Paid by Alice",
                account = "Other",
                exactLinked = PayableCandidate(
                    uuid = "pay_partly_1",
                    paidBy = "Alice",
                    whatFor = "Paid by Alice",
                    amountIOwe = 200.0,
                    repaymentStatus = "Pending",
                    dateIncurredSerial = fixedDateSerial,
                    timeSerial = fixedTimeSerial
                ),
                allCandidates = emptyList(),
                expectedDecisionClass = VoidDecision.Block::class.java,
                expectedBlockReason = VoidDecision.BlockReason.DEBT_PARTIALLY_OR_FULLY_REPAID
            ),
            TestCase(
                name = "5. Offset debt: Pending but amount changed -> Block",
                expenseTotal = 500.0,
                expenseId = "txn_offset_1",
                merchant = "Paid by Alice",
                account = "Other",
                exactLinked = PayableCandidate(
                    uuid = "pay_offset_1",
                    paidBy = "Alice",
                    whatFor = "Paid by Alice",
                    amountIOwe = 600.0,
                    repaymentStatus = "Pending",
                    dateIncurredSerial = fixedDateSerial,
                    timeSerial = fixedTimeSerial
                ),
                allCandidates = emptyList(),
                expectedDecisionClass = VoidDecision.Block::class.java,
                expectedBlockReason = VoidDecision.BlockReason.DEBT_OFFSET
            ),
            TestCase(
                name = "6. Ambiguous matching candidates (2 match) -> Block(AMBIGUOUS_DEBT_MATCH)",
                expenseTotal = 300.0,
                expenseId = "txn_legacy_ambiguous",
                merchant = "Paid by Bob",
                account = "Other",
                exactLinked = null,
                allCandidates = listOf(
                    PayableCandidate(
                        uuid = "pay_cand_1",
                        paidBy = "Bob",
                        whatFor = "Paid by Bob",
                        amountIOwe = 300.0,
                        repaymentStatus = "Pending",
                        dateIncurredSerial = fixedDateSerial,
                        timeSerial = fixedTimeSerial
                    ),
                    PayableCandidate(
                        uuid = "pay_cand_2",
                        paidBy = "Bob",
                        whatFor = "Paid by Bob",
                        amountIOwe = 300.0,
                        repaymentStatus = "Pending",
                        dateIncurredSerial = fixedDateSerial,
                        timeSerial = fixedTimeSerial
                    )
                ),
                expectedDecisionClass = VoidDecision.Block::class.java,
                expectedBlockReason = VoidDecision.BlockReason.AMBIGUOUS_DEBT_MATCH
            ),
            TestCase(
                name = "7. R1: Ordinary expense on account 'Other' with no linked debt -> OrdinaryVoid (never blocked)",
                expenseTotal = 150.0,
                expenseId = "txn_ordinary_other_1",
                merchant = "Grocery Store",
                account = "Other",
                exactLinked = null,
                allCandidates = emptyList(),
                expectedDecisionClass = VoidDecision.OrdinaryVoid::class.java
            ),
            TestCase(
                name = "8. Ordinary expense on standard bank account -> OrdinaryVoid (never blocked)",
                expenseTotal = 750.0,
                expenseId = "txn_bank_1",
                merchant = "Restaurant",
                account = "HDFC",
                exactLinked = null,
                allCandidates = emptyList(),
                expectedDecisionClass = VoidDecision.OrdinaryVoid::class.java
            ),
            TestCase(
                name = "9. R2 near-miss creditor: candidate creditor 'Charlie' != expense creditor 'Alice' -> OrdinaryVoid",
                expenseTotal = 400.0,
                expenseId = "txn_near_creditor",
                merchant = "Paid by Alice",
                account = "Other",
                exactLinked = null,
                allCandidates = listOf(
                    PayableCandidate(
                        uuid = "pay_cand_charlie",
                        paidBy = "Charlie",
                        whatFor = "Paid by Alice",
                        amountIOwe = 400.0,
                        repaymentStatus = "Pending",
                        dateIncurredSerial = fixedDateSerial,
                        timeSerial = fixedTimeSerial
                    )
                ),
                expectedDecisionClass = VoidDecision.OrdinaryVoid::class.java
            ),
            TestCase(
                name = "10. R2 near-miss description: candidate 'Din' != expense 'Dinner' -> OrdinaryVoid",
                expenseTotal = 250.0,
                expenseId = "txn_near_desc",
                merchant = "Dinner",
                account = "Alice",
                exactLinked = null,
                allCandidates = listOf(
                    PayableCandidate(
                        uuid = "pay_cand_din",
                        paidBy = "Alice",
                        whatFor = "Din",
                        amountIOwe = 250.0,
                        repaymentStatus = "Pending",
                        dateIncurredSerial = fixedDateSerial,
                        timeSerial = fixedTimeSerial
                    )
                ),
                expectedDecisionClass = VoidDecision.OrdinaryVoid::class.java
            ),
            TestCase(
                name = "11. R2 near-miss empty creditor: candidate paidBy is blank -> OrdinaryVoid",
                expenseTotal = 100.0,
                expenseId = "txn_blank_creditor",
                merchant = "Snacks",
                account = "Other",
                exactLinked = null,
                allCandidates = listOf(
                    PayableCandidate(
                        uuid = "pay_cand_blank",
                        paidBy = "",
                        whatFor = "Snacks",
                        amountIOwe = 100.0,
                        repaymentStatus = "Pending",
                        dateIncurredSerial = fixedDateSerial,
                        timeSerial = fixedTimeSerial
                    )
                ),
                expectedDecisionClass = VoidDecision.OrdinaryVoid::class.java
            )
        )

        for (tc in testCases) {
            val decision = VoidDecisionEngine.decide(
                expenseTotal = tc.expenseTotal,
                expenseId = tc.expenseId,
                merchant = tc.merchant,
                account = tc.account,
                dateSerial = fixedDateSerial,
                timeSerial = fixedTimeSerial,
                exactLinkedPayable = tc.exactLinked,
                allPayableCandidates = tc.allCandidates
            )

            assertTrue(
                "Test case '${tc.name}' expected ${tc.expectedDecisionClass.simpleName} but got ${decision::class.java.simpleName}",
                tc.expectedDecisionClass.isInstance(decision)
            )

            if (tc.expectedBlockReason != null) {
                val block = decision as VoidDecision.Block
                assertEquals(
                    "Test case '${tc.name}' expected block reason ${tc.expectedBlockReason} but got ${block.reason}",
                    tc.expectedBlockReason,
                    block.reason
                )
            }
        }
    }
}
