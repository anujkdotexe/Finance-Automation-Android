# Finance Automation Android: UI and UX Design Guide

Status: draft v1. Applies to every screen, the overlay sheet, the widget, the shortcuts and the tile. Build agents implement this in the UI/UX phase of `ANDROID_V8_PHASE_GUIDE.md`.

## 1. Decisions (owner approved)

| Topic | Decision |
| :--- | :--- |
| Look | Calm utility. Neutral surfaces, one accent, the workbook palette for meaning. Numbers are the hero. No gradients, glow, mascots or decorative icons. |
| Home screen | KPIs first: living burn this month (with daily average), owed to me, I owe, and a "needs review" chip. Savings and income sit below the fold. |
| Copy | Reuse the iOS Shortcuts wording wherever it exists. Everything the iPhone never had uses plain, literal labels. No slogans, no invented product names. |
| Overlay sheet | Copies the iOS Shortcuts prompt feel: a bottom sheet over a dimmed screen, one question at a time, big tap targets, no timeout. |

The app is a utility. Success is "I logged it in two taps and the numbers are right", not "it looks exciting".

## 2. Principles

1. Data first. The amount, the person and the status are always the largest, highest contrast items on a screen.
2. One accent. Colour carries meaning, never decoration: red is money out, green is money in or owed to me, amber is money I owe, navy is structure and the brand. Meaning is never colour only (see section 8).
3. Quiet surfaces. White or near black surfaces, a 1 dp outline instead of shadows, tonal elevation for sheets.
4. Few words. A label says what it is. A button says what it does ("Add expense", "Save", "Settle").
5. Native behaviour. Edge to edge, predictive back, system bars, dynamic font size, TalkBack, dark theme, the system share and permission flows.
6. Everything visual comes from tokens and shared components (section 3 and 4). A screen contains layout and wiring only. This is what makes a later redesign cheap.

## 3. Design tokens (single source: `presentation/theme/`)

No screen, component or widget may contain a colour literal, a text size, a corner radius, a spacing number or a user-visible string. They reference tokens in `theme/` and `res/values/strings.xml`.

### 3.1 Colour

Keep the existing semantic tokens in `Color.kt` (`SheetNavyBanner`, `SheetDebitCrimson`, `SheetCreditGreen`, `SheetPayableAmber`, `SheetSlateHeader`, `SheetSpacerGray`) and expose them through one `FinanceColors` object provided by the theme (`LocalFinanceColors`) with light and dark values:

| Token | Meaning | Light | Dark |
| :--- | :--- | :--- | :--- |
| `moneyOut` | burn, expenses | `#C62828` | `#EF9A9A` |
| `moneyIn` | income, owed to me, settled | `#2E7D32` | `#A5D6A7` |
| `moneyOwed` | I owe people | `#E65100` | `#FFCC80` |
| `brand` | titles, selected state, primary buttons | `#19227E` | `#C5CAE9` |
| `info` | charts, links, daily average | `#1465BF` | `#90CAF8` |
| `surface`, `surfaceVariant`, `outline` | cards, rows, dividers | white, `#F1F5F9`, `#E2E8F0` | `#121418`, `#1C1F26`, `#2A2E37` |

Rules: body text on surfaces has a contrast ratio of at least 4.5:1, large text and icons at least 3:1 (check both themes). Do not use dynamic (wallpaper) colour. Charts use the six slice colours from the spec in light and dark variants.

### 3.2 Typography (`Type.kt`)

Default Material 3 type scale on the system font, sizes in `sp`, these roles only:

| Role | Use | Size and weight |
| :--- | :--- | :--- |
| `amountHero` | living burn value | 32 sp, semi-bold |
| `amountLarge` | KPI values, sheet amount | 22 sp, semi-bold |
| `titleMedium` | screen and card titles | 16 sp, medium |
| `bodyMedium` | rows, descriptions | 14 sp |
| `labelMedium` | captions, chips, column labels | 12 sp, medium |

