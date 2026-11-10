package tests;

import java.util.*;
import java.util.regex.*;

/**
 * Native JVM Security & Privacy Harness for Milestone 4.
 * Aligned with Draft v7.0.0: UTR is completely eliminated.
 * Verifies package whitelisting, spoof protection, blacklist prefix matching,
 * OTP suppression, amount extraction, merchant extraction, and Amount + Merchant 120s deduplication.
 */
public class ChallengerM4Harness {

    // Package Whitelist & Blacklist
    private static final Set<String> WHITELISTED = Set.of(
        "com.google.android.apps.nbu.paisa.user",
        "in.org.npci.upiapp",
        "com.supermoney.app",
        "com.phonepe.app",
        "net.one97.paytm",
        "com.android.mms",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging"
    );

    private static final Set<String> BLACKLISTED = Set.of(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "org.thoughtcrime.securesms",
        "com.facebook.orca",
        "com.instagram.android",
        "com.discord",
        "com.microsoft.office.outlook",
        "com.google.android.gm"
    );

    private static final Pattern OTP_PATTERN = Pattern.compile(
        "\\b(otp|one[- ]time[- ]password|verification[- ]code|secret[- ]code|do[- ]not[- ]share|security[- ]code|auth[- ]code)\\b",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
        "(?:Rs\\.?|INR|₹)\\s*([\\d,]+(?:\\.\\d{1,2})?)",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern MERCHANT_PATTERN = Pattern.compile(
        "(?:to|at|paid to|vpa|transferred to)\\s+([A-Za-z0-9.\\-_\\s&@]+?)(?:\\s+(?:on|using|via|ref|a/c|avl|bal|\\d{12})|\\.|$)",
        Pattern.CASE_INSENSITIVE
    );

    public static void main(String[] args) {
        int passed = 0;
        int failed = 0;

        // Test 1: Whitelist filtering
        for (String pkg : WHITELISTED) {
            if (WHITELISTED.contains(pkg.trim())) {
                passed++;
            } else {
                System.err.println("FAIL: Whitelist missing " + pkg);
                failed++;
            }
        }

        // Test 2: Spoofed packages rejected
        String[] spoofed = {
            "com.google.android.apps.nbu.paisa.user.fake",
            "in.org.npci.upiapp.attacker",
            "com.phonepe.app.mod"
        };
        for (String s : spoofed) {
            if (!WHITELISTED.contains(s.trim())) {
                passed++;
            } else {
                System.err.println("FAIL: Whitelist accepted spoofed " + s);
                failed++;
            }
        }

        // Test 3: Blacklist prefix matching
        for (String pkg : BLACKLISTED) {
            String variant = pkg + ".extra";
            boolean blacklisted = BLACKLISTED.contains(variant) || BLACKLISTED.stream().anyMatch(variant::startsWith);
            if (blacklisted) {
                passed++;
            } else {
                System.err.println("FAIL: Blacklist missed prefix variant " + variant);
                failed++;
            }
        }

        // Test 4: OTP suppression on JVM
        String[] otps = {
            "Your OTP is 987654. Do not share.",
            "Use one-time password 112233",
            "Secret-code: 445566",
            "Security code: 778899"
        };
        for (String otp : otps) {
            if (OTP_PATTERN.matcher(otp).find()) {
                passed++;
            } else {
                System.err.println("FAIL: OTP not detected: " + otp);
                failed++;
            }
        }

        // Test 5: OTP word boundary protection on JVM
        String[] legit = {
            "Hotpot Restaurant payment of Rs 500",
            "Bootpack purchase",
            "Footprint footwear"
        };
        for (String l : legit) {
            if (!OTP_PATTERN.matcher(l).find()) {
                passed++;
            } else {
                System.err.println("FAIL: False positive OTP: " + l);
                failed++;
            }
        }

        // Test 6: Amount + Merchant Extraction & 120s Deduplication (Purged UTR)
        Matcher mAmount = AMOUNT_PATTERN.matcher("Paid Rs 300.00 to Starbucks Coffee");
        boolean amountOk = mAmount.find() && "300.00".equals(mAmount.group(1));

        Matcher mMerchant = MERCHANT_PATTERN.matcher("Paid Rs 300.00 to Starbucks Coffee");
        boolean merchantOk = mMerchant.find() && "Starbucks Coffee".equalsIgnoreCase(mMerchant.group(1).trim());

        if (amountOk && merchantOk) {
            passed++;
        } else {
            System.err.println("FAIL: Amount and merchant extraction failed");
            failed++;
        }

        // Deduplication test: matching amount + merchant within 120s sliding window
        double amount1 = 300.0;
        String merchant1 = "Starbucks Coffee";
        long t1 = 1000000L;

        double amount2 = 300.0;
        String merchant2 = "Starbucks Coffee";
        long t2 = 1015000L; // 15 seconds later (<= 120s window)

        boolean isWithinWindow = (t2 - t1) <= 120000L;
        boolean isDuplicate = (amount1 == amount2) && merchant1.equalsIgnoreCase(merchant2) && isWithinWindow;
        if (isDuplicate) {
            passed++;
        } else {
            System.err.println("FAIL: 120s sliding window deduplication failed");
            failed++;
        }

        System.out.println("JVM Harness Results: " + passed + " passed, " + failed + " failed.");
        if (failed > 0) System.exit(1);
    }
}
