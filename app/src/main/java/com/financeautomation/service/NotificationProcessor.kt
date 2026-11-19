package com.financeautomation.service

import android.content.Context
import androidx.room.withTransaction
import com.financeautomation.BuildConfig
import com.financeautomation.FinanceApplication
import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.constants.PackageWhitelist
import com.financeautomation.core.utils.CrashlyticsManager
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.core.utils.PipelineCode
import com.financeautomation.core.utils.PipelineStage
import com.financeautomation.core.utils.PipelineTrail
import com.financeautomation.core.utils.RegexPatterns
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
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.domain.usecase.DropReason
import com.financeautomation.domain.usecase.NotificationParseResult
import com.financeautomation.domain.usecase.ParseNotificationUseCase
import com.financeautomation.domain.usecase.SubscriptionMatcher
import com.financeautomation.domain.usecase.WordingClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private val processMutex = Mutex()

    private fun ignored(reason: String): NotificationProcessingOutcome.Ignored {
        com.financeautomation.core.utils.Diagnostics.recordDrop(reason)
        return NotificationProcessingOutcome.Ignored(reason)
    }

    suspend fun process(
        sourcePackage: String,
        title: String,
        text: String,
        postTime: Long = System.currentTimeMillis(),
        sbnKey: String = "",
        channelId: String? = null,
        category: String? = null
    ): NotificationProcessingOutcome = processMutex.withLock {
        // 1. Self-notification check
        if (sourcePackage.trim() == ownPackageName) {
            return ignored("Self notification dropped")
        }

        // 2. Blacklist check (privacy-sensitive messaging apps)
        if (PackageWhitelist.isBlacklisted(sourcePackage)) {
            PipelineTrail.record(PipelineStage.ALERT, PipelineCode.DROPPED_BLACKLIST)
            return ignored("Blacklisted privacy package: $sourcePackage")
        }

        // 2b. Chat apps that carry payments (WhatsApp, WhatsApp Business):
        // Only payment notifications on a payments channel or category are allowed. Any chat or general notification is dropped immediately.
        if (PackageWhitelist.isChatApp(sourcePackage) && !PackageWhitelist.isPaymentNotification(channelId, category)) {
            PipelineTrail.record(PipelineStage.ALERT, PipelineCode.DROPPED_BLACKLIST)
            return ignored("Non-payment chat notification dropped: $sourcePackage")
        }

        // 3. Whitelist check (financial applications and SMS alerts only, includes BuildConfig.DEBUG guard)
        if (!PackageWhitelist.isWhitelisted(sourcePackage)) {
            PipelineTrail.recordDroppedNotWhitelisted()
            return ignored("Non-whitelisted package: $sourcePackage")
        }

        // 3b. SMS from a personal number is rejected on the sender alone, before any text is examined
        if (PackageWhitelist.isSmsPackage(sourcePackage) && !com.financeautomation.core.utils.RegexPatterns.isBankSenderId(title)) {
            PipelineTrail.recordDroppedNotWhitelisted()
            return ignored("Personal sender SMS dropped")
        }

        // 4. OTP and verification suppression check
        val fullContent = if (title.isNotBlank()) "$title. $text".trim() else text.trim()
        if (PackageWhitelist.isOtpOrAuth(fullContent)) {
            PipelineTrail.record(PipelineStage.ALERT, PipelineCode.DROPPED_OTP)
            return ignored("OTP or auth alert suppressed")
        }

        // Memory-only content hash (SHA-256 of lowercase title + text, 16 hex characters)
        val contentHash = computeContentHash(title, text)
        val key = if (sbnKey.isNotBlank()) sbnKey else "$sourcePackage|$postTime"

        // 5. Handled-notification and reconnect replay check
        val cutoff = postTime - 24L * 60L * 60L * 1000L
        if (handledNotificationDao != null) {
            val existingHandled = handledNotificationDao.get(key = key, postTime = postTime)
                ?: handledNotificationDao.getLatestByKey(key)?.takeIf { it.contentHash == contentHash }
                ?: handledNotificationDao.getByContentHash(contentHash, cutoff)
            if (existingHandled != null) {
                // Reconnect replay or duplicate posted alert
                PipelineTrail.record(PipelineStage.ALERT, PipelineCode.DROPPED_DUPLICATE)
                return ignored("Duplicate or reconnect replay notification dropped")
            }

            // Prune rows older than 24 hours
            handledNotificationDao.pruneOlderThan(cutoff)
        }

        if (pendingCaptureDao != null) {
            val existingCapture = pendingCaptureDao.getByContentHash(contentHash, cutoff)
            if (existingCapture != null && (existingCapture.state == PendingCaptureEntity.STATE_WAITING ||
                existingCapture.state == PendingCaptureEntity.STATE_SHOWN ||
                existingCapture.state == PendingCaptureEntity.STATE_DONE ||
                existingCapture.state == PendingCaptureEntity.STATE_NEEDS_REVIEW)) {
                PipelineTrail.record(PipelineStage.ALERT, PipelineCode.DROPPED_DUPLICATE)
                return ignored("Duplicate or reconnect replay notification dropped")
            }
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
        val allAccounts = try {
            accountDao?.getAllAccounts() ?: emptyList()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Diagnostics.record("NotificationProcessor", "${e.javaClass.simpleName}: getAllAccounts fallback")
            CrashlyticsManager.recordNonFatal(e, "NotificationProcessor.getAllAccounts")
            emptyList()
        }
        val configuredAccounts: List<String> = allAccounts.map { it.name }
        val accountDigits: Map<String, String?> = allAccounts.associate { it.name to it.lastFourDigits }
        val defaultAccount: String = try {
            preferenceDao?.getPreference(PreferencesRepository.KEY_DEFAULT_ACCOUNT) ?: CategoryConstants.ACCOUNT_OTHER
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
            defaultAccount = defaultAccount,
            accountDigits = accountDigits,
            channelId = channelId,
            category = category
        )

        return when (parseResult) {
            is NotificationParseResult.Dropped -> {
                // Counted by reason only; the notification text is never recorded
                PipelineTrail.record(PipelineStage.ALERT, pipelineCodeFor(parseResult.reason))
                ignored("ParseUseCase dropped: ${parseResult.reason}")
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
                ignored("Payment failed, declined or reversed")
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
                    detectedAccountHint = parseResult.detectedAccountHint,
                    text = text
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
                    detectedAccountHint = parseResult.detectedAccountHint,
                    text = text
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
        detectedAccountHint: String? = null,
        text: String = ""
    ): NotificationProcessingOutcome {
        val cleanPayee = com.financeautomation.core.utils.InputPolicy.cleanName(payee)
            .take(com.financeautomation.core.utils.InputPolicy.MAX_MERCHANT_NAME_LENGTH)
            .ifBlank { "Unknown Payee" }

        val targetState = when {
            flags.split(",").map { it.trim() }.contains(PendingCaptureEntity.FLAG_REFUND_REVERSAL) -> PendingCaptureEntity.STATE_NEEDS_REVIEW
            wording == WordingClass.PROCESSING -> PendingCaptureEntity.STATE_AWAITING_SUCCESS
            else -> PendingCaptureEntity.STATE_WAITING
        }

        // Check if an existing capture with the same sbn.key exists (e.g. processing -> success update)
        if (pendingCaptureDao != null && sbnKey.isNotBlank()) {
            val existingByKey = pendingCaptureDao.getByNotificationKey(sbnKey)
            if (existingByKey != null && existingByKey.state == PendingCaptureEntity.STATE_AWAITING_SUCCESS) {
                val updated = existingByKey.copy(
                    amount = amount ?: existingByKey.amount,
                    payee = if (cleanPayee != "Unknown Payee") cleanPayee else existingByKey.payee,
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

        val incomingRefId = RegexPatterns.extractReferenceId(text)
        var activeFlags = if (incomingRefId != null && !flags.contains("REF_")) {
            if (flags.isNotBlank()) "$flags,REF_$incomingRefId" else "REF_$incomingRefId"
        } else {
            flags
        }

        // Deduplication (same-package, cross-package, and confirmed late-merge)
        if (pendingCaptureDao != null && amount != null && amount > 0.0) {
            val searchMinTime = postTime - WINDOW_CONFIRMED_LATE_MERGE_MS
            val searchMaxTime = postTime + WINDOW_CONFIRMED_LATE_MERGE_MS
            val similarCaptures = pendingCaptureDao.findSimilarByAmountAndTime(amount, searchMinTime, searchMaxTime)
                .filter { candidate ->
                    // Must be the same transaction kind (debits merge with debits)
                    if (candidate.kind != kind) return@filter false

                    // Same-package merge is allowed for debits
                    if (candidate.sourcePackage == sourcePackage && kind != PendingCaptureEntity.KIND_DEBIT) {
                        return@filter false
                    }

                    // Account digits must be compatible if both are present
                    if (!areAccountsCompatible(candidate.inferredAccount, candidate.detectedAccountHint, inferredAccount, detectedAccountHint)) {
                        return@filter false
                    }

                    // Payees must be compatible, so two real payments of the same amount to different payees stay separate
                    if (!com.financeautomation.core.utils.MerchantSimilarity.compatible(candidate.payee, cleanPayee)) {
                        return@filter false
                    }

                    // Reference ID beats the window when both messages carry one
                    val candidateRef = extractRefFromFlags(candidate.flags)
                    if (candidateRef != null && incomingRefId != null) {
                        if (!candidateRef.equals(incomingRefId, ignoreCase = true)) {
                            return@filter false // Different reference IDs stay separate
                        }
                        return@filter true // Equal reference ID always merges up to 24h
                    }

                    // For late merge into a confirmed/done capture:
                    if (candidate.state == PendingCaptureEntity.STATE_DONE) {
                        // Allow late merge ONLY ONCE per confirmed transaction
                        if (candidate.flags.contains("BANK_SMS_ATTACHED")) return@filter false

                        val candidateRef = extractRefFromFlags(candidate.flags)
                        val hasMatchingRef = candidateRef != null && incomingRefId != null && candidateRef == incomingRefId

                        val delta = kotlin.math.abs(candidate.timestamp - postTime)
                        if (hasMatchingRef) {
                            // Equal reference id may still merge up to 24 h
                            return@filter delta <= WINDOW_CONFIRMED_LATE_MERGE_MS
                        }

                        // Without matching ref, allow late merge ONLY for a generic bank SMS (Unknown Payee) within 30 minutes
                        if (cleanPayee != "Unknown Payee") return@filter false
                        return@filter delta <= WINDOW_CONFIRMED_GENERIC_MERGE_MS
                    }

                    // Window checks for open captures (WAITING, AWAITING_SUCCESS, NEEDS_REVIEW)
                    val delta = kotlin.math.abs(candidate.timestamp - postTime)
                    if (candidate.sourcePackage != sourcePackage) {
                        delta <= WINDOW_CROSS_PACKAGE_MERGE_MS
                    } else {
                        delta <= WINDOW_SAME_PACKAGE_MERGE_MS
                    }
                }

            if (similarCaptures.isNotEmpty()) {
                val openMergeTarget = similarCaptures.firstOrNull {
                    it.state == PendingCaptureEntity.STATE_WAITING ||
                        it.state == PendingCaptureEntity.STATE_AWAITING_SUCCESS ||
                        it.state == PendingCaptureEntity.STATE_NEEDS_REVIEW
                }
                if (openMergeTarget != null) {
                    val richerPayee = selectRicherPayee(openMergeTarget.payee, cleanPayee)
                    val candidateRef = extractRefFromFlags(openMergeTarget.flags)
                    val mergedFlags = if (candidateRef == null && incomingRefId != null) {
                        if (openMergeTarget.flags.isNotBlank()) "${openMergeTarget.flags},REF_$incomingRefId" else "REF_$incomingRefId"
                    } else {
                        openMergeTarget.flags
                    }
                    val merged = openMergeTarget.copy(
                        payee = richerPayee,
                        inferredAccount = if (openMergeTarget.inferredAccount.isNullOrBlank() && inferredAccount.isNotBlank()) inferredAccount else openMergeTarget.inferredAccount,
                        detectedAccountHint = openMergeTarget.detectedAccountHint ?: detectedAccountHint,
                        flags = mergedFlags,
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

                // If match is already confirmed / DONE, attach missing facts to capture only (never touch confirmed transaction in DB)
                val confirmedTarget = similarCaptures.firstOrNull { it.state == PendingCaptureEntity.STATE_DONE }
                if (confirmedTarget != null) {
                    val candidateRef = extractRefFromFlags(confirmedTarget.flags)
                    val flagsWithBankAttached = buildList {
                        add(confirmedTarget.flags)
                        if (!confirmedTarget.flags.contains("BANK_SMS_ATTACHED")) add("BANK_SMS_ATTACHED")
                        if (candidateRef == null && incomingRefId != null) add("REF_$incomingRefId")
                    }.filter { it.isNotBlank() }.joinToString(",")

                    val enrichedConfirmed = confirmedTarget.copy(
                        inferredAccount = if ((confirmedTarget.inferredAccount.isNullOrBlank() || confirmedTarget.inferredAccount == CategoryConstants.ACCOUNT_OTHER) && inferredAccount.isNotBlank()) inferredAccount else confirmedTarget.inferredAccount,
                        detectedAccountHint = confirmedTarget.detectedAccountHint ?: detectedAccountHint,
                        flags = flagsWithBankAttached,
                        updatedAt = System.currentTimeMillis()
                    )
                    val attachOp: suspend () -> Unit = {
                        handledNotificationDao?.insert(handledEntity)
                        pendingCaptureDao.update(enrichedConfirmed)
                    }
                    if (runInTransaction != null) runInTransaction.invoke(attachOp) else attachOp()
                    PipelineTrail.record(PipelineStage.CAPTURE, PipelineCode.MERGED_INTO_CONFIRMED)
                    return NotificationProcessingOutcome.Merged(confirmedTarget.id, enriched = true)
                }

                // If already SHOWN, mark new capture as POSSIBLE_DUPLICATE
                val hasShown = similarCaptures.any { it.state == PendingCaptureEntity.STATE_SHOWN }
                if (hasShown) {
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
            payee = cleanPayee,
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
            val cutoffTime = postTime - 24L * 60L * 60L * 1000L
            val isDuplicate = handledNotificationDao?.getByContentHash(contentHash, cutoffTime) != null ||
                pendingCaptureDao?.getByContentHash(contentHash, cutoffTime) != null
            if (!isDuplicate) {
                handledNotificationDao?.insert(handledEntity)
                pendingCaptureDao?.insert(capture)
            }
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
        const val WINDOW_SAME_PACKAGE_MERGE_MS: Long = 180_000L
        const val WINDOW_CROSS_PACKAGE_MERGE_MS: Long = 600_000L
        const val WINDOW_CONFIRMED_LATE_MERGE_MS: Long = 86_400_000L // 24 hours for matching reference id
        const val WINDOW_CONFIRMED_GENERIC_MERGE_MS: Long = 1_800_000L // 30 minutes for generic bank SMS
        const val WINDOW_CROSS_SOURCE_MERGE_MS: Long = WINDOW_SAME_PACKAGE_MERGE_MS

        fun selectRicherPayee(existingPayee: String, incomingPayee: String): String = when {
            existingPayee == "Unknown Payee" && incomingPayee != "Unknown Payee" -> incomingPayee
            incomingPayee != "Unknown Payee" && existingPayee != "Unknown Payee" &&
                incomingPayee.length > existingPayee.length &&
                incomingPayee.contains(existingPayee, ignoreCase = true) -> incomingPayee
            else -> existingPayee
        }

        fun extractRefFromFlags(flags: String): String? {
            return flags.split(',').find { it.startsWith("REF_") }?.removePrefix("REF_")
        }

        fun areAccountsCompatible(
            targetAcc: String?,
            targetHint: String?,
            incomingAcc: String?,
            incomingHint: String?
        ): Boolean {
            val d1 = extractAccountDigits(targetAcc) ?: extractAccountDigits(targetHint)
            val d2 = extractAccountDigits(incomingAcc) ?: extractAccountDigits(incomingHint)
            if (d1 != null && d2 != null) {
                return d1 == d2 || d1.endsWith(d2) || d2.endsWith(d1)
            }
            return true
        }

        private fun extractAccountDigits(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val match = Regex("""(?i)(?:xx|ending\s+|a/c\s*no\.?\s*|\.\.)*(\d{3,4})\b""").find(raw)
            return match?.groupValues?.get(1)
        }

        /** Maps a drop reason to its diagnostics counter code. */
        fun pipelineCodeFor(reason: com.financeautomation.domain.usecase.DropReason): PipelineCode = when (reason) {
            com.financeautomation.domain.usecase.DropReason.OTP_MESSAGE -> PipelineCode.DROPPED_OTP
            com.financeautomation.domain.usecase.DropReason.PROMOTIONAL -> PipelineCode.DROPPED_PROMO
            com.financeautomation.domain.usecase.DropReason.NO_SUCCESS_WORDING -> PipelineCode.DROPPED_NO_SUCCESS_WORDING
            com.financeautomation.domain.usecase.DropReason.UNKNOWN_DIRECTION -> PipelineCode.DROPPED_UNKNOWN_DIRECTION
            com.financeautomation.domain.usecase.DropReason.NOT_WHITELISTED -> PipelineCode.DROPPED_NOT_WHITELISTED_COUNT
            com.financeautomation.domain.usecase.DropReason.BLACKLISTED,
            com.financeautomation.domain.usecase.DropReason.SELF_NOTIFICATION -> PipelineCode.DROPPED_BLACKLIST
            com.financeautomation.domain.usecase.DropReason.PAYMENT_REQUEST -> PipelineCode.DROPPED_PAYMENT_REQUEST
            com.financeautomation.domain.usecase.DropReason.MANDATE_NOTICE -> PipelineCode.DROPPED_MANDATE_NOTICE
            com.financeautomation.domain.usecase.DropReason.BALANCE_HOLD -> PipelineCode.DROPPED_BALANCE_HOLD
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