All amounts use tabular figures (`fontFeatureSettings = "tnum"`) so columns line up. Never fix text heights in dp; layouts must survive 200 percent font scale.

### 3.3 Spacing, shape, elevation, motion

- Spacing grid of 4 dp: `xs 4`, `s 8`, `m 12`, `l 16`, `xl 24`, `xxl 32`. Screen edge padding 16 dp.
- Shapes: `small 8`, `medium 12`, `large 16`, `sheet 28` (top corners of bottom sheets), chips fully rounded.
- Elevation: none for cards (1 dp outline). Only sheets and the result pill use tonal elevation.
- Touch targets at least 48 dp by 48 dp.
- Motion: 150 to 250 ms standard easing for transitions; the overlay sheet uses one spring in and one out (as in Phase 5). Respect the system "remove animations" setting.

### 3.4 Money formatting

One function `formatRupees(amount: Double)` in a shared utility, using `NumberFormat` for `Locale("en", "IN")` so the grouping is Indian (`₹1,23,456`), no decimals for KPI values, two decimals in rows and the sheet. Negative amounts show a minus sign, not brackets. No other place formats money.

## 4. Components (`presentation/components/`, stateless, previewed)

Build these once, use them everywhere, delete the older duplicates (`AppleExpensePromptSheet`, `ExpenseBottomSheet`, the `presentation/ui/components` and `presentation/ui/screens` placeholders).

| Component | Purpose |
| :--- | :--- |
| `AmountText` | amount with the right colour token, sign and tabular figures |
| `KpiCard` | label, value, caption, optional trend; one tap target when it navigates |
| `SectionHeader` | title and optional action ("See all") |
| `TransactionRow` | description, date, account, amount, status chip; swipe actions only where documented |
| `StatusChip` | Personal, Pending, Settled, Carried Fwd, Void, Partial, Repaid (text plus colour) |
| `PersonRow` | name, amount, oldest date |
| `FieldRow` and `ChoiceChips` | the building blocks of every sheet step |
| `PrimaryButton`, `SecondaryButton`, `TextAction` | exactly three button styles |
| `SheetScaffold` | the one bottom sheet shell used by the overlay and by the fallback activity (grabber, title, content slot, sticky action row, insets) |
| `EmptyState` | one line of text and at most one action, no illustrations |
| `SyncStatusLine` | "Synced 2 min ago", "2 waiting to sync", "Offline" |

Every component has a `@Preview` for light, dark and 200 percent font scale.

## 5. Screens

### 5.1 Home (Dashboard)

Top bar: month selector ("November 2026") and a sync status line under the title. Content in this order:

1. Hero card: living burn this month (`amountHero`, `moneyOut`) with "Daily average" and "Last month" as one caption line.
2. Two cards side by side: Owed to me (`moneyIn`) and I owe (`moneyOwed`), each with the top three names and amounts under the value; tap opens the settlements list.
3. A "Needs review" chip row (count and the word "Review"), shown only when the count is above zero; opens Review.
4. Section "Where it went": category list with a thin proportional bar per category (no pie chart on the phone), top five and "See all".
5. Section "Recent": the last five transactions, then "See all".
6. Below the fold: Savings and Income cards.

Pull to refresh runs a sync. Empty state when nothing is logged yet: "Nothing logged yet. Pay with UPI and the prompt will appear, or tap Add expense."

### 5.2 Other screens

- Review: grouped list (Needs your input, Failed to sync, Possible duplicates); each row has one primary action.
- Settings: grouped list rows (Account, Sheets and sync, Capture, Home screen, Advanced). The Setup health checklist is its own screen reached from Capture; the Settings tab shows a badge when a row is missing.
- Onboarding: one step per screen, each with one sentence of explanation and one button.

### 5.3 Overlay sheet (the iOS Shortcuts feel)

