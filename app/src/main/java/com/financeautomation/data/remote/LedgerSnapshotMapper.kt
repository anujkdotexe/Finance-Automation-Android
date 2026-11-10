package com.financeautomation.data.remote

import com.financeautomation.domain.model.LedgerDebit
import com.financeautomation.domain.model.LedgerPayable
import com.financeautomation.domain.model.LedgerSnapshot

/**
 * Mapper converting SheetGateway snapshot models into pure domain accounting models.
 */
object LedgerSnapshotMapper {

    fun mapToDomain(snapshot: LedgerWorkbookSnapshot): LedgerSnapshot {
        val debits = snapshot.monthTabs.flatMap { month ->
            month.debits.map { row ->
                LedgerDebit(
                    tab = month.tabName,
                    row = row.rowNumber,
                    uuid = row.uuid,
                    dateSerial = row.dateSerial,
                    timeSerial = row.timeSerial,
                    account = row.account,
                    item = row.description,
                    tripTag = row.tripTag,
                    category = row.category,
                    total = row.totalAmount,
                    companions = row.companions,
                    myShare = row.myShare,
                    owed = row.owedToMe,
                    status = row.status
                )
            }
        }

        val payables = snapshot.monthTabs.flatMap { month ->
            month.payables.map { row ->
                LedgerPayable(
                    tab = month.tabName,
                    row = row.rowNumber,
                    uuid = row.uuid,
                    dateIncurredSerial = row.dateIncurredSerial,
                    timeSerial = row.timeSerial,
                    paidBy = row.paidBy,
                    whatFor = row.whatFor,
                    amountOwed = row.amountIOwe,
                    dateRepaidSerial = row.dateRepaidSerial,
                    status = row.repaymentStatus
                )
            }
        }

        return LedgerSnapshot(debits = debits, payables = payables)
    }
}

