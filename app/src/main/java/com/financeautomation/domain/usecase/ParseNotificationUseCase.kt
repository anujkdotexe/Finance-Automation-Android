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
    UNKNOWN_DIRECTION,
    PAYMENT_REQUEST,
    MANDATE_NOTICE,
    BALANCE_HOLD
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
        val FAILURE_WORDS: List<String> = listOf("failed", "declined", "unsuccessful", "could not be processed", "wrong upi pin")

        private val FAILURE_REGEX = Regex("""(?i)\b(failed|declined|unsuccessful|could\s+not\s+be\s+(?:recovered|processed)|wrong\s+upi\s+pin|not\s+successful)\b""")
        private val PROCESSING_REGEX = Regex("""(?i)\b(processing|pending|initiated|in\s+progress)\b""")
        private val SUCCESS_REGEX = Regex("""(?i)\b(paid|successful(?:ly)?|debited|sent|credited|received)\b""")

        private val PAYMENT_REQUEST_REGEX = Regex("""(?i)\b(requested\s+money|collect\s+request|has\s+requested|requests?\s+(?:rs\.?|inr|₹)|payment\s+request)\b""")
        private val BALANCE_HOLD_REGEX = Regex("""(?i)\b(kept\s+as\s+hold|kept\s+on\s+hold|hold\s+has\s+been\s+removed|on\s+hold\s+has\s+been\s+removed)\b""")
        private val MANDATE_NOTICE_REGEX = Regex("""(?i)\b(upcoming\s+mandate|will\s+be\s+(?:auto\s+)?debited|will\s+be\s+(?:auto\s*)?renewed|auto\s+debited\s+via|mandate\s+(?:has\s+been\s+)?(?:successfully\s+)?(?:created|set|paused|revoked|modified|cancel\w*)|mandate\s+request|autopay\s+(?:successfully\s+)?created|autopay\b.{0,100}?\b(?:is\s+paused|is\s+scheduled)|is\s+scheduled\s+on|auto\s*pay\s+facility)\b""")
        private val EXECUTED_DEBIT_WORDING_REGEX = Regex("""(?i)\b(has\s+been\s+debited|debited\s+by|paid\s+thru|paid\s+to|dr\.?|successfully\s+paid)\b""")
        private val REVERSAL_CREDIT_REGEX = Regex("""(?i)\b(?:reversed|refund(?:ed)?)\s+to\s+your\s+account\b|\bamount\s+of\s+[^\n]+has\s+been\s+reversed\b""")
        private val INVESTMENT_NON_MONEY_REGEX = Regex("""(?i)\b(?:cdsl|nsdl):?\s*(?:credit|debit)\b|\b(?:redemption\s+request|payout\s+shall\s+be\s+made)\b""")
        private val STORE_CREDIT_REGEX = Regex("""(?i)\b(?:can\s+be\s+redeemed\s+on\s+your\s+subsequent\s+orders|credits\s+have\s+been\s+added\s+to\s+your\s+\w+\s+account)\b""")
        private val AGGREGATOR_RECEIPT_REGEX = Regex("""(?i)\b(?:thank\s+you\s+for\s+(?:making\s+a\s+)?successful\s+transaction|transaction\s+with\s+txn\s+id\b.*has\s+been\s+successfully\s+processed)\b""")

        fun classifyWording(text: String): WordingClass {
            if (FAILURE_REGEX.containsMatchIn(text)) return WordingClass.FAILURE
            if (REVERSAL_CREDIT_REGEX.containsMatchIn(text)) return WordingClass.SUCCESS
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
            defaultAccount: String = CategoryConstants.ACCOUNT_OTHER,
            accountDigits: Map<String, String?> = emptyMap()
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

                val digits = accountDigits[trimmedAccount]?.takeIf { it.isNotBlank() }
                if (digits != null) {
                    if (extractedDigits == digits || text.contains(digits)) {
                        return InferredAccountResult(trimmedAccount, false, null)
                    }
                } else if (extractedDigits != null && trimmedAccount.contains(extractedDigits)) {
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
            defaultAccount: String = CategoryConstants.ACCOUNT_OTHER,
            accountDigits: Map<String, String?> = emptyMap()
        ): String {
            return inferAccountWithFlag(text, configuredAccounts, defaultAccount, accountDigits).account
        }
    }

    operator fun invoke(
        sourcePackage: String,
        title: String,
        text: String,
        appName: String? = null,
        timestamp: Long = System.currentTimeMillis(),
        configuredAccounts: List<String> = emptyList(),
        defaultAccount: String = CategoryConstants.ACCOUNT_OTHER,
        accountDigits: Map<String, String?> = emptyMap(),
        channelId: String? = null,
        category: String? = null
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

        // 2b. Chat apps that carry payments (WhatsApp, WhatsApp Business):
        // Only payment notifications on a payments channel or category are allowed. Any chat or general notification is dropped immediately.
        if (PackageWhitelist.isChatApp(sourcePackage) && !PackageWhitelist.isPaymentNotification(channelId, category)) {
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

        // 5b. Negative recognisers (P1-9 non-transactional messages)
        if (PAYMENT_REQUEST_REGEX.containsMatchIn(fullContent)) {
            return NotificationParseResult.Dropped(
                reason = DropReason.PAYMENT_REQUEST,
                sourcePackage = sourcePackage
            )
        }

        if (BALANCE_HOLD_REGEX.containsMatchIn(fullContent)) {
            return NotificationParseResult.Dropped(
                reason = DropReason.BALANCE_HOLD,
                sourcePackage = sourcePackage
            )
        }

        val hasExecutedDebit = EXECUTED_DEBIT_WORDING_REGEX.containsMatchIn(fullContent) && RegexPatterns.extractAmount(fullContent) != null
        if (MANDATE_NOTICE_REGEX.containsMatchIn(fullContent) && !hasExecutedDebit) {
            return NotificationParseResult.Dropped(
                reason = DropReason.MANDATE_NOTICE,
                sourcePackage = sourcePackage
            )
        }

        if (INVESTMENT_NON_MONEY_REGEX.containsMatchIn(fullContent) && !hasExecutedDebit) {
            return NotificationParseResult.Dropped(
                reason = DropReason.NOT_WHITELISTED,
                sourcePackage = sourcePackage
            )
        }

        if (STORE_CREDIT_REGEX.containsMatchIn(fullContent)) {
            return NotificationParseResult.Dropped(
                reason = DropReason.NOT_WHITELISTED,
                sourcePackage = sourcePackage
            )
        }

        if (AGGREGATOR_RECEIPT_REGEX.containsMatchIn(fullContent) && !hasExecutedDebit) {
            return NotificationParseResult.Dropped(
                reason = DropReason.NOT_WHITELISTED,
                sourcePackage = sourcePackage
            )
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
            if (wording == WordingClass.UNKNOWN && !PackageWhitelist.isGenericWatchedApp(sourcePackage)) {
                return NotificationParseResult.Dropped(reason = DropReason.NO_SUCCESS_WORDING, sourcePackage = sourcePackage)
            }
            val knownDirection = RegexPatterns.isDebit(fullContent) ||
                RegexPatterns.isPaymentSuccessful(fullContent) ||
                RegexPatterns.isCredit(fullContent)
            if (wording == WordingClass.SUCCESS && !knownDirection && !PackageWhitelist.isGenericWatchedApp(sourcePackage)) {
                return NotificationParseResult.Dropped(reason = DropReason.UNKNOWN_DIRECTION, sourcePackage = sourcePackage)
            }
        }

        // 7. Direction discrimination: check verb attached to the amount first (C-2)
        val isReversalCredit = REVERSAL_CREDIT_REGEX.containsMatchIn(fullContent)
        val hasDebitAmount = RegexPatterns.hasDebitConfirmation(fullContent)
        val hasCreditAmount = RegexPatterns.hasCreditConfirmation(fullContent)

        val hasKnownDirection = isReversalCredit || hasDebitAmount || hasCreditAmount ||
            RegexPatterns.isDebit(fullContent) || RegexPatterns.isCredit(fullContent) ||
            RegexPatterns.isPaymentSuccessful(fullContent)

        if (isSms && !hasKnownDirection) {
            // SMS without direction indicators (e.g. balance only) cannot be processed as transactions
            return NotificationParseResult.Dropped(
                reason = DropReason.UNKNOWN_DIRECTION,
                sourcePackage = sourcePackage
            )
        }

        val isCredit = when {
            isReversalCredit -> true
            hasDebitAmount -> false
            hasCreditAmount -> true
            RegexPatterns.isDebit(fullContent) -> false
            RegexPatterns.isCredit(fullContent) -> true
            RegexPatterns.isPaymentSuccessful(fullContent) -> false
            else -> false
        }
        val isDebit = !isCredit
        val kind = if (isCredit) "CREDIT" else "DEBIT"

        // 8. Funding account inference
        val inferred = inferAccountWithFlag(fullContent, configuredAccounts, defaultAccount, accountDigits)
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
        val biller = RegexPatterns.extractBiller(title, text)
        val rawMerchant = biller
            ?: (if (title.isNotBlank()) RegexPatterns.extractMerchant(title) else null)
            ?: RegexPatterns.extractMerchant(text)
            ?: RegexPatterns.extractMerchant(fullContent)
            ?: "Unknown Payee"
        val merchant = com.financeautomation.core.utils.InputPolicy.cleanName(rawMerchant)
            .take(com.financeautomation.core.utils.InputPolicy.MAX_MERCHANT_NAME_LENGTH)
            .ifBlank { "Unknown Payee" }

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
            category = if (isReversalCredit) CategoryConstants.CATEGORY_REFUND else CategoryConstants.CATEGORY_OTHER,
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

        val flags = buildList {
            if (isNewAccount) add("NEW_ACCOUNT")
            if (isReversalCredit) add("REFUND_REVERSAL")
        }.joinToString(",")
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
