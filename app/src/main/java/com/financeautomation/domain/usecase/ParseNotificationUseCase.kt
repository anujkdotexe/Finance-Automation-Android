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
        val inferredAccount: String = transaction.account
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
        val timestamp: Long = System.currentTimeMillis()
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
    SELF_NOTIFICATION
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

        fun classifyWording(text: String): WordingClass {
            val lower = text.lowercase()
            if (FAILURE_WORDS.any { lower.contains(it) }) return WordingClass.FAILURE
            if (PROCESSING_WORDS.any { lower.contains(it) }) return WordingClass.PROCESSING
            if (SUCCESS_WORDS.any { lower.contains(it) }) return WordingClass.SUCCESS
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
            // Drop SMS from personal numbers or contacts
            if (RegexPatterns.isPersonalSender(title)) {
                return NotificationParseResult.Dropped(
                    reason = DropReason.NOT_WHITELISTED,
                    sourcePackage = sourcePackage
                )
            }
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

        // 7. Direction discrimination
        val isCredit = RegexPatterns.isCredit(fullContent)
        val isDebit = RegexPatterns.isDebit(fullContent) || !isCredit
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
                inferredAccount = detectedAccount,
                kind = kind,
                flags = fallbackFlags,
                timestamp = timestamp
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
            inferredAccount = detectedAccount
        )
    }
}
