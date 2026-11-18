# Stitch v2: Categories and Accounts (exported by the reviewer, 2026-11-18)

Stitch project "Finance Automation Android Redesign". New screens, the original Categories and Accounts screens are untouched.

| Screenshot (this folder) | Stitch title | Screen id | HTML file resource (download with Stitch download_file) |
|---|---|---|---|
| categories-v2-light.png | Categories v2 | 6e1d5f360a0945a384f7d8b81d7fa985 | projects/14304074461979593421/files/01314601a8d54000ab49d1fc55d10274 |
| categories-v2-dark.png | Categories v2 - Dark | 590986df69bf40348cdadd13a3e71ec1 | projects/14304074461979593421/files/e43cce34ecf94aaa8998948fe8840e90 |
| accounts-v2-light.png | Accounts v2 | decd4163ac3949f49376765367cb98d1 | projects/14304074461979593421/files/d5912ed486e140869ca96cb8dc74e852 |
| accounts-v2-dark.png | Accounts v2 - Dark | c99ae9a9d02b42b0ab1e10a7c3e0c5b0 | projects/14304074461979593421/files/e951517b613c430a90198f318bc7d933 |

The builder downloads the four HTML files into this folder (names categories-v2-light.html and so on) before coding; the HTML is the exact spacing, sizes and colour source.

## How to use these (reviewer notes)
- The LIGHT screens are the reference. They keep the original look and add what the owner asked for: a drag handle at the left of every row, a visible three-dot menu (Rename, Colour, Archive), a lifted row while dragging, locked built-in rows with a lock and the caption "Built in, cannot be renamed or archived", the hint "Hold and drag to reorder", an empty state, and on Accounts the last four digits and a Default pill.
- DRAG HANDLE ICON: the owner chose the "sandwich" icon (three stacked horizontal lines, Material Menu style). Stitch drew six dots (drag_indicator) because it was generated before that choice. Build the handle with the three-line icon, not the six dots; the position and size stay as in the design.
- The caption on the built-in rows is cut off ("Built in, cannot be renamed ...") in the light Categories screenshot: let it wrap to two lines in the app.
- The DARK screens contain extra invented content that is NOT part of the app and must be ignored: the "Burn Engine Config" summary card and "Active Ceiling Impact" counter, "SMS Engine 4.2", "Live Sync", "Auto-sync active", "Last TX" amounts, "Rules" and "Edit" buttons, a Cash balance, an "Ignored / Inactive accounts" block, the "On-Device Processing" card, renamed categories (Food & Dining, Commute & Fuel, Savings & Wealth, Transfers & Loans), per-row icons in tiles. For dark, use the LIGHT layout with the dark theme tokens from the existing dark screens (Categories - Dark, Accounts - Dark).
- Account rows may show "N transactions this month" (light v2); optional, only if the data is already available cheaply; no balances anywhere.
- Locked items: Savings, Transfer (categories); Cash and Other (accounts). The light Accounts v2 shows "Cash in Hand"; the real names stay Cash and Other.
