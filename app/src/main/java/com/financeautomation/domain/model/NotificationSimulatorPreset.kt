package com.financeautomation.domain.model

import com.financeautomation.core.constants.PackageWhitelist

/**
 * Preset data structure for simulating incoming payment notifications in developer mode (X5).
 */
data class NotificationSimulatorPreset(
    val id: String,
    val name: String,
    val sourcePackage: String,
    val title: String,
    val text: String,
    val expectedAmount: Double?,
    val expectedMerchant: String?
)

object NotificationSimulatorPresets {
    val PRESETS = listOf(
        NotificationSimulatorPreset(
            id = "gpay",
            name = "Google Pay",
            sourcePackage = PackageWhitelist.PACKAGE_GPAY,
            title = "Payment to Acme Tea Stall",
            text = "Paid Rs 45.00 to Acme Tea Stall using Bank A/c ending 1234. UPI Ref: 987654321012",
            expectedAmount = 45.0,
            expectedMerchant = "Acme Tea Stall"
        ),
        NotificationSimulatorPreset(
            id = "bhim",
            name = "BHIM",
            sourcePackage = PackageWhitelist.PACKAGE_BHIM,
            title = "UPI Transaction Successful",
            text = "Sent Rs 60.00 to Fresh Bakery via UPI. Ref No: 887766554433",
            expectedAmount = 60.0,
            expectedMerchant = "Fresh Bakery"
        ),
        NotificationSimulatorPreset(
            id = "supermoney",
            name = "Supermoney",
            sourcePackage = PackageWhitelist.PACKAGE_SUPERMONEY,
            title = "Payment to City Books",
            text = "Paid Rs 150.00 to City Books Store using Supermoney UPI",
            expectedAmount = 150.0,
            expectedMerchant = "City Books"
        ),
        NotificationSimulatorPreset(
            id = "phonepe",
            name = "PhonePe",
            sourcePackage = PackageWhitelist.PACKAGE_PHONEPE,
            title = "Payment to Bean & Brew Cafe",
            text = "Debited INR 120.00 to Bean & Brew Cafe. Txn ID: T261113123456",
            expectedAmount = 120.0,
            expectedMerchant = "Bean & Brew Cafe"
        ),
        NotificationSimulatorPreset(
            id = "paytm",
            name = "Paytm",
            sourcePackage = PackageWhitelist.PACKAGE_PAYTM,
            title = "Paid to Quick Mart Grocery",
            text = "Paid Rs 85.00 to Quick Mart Grocery from Paytm Payments Bank A/c ending 5678",
            expectedAmount = 85.0,
            expectedMerchant = "Quick Mart Grocery"
        ),
        NotificationSimulatorPreset(
            id = "bank_sms",
            name = "Bank SMS debit",
            sourcePackage = PackageWhitelist.PACKAGE_GOOGLE_MESSAGES,
            title = "VK-HDFCBK",
            text = "Dear Customer, your A/c ending 4321 has been debited for INR 250.00 on 13-Nov-26 towards Corner Bakery. UPI Ref 112233445566.",
            expectedAmount = 250.0,
            expectedMerchant = "Corner Bakery"
        ),
        NotificationSimulatorPreset(
            id = "unreadable_amount",
            name = "Amount not found test",
            sourcePackage = PackageWhitelist.PACKAGE_GPAY,
            title = "Payment to Corner Store",
            text = "Payment completed successfully to Corner Store. Thank you for using UPI.",
            expectedAmount = null,
            expectedMerchant = "Corner Store"
        )
    )
}

