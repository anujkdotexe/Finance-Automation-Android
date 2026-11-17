package com.financeautomation.presentation.viewmodel

import com.financeautomation.R

/**
 * Presentation helper providing the standard user notice when a Google Drive workbook
 * is replaced because it was missing or trashed.
 */
object WorkbookReplacedNotice {

    /**
     * Builds the [UiText] informing the user that their Finance workbook was missing,
     * so a new one was created, and that items such as repayment history and debt links
     * could not be restored automatically.
     */
    fun text(): UiText = UiText.res(
        R.string.workbook_replaced_notice_partial,
        UiText.res(R.string.workbook_replaced_lost_items)
    )

    /**
     * Checks whether [uiText] represents the workbook replaced notice.
     */
    fun isReplacedNotice(uiText: UiText?): Boolean =
        uiText?.resId == R.string.workbook_replaced_notice_partial
}
