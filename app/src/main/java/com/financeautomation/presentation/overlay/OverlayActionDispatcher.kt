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
    private val resultPill: ResultPill,
    private val onDismiss: () -> Unit,
    private val mainScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val isSubmitting = java.util.concurrent.atomic.AtomicBoolean(false)

    fun confirmIncome(
        amount: Double,
        account: String,
        inflowType: String,
        settler: String,
        reason: String,
        explicitRefs: List<String>,
        onResult: ((Boolean) -> Unit)? = null
    ) {
        if (!isSubmitting.compareAndSet(false, true)) return
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
                    when (res) {
                        is Result.Success -> {
                            isSubmitting.set(false)
                            onDismiss()
                            onResult?.invoke(true)
                            val formatted = String.format(Locale.US, "%.2f", amount)
                            val message = if (settler.isNotBlank()) "Recorded $formatted from $settler" else "Recorded $formatted"
                            val txnId = res.data
                            resultPill.show(
                                message = message,
                                transactionId = txnId,
                                onUndo = { id ->
                                    mainScope.launch(ioDispatcher) {
                                        transactionRepository.undoRecentSave(id)
                                    }
                                }
                            )
                        }
                        is Result.Error -> {
                            isSubmitting.set(false)
                            onResult?.invoke(false)
                            resultPill.show("Failed to record income")
                        }
                        is Result.Loading -> Unit
                    }
                }
            } catch (e: Exception) {
                isSubmitting.set(false)
                if (e is CancellationException) throw e
                Diagnostics.record("OverlayActionDispatcher", "${e.javaClass.simpleName}: confirmIncome failed")
                mainScope.launch {
                    onResult?.invoke(false)
                    resultPill.show("Failed to record income")
                }
            }
        }
    }

    fun confirmPayable(
        amount: Double,
        creditor: String,
        whatFor: String,
        category: String = "",
        timestamp: Long = System.currentTimeMillis()
    ) {
        if (!isSubmitting.compareAndSet(false, true)) return
        mainScope.launch(ioDispatcher) {
            try {
                val res = transactionRepository.logPayable(
                    creditor = creditor,
                    amount = amount,
                    whatFor = whatFor,
                    timestamp = timestamp,
                    category = category
                )
                mainScope.launch {
                    when (res) {
                        is Result.Success -> {
                            isSubmitting.set(false)
                            onDismiss()
                            val formatted = String.format(Locale.US, "%.2f", amount)
                            val message = if (creditor.isNotBlank()) "Recorded payable $formatted to $creditor" else "Recorded payable $formatted"
                            resultPill.show(message = message)
                        }
                        is Result.Error -> {
                            isSubmitting.set(false)
                            resultPill.show("Failed to record payable")
                        }
                        is Result.Loading -> Unit
                    }
                }
            } catch (e: Exception) {
                isSubmitting.set(false)
                if (e is CancellationException) throw e
                Diagnostics.record("OverlayActionDispatcher", "${e.javaClass.simpleName}: confirmPayable failed")
                mainScope.launch {
                    resultPill.show("Failed to record payable")
                }
            }
        }
    }

    fun confirmRepaid(
        amount: Double,
        person: String,
        timestamp: Long,
        account: String? = null,
        note: String? = null
    ) {
        if (!isSubmitting.compareAndSet(false, true)) return
        mainScope.launch(ioDispatcher) {
            try {
                val res = transactionRepository.logRepayment(
                    creditor = person,
                    amount = amount,
                    explicitRef = null,
                    timestamp = timestamp,
                    account = account,
                    note = note
                )
                mainScope.launch {
                    when (res) {
                        is Result.Success -> {
                            isSubmitting.set(false)
                            onDismiss()
                            val formatted = String.format(Locale.US, "%.2f", amount)
                            val message = if (person.isNotBlank()) "Recorded repayment $formatted to $person" else "Recorded repayment $formatted"
                            resultPill.show(message = message)
                        }
                        is Result.Error -> {
                            isSubmitting.set(false)
                            resultPill.show(res.exception?.message ?: "Failed to record repayment")
                        }
                        is Result.Loading -> Unit
                    }
                }
            } catch (e: Exception) {
                isSubmitting.set(false)
                if (e is CancellationException) throw e
                Diagnostics.record("OverlayActionDispatcher", "${e.javaClass.simpleName}: confirmRepaid failed")
                mainScope.launch {
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

