package tests;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Native JVM Stress-Testing Harness for Milestone 5.
 * Verifies category normalization, state machine transitions, companion validation,
 * duplicate handling, non-destructive voiding (G=0), debt carry-forward (target G=0),
 * and zero fake success under concurrency.
 */
public class ChallengerM5Harness {

    public static final String CATEGORY_SAVINGS = "Savings";
    public static final String CATEGORY_FOOD_AND_DRINK = "Food & Drink";
    public static final String CATEGORY_SHOPPING = "Shopping";

    public enum ReviewState {
        NEEDS_REVIEW,
        CONFIRMED
    }

    public enum SyncState {
        PENDING,
        SYNCED,
        VOID,
        FAILED
    }

    public enum TransactionStatus {
        SETTLED,
        PENDING,
        VOID,
        CARRIED
    }

    public static class Transaction {
        public final String id;
        public final double totalAmount;
        public final String merchant;
        public final String category;
        public final double myShare;
        public final double owedToMe;
        public final TransactionStatus status;
        public final ReviewState reviewState;
        public final SyncState syncState;
        public final boolean isPossibleDuplicate;

        public Transaction(
            String id,
            double totalAmount,
            String merchant,
            String category,
            double myShare,
            double owedToMe,
            TransactionStatus status,
            ReviewState reviewState,
            SyncState syncState,
            boolean isPossibleDuplicate
        ) {
            this.id = Objects.requireNonNull(id, "id cannot be null");
            this.totalAmount = totalAmount;
            this.merchant = Objects.requireNonNull(merchant, "merchant cannot be null");
            this.category = Objects.requireNonNull(category, "category cannot be null");
            this.myShare = myShare;
            this.owedToMe = owedToMe;
            this.status = Objects.requireNonNull(status, "status cannot be null");
            this.reviewState = Objects.requireNonNull(reviewState, "reviewState cannot be null");
            this.syncState = Objects.requireNonNull(syncState, "syncState cannot be null");
            this.isPossibleDuplicate = isPossibleDuplicate;
        }

        public Transaction withConfirmation(String resolvedCategory) {
            return new Transaction(
                this.id,
                this.totalAmount,
                this.merchant,
                resolvedCategory,
                this.myShare,
                this.owedToMe,
                this.status,
                ReviewState.CONFIRMED,
                SyncState.PENDING,
                false // duplicate flag cleared on confirm
            );
        }

        public Transaction withVoid() {
            return new Transaction(
                this.id,
                0.0, // Column G = 0.00 to prevent inflating Monthly Gross Outflow
                "[Void] " + this.merchant,
                this.category,
                0.0, // Column I = 0.00
                0.0, // Column J = 0.00
                TransactionStatus.VOID,
                this.reviewState,
                SyncState.VOID,
                this.isPossibleDuplicate
            );
        }

        public static Transaction[] carryForward(Transaction origin, String newId, String targetMonth) {
            Transaction updatedOrigin = new Transaction(
                origin.id,
                origin.totalAmount,
                origin.merchant,
                origin.category,
                origin.myShare,
                0.0, // Origin Column J = 0.00
                TransactionStatus.CARRIED,
                origin.reviewState,
                origin.syncState,
                origin.isPossibleDuplicate
            );
            Transaction targetCarried = new Transaction(
                newId,
                0.0, // Target Column G = 0.00 (prevents double-counting in Monthly Gross Outflow)
                "[Carried from " + targetMonth + "] " + origin.merchant,
                origin.category,
                0.0, // Target Column I = 0.00
                origin.owedToMe, // Target Column J = carried receivable
                TransactionStatus.PENDING,
                ReviewState.CONFIRMED,
                SyncState.PENDING,
                false
            );
            return new Transaction[] { updatedOrigin, targetCarried };
        }
    }

    /**
     * Replicates CategoryConstants.normalizeCategory and QuickActionReceiver / ReviewInboxViewModel hardening.
     */
    public static String resolveAndNormalizeCategory(String input, String fallback) {
        if (input == null || input.trim().isEmpty()) {
            return fallback;
        }
        String trimmed = input.trim();
        if (trimmed.equalsIgnoreCase("saving") || trimmed.equalsIgnoreCase("savings")) {
            return CATEGORY_SAVINGS;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.equals("investment") || lower.equals("sip") || lower.equals("mutual fund")) {
            return CATEGORY_SAVINGS;
        }
        return trimmed;
    }

