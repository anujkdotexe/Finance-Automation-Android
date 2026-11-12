package com.financeautomation.presentation.overlay

import com.financeautomation.core.model.Result
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.domain.repository.TripTagRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Handles execution and error classification for Choose Action flows dispatched from the overlay window.
 * Catches all runtime exceptions, records diagnostics, rethrows CancellationException,
 * and surfaces user-friendly outcome pills.
 */
class OverlayActionDispatcher(
    private val transactionRepository: TransactionRepository,
    private val tripTagRepository: TripTagRepository,
    private val resultPill: ResultPillOverlay,
    private val onDismiss: () -> Unit,
    private val mainScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    fun confirmIncome(
        amount: Double,
        account: String,
        inflowType: String,
        settler: String,
        reason: String,
        explicitRefs: List<String>
    ) {
        mainScope.launch(ioDispatcher) {
            try {
                val res = transactionRepository.logCredit(
                    settler = settler,
                    amount = amount,
                    explicitRefs = explicitRefs,
                    account = account,
                    inflowType = inflowType,
                    reason = reason
                )
                mainScope.launch {
                    onDismiss()
                    val formatted = String.format(Locale.US, "%.2f", amount)
                    val message = if (res is Result.Success) {
                        if (settler.isNotBlank()) "Recorded $formatted from $settler" else "Recorded $formatted"
                    } else "Failed to record income"
                    val txnId = (res as? Result.Success)?.data
                    resultPill.show(
                        message = message,
                        transactionId = txnId,
                        onUndo = if (txnId != null) {
                            { id ->
                                mainScope.launch(ioDispatcher) {
                                    transactionRepository.voidTransaction(id)
                                }
                            }
                        } else null
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Diagnostics.record("OverlayActionDispatcher", "${e.javaClass.simpleName}: confirmIncome failed")
                mainScope.launch {
                    onDismiss()
                    resultPill.show("Failed to record income")
                }
            }
        }
    }

    fun confirmPayable(amount: Double, creditor: String, whatFor: String) {
        mainScope.launch(ioDispatcher) {
            try {
                val res = transactionRepository.logPayable(
                    creditor = creditor,
                    amount = amount,
                    whatFor = whatFor
                )
                mainScope.launch {
                    onDismiss()
                    val formatted = String.format(Locale.US, "%.2f", amount)
                    val message = if (res is Result.Success) {
                        if (creditor.isNotBlank()) "Recorded payable $formatted to $creditor" else "Recorded payable $formatted"
                    } else "Failed to record payable"
                    val txnId = (res as? Result.Success)?.data
                    resultPill.show(
                        message = message,
                        transactionId = txnId,
                        onUndo = if (txnId != null) {
                            { id ->
                                mainScope.launch(ioDispatcher) {
                                    transactionRepository.voidTransaction(id)
                                }
                            }
                        } else null
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Diagnostics.record("OverlayActionDispatcher", "${e.javaClass.simpleName}: confirmPayable failed")
                mainScope.launch {
                    onDismiss()
                    resultPill.show("Failed to record payable")
                }
            }
        }
    }

    fun confirmRepaid(amount: Double, person: String, timestamp: Long) {
        mainScope.launch(ioDispatcher) {
            try {
                val res = transactionRepository.logRepayment(
                    creditor = person,
                    amount = amount,
                    explicitRef = null,
                    timestamp = timestamp
                )
                mainScope.launch {
                    onDismiss()
                    val formatted = String.format(Locale.US, "%.2f", amount)
                    val message = if (res is Result.Success) {
                        if (person.isNotBlank()) "Recorded repayment $formatted to $person" else "Recorded repayment $formatted"
                    } else "Failed to record repayment"
                    val txnId = (res as? Result.Success)?.data
                    resultPill.show(
                        message = message,
                        transactionId = txnId,
                        onUndo = if (txnId != null) {
                            { id ->
                                mainScope.launch(ioDispatcher) {
                                    transactionRepository.voidTransaction(id)
                                }
                            }
                        } else null
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Diagnostics.record("OverlayActionDispatcher", "${e.javaClass.simpleName}: confirmRepaid failed")
                mainScope.launch {
                    onDismiss()
                    resultPill.show("Failed to record repayment")
                }
            }
        }
    }

    fun startTrip(tagName: String) {
        mainScope.launch(ioDispatcher) {
            try {
                val tag = tripTagRepository.getTagByName(tagName) ?: tripTagRepository.insertTripTag(tagName)
                tripTagRepository.setActiveTag(tag.id)
                mainScope.launch {
                    onDismiss()
                    resultPill.show("Trip '$tagName' started")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Diagnostics.record("OverlayActionDispatcher", "${e.javaClass.simpleName}: startTrip failed")
                mainScope.launch {
                    onDismiss()
                    resultPill.show("Failed to start trip")
                }
            }
        }
    }

    fun endTrip() {
        mainScope.launch(ioDispatcher) {
            try {
                tripTagRepository.setActiveTag(null)
                mainScope.launch {
                    onDismiss()
                    resultPill.show("Trip ended")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Diagnostics.record("OverlayActionDispatcher", "${e.javaClass.simpleName}: endTrip failed")
                mainScope.launch {
                    onDismiss()
                    resultPill.show("Failed to end trip")
                }
            }
        }
    }
}

