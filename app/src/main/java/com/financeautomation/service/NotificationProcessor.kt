package com.financeautomation.service

import android.content.Context
import androidx.room.withTransaction
import com.financeautomation.BuildConfig
import com.financeautomation.FinanceApplication
import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.constants.PackageWhitelist
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.core.utils.PipelineCode
import com.financeautomation.core.utils.PipelineStage
import com.financeautomation.core.utils.PipelineTrail
import com.financeautomation.data.local.dao.AccountDao
import com.financeautomation.data.local.dao.HandledNotificationDao
import com.financeautomation.data.local.dao.PendingCaptureDao
import com.financeautomation.data.local.dao.PreferenceDao
import com.financeautomation.data.local.dao.TransactionDao
import com.financeautomation.data.local.entity.HandledNotificationEntity
import com.financeautomation.data.local.entity.PendingCaptureEntity
import com.financeautomation.data.local.entity.TransactionEntity
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.utils.DateSerialUtils
import com.financeautomation.domain.usecase.DropReason
import com.financeautomation.domain.usecase.NotificationParseResult
import com.financeautomation.domain.usecase.ParseNotificationUseCase
import com.financeautomation.domain.usecase.SubscriptionMatcher
import com.financeautomation.domain.usecase.WordingClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.security.MessageDigest
import java.util.UUID

/**
 * Outcome of processing an incoming system notification.
 */
sealed interface NotificationProcessingOutcome {
    data class Captured(
        val capture: PendingCaptureEntity
    ) : NotificationProcessingOutcome

    data class Updated(
        val capture: PendingCaptureEntity
    ) : NotificationProcessingOutcome

    data class Merged(
        val existingTransactionId: String,
        val enriched: Boolean = false
    ) : NotificationProcessingOutcome

    data class Dismissed(
        val captureId: String
    ) : NotificationProcessingOutcome

    data class Ignored(
        val reason: String
    ) : NotificationProcessingOutcome
}

/**
 * Background notification parsing and capture engine.
 * Filters notifications, enforces deduplication, maintains pending_captures and handled_notifications.
 */