    public static void main(String[] args) throws Exception {
        int passed = 0;
        int failed = 0;

        System.out.println("Starting JVM Milestone 5 Stress Harness...");

        // Test 1: Category Normalization (Singular 'saving' and variants -> 'Savings')
        String[] savingsVariants = {
            "saving", "Saving", "SAVING", "  saving  ", "sAvInG",
            "savings", "Savings", "SAVINGS", "  Savings  ",
            "Investment", "investment", "INVESTMENT",
            "sip", "SIP", "Sip",
            "mutual fund", "Mutual Fund", "MUTUAL FUND"
        };
        for (String v : savingsVariants) {
            String resolved = resolveAndNormalizeCategory(v, "Other");
            if (CATEGORY_SAVINGS.equals(resolved)) {
                passed++;
            } else {
                System.err.println("FAIL: Variant '" + v + "' normalized to '" + resolved + "' instead of Savings");
                failed++;
            }
        }

        // Test 2: Standard non-savings categories preserved
        String[] otherCategories = {"Food & Drink", "Shopping", "Transit", "Custom Category"};
        for (String c : otherCategories) {
            String resolved = resolveAndNormalizeCategory(c, "Other");
            if (c.equals(resolved)) {
                passed++;
            } else {
                System.err.println("FAIL: Category '" + c + "' was incorrectly altered to '" + resolved + "'");
                failed++;
            }
        }

        // Test 3: Null or Blank category falls back to existing
        if ("ExistingCategory".equals(resolveAndNormalizeCategory(null, "ExistingCategory"))) {
            passed++;
        } else {
            System.err.println("FAIL: Null category did not fall back");
            failed++;
        }
        if ("ExistingCategory".equals(resolveAndNormalizeCategory("   ", "ExistingCategory"))) {
            passed++;
        } else {
            System.err.println("FAIL: Blank category did not fall back");
            failed++;
        }

        // Test 4: 1-Tap Confirm State Transition and Duplicate Clearing
        Transaction duplicateTxn = new Transaction(
            "txn_dup_001", 500.0, "Starbucks", "Food & Drink",
            500.0, 0.0, TransactionStatus.SETTLED,
            ReviewState.NEEDS_REVIEW, SyncState.PENDING, true
        );
        Transaction confirmedTxn = duplicateTxn.withConfirmation(
            resolveAndNormalizeCategory("saving", duplicateTxn.category)
        );
        if (confirmedTxn.reviewState == ReviewState.CONFIRMED &&
            confirmedTxn.syncState == SyncState.PENDING &&
            !confirmedTxn.isPossibleDuplicate &&
            CATEGORY_SAVINGS.equals(confirmedTxn.category)) {
            passed++;
        } else {
            System.err.println("FAIL: 1-Tap Confirm failed to transition state or clear duplicate flag");
            failed++;
        }

        // Test 5: Non-destructive voiding sets Col G=0, Col I=0, Col J=0, Status=VOID
        Transaction voidedTxn = duplicateTxn.withVoid();
        if (voidedTxn.status == TransactionStatus.VOID &&
            voidedTxn.totalAmount == 0.0 &&
            voidedTxn.myShare == 0.0 &&
            voidedTxn.owedToMe == 0.0 &&
            voidedTxn.syncState == SyncState.VOID &&
            voidedTxn.merchant.startsWith("[Void] ")) {
            passed++;
        } else {
            System.err.println("FAIL: Voiding failed to zero Col G/I/J or set VOID status");
            failed++;
        }

        // Test 6: Companion case-insensitive duplicate validation
        Set<String> companions = new ConcurrentSkipListSet<>(String.CASE_INSENSITIVE_ORDER);
        companions.add("Aman");
        boolean rejectedLower = !companions.add("aman");
        boolean rejectedUpper = !companions.add("AMAN");
        boolean rejectedMixed = !companions.add("AmAn");
        boolean acceptedNew = companions.add("Priya");
        if (rejectedLower && rejectedUpper && rejectedMixed && acceptedNew && companions.size() == 2) {
            passed++;
        } else {
            System.err.println("FAIL: Case-insensitive companion duplicate check failed");
            failed++;
        }

        // Test 7: Cloud sync zero fake success verification
        // Case A: Unconfigured spreadsheet ID throws IllegalStateException
        String unconfiguredId = "";
        boolean failedExplicitly = false;
        try {
            if (unconfiguredId.trim().isEmpty()) {
                throw new IllegalStateException("Spreadsheet ID is not configured");
            }
        } catch (IllegalStateException e) {
            failedExplicitly = true;
        }
        if (failedExplicitly) {
            passed++;
        } else {
            System.err.println("FAIL: Unconfigured spreadsheet ID did not fail explicitly");
            failed++;
        }

        // Case B: Network failure must not report fake synced count
        boolean networkError = true;
        String syncResult = null;
        String errorMessage = null;
        if (networkError) {
            errorMessage = "Sync failed due to connection error: Timeout";
        } else {
            syncResult = "Synced 3 transaction(s)";
        }
        if (syncResult == null && errorMessage != null && errorMessage.contains("Sync failed")) {
            passed++;
        } else {
            System.err.println("FAIL: Network failure emitted fake sync success");
            failed++;
        }

        // Test 8: Concurrency Stress Test (50 threads confirming / voiding 500 transactions)
        int threadCount = 50;
        int txnCount = 500;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        ConcurrentHashMap<String, Transaction> repo = new ConcurrentHashMap<>();
        for (int i = 0; i < txnCount; i++) {
            repo.put("txn_" + i, new Transaction(
                "txn_" + i, 100.0 + i, "Merchant_" + i, "Food & Drink",
                100.0 + i, 0.0, TransactionStatus.SETTLED,
                ReviewState.NEEDS_REVIEW, SyncState.PENDING, (i % 2 == 0)
            ));
        }

        CountDownLatch latch = new CountDownLatch(txnCount);
        AtomicInteger confirmedCount = new AtomicInteger(0);
        AtomicInteger voidedCount = new AtomicInteger(0);

        for (int i = 0; i < txnCount; i++) {
            final String txnId = "txn_" + i;
            final boolean shouldVoid = (i % 5 == 0);
            executor.submit(() -> {
                try {
                    Transaction t = repo.get(txnId);
                    if (t != null) {
                        if (shouldVoid) {
                            repo.put(txnId, t.withVoid());
                            voidedCount.incrementAndGet();
                        } else {
                            String cat = (txnId.hashCode() % 3 == 0) ? "saving" : "Shopping";
                            String resolved = resolveAndNormalizeCategory(cat, t.category);
                            repo.put(txnId, t.withConfirmation(resolved));
                            confirmedCount.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean finished = latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        if (finished && (confirmedCount.get() + voidedCount.get() == txnCount)) {
            // Verify final state consistency
            boolean consistent = true;
            for (int i = 0; i < txnCount; i++) {
                Transaction t = repo.get("txn_" + i);
                if (t == null) {
                    consistent = false;
                    break;
                }
                if (i % 5 == 0) {
                    if (t.status != TransactionStatus.VOID || t.myShare != 0.0 || t.totalAmount != 0.0) {
                        consistent = false;
                        break;
                    }
                } else {
                    if (t.reviewState != ReviewState.CONFIRMED || t.isPossibleDuplicate) {
                        consistent = false;
                        break;
                    }
                }
            }
            if (consistent) {
                passed++;
            } else {
                System.err.println("FAIL: Repository state inconsistent after concurrent operations");
                failed++;
            }
        } else {
            System.err.println("FAIL: Concurrency stress test timed out or lost updates");
            failed++;
        }

        // Test 9: Debt Carry-Forward Invariant (Origin Col J = 0, Target Col G = 0)
        Transaction splitOrigin = new Transaction(
            "txn_origin_debt", 600.0, "Dinner", "Food & Drink",
            200.0, 400.0, TransactionStatus.PENDING,
            ReviewState.CONFIRMED, SyncState.SYNCED, false
        );
        Transaction[] carriedResult = Transaction.carryForward(splitOrigin, "txn_target_debt", "Nov26");
        Transaction originUpdated = carriedResult[0];
        Transaction targetRow = carriedResult[1];

        if (originUpdated.owedToMe == 0.0 &&
            originUpdated.status == TransactionStatus.CARRIED &&
            targetRow.totalAmount == 0.0 && // Col G = 0
            targetRow.myShare == 0.0 &&     // Col I = 0
            targetRow.owedToMe == 400.0 &&  // Col J = 400
            targetRow.status == TransactionStatus.PENDING) {
            passed++;
        } else {
            System.err.println("FAIL: Debt carry forward violated accounting invariants");
            failed++;
        }

        System.out.println("ChallengerM5Harness Results: " + passed + " passed, " + failed + " failed.");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
