package com.financeautomation.core.utils

import java.text.Normalizer

/**
 * Shared input policy and validation rules across the application (P1-5).
 *
 * Enforces edge validation and cleaning for every input surface:
 * - Names: categories, accounts, friends/companions, trips, subscriptions, budgets, merchants/payees
 * - Notes & descriptions: transaction notes, descriptions, bug reports
 * - Search queries: search and filter bars
 * - Digits: account last 4 digits
 * - Spreadsheet IDs: typed or pasted Google Sheets IDs or full URLs
 */
object InputPolicy {

    const val MAX_NAME_LENGTH = 40
    const val MAX_MERCHANT_NAME_LENGTH = 80
    const val MAX_NOTE_LENGTH = 120
    const val MAX_SEARCH_LENGTH = 100
    const val MAX_DIGITS4_LENGTH = 4

    /**
     * Reserved names that cannot be created or renamed by users to prevent collision
     * with system categories, accounts, ledger tabs, and status keywords.
     */
    val RESERVED_NAMES: Set<String> = setOf(
        "Cash",
        "Other",
        "Savings",
        "Saving",
        "Transfer",
        "Personal",
        "Dashboard",
        "Config"
    )

    /**
     * Characters that are strictly forbidden in names because they serve as delimiters
     * in Config sync serialization, companion lists, or ledger encoding.
     */
    private val FORBIDDEN_NAME_DELIMITERS = setOf('|', ':', ',')

    /**
     * Regex matching Unicode bidirectional override formatting characters:
     * U+202A..U+202E (Embedding/Override), U+2066..U+2069 (Isolate), U+200E (LRM), U+200F (RLM).
     */
    private val BIDI_CONTROL_REGEX = Regex("[\u202A-\u202E\u2066-\u2069\u200E\u200F]")

    /**
     * Regex matching standard ASCII control characters (0x00..0x1F and 0x7F).
     */
    private val CONTROL_CHARS_REGEX = Regex("[\\x00-\\x1F\\x7F]")

    /**
     * Regex matching multiple consecutive whitespace characters.
     */
    private val MULTI_WHITESPACE_REGEX = Regex("\\s+")

    /**
     * Regex for Google Spreadsheet ID: 5 to 100 characters of letters, digits, hyphen, or underscore.
     */
    private val SPREADSHEET_ID_REGEX = Regex("^[a-zA-Z0-9-_]{5,100}$")

    /**
     * Regex to extract spreadsheet ID from a pasted Google Sheets URL:
     * e.g. https://docs.google.com/spreadsheets/d/1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms/edit...
     */
    private val SPREADSHEET_URL_REGEX = Regex("/spreadsheets/d/([a-zA-Z0-9-_]+)")

    sealed class ValidationResult {
        data class Valid(val cleanValue: String) : ValidationResult()
        data class Error(val reason: InputError, val limit: Int? = null) : ValidationResult()

        val isValid: Boolean get() = this is Valid
        val valueOrNull: String? get() = (this as? Valid)?.cleanValue
    }

    enum class InputError {
        BLANK,
        TOO_LONG,
        INVALID_CHARS,
        RESERVED_NAME,
        DUPLICATE_NAME,
        INVALID_SPREADSHEET_ID
    }

    /**
     * Cleans a name string:
     * 1. Strips bidirectional override characters.
     * 2. Strips ASCII control characters.
     * 3. Trims leading and trailing whitespace.
     * 4. Collapses inner consecutive whitespace to a single space.
     * 5. Normalizes to Unicode NFC.
     */
    fun cleanName(raw: String): String {
        if (raw.isEmpty()) return ""
        val withoutBidi = BIDI_CONTROL_REGEX.replace(raw, "")
        val withoutControl = CONTROL_CHARS_REGEX.replace(withoutBidi, "")
        val collapsed = MULTI_WHITESPACE_REGEX.replace(withoutControl.trim(), " ")
        return Normalizer.normalize(collapsed, Normalizer.Form.NFC)
    }