class NotificationProcessor(
    private val pendingCaptureDao: PendingCaptureDao? = null,
    private val handledNotificationDao: HandledNotificationDao? = null,
    private val accountDao: AccountDao? = null,
    private val preferenceDao: PreferenceDao? = null,
    private val transactionDao: TransactionDao? = null,
    private val subscriptionMatcher: SubscriptionMatcher? = null,
    private val parseUseCase: ParseNotificationUseCase = ParseNotificationUseCase(),
    private val runInTransaction: (suspend (suspend () -> Unit) -> Unit)? = null,
    private val ownPackageName: String = "com.financeautomation"
) {

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
            PipelineTrail.record(PipelineStage.ALERT, PipelineCode.DROPPED_BLACKLIST)
            return NotificationProcessingOutcome.Ignored("Blacklisted privacy package: $sourcePackage")
        }

        // 3. Whitelist check (financial applications and SMS alerts only, includes BuildConfig.DEBUG guard)
        if (!PackageWhitelist.isWhitelisted(sourcePackage)) {
            PipelineTrail.recordDroppedNotWhitelisted()
            return NotificationProcessingOutcome.Ignored("Non-whitelisted package: $sourcePackage")
        }

        // 3b. SMS from a personal number is rejected on the sender alone, before any text is examined
        if (PackageWhitelist.isSmsPackage(sourcePackage) && com.financeautomation.core.utils.RegexPatterns.isPersonalSender(title)) {
            PipelineTrail.recordDroppedNotWhitelisted()
            return NotificationProcessingOutcome.Ignored("Personal sender SMS dropped")
        }

        // 4. OTP and verification suppression check
        val fullContent = if (title.isNotBlank()) "$title. $text".trim() else text.trim()
        if (PackageWhitelist.isOtpOrAuth(fullContent)) {
            PipelineTrail.record(PipelineStage.ALERT, PipelineCode.DROPPED_OTP)
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
                PipelineTrail.record(PipelineStage.ALERT, PipelineCode.DROPPED_DUPLICATE)
                return NotificationProcessingOutcome.Ignored("Duplicate or reconnect replay notification dropped")
            }

            // Prune rows older than 24 hours
            val cutoff = postTime - 24L * 60L * 60L * 1000L
            handledNotificationDao.pruneOlderThan(cutoff)
        }

        // 5b. Expire WAITING captures older than 24 hours quietly to Needs Review
        expireStaleWaitingCaptures(postTime)

        val handledEntity = HandledNotificationEntity(
            key = key,
            postTime = postTime,
            contentHash = contentHash,
            handledAt = System.currentTimeMillis()
        )

        // 6. Inferred account resolution from configured accounts
        val configuredAccounts: List<String> = try {
            accountDao?.getAllAccounts()?.map { it.name } ?: emptyList()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Diagnostics.record("NotificationProcessor", "${e.javaClass.simpleName}: getAllAccounts fallback")
            emptyList()
        }
        val defaultAccount: String = try {
            preferenceDao?.getPreference("default_account") ?: CategoryConstants.ACCOUNT_OTHER
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Diagnostics.record("NotificationProcessor", "${e.javaClass.simpleName}: defaultAccount preference fallback")
            CategoryConstants.ACCOUNT_OTHER
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
                // Counted by reason only; the notification text is never recorded
                PipelineTrail.record(PipelineStage.ALERT, pipelineCodeFor(parseResult.reason))
                NotificationProcessingOutcome.Ignored("ParseUseCase dropped: ${parseResult.reason}")
            }
            is NotificationParseResult.FailureAlert -> {
                PipelineTrail.record(PipelineStage.ALERT, PipelineCode.FAILURE_WORDING)
                // Check if an existing capture is AWAITING_SUCCESS for this notification key
                if (pendingCaptureDao != null && sbnKey.isNotBlank()) {
                    val existing = pendingCaptureDao.getByNotificationKey(sbnKey)
                    if (existing != null && existing.state == PendingCaptureEntity.STATE_AWAITING_SUCCESS) {
                        pendingCaptureDao.updateState(existing.id, PendingCaptureEntity.STATE_DISMISSED)
                        PipelineTrail.record(PipelineStage.CAPTURE, PipelineCode.DISMISSED)
                        return NotificationProcessingOutcome.Dismissed(existing.id)
                    }
                }
                NotificationProcessingOutcome.Ignored("Payment failed, declined or reversed")
            }
            is NotificationParseResult.FallbackPrompt -> {
                PipelineTrail.record(PipelineStage.ALERT, PipelineCode.PARSE_UNREADABLE)
                val outcome = handleCaptureDecision(
                    sourcePackage = sourcePackage,
                    amount = null,
                    payee = "Unknown Payee",
                    inferredAccount = parseResult.inferredAccount,
                    kind = parseResult.kind,
                    wording = parseResult.wording,
                    flags = parseResult.flags,
                    postTime = postTime,
                    sbnKey = sbnKey,
                    contentHash = contentHash,
                    handledEntity = handledEntity,
                    detectedAccountHint = parseResult.detectedAccountHint
                )
                outcome
            }
            is NotificationParseResult.Success -> {
                val kind = if (parseResult.isCredit) PendingCaptureEntity.KIND_CREDIT else PendingCaptureEntity.KIND_DEBIT
                if (parseResult.isCredit) {
                    PipelineTrail.record(PipelineStage.ALERT, PipelineCode.PARSE_OK_CREDIT)
                } else {
                    PipelineTrail.record(PipelineStage.ALERT, PipelineCode.PARSE_OK_DEBIT)
                }
                var flags = parseResult.flags
                var inferredAccount = parseResult.inferredAccount
                if (!parseResult.isCredit) {
                    val match = matchSubscription(parseResult.transaction.totalAmount, fullContent, postTime)
                    if (match != null) {
                        if (match.isDuplicate) {
                            flags = appendFlag(flags, PendingCaptureEntity.FLAG_POSSIBLE_DUPLICATE)
                            flags = appendFlag(flags, PendingCaptureEntity.FLAG_DUPLICATE_OF_PREFIX + match.duplicateOfTransactionId)
                        } else {
                            flags = appendFlag(flags, PendingCaptureEntity.FLAG_SUBSCRIPTION_PREFIX + match.subscription.id)
                            inferredAccount = match.subscription.account.ifBlank { inferredAccount }
                        }
                    }
                }
                val outcome = handleCaptureDecision(
                    sourcePackage = sourcePackage,
                    amount = parseResult.transaction.totalAmount,
                    payee = parseResult.transaction.merchant,
                    inferredAccount = inferredAccount,
                    kind = kind,
                    wording = parseResult.wording,
                    flags = flags,
                    postTime = postTime,
                    sbnKey = sbnKey,
                    contentHash = contentHash,
                    handledEntity = handledEntity,
                    detectedAccountHint = parseResult.detectedAccountHint
                )
                outcome
            }
        }
    }

    private suspend fun matchSubscription(
        amount: Double,
        alertText: String,
        postTime: Long
    ) = try {
        val epochDay = java.time.Instant.ofEpochMilli(postTime)
            .atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate().toEpochDay()
        subscriptionMatcher?.match(amount, alertText, epochDay)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Diagnostics.record("NotificationProcessor", "${e.javaClass.simpleName}: subscription match")
        null
    }

    private fun appendFlag(flags: String, flag: String): String =
        if (flags.isBlank()) flag else "$flags,$flag"

    suspend fun expireStaleWaitingCaptures(now: Long = System.currentTimeMillis()) {
        val pDao = pendingCaptureDao ?: return
        expireStaleCaptures(
            pendingCaptureDao = pDao,
            transactionDao = transactionDao,
            now = now,
            runInTransaction = runInTransaction
        )
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
        handledEntity: HandledNotificationEntity,
        detectedAccountHint: String? = null
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
                    detectedAccountHint = detectedAccountHint ?: existingByKey.detectedAccountHint,
                    updatedAt = System.currentTimeMillis()
                )
                val updateOp: suspend () -> Unit = {
                    handledNotificationDao?.insert(handledEntity)
                    pendingCaptureDao.update(updated)
                }
                if (runInTransaction != null) runInTransaction.invoke(updateOp) else updateOp()
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
                        detectedAccountHint = mergeTarget.detectedAccountHint ?: detectedAccountHint,
                        updatedAt = System.currentTimeMillis()
                    )
                    val mergeOp: suspend () -> Unit = {
                        handledNotificationDao?.insert(handledEntity)
                        pendingCaptureDao.update(merged)
                    }
                    if (runInTransaction != null) runInTransaction.invoke(mergeOp) else mergeOp()
                    PipelineTrail.record(PipelineStage.CAPTURE, PipelineCode.MERGED)
                    return NotificationProcessingOutcome.Merged(merged.id, enriched = true)
                }

                // If already SHOWN or DONE, mark new capture as POSSIBLE_DUPLICATE
                val hasShownOrDone = similarCaptures.any {
                    it.state == PendingCaptureEntity.STATE_SHOWN || it.state == PendingCaptureEntity.STATE_DONE
                }
                if (hasShownOrDone) {
                    PipelineTrail.record(PipelineStage.CAPTURE, PipelineCode.FLAGGED_DUPLICATE)
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
            updatedAt = System.currentTimeMillis(),
            detectedAccountHint = detectedAccountHint
        )

        val insertOp: suspend () -> Unit = {
            handledNotificationDao?.insert(handledEntity)
            pendingCaptureDao?.insert(capture)
        }
        if (runInTransaction != null) runInTransaction.invoke(insertOp) else insertOp()

        if (targetState == PendingCaptureEntity.STATE_AWAITING_SUCCESS) {
            PipelineTrail.record(PipelineStage.CAPTURE, PipelineCode.AWAITING_SUCCESS)
        } else {
            PipelineTrail.record(PipelineStage.CAPTURE, PipelineCode.CREATED_WAITING)
        }

        return NotificationProcessingOutcome.Captured(capture)
    }

    companion object {
        /** Maps a drop reason to its diagnostics counter code. */
        fun pipelineCodeFor(reason: com.financeautomation.domain.usecase.DropReason): PipelineCode = when (reason) {
            com.financeautomation.domain.usecase.DropReason.OTP_MESSAGE -> PipelineCode.DROPPED_OTP
            com.financeautomation.domain.usecase.DropReason.PROMOTIONAL -> PipelineCode.DROPPED_PROMO
            com.financeautomation.domain.usecase.DropReason.NO_SUCCESS_WORDING -> PipelineCode.DROPPED_NO_SUCCESS_WORDING
            com.financeautomation.domain.usecase.DropReason.UNKNOWN_DIRECTION -> PipelineCode.DROPPED_UNKNOWN_DIRECTION
            com.financeautomation.domain.usecase.DropReason.NOT_WHITELISTED -> PipelineCode.DROPPED_NOT_WHITELISTED_COUNT
            com.financeautomation.domain.usecase.DropReason.BLACKLISTED,
            com.financeautomation.domain.usecase.DropReason.SELF_NOTIFICATION -> PipelineCode.DROPPED_BLACKLIST
        }

        /**
         * Developer-mode entry point for simulating financial payment notifications (X5).
         * Runs through the real production NotificationProcessor pipeline.
         */
        suspend fun simulate(
            context: Context,
            sourcePackage: String,
            title: String,
            text: String
        ): NotificationProcessingOutcome {
            val app = context.applicationContext as FinanceApplication
            val container = app.appContainer
            val db = container.awaitDatabase()

            val isDevMode = try {
                container.preferencesRepository.getDeveloperModeSync()
            } catch (e: Exception) {
                false
            }

            if (!BuildConfig.ENABLE_SIMULATOR || !isDevMode) {
                return NotificationProcessingOutcome.Ignored("Simulator is disabled in this build or developer mode is inactive")
            }
            val processor = NotificationProcessor(
                pendingCaptureDao = db.pendingCaptureDao(),
                handledNotificationDao = db.handledNotificationDao(),
                accountDao = db.accountDao(),
                preferenceDao = db.preferenceDao(),
                transactionDao = db.transactionDao(),
                subscriptionMatcher = container.subscriptionMatcher,
                parseUseCase = container.parseNotificationUseCase,
                runInTransaction = { block -> db.withTransaction { block() } },
                ownPackageName = context.packageName
            )
            val sbnKey = "sim_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
            val outcome = processor.process(
                sourcePackage = sourcePackage,
                title = title,
                text = text,
                postTime = System.currentTimeMillis(),
                sbnKey = sbnKey
            )
            when (outcome) {
                is NotificationProcessingOutcome.Captured -> {
                    if (outcome.capture.state == PendingCaptureEntity.STATE_WAITING) {
                        container.foregroundWatcher.notifyCaptureWaiting(CoroutineScope(Dispatchers.IO))
                    }
                }
                is NotificationProcessingOutcome.Updated -> {
                    if (outcome.capture.state == PendingCaptureEntity.STATE_WAITING) {
                        container.foregroundWatcher.notifyCaptureWaiting(CoroutineScope(Dispatchers.IO))
                    }
                }
                else -> {}
            }
            return outcome
        }

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

        /**
         * Single canonical implementation for expiring stale WAITING captures (> 24 hours).
         * - Captures with missing or zero amount (NEEDS_AMOUNT) are marked NEEDS_REVIEW without creating a 0-rupee transaction.
         * - CREDIT captures are marked NEEDS_REVIEW without creating a debit expense.
         * - DEBIT captures with valid amount (> 0.0) are converted into NEEDS_REVIEW transactions with CATEGORY_OTHER.
         */
        suspend fun expireStaleCaptures(
            pendingCaptureDao: PendingCaptureDao,
            transactionDao: TransactionDao?,
            now: Long = System.currentTimeMillis(),
            defaultAccount: String = CategoryConstants.ACCOUNT_OTHER,
            runInTransaction: (suspend (suspend () -> Unit) -> Unit)? = null
        ) {
            val cutoff = now - 24L * 60L * 60L * 1000L
            val expired = pendingCaptureDao.getExpiredWaitingCaptures(cutoff)
            for (cap in expired) {
                val hasNoAmount = cap.amount == null || cap.amount <= 0.0 ||
                    cap.flags.split(",").map { it.trim() }.contains(PendingCaptureEntity.FLAG_NEEDS_AMOUNT)
                if (hasNoAmount) {
                    pendingCaptureDao.updateState(cap.id, PendingCaptureEntity.STATE_NEEDS_REVIEW)
                    continue
                }

                if (cap.kind.equals(PendingCaptureEntity.KIND_CREDIT, ignoreCase = true)) {
                    pendingCaptureDao.updateState(cap.id, PendingCaptureEntity.STATE_NEEDS_REVIEW)
                    continue
                }

                if (transactionDao != null) {
                    val capAmount = cap.amount ?: 0.0
                    val similar = transactionDao.getRecentSimilar(
                        minTimestamp = cap.timestamp - 120_000L,
                        maxTimestamp = cap.timestamp + 5_000L,
                        minAmount = capAmount - 0.01,
                        maxAmount = capAmount + 0.01
                    ).firstOrNull { it.id != cap.id }

                    val isDup = similar != null || cap.flags.contains(PendingCaptureEntity.FLAG_POSSIBLE_DUPLICATE)
                    val duplicateOfId = similar?.id

                    val txnId = "txn_${UUID.randomUUID().toString().replace("-", "").take(12)}"
                    val txn = TransactionEntity(
                        id = txnId,
                        timestamp = cap.timestamp,
                        dateSerial = DateSerialUtils.toDateSerial(cap.timestamp),
                        timeSerial = DateSerialUtils.toTimeSerial(cap.timestamp),
                        merchant = cap.payee.ifBlank { "Expense" },
                        category = CategoryConstants.CATEGORY_OTHER,
                        account = cap.inferredAccount?.takeIf { it.isNotBlank() } ?: defaultAccount,
                        totalAmount = cap.amount,
                        myShare = cap.amount,
                        owedToMe = 0.0,
                        status = "Personal",
                        companions = "Personal",
                        reviewState = ReviewState.NEEDS_REVIEW.name,
                        syncState = SyncState.PENDING.name,
                        sourcePackage = cap.sourcePackage,
                        isPossibleDuplicate = isDup,
                        duplicateOfId = duplicateOfId
                    )
                    val op: suspend () -> Unit = {
                        transactionDao.insert(txn)
                        pendingCaptureDao.updateState(cap.id, PendingCaptureEntity.STATE_DONE)
                    }
                    if (runInTransaction != null) {
                        runInTransaction.invoke(op)
                    } else {
                        op()
                    }
                } else {
                    pendingCaptureDao.updateState(cap.id, PendingCaptureEntity.STATE_NEEDS_REVIEW)
                }
            }
        }
    }
}
