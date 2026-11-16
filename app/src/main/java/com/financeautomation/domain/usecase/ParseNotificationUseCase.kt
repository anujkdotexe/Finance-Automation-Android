package com.financeautomation.domain.usecase

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.constants.PackageWhitelist
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.core.utils.DateSerialUtils
import com.financeautomation.core.utils.RegexPatterns
import com.financeautomation.domain.model.Transaction
import java.util.UUID

/**
 * Wording classification of financial notification alert text.
 */
enum class WordingClass {
    SUCCESS,
    PROCESSING,
    FAILURE,
    UNKNOWN
}

/**
 * Result representing the outcome of notification interception parsing.
 */
sealed interface NotificationParseResult {

    /**
     * Successfully parsed a financial notification into a transaction candidate.
     */
    data class Success(
        val transaction: Transaction,
        val isDebit: Boolean,
        val isCredit: Boolean,
        val wording: WordingClass = WordingClass.SUCCESS,
        val flags: String = "",
        val inferredAccount: String = transaction.account,
        val detectedAccountHint: String? = null
    ) : NotificationParseResult

    /**
     * Whitelisted financial notification detected, but amount was not extractable.
     * Surfaces a fallback prompt with unreadable alert flags.
     */
    data class FallbackPrompt(
        val message: String,
        val sourcePackage: String,
        val wording: WordingClass = WordingClass.UNKNOWN,
        val inferredAccount: String = CategoryConstants.ACCOUNT_OTHER,
        val kind: String = "DEBIT",
        val flags: String = "NEEDS_AMOUNT",
        val timestamp: Long = System.currentTimeMillis(),
        val detectedAccountHint: String? = null
    ) : NotificationParseResult

