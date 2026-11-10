package com.financeautomation.domain.model

/**
 * Domain request model for recording income / friend settlement in Table 2
 * and reconciling open debits via SettlementWaterfallUseCase.
 */
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
    val timestamp: Long = System.currentTimeMillis()
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
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Domain request model for rolling over open debts and payables to a new month tab
 * via CarryForwardUseCase.
 */
data class CarryForwardRequest(
    val targetTab: String,
    val sourceTab: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Domain request model for compiling year-end statement and Drive backup JSON
 * via YearCloseUseCase.
 */
data class YearCloseRequest(
    val year: Int,
    val timestamp: Long = System.currentTimeMillis()
)

