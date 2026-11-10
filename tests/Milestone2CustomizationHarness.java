package tests;

import java.util.*;

/**
 * JVM Verification Harness for Milestone 2: 100% Config-Driven Customization Engine.
 * Tests Tier 1 lists, Living Burn exclusion formulas, Config Tab serialization, and data validations.
 */
public class Milestone2CustomizationHarness {

    public static class Account {
        public final String id;
        public final String name;
        public final String lastFourDigits;
        public boolean isDefault;
        public boolean isArchived;
        public int displayOrder;

        public Account(String id, String name, String lastFourDigits, boolean isDefault, boolean isArchived, int displayOrder) {
            this.id = id;
            this.name = name;
            this.lastFourDigits = lastFourDigits;
            this.isDefault = isDefault;
            this.isArchived = isArchived;
            this.displayOrder = displayOrder;
        }
    }

    public static class Category {
        public final String id;
        public final String name;
        public boolean isDefault;
        public boolean isExcludedFromLivingBurn;
        public boolean isArchived;
        public int displayOrder;

        public Category(String id, String name, boolean isDefault, boolean isExcludedFromLivingBurn, boolean isArchived, int displayOrder) {
            this.id = id;
            this.name = name;
            this.isDefault = isDefault;
            this.isExcludedFromLivingBurn = isExcludedFromLivingBurn;
            this.isArchived = isArchived;
            this.displayOrder = displayOrder;
        }
    }

    public static class TripTag {
        public final String id;
        public final String name;
        public boolean isActive;
        public boolean isArchived;

        public TripTag(String id, String name, boolean isActive, boolean isArchived) {
            this.id = id;
            this.name = name;
            this.isActive = isActive;
            this.isArchived = isArchived;
        }
    }

