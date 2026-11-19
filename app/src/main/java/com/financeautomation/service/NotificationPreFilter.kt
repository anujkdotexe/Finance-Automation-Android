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

    fun isAllowedSender(sourcePackage: String, title: String): Boolean {
        if (!PackageWhitelist.isSmsPackage(sourcePackage)) return true
        return RegexPatterns.isBankSenderId(title)
    }

    fun shouldReadFullText(sourcePackage: String, title: String, textPrefix: String): Boolean {
        if (!isAllowedSender(sourcePackage, title)) return false
        if (!PackageWhitelist.isSmsPackage(sourcePackage)) return true
        val head = "$title. ${textPrefix.take(PREFIX_LENGTH)}"
        return RegexPatterns.hasFinancialKeywords(head)
    }
}