    /**
     * Validates a name (category, account, companion, trip, subscription, budget).
     *
     * @param raw The raw input string typed or pasted by the user.
     * @param existingNames Existing active names in the same entity domain to check for duplicates.
     * @param maxLength Maximum allowed length (defaults to 40).
     * @param allowReserved If true, skips the reserved names check (useful for system lookups).
     */
    fun validateName(
        raw: String,
        existingNames: Collection<String> = emptyList(),
        maxLength: Int = MAX_NAME_LENGTH,
        allowReserved: Boolean = false
    ): ValidationResult {
        val clean = cleanName(raw)
        if (clean.isBlank()) {
            return ValidationResult.Error(InputError.BLANK)
        }
        if (clean.length > maxLength) {
            return ValidationResult.Error(InputError.TOO_LONG, maxLength)
        }
        if (clean.any { it in FORBIDDEN_NAME_DELIMITERS }) {
            return ValidationResult.Error(InputError.INVALID_CHARS)
        }
        if (!allowReserved && RESERVED_NAMES.any { it.equals(clean, ignoreCase = true) }) {
            return ValidationResult.Error(InputError.RESERVED_NAME)
        }
        val normalizedClean = Normalizer.normalize(clean, Normalizer.Form.NFC)
        val duplicateFound = existingNames.any { existing ->
            Normalizer.normalize(existing.trim(), Normalizer.Form.NFC).equals(normalizedClean, ignoreCase = true)
        }
        if (duplicateFound) {
            return ValidationResult.Error(InputError.DUPLICATE_NAME)
        }
        return ValidationResult.Valid(clean)
    }

    /**
     * Cleans a description or note string:
     * - Strips bidi overrides and non-printable control characters (preserving newlines and tabs).
     * - Trims outer whitespace.
     * - Normalizes to Unicode NFC.
     */
    fun cleanNote(raw: String): String {
        if (raw.isEmpty()) return ""
        val withoutBidi = BIDI_CONTROL_REGEX.replace(raw, "")
        // Strip control characters except newline (\n, 0x0A) and tab (\t, 0x09)
        val filtered = withoutBidi.filter { c ->
            c == '\n' || c == '\t' || (c.code >= 0x20 && c.code != 0x7F)
        }
        return Normalizer.normalize(filtered.trim(), Normalizer.Form.NFC)
    }

    /**
     * Validates a note or description string.
     */
    fun validateNote(raw: String, maxLength: Int = MAX_NOTE_LENGTH): ValidationResult {
        val clean = cleanNote(raw)
        if (clean.length > maxLength) {
            return ValidationResult.Error(InputError.TOO_LONG, maxLength)
        }
        return ValidationResult.Valid(clean)
    }

    /**
     * Cleans search query text:
     * - Strips bidi overrides and control characters.
     * - Caps to MAX_SEARCH_LENGTH.
     */
    fun cleanSearch(raw: String, maxLength: Int = MAX_SEARCH_LENGTH): String {
        if (raw.isEmpty()) return ""
        val withoutBidi = BIDI_CONTROL_REGEX.replace(raw, "")
        val withoutControl = CONTROL_CHARS_REGEX.replace(withoutBidi, "")
        val collapsed = MULTI_WHITESPACE_REGEX.replace(withoutControl.trim(), " ")
        return Normalizer.normalize(collapsed, Normalizer.Form.NFC).take(maxLength)
    }

    /**
     * Cleans a 4-digit bank account hint string:
     * - Retains only ASCII digits 0-9.
     * - Caps to 4 characters.
     */
    fun cleanDigits4(raw: String): String {
        return raw.filter { it in '0'..'9' }.take(MAX_DIGITS4_LENGTH)
    }

    /**
     * Extracts a valid Google Spreadsheet ID from a typed ID or a pasted Google Sheets URL.
     * Returns the cleaned ID string if valid, or null if invalid.
     */
    fun extractSpreadsheetId(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        // Check if full URL was pasted
        val urlMatch = SPREADSHEET_URL_REGEX.find(trimmed)
        if (urlMatch != null) {
            val candidate = urlMatch.groupValues[1]
            if (SPREADSHEET_ID_REGEX.matches(candidate)) return candidate
        }

        // Direct ID check
        if (SPREADSHEET_ID_REGEX.matches(trimmed)) {
            return trimmed
        }

        return null
    }

    /**
     * Validates a typed or pasted spreadsheet ID.
     */
    fun validateSpreadsheetId(raw: String): ValidationResult {
        val extracted = extractSpreadsheetId(raw)
        return if (extracted != null) {
            ValidationResult.Valid(extracted)
        } else {
            ValidationResult.Error(InputError.INVALID_SPREADSHEET_ID)
        }
    }
}
