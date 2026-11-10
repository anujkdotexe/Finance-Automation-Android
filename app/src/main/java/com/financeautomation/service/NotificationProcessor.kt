package com.financeautomation.service

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.constants.PackageWhitelist
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.data.local.dao.AccountDao
import com.financeautomation.data.local.dao.HandledNotificationDao
import com.financeautomation.data.local.dao.PendingCaptureDao
import com.financeautomation.data.local.dao.PreferenceDao
import com.financeautomation.data.local.entity.HandledNotificationEntity
import com.financeautomation.data.local.entity.PendingCaptureEntity
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.domain.usecase.DropReason
import com.financeautomation.domain.usecase.NotificationParseResult
import com.financeautomation.domain.usecase.ParseNotificationUseCase
import com.financeautomation.domain.usecase.WordingClass
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * Outcome of processing an incoming system notification.
 */
sealed interface NotificationProcessingOutcome {
    data class Captured(
        val capture: PendingCaptureEntity
    ) : NotificationProcessingOutcome

    data class Created(
        val transaction: Transaction,
        val isPossibleDuplicate: Boolean,
        val headsUpNotificationData: HeadsUpData
    ) : NotificationProcessingOutcome

    data class Updated(
        val capture: PendingCaptureEntity
    ) : NotificationProcessingOutcome

    data class Merged(
        val existingTransactionId: String,
        val enriched: Boolean = false
    ) : NotificationProcessingOutcome

    data class FallbackPrompt(
        val message: String,
        val sourcePackage: String
    ) : NotificationProcessingOutcome

    data class Dismissed(
        val captureId: String
    ) : NotificationProcessingOutcome

    data class Ignored(
        val reason: String
    ) : NotificationProcessingOutcome
}

/**
 * Data needed to construct a Heads-Up notification for review or fallback.
 */
data class HeadsUpData(
    val notificationId: Int,
    val title: String,
    val text: String,
    val category1: String,
    val category2: String,
    val detailsActionTitle: String,
    val transactionId: String
)

/**
 * Background notification parsing and capture engine.
 * Filters notifications, enforces deduplication, maintains pending_captures and handled_notifications.
 */