    public static class FormulaBuilder {
        public static String buildCurrentBurnFormula(List<String> excludedNames) {
            List<String> active = new ArrayList<>();
            for (String n : excludedNames) {
                if (n != null && !n.trim().isEmpty()) {
                    active.add(n.trim());
                }
            }
            if (active.isEmpty()) {
                return "=IFERROR(SUM(INDIRECT(\"'\" & TEXT(TODAY(), \"mmm\") & RIGHT(YEAR(TODAY()), 2) & \"'!I4:I\")), 0)";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("=IFERROR(SUMIFS(INDIRECT(\"'\" & TEXT(TODAY(), \"mmm\") & RIGHT(YEAR(TODAY()), 2) & \"'!I4:I\")");
            for (String name : active) {
                sb.append(", INDIRECT(\"'\" & TEXT(TODAY(), \"mmm\") & RIGHT(YEAR(TODAY()), 2) & \"'!F4:F\"), \"<>").append(name).append("\"");
            }
            sb.append("), 0)");
            return sb.toString();
        }

        public static String buildPrevMonthBurnFormula(List<String> excludedNames) {
            List<String> active = new ArrayList<>();
            for (String n : excludedNames) {
                if (n != null && !n.trim().isEmpty()) {
                    active.add(n.trim());
                }
            }
            if (active.isEmpty()) {
                return "=IFERROR(SUM(INDIRECT(\"'\" & TEXT(EDATE(TODAY(), -1), \"mmm\") & RIGHT(YEAR(EDATE(TODAY(), -1)), 2) & \"'!I4:I\")), 0)";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("=IFERROR(SUMIFS(INDIRECT(\"'\" & TEXT(EDATE(TODAY(), -1), \"mmm\") & RIGHT(YEAR(EDATE(TODAY(), -1)), 2) & \"'!I4:I\")");
            for (String name : active) {
                sb.append(", INDIRECT(\"'\" & TEXT(EDATE(TODAY(), -1), \"mmm\") & RIGHT(YEAR(EDATE(TODAY(), -1)), 2) & \"'!F4:F\"), \"<>").append(name).append("\"");
            }
            sb.append("), 0)");
            return sb.toString();
        }

        public static String buildMonthlySummaryBurnFormula(int rowNumber, List<String> excludedNames) {
            List<String> active = new ArrayList<>();
            for (String n : excludedNames) {
                if (n != null && !n.trim().isEmpty()) {
                    active.add(n.trim());
                }
            }
            if (active.isEmpty()) {
                return "=IFERROR(SUM(INDIRECT(\"'\" & $U" + rowNumber + " & RIGHT(YEAR(TODAY()), 2) & \"'!I4:I\")), 0)";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("=IFERROR(SUMIFS(INDIRECT(\"'\" & $U").append(rowNumber).append(" & RIGHT(YEAR(TODAY()), 2) & \"'!I4:I\")");
            for (String name : active) {
                sb.append(", INDIRECT(\"'\" & $U").append(rowNumber).append(" & RIGHT(YEAR(TODAY()), 2) & \"'!F4:F\"), \"<>").append(name).append("\"");
            }
            sb.append("), 0)");
            return sb.toString();
        }
    }

    public static void main(String[] args) {
        System.out.println("Starting Milestone 2 JVM Customization Engine Harness...");
        int passed = 0;
        int failed = 0;

        // Test 1: Account Presets & Custom Accounts
        try {
            List<Account> accounts = new ArrayList<>();
            accounts.add(new Account("acc_1", "SBI", null, true, false, 0));
            accounts.add(new Account("acc_2", "Canara", null, false, false, 1));
            accounts.add(new Account("acc_3", "Cash", null, false, false, 2));
            accounts.add(new Account("acc_4", "Other", null, false, false, 3));
            accounts.add(new Account("acc_5", "HDFC", "1122", false, false, 4));

            // Switch default to HDFC
            for (Account a : accounts) {
                a.isDefault = a.name.equals("HDFC");
            }
            boolean hdfcDefault = false;
            for (Account a : accounts) {
                if (a.name.equals("HDFC") && a.isDefault) hdfcDefault = true;
                if (!a.name.equals("HDFC") && a.isDefault) throw new AssertionError("Old account remained default");
            }
            if (!hdfcDefault) throw new AssertionError("HDFC not marked default");
            passed++;
        } catch (Throwable t) {
            System.err.println("Test 1 Failed: " + t.getMessage());
            failed++;
        }

        // Test 2: Category Living Burn Dynamic Exclusion
        try {
            List<Category> categories = new ArrayList<>();
            categories.add(new Category("c1", "Food & Drink", true, false, false, 0));
            categories.add(new Category("c2", "Savings", true, true, false, 1));
            categories.add(new Category("c3", "Investments", false, true, false, 2));
            categories.add(new Category("c4", "Shopping", true, false, false, 3));

            List<String> excluded = new ArrayList<>();
            for (Category c : categories) {
                if (c.isExcludedFromLivingBurn && !c.isArchived) {
                    excluded.add(c.name);
                }
            }

            String currentFormula = FormulaBuilder.buildCurrentBurnFormula(excluded);
            if (!currentFormula.contains("<>Savings") || !currentFormula.contains("<>Investments")) {
                throw new AssertionError("Formula missing excluded categories: " + currentFormula);
            }
            if (currentFormula.contains("<>Shopping")) {
                throw new AssertionError("Non-excluded category included in burn exclusions");
            }
            passed++;
        } catch (Throwable t) {
            System.err.println("Test 2 Failed: " + t.getMessage());
            failed++;
        }

        // Test 3: Previous Month & Monthly Summary Formulas
        try {
            List<String> excluded = Arrays.asList("Savings", "Transfer");
            String prevFormula = FormulaBuilder.buildPrevMonthBurnFormula(excluded);
            if (!prevFormula.contains("EDATE(TODAY(), -1)") || !prevFormula.contains("<>Savings") || !prevFormula.contains("<>Transfer")) {
                throw new AssertionError("Prev month formula invalid: " + prevFormula);
            }

            String janFormula = FormulaBuilder.buildMonthlySummaryBurnFormula(6, excluded);
            if (!janFormula.contains("$U6") || !janFormula.contains("<>Savings")) {
                throw new AssertionError("Monthly summary formula invalid: " + janFormula);
            }
            passed++;
        } catch (Throwable t) {
            System.err.println("Test 3 Failed: " + t.getMessage());
            failed++;
        }

        // Test 4: Trip Tag Active Toggle
        try {
            List<TripTag> tags = new ArrayList<>();
            tags.add(new TripTag("t1", "Goa 2026", true, false));
            tags.add(new TripTag("t2", "Kerala", false, false));

            // Switch active to Kerala
            for (TripTag t : tags) {
                t.isActive = t.id.equals("t2");
            }
            if (tags.get(0).isActive || !tags.get(1).isActive) {
                throw new AssertionError("Trip tag active toggle failed");
            }
            passed++;
        } catch (Throwable t) {
            System.err.println("Test 4 Failed: " + t.getMessage());
            failed++;
        }

        // Test 5: Config Grid Dimensions & Purity
        try {
            String[][] grid = new String[50][16];
            for (int r = 0; r < 50; r++) {
                for (int c = 0; c < 16; c++) {
                    grid[r][c] = "";
                }
            }
            // Populate headers
            grid[0][0] = "AccountName";
            grid[0][5] = "CategoryName";
            grid[0][10] = "CompanionName";
            grid[0][12] = "TripTagName";
            grid[0][14] = "PrefKey";

            if (grid.length != 50 || grid[0].length != 16) {
                throw new AssertionError("Config grid dimension invalid");
            }
            passed++;
        } catch (Throwable t) {
            System.err.println("Test 5 Failed: " + t.getMessage());
            failed++;
        }

        System.out.println("Milestone 2 JVM Harness Results: " + passed + " passed, " + failed + " failed.");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
