package com.financeautomation.domain.model

/**
 * Domain request model for recording income / friend settlement in Table 2
 * and reconciling open debits via SettlementWaterfallUseCase.
 */
/**
 * Domain request to void an income or refund row (Table 2). The inflow id is the credit request id, which is also
 * written to the row's UUID cell (column AD) when the credit is appended.
 */
data class VoidInflowRequest(
    val inflowId: String,
    val timestamp: Long,
    val deleteSheetRow: Boolean = false
)

data class VoidExpenseRequest(
    val id: String,
    val deleteSheetRow: Boolean = false,
    val linkedPayableId: String? = null
)

data class LogCreditRequest(
    val requestId: String,
    val settler: String,
    val amount: Double,
    val explicitRefs: List<String> = emptyList(),
    val account: String,
    val inflowType: String,
    val reason: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Domain request model for recording money borrowed from friends in Table 3
 * and offsetting against open debits via NettingUseCase.
 */
data class LogPayableRequest(
    val requestId: String,
    val creditor: String,
    val amount: Double,
    val whatFor: String,
    val timestamp: Long = System.currentTimeMillis(),
    val category: String = com.financeautomation.core.constants.CategoryConstants.CATEGORY_OTHER,
    val transactionId: String? = null
)

/**
 * Domain request model for repaying money owed to friends
 * and reconciling open payables via RepaymentWaterfallUseCase.
 */
data class LogRepaymentRequest(
    val requestId: String,
    val creditor: String,
    val amount: Double,
    val explicitRef: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val account: String? = null,
    val note: String? = null
)

/**
 * Domain request model for rolling over open debts and payables to a new month tab
 * via CarryForwardUseCase.
 */
data class CarryForwardRequest(
    val targetTab: String,
    val sourceTab: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val accounts: List<String> = emptyList(),
    val categories: List<String> = emptyList()
)

/**
 * Domain request model for compiling year-end statement and Drive backup JSON
 * via YearCloseUseCase.
 */
data class YearCloseRequest(
    val year: Int,
    val timestamp: Long = System.currentTimeMillis()
)

data class RestoreInflowRequest(
    val inflowId: String,
    val timestamp: Long
)

data class RestorePayableRequest(
    val payableUuid: String,
    val tabName: String,
    val year: Int
)