    /**
     * Notification wording explicitly indicates failure, decline, or reversal.
     */
    data class FailureAlert(
        val sourcePackage: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : NotificationParseResult

    /**
     * Notification dropped by whitelist, blacklist, OTP suppression, or self-package rule.
     */
    data class Dropped(
        val reason: DropReason,
        val sourcePackage: String
    ) : NotificationParseResult
}

enum class DropReason {
    NOT_WHITELISTED,
    BLACKLISTED,
    OTP_MESSAGE,
    SELF_NOTIFICATION,
    PROMOTIONAL,
    NO_SUCCESS_WORDING,
    UNKNOWN_DIRECTION
}

/**
 * Pure domain UseCase for intercepting and parsing Android system notifications.
 * Filters by whitelist/blacklist, suppresses OTP alerts, and extracts transaction candidates.
 * Zero Android framework dependencies.
 */
class ParseNotificationUseCase(
    private val ownPackageName: String = "com.financeautomation"
) {

    companion object {
        val PROCESSING_WORDS: List<String> = listOf("processing", "pending", "initiated", "in progress")
        val SUCCESS_WORDS: List<String> = listOf("paid", "successful", "debited", "sent", "credited", "received")
        val FAILURE_WORDS: List<String> = listOf("failed", "declined", "unsuccessful", "reversed")

        private val FAILURE_REGEX = Regex("""(?i)\b(failed|declined|unsuccessful|reversed)\b""")
        private val PROCESSING_REGEX = Regex("""(?i)\b(processing|pending|initiated|in\s+progress)\b""")
        private val SUCCESS_REGEX = Regex("""(?i)\b(paid|successfully?|debited|sent|credited|received)\b""")

        fun classifyWording(text: String): WordingClass {
            if (FAILURE_REGEX.containsMatchIn(text)) return WordingClass.FAILURE
            if (PROCESSING_REGEX.containsMatchIn(text)) return WordingClass.PROCESSING
            if (SUCCESS_REGEX.containsMatchIn(text)) return WordingClass.SUCCESS
            return WordingClass.UNKNOWN
        }

        data class InferredAccountResult(
            val account: String,
            val isNewAccount: Boolean,
            val detectedName: String? = null
        )

        fun inferAccountWithFlag(
            text: String,
            configuredAccounts: List<String>,
            defaultAccount: String = CategoryConstants.ACCOUNT_OTHER
        ): InferredAccountResult {
            val digitMatch = Regex("""(?i)(?:xx|x|ending\s+|a/c\s*no\.?\s*|\.\.)(\d{3,4})\b""").find(text)
            val extractedDigits = digitMatch?.groupValues?.get(1)

            for (account in configuredAccounts) {
                val trimmedAccount = account.trim()
                if (trimmedAccount.isBlank()) continue

                val wordPattern = Regex("""(?i)\b${Regex.escape(trimmedAccount)}\b""")
                if (wordPattern.containsMatchIn(text)) {
                    return InferredAccountResult(trimmedAccount, false, null)
                }

                if (extractedDigits != null && trimmedAccount.contains(extractedDigits)) {
                    return InferredAccountResult(trimmedAccount, false, null)
                }
            }

            // Check if text mentions an unconfigured/unknown account pattern (e.g. A/c XX4821 or HDFC Bank)
            val extractedUnknown = RegexPatterns.extractAccount(text)
            val safeDefault = defaultAccount.ifBlank { CategoryConstants.ACCOUNT_OTHER }
            if (extractedUnknown != null) {
                return InferredAccountResult(safeDefault, true, extractedUnknown)
            }

            return InferredAccountResult(safeDefault, false, null)
        }

        fun inferAccount(
            text: String,
            configuredAccounts: List<String>,
            defaultAccount: String = CategoryConstants.ACCOUNT_OTHER
        ): String {
            return inferAccountWithFlag(text, configuredAccounts, defaultAccount).account
        }
    }

    operator fun invoke(
        sourcePackage: String,
        title: String,
        text: String,
        appName: String? = null,
        timestamp: Long = System.currentTimeMillis(),
        configuredAccounts: List<String> = emptyList(),
        defaultAccount: String = CategoryConstants.ACCOUNT_OTHER
    ): NotificationParseResult {
        // 1. Self-notification check
        if (sourcePackage.trim() == ownPackageName) {
            return NotificationParseResult.Dropped(
                reason = DropReason.SELF_NOTIFICATION,
                sourcePackage = sourcePackage
            )
        }

        // 2. Blacklist check (privacy-sensitive messaging apps)
        if (PackageWhitelist.isBlacklisted(sourcePackage)) {
            return NotificationParseResult.Dropped(
                reason = DropReason.BLACKLISTED,
                sourcePackage = sourcePackage
            )
        }

        // 3. Whitelist check (financial applications and SMS alerts only)
        if (!PackageWhitelist.isWhitelisted(sourcePackage)) {
            return NotificationParseResult.Dropped(
                reason = DropReason.NOT_WHITELISTED,
                sourcePackage = sourcePackage
            )
        }

        // 3b. SMS from a personal number is rejected on the sender alone, before any of its text is examined
        if (PackageWhitelist.isSmsPackage(sourcePackage) && !RegexPatterns.isBankSenderId(title)) {
            return NotificationParseResult.Dropped(
                reason = DropReason.NOT_WHITELISTED,
                sourcePackage = sourcePackage
            )
        }

        val fullContent = if (title.isNotBlank()) "$title. $text".trim() else text.trim()

        // 4. OTP and verification suppression check
        if (PackageWhitelist.isOtpMessage(fullContent)) {
            return NotificationParseResult.Dropped(
                reason = DropReason.OTP_MESSAGE,
                sourcePackage = sourcePackage
            )
        }

        val isSms = PackageWhitelist.isSmsPackage(sourcePackage)

        // 5. SMS privacy and financial content guard
        if (isSms) {
            // Drop SMS that lack financial transaction indicators
            if (!RegexPatterns.hasFinancialKeywords(fullContent)) {
                return NotificationParseResult.Dropped(
                    reason = DropReason.NOT_WHITELISTED,
                    sourcePackage = sourcePackage
                )
            }
        }

        // 6. Wording classification
        val wording = classifyWording(fullContent)
        if (wording == WordingClass.FAILURE) {
            return NotificationParseResult.FailureAlert(
                sourcePackage = sourcePackage,
                timestamp = timestamp
            )
        }

        // 6b. Promotions and reminders are ignored. A text that confirms a completed payment with an amount is kept.
        // Soft words (bill, recharge, EMI, due, earn, win, loan) are promotional only without success wording.
        if (!RegexPatterns.hasConfirmation(fullContent)) {
            val successWithAmount = wording == WordingClass.SUCCESS && RegexPatterns.extractAmount(fullContent) != null
            val hard = RegexPatterns.isHardPromotional(fullContent)
            val soft = RegexPatterns.isSoftPromotional(fullContent) &&
                !RegexPatterns.isPaymentSuccessful(fullContent) && !successWithAmount
            if (hard || soft) {
                return NotificationParseResult.Dropped(reason = DropReason.PROMOTIONAL, sourcePackage = sourcePackage)
            }
        }

        // 6c. Payment apps must show success (or in-progress) wording and a known direction
        if (!isSms) {
            if (wording == WordingClass.UNKNOWN) {
                return NotificationParseResult.Dropped(reason = DropReason.NO_SUCCESS_WORDING, sourcePackage = sourcePackage)
            }
            val knownDirection = RegexPatterns.isDebit(fullContent) ||
                RegexPatterns.isPaymentSuccessful(fullContent) ||
                RegexPatterns.isCredit(fullContent)
            if (wording == WordingClass.SUCCESS && !knownDirection) {
                return NotificationParseResult.Dropped(reason = DropReason.UNKNOWN_DIRECTION, sourcePackage = sourcePackage)
            }
        }

        // 7. Direction discrimination: check verb attached to the amount first (C-2)
        val hasDebitAmount = RegexPatterns.hasDebitConfirmation(fullContent)
        val hasCreditAmount = RegexPatterns.hasCreditConfirmation(fullContent)

        val isCredit = when {
            hasDebitAmount -> false
            hasCreditAmount -> true
            RegexPatterns.isDebit(fullContent) -> false
            RegexPatterns.isCredit(fullContent) -> true
            else -> false
        }
        val isDebit = !isCredit
        val kind = if (isCredit) "CREDIT" else "DEBIT"

        // 8. Funding account inference
        val inferred = inferAccountWithFlag(fullContent, configuredAccounts, defaultAccount)
        val account = inferred.account
        val isNewAccount = inferred.isNewAccount
        val detectedAccount = inferred.detectedName ?: account

        // 9. Amount extraction
        val amount = RegexPatterns.extractAmount(fullContent)
        if (amount == null || amount <= 0.0) {
            if (isSms) {
                // SMS without an explicit currency amount is non-transactional (e.g. promo, advisory)
                return NotificationParseResult.Dropped(
                    reason = DropReason.NOT_WHITELISTED,
                    sourcePackage = sourcePackage
                )
            }
            val fallbackFlags = if (isNewAccount) "NEEDS_AMOUNT,NEW_ACCOUNT" else "NEEDS_AMOUNT"
            return NotificationParseResult.FallbackPrompt(
                message = "Payment detected, tap to enter amount",
                sourcePackage = sourcePackage,
                wording = wording,
                inferredAccount = account,
                kind = kind,
                flags = fallbackFlags,
                timestamp = timestamp,
                detectedAccountHint = inferred.detectedName
            )
        }

        // 10. Merchant / Payee extraction
        val merchant = (if (title.isNotBlank()) RegexPatterns.extractMerchant(title) else null)
            ?: RegexPatterns.extractMerchant(text)
            ?: RegexPatterns.extractMerchant(fullContent)
            ?: "Unknown Payee"

        // 10. Date and time serial conversion
        val dateSerial = DateSerialUtils.toDateSerial(timestamp)
        val timeSerial = DateSerialUtils.toTimeSerial(timestamp)

        val transaction = Transaction(
            id = "txn_${UUID.randomUUID().toString().replace("-", "")}",
            timestamp = timestamp,
            dateSerial = dateSerial,
            timeSerial = timeSerial,
            account = account,
            merchant = merchant,
            tripTag = "",
            category = CategoryConstants.CATEGORY_OTHER,
            totalAmount = amount,
            companions = "Personal",
            myShare = amount,
            owedToMe = 0.00,
            status = TransactionStatus.PERSONAL,
            sourcePackage = sourcePackage,
            reviewState = ReviewState.NEEDS_REVIEW,
            syncState = SyncState.PENDING,
            sheetRowIndex = null,
            isPossibleDuplicate = false
        )

        val flags = if (isNewAccount) "NEW_ACCOUNT" else ""
        return NotificationParseResult.Success(
            transaction = transaction,
            isDebit = isDebit,
            isCredit = isCredit,
            wording = wording,
            flags = flags,
            inferredAccount = account,
            detectedAccountHint = inferred.detectedName
        )
    }
}
