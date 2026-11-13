package com.financeautomation.service

import com.financeautomation.core.constants.PackageWhitelist
import com.financeautomation.core.utils.RegexPatterns

/**
 * First look at an incoming notification, using only the sender (title) and a short prefix of the text.
 * For SMS apps, messages from personal numbers and messages without any financial keyword are not read further,
 * so the full text of personal and non-financial SMS is never loaded. Payment apps are always read.
 * Pure Kotlin, nothing is logged or stored.
 */
object NotificationPreFilter {

    const val PREFIX_LENGTH: Int = 400

    fun shouldReadFullText(sourcePackage: String, title: String, textPrefix: String): Boolean {
        if (!PackageWhitelist.isSmsPackage(sourcePackage)) return true
        if (!RegexPatterns.isBankSenderId(title)) return false
        val head = "$title. ${textPrefix.take(PREFIX_LENGTH)}"
        return RegexPatterns.hasFinancialKeywords(head)
    }
}
