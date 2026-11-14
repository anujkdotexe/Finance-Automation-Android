package com.financeautomation.domain.usecase

import com.financeautomation.domain.model.Transaction

/** A carried forward copy is a debt moved into a later month: its text starts with "[Carried from". */
object CarriedCopies {
    private const val PREFIX = "[Carried from"

    fun isCopy(t: Transaction): Boolean = t.merchant.trim().startsWith(PREFIX, ignoreCase = true)
}
