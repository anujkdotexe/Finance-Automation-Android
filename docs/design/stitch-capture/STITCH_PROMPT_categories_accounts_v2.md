# Stitch prompt: Categories and Accounts, version 2 (drag to reorder, visible edit)

Use in the Stitch project "Finance Automation Android Redesign". Select these four screens as the reference and ask for VARIANTS (new screens, the old ones stay untouched), one variant each:
- Categories (id 994c6ded0b4f4499b3192e645465e860)
- Categories - Dark (id 7582220aed6f4fe18312d2c97807be43)
- Accounts (id 4eae72a5a62b433f87b8bca25ad08790)
- Accounts - Dark (id 1112c06a30694e5dbe5ba89634445b6c)

Prompt:

Create version 2 of these four mobile screens (Categories light, Categories dark, Accounts light, Accounts dark). Keep the existing look exactly: same teal palette, Inter and JetBrains Mono fonts, white rounded row cards on the pale teal background, colour dot, name, living-burn switch, Add action in the top bar. Add ONLY these things: (1) a drag handle icon at the far left of every row, drawn as three short horizontal lines stacked (the sandwich or hamburger icon, also used to hint move up and down), shown on the row that is being dragged in a lifted state with a soft shadow while the other rows are slightly shifted; (2) a visible three-dot overflow button on every row opening a small menu with Rename, Colour and Archive (show one row with the menu open); (3) locked rows (Savings, Transfer for categories; Cash and Other for accounts) keep the handle but show a small lock icon and no overflow menu, with the caption "Built in, cannot be renamed or archived"; (4) a short hint line under the title: "Hold and drag to reorder"; (5) an empty-state example below the list: "Add your first category" or "Add your first account" with a filled Add button. On the Accounts screens each row also shows the optional last four digits in the monospace label and a "Default" pill on the default account. Mark the screen title text with a small "v2" tag. Do not change any other styling.

When the owner exports the v2 screens, they go to docs/design/stitch-categories-v2/ and the builder uses them for P2-B (and P2-D, which reuses the account list).