Dimmed scrim, bottom sheet with a grabber, one question per step, large choice rows (min 56 dp), the primary action pinned at the bottom, a result pill at the top after saving. No timeout. Wording follows section 6.

## 6. Copy

### 6.1 Rules

Sentence case. Verbs for actions, nouns for labels. No exclamation marks, no slogans, no "Quick log" style product names, no references to other platforms ("Apple", "Island", "Shortcuts") in user-visible text. Numbers and names are never truncated before the label is.

### 6.2 Prompts taken from the iOS Shortcuts

| Place | Text |
| :--- | :--- |
| Action menu title | Choose Action |
| Menu entries | Expense, Income / Return, Friend Paid For Me, Repaid a Friend, Trip Mode |
| Expense amount | Enter Amount ₹ (with the trip tag in brackets when a trip is active) |
| Expense account | Select Account |
| Expense description | What was this for? |
| Expense category | Select Category |
| Split | Split Mode (Just Me, Split Equally, Custom Split) |
| Friends | Select friends, + Add New Friend, Enter friend's Name: |
| Custom split | Enter your share ₹ |
| Income amount | Amount Received ₹ |
| Income type | Friend Settlement, Salary/Stipend, Allowance, Other |
| Income sender | Who sent this? |
| Settle picker | Select Bill to settle, Auto-Settle (All / Oldest First) |
| Income note | Reason / Note |
| Friend paid | Amount I Owe ₹, Who paid for you?, What was this for? |
| Repaid | Who did you repay? |
| Trip | Enter Trip Tag (e.g. Goa 2026): and, with an active trip, End Trip, Keep Active |

### 6.3 Labels for things the iPhone did not have

| Place | Text |
| :--- | :--- |
| Widget, shortcut, tile label | Add expense |
| Shortcut long label | Add an expense |
| Widget description | Opens the expense prompt |
| Settings card, home screen | Add to home screen |
| Settings button, pinned shortcut | Add as app icon |
| Settings button, widget | Add widget |
| Sync status | Synced 2 min ago / 2 waiting to sync / Offline |
| Result pill | Saved, will sync / Saved |
| Capture off notification | Capture is off. Tap to fix. |
| Force-stop warning | If you force-stop the app, Android turns capture off until you open the app. |

The agent proposes strings for every other screen in `strings.xml` following 6.1; the owner reviews the full list once (the agent lists all strings in its report).

## 7. Widget, shortcut and tile appearance

The widget is a plain app-icon look: the adaptive icon background colour, the plus symbol in the foreground colour, themed in light and dark. No text on the widget. The static shortcut, the pinned shortcut and the Quick Settings tile use the label "Add expense" and the same symbol.

## 8. Accessibility and Android practice

- Touch targets 48 dp, visible focus and pressed states, content descriptions on every icon button, headings marked for TalkBack, a logical focus order.
- State is never colour only: money in and out also carry a sign or the words, chips carry text.
- Edge to edge with window insets handled; predictive back enabled; the keyboard never covers the focused field.
- Dark theme equals light in structure, not an inverted copy; check both for contrast.
- Lists use stable keys and `LazyColumn`; no work in composition; state is hoisted; ViewModels expose immutable UI state; previews for every component.
- Strings in `strings.xml` with plurals; dates through `java.time` formatters; money through `formatRupees`.
- Phone first; tablets and foldables are not designed in this phase.

## 9. Changing the design later

A new design (for example from Figma or screenshots) is applied in this order, and the backend, use cases, repositories and the Sheets contract are never touched:

1. Replace token values in `theme/` (colours, type, shapes, spacing). Cost: small.
2. Restyle or replace components in `components/`. Cost: medium; screens keep working because they only use the components.
3. Re-compose screens and flows. Cost: larger, but limited to the screen and ViewModel UI state of each screen.

Provide the design as frames or screenshots with sizes and spacing; the agent maps it to tokens first, components second, screens last, and reports each mapping.