class NotificationProcessor(
    private val pendingCaptureDao: PendingCaptureDao? = null,
    private val handledNotificationDao: HandledNotificationDao? = null,
    private val accountDao: AccountDao? = null,
    private val preferenceDao: PreferenceDao? = null,
    private val repository: TransactionRepository? = null,
    private val parseUseCase: ParseNotificationUseCase = ParseNotificationUseCase(),
    private val ownPackageName: String = "com.financeautomation"
) {

    /**
     * Secondary constructor for tests and legacy callers.
     */
    constructor(
        repository: TransactionRepository,
        parseUseCase: ParseNotificationUseCase = ParseNotificationUseCase(),
        ownPackageName: String = "com.financeautomation"
    ) : this(
        pendingCaptureDao = null,
        handledNotificationDao = null,
        accountDao = null,
        preferenceDao = null,
        repository = repository,
        parseUseCase = parseUseCase,
        ownPackageName = ownPackageName
    )

    suspend fun process(
        sourcePackage: String,
        title: String,
        text: String,
        postTime: Long = System.currentTimeMillis(),
        sbnKey: String = ""
    ): NotificationProcessingOutcome {
        // 1. Self-notification check
        if (sourcePackage.trim() == ownPackageName) {
            return NotificationProcessingOutcome.Ignored("Self notification dropped")
        }

        // 2. Blacklist check (privacy-sensitive messaging apps)
        if (PackageWhitelist.isBlacklisted(sourcePackage)) {
            return NotificationProcessingOutcome.Ignored("Blacklisted privacy package: $sourcePackage")
        }

        // 3. Whitelist check (financial applications and SMS alerts only, includes BuildConfig.DEBUG guard)
        if (!PackageWhitelist.isWhitelisted(sourcePackage)) {
            return NotificationProcessingOutcome.Ignored("Non-whitelisted package: $sourcePackage")
        }

        // 4. OTP and verification suppression check
        val fullContent = if (title.isNotBlank()) "$title. $text".trim() else text.trim()
        if (PackageWhitelist.isOtpOrAuth(fullContent)) {
            return NotificationProcessingOutcome.Ignored("OTP or auth alert suppressed")
        }

        // Memory-only content hash (SHA-256 of lowercase title + text, 16 hex characters)
        val contentHash = computeContentHash(title, text)
        val key = if (sbnKey.isNotBlank()) sbnKey else "$sourcePackage|$postTime"

        // 5. Handled-notification check
        if (handledNotificationDao != null) {
            val existingHandled = handledNotificationDao.get(key = key, postTime = postTime)
            if (existingHandled != null && existingHandled.contentHash == contentHash) {
                // Reconnect replay or duplicate posted alert
                return NotificationProcessingOutcome.Ignored("Duplicate or reconnect replay notification dropped")
            }

            // Prune rows older than 24 hours
            val cutoff = postTime - 24L * 60L * 60L * 1000L
            handledNotificationDao.pruneOlderThan(cutoff)

            // Record this alert in handled_notifications
            handledNotificationDao.insert(
                HandledNotificationEntity(
                    key = key,
                    postTime = postTime,
                    contentHash = contentHash,
                    handledAt = System.currentTimeMillis()
                )
            )
        }

        // 6. Inferred account resolution from configured accounts
        val configuredAccounts: List<String> = try {
            accountDao?.getAllAccounts()?.map { it.name } ?: emptyList()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Diagnostics.record("NotificationProcessor", "${e.javaClass.simpleName}: getAllAccounts fallback")
            emptyList()
        }
        val defaultAccount: String = try {
            preferenceDao?.getPreference("default_account") ?: CategoryConstants.ACCOUNT_SBI
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Diagnostics.record("NotificationProcessor", "${e.javaClass.simpleName}: defaultAccount preference fallback")
            CategoryConstants.ACCOUNT_SBI
        }

        // 7. Parse financial alert candidate via domain ParseNotificationUseCase
        val parseResult = parseUseCase(
            sourcePackage = sourcePackage,
            title = title,
            text = text,
            appName = null,
            timestamp = postTime,
            configuredAccounts = configuredAccounts,
            defaultAccount = defaultAccount
        )

        return when (parseResult) {
            is NotificationParseResult.Dropped -> {
                NotificationProcessingOutcome.Ignored("ParseUseCase dropped: ${parseResult.reason}")
            }
            is NotificationParseResult.FailureAlert -> {
                // Check if an existing capture is AWAITING_SUCCESS for this notification key
                if (pendingCaptureDao != null && sbnKey.isNotBlank()) {
                    val existing = pendingCaptureDao.getByNotificationKey(sbnKey)
                    if (existing != null && existing.state == PendingCaptureEntity.STATE_AWAITING_SUCCESS) {
                        pendingCaptureDao.updateState(existing.id, PendingCaptureEntity.STATE_DISMISSED)
                        return NotificationProcessingOutcome.Dismissed(existing.id)
                    }
                }
                NotificationProcessingOutcome.Ignored("Payment failed, declined or reversed")
            }
            is NotificationParseResult.FallbackPrompt -> {
                // Unreadable alert: amount = null, payee = "Unknown Payee", flags = NEEDS_AMOUNT
                handleCaptureDecision(
                    sourcePackage = sourcePackage,
                    amount = null,
                    payee = "Unknown Payee",
                    inferredAccount = parseResult.inferredAccount,
                    kind = parseResult.kind,
                    wording = parseResult.wording,
                    flags = PendingCaptureEntity.FLAG_NEEDS_AMOUNT,
                    postTime = postTime,
                    sbnKey = sbnKey,
                    contentHash = contentHash,
                    candidateTransaction = null
                )
            }
            is NotificationParseResult.Success -> {
                val kind = if (parseResult.isCredit) PendingCaptureEntity.KIND_CREDIT else PendingCaptureEntity.KIND_DEBIT
                handleCaptureDecision(
                    sourcePackage = sourcePackage,
                    amount = parseResult.transaction.totalAmount,
                    payee = parseResult.transaction.merchant,
                    inferredAccount = parseResult.inferredAccount,
                    kind = kind,
                    wording = parseResult.wording,
                    flags = "",
                    postTime = postTime,
                    sbnKey = sbnKey,
                    contentHash = contentHash,
                    candidateTransaction = parseResult.transaction
                )
            }
        }
    }

    private suspend fun handleCaptureDecision(
        sourcePackage: String,
        amount: Double?,
        payee: String,
        inferredAccount: String,
        kind: String,
        wording: WordingClass,
        flags: String,
        postTime: Long,
        sbnKey: String,
        contentHash: String,
        candidateTransaction: Transaction?
    ): NotificationProcessingOutcome {
        val targetState = if (wording == WordingClass.PROCESSING) {
            PendingCaptureEntity.STATE_AWAITING_SUCCESS
        } else {
            PendingCaptureEntity.STATE_WAITING
        }

        // Check if an existing capture with the same sbn.key exists (e.g. processing -> success update)
        if (pendingCaptureDao != null && sbnKey.isNotBlank()) {
            val existingByKey = pendingCaptureDao.getByNotificationKey(sbnKey)
            if (existingByKey != null && existingByKey.state == PendingCaptureEntity.STATE_AWAITING_SUCCESS) {
                val updated = existingByKey.copy(
                    amount = amount ?: existingByKey.amount,
                    payee = if (payee != "Unknown Payee") payee else existingByKey.payee,
                    inferredAccount = inferredAccount.ifBlank { existingByKey.inferredAccount },
                    state = if (targetState == PendingCaptureEntity.STATE_WAITING) PendingCaptureEntity.STATE_WAITING else existingByKey.state,
                    contentHash = contentHash,
                    updatedAt = System.currentTimeMillis()
                )
                pendingCaptureDao.update(updated)
                return NotificationProcessingOutcome.Updated(updated)
            }
        }

        var activeFlags = flags

        // Cross-source deduplication within 90 seconds
        if (pendingCaptureDao != null && amount != null && amount > 0.0) {
            val minTime = postTime - 90_000L
            val maxTime = postTime + 90_000L
            val similarCaptures = pendingCaptureDao.findSimilarByAmountAndTime(amount, minTime, maxTime)
                .filter { it.sourcePackage != sourcePackage }

            if (similarCaptures.isNotEmpty()) {
                val mergeTarget = similarCaptures.firstOrNull {
                    it.state == PendingCaptureEntity.STATE_WAITING || it.state == PendingCaptureEntity.STATE_AWAITING_SUCCESS
                }
                if (mergeTarget != null) {
                    val merged = mergeTarget.copy(
                        payee = if (mergeTarget.payee == "Unknown Payee" && payee != "Unknown Payee") payee else mergeTarget.payee,
                        inferredAccount = if (mergeTarget.inferredAccount.isNullOrBlank() && inferredAccount.isNotBlank()) inferredAccount else mergeTarget.inferredAccount,
                        updatedAt = System.currentTimeMillis()
                    )
                    pendingCaptureDao.update(merged)
                    return NotificationProcessingOutcome.Merged(merged.id, enriched = true)
                }

                // If already SHOWN or DONE, mark new capture as POSSIBLE_DUPLICATE
                val hasShownOrDone = similarCaptures.any {
                    it.state == PendingCaptureEntity.STATE_SHOWN || it.state == PendingCaptureEntity.STATE_DONE
                }
                if (hasShownOrDone) {
                    activeFlags = if (activeFlags.isNotBlank()) {
                        "$activeFlags,${PendingCaptureEntity.FLAG_POSSIBLE_DUPLICATE}"
                    } else {
                        PendingCaptureEntity.FLAG_POSSIBLE_DUPLICATE
                    }
                }
            }
        }

        val captureId = "cap_${UUID.randomUUID().toString().replace("-", "")}"
        val capture = PendingCaptureEntity(
            id = captureId,
            sourcePackage = sourcePackage,
            amount = amount,
            payee = payee,
            inferredAccount = inferredAccount,
            timestamp = postTime,
            kind = kind,
            flags = activeFlags,
            state = targetState,
            notificationKey = sbnKey.takeIf { it.isNotBlank() },
            contentHash = contentHash,
            updatedAt = System.currentTimeMillis()
        )

        pendingCaptureDao?.insert(capture)

        // Legacy / fallback repository support for direct transaction logging and tests
        if (repository != null && candidateTransaction != null) {
            val isPossibleDuplicate = activeFlags.contains(PendingCaptureEntity.FLAG_POSSIBLE_DUPLICATE)
            val finalTxn = candidateTransaction.copy(
                account = inferredAccount,
                merchant = payee,
                isPossibleDuplicate = isPossibleDuplicate,
                reviewState = ReviewState.NEEDS_REVIEW,
                syncState = SyncState.PENDING
            )
            repository.insertTransaction(finalTxn)

            val notificationId = (finalTxn.id.hashCode() and 0x7FFFFFFF)
            val headsUpData = HeadsUpData(
                notificationId = notificationId,
                title = String.format(Locale.US, "Paid ₹%.2f to %s", finalTxn.totalAmount, finalTxn.merchant),
                text = if (isPossibleDuplicate) "Possible duplicate • Tap to review" else "Tap Details / Split",
                category1 = CategoryConstants.CATEGORY_FOOD_AND_DRINK,
                category2 = CategoryConstants.CATEGORY_GROCERIES,
                detailsActionTitle = "Details / Split",
                transactionId = finalTxn.id
            )
            return NotificationProcessingOutcome.Created(
                transaction = finalTxn,
                isPossibleDuplicate = isPossibleDuplicate,
                headsUpNotificationData = headsUpData
            )
        }

        return NotificationProcessingOutcome.Captured(capture)
    }

    companion object {
        /**
         * Computes memory-only SHA-256 hash of lower-cased title and text, truncated to 16 hex characters.
         */
        fun computeContentHash(title: String, text: String): String {
            val input = "${title.lowercase().trim()} ${text.lowercase().trim()}".trim()
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            val hex = StringBuilder()
            for (i in 0 until 8) {
                val b = digest[i].toInt() and 0xFF
                if (b < 16) hex.append('0')
                hex.append(Integer.toHexString(b))
            }
            return hex.toString()
        }
    }
}
