# Finance Automation Android - Design Brief (current stage)

Purpose: hand this file to a designer or a design tool. It lists every screen, every button, every state and every rule of the app as it exists today, then the planned changes the owner has already decided. Section 1 to 11 describe what exists. Section 12 describes what is queued for the redesign.

Scope note: the logic is stable but still under review. Screen structure is current as of this file; wording and layout are free to change as long as the behaviour in this file is kept.

---

## 1. Product in one paragraph

An Android app that logs expenses with as little effort as possible. When a UPI, bank or payment-app alert arrives, the app reads it on the device, shows a small sheet over whatever app is open, the user taps a category and optionally a split, and the transaction is written to the user's own Google Sheet (the source of truth). The phone keeps an encrypted local copy that works offline and syncs later. The accounting rules (splits, who owes whom, netting, carry-forward) are identical to the iOS version of the system.

Target: Android 9 to 15, optimised for Samsung One UI, working on all makers. Language: English. Currency: rupee. Time zone for months: Asia/Kolkata.

## 2. Users and key moments

- One owner-user per install, plus up to ten friends in the beta who each use their own Google account and their own sheet.
- Moment A: just paid someone by UPI. A sheet pops up. Goal: finish in one or two taps.
- Moment B: opened the app on purpose to check the month, settle with a friend, or fix something.
- Moment C: setup. Sign in, create the sheet, grant permissions. Done once, but must not be confusing.
- Moment D: something is wrong (capture is off, sync failed, a row needs confirming). The app must say so plainly and offer a fix.

## 3. App map (today)

```
Launch
 |- Onboarding (only until Google sign-in and workbook exist)
 `- Main screen (top bar + bottom bar with 3 tabs + one extended button)
      |- Needs Review   (tab 1, default)
      |- Dashboard      (tab 2)
      `- Settings & Sync(tab 3)

Entry points outside the app
 |- Capture sheet over other apps (after a payment alert)
 |- Heads-up notification with quick buttons (when overlay is not allowed)
 |- Home screen widget (1x1, Add expense)
 |- Quick Settings tile (Add expense)
 |- App shortcut (Add an expense)
 `- Result pill (small confirmation after saving)
```

## 4. Onboarding screen

Shown when there is no Google account or no workbook for the current year.

Content:
- Title: Welcome to Finance Automation. Short description.
- Checklist of four steps, each with a title, a state line and a button:
  1. Sign in with Google. Button: Get Started. States: not started, authorising, completed. Asks only the minimal Drive and Sheets scopes (files the app itself creates).
  2. Creating your finance workspace. Automatic after sign-in. States: progress, error. Button on error: Retry Workspace Setup.
  3. Notification listener permission. Button: Grant Permission (opens Android's notification access settings). Reason shown: needed to read payment alerts.
  4. Ready. Shown when all steps are complete, with the button to enter the app.
- Error messages (each needs a calm, readable error state): Sheets or Drive API disabled, permission not granted, rate limited, generic failure with an HTTP code, unknown failure. Each points to Advanced Diagnostics.

Rules: the screen can be re-opened from Settings (Re-run setup). It must survive rotation and process death.

## 5. Main screen frame

- Top bar: title and subtitle for the current tab (Needs Review: Automated UPI and Card Interceptions; Dashboard: Live Monthly KPIs and Sync Status; Settings and Sync: Google Sheets and Split Configuration).
- Bottom bar, three items: Needs Review, Dashboard, Settings and Sync. The Settings item shows a small badge when setup health has a problem.
- Extended button: Add expense. Opens the expense sheet (section 8) from the Dashboard and Review tabs.
- Setup health is re-checked every time the app returns to the foreground.

## 6. Needs Review tab

Lists transactions and captures that need a person.

Items and what each card shows:
- Transaction card: merchant (or Unknown Merchant), amount, category, account, date and time, split summary ("Split with ..."), trip tag if any, raw alert text for captured items, a "Possible duplicate" warning banner when a similar alert was seen recently.
- Card buttons: Confirm, Edit (opens the expense sheet pre-filled), Void (icon button).
- Failed sync cards: shows the failed item with two buttons, Retry and Discard. These mean money may be missing from the sheet.
- Header text: "N transactions awaiting confirmation".
- Empty state: "All Caught Up" with an illustration slot and a description that new alerts will appear here in real time.

Rules: Confirm marks the item reviewed and queues it for sync. Void is a soft delete that marks the sheet row as void (never removes rows). A row already settled by a friend cannot have its money fields edited.

## 7. Dashboard tab

Month-scoped view. Order today:
1. Month switcher: previous arrow, month name (for example November 2026), next arrow.
2. Hero card: "Living burn this month" with the amount, daily average, and comparison with last month. Living burn excludes categories marked excluded (default: Savings).
3. Two debt cards, tappable: "Owed to me" and "I owe". Each shows the total and the per-person list, or "None pending". Tapping opens settlement (today it routes to the review area).
4. Needs-review chip with a Review button when items are waiting.
5. "Where it went": category list with amounts and bars. See all / Show less toggle.
6. "Recent": latest transactions with See all.
7. Two small cards: Savings (SIPs and investments) and Income (total inflows).
8. Link-style button: "A payment did not show a prompt" (opens the compatibility report dialog, section 10.6).
9. Sync status line: Synced just now, Syncing, Offline, with pending count.

Empty state: "Nothing logged yet. Pay with UPI and the prompt will appear, or tap Add expense." with an Add expense button.

## 8. Expense sheet (the most important surface)

Used for payment captures (over other apps), manual adds, widget, tile, shortcut and Edit.

Header: badge ("Payment Intercepted" or "Quick Expense"), close button.

Fields in order:
1. Amount (rupee). Shown filled for captured payments. Editable. Accepts digits and one decimal point, two decimals max, never negative. If the alert had no readable amount, the field is empty and highlighted.
2. Paid to / what was this for (merchant or purpose), prefilled from the alert.
3. Account chips: user-defined accounts, plus Cash and Other. Preselected from the alert (matched by name or last four digits), else the default account, else Other.
4. Category chips: ranked by what the user usually picks for that merchant. Defaults: Food and Drink, Groceries, Transit, Subscriptions, Shopping, Trip Travel, Health, Family, Savings, Other. The word is always "Savings", never "Saving".
5. Split mode, three choices: Just Me, Split Equally, Custom Split.
   - Just Me: my share = total, owed to me = 0, status Personal.
   - Split Equally: choose one or more friend chips (plus "+ Add New Friend" inline with a name field and Add button). Me plus N friends share equally. Shows "My share" and "Owed to me" live. Status Pending.
   - Custom Split: field "Your Personal Share"; owed to me = total minus my share. Status Pending.
6. Trip tag line if a trip is active: "Trip: Goa 2026" with an End Trip action.
7. Duplicate warning banner when relevant.
8. Primary button: Save Expense. Secondary: Quick Save (one tap with suggested category) on the notification variant. Void (for already-saved items opened for editing). Error line for validation failures.

Behaviour rules: the keyboard must never cover the Save button; the amount must never change by accident; after saving, show a short result pill with the saved amount and category; the sheet closes itself. Saving works offline.

## 9. Other action flows (opened from the add menu)

The add menu offers five actions. Each opens its own sheet.

1. Expense: the sheet in section 8.
2. Income / Refund. Fields: Inflow type (Income / Salary, Refund / Return, Friend Settlement, Self Transfer), Receiving account, Amount received, Sender or source (or "Who settled" friend chips for Friend Settlement), Reason or note, optional "Settle open debt" picker with Auto-Settle (all, oldest first). Button: Record Inflow. Inflows are written to the inflow table (separate from expenses).
3. Friend Paid for Me. Fields: Who paid (friend chips, add new), Amount I owe, What was this for. Button: Record Debt. Creates an item in "I owe".
4. Repaid a Friend. Fields: Who did you repay, Amount, Date repaid. Button: Record Repayment. Applied against that friend's open debts oldest first.
5. Trip / Event Mode. Shows the active trip badge, "All new transactions will be tagged with this event until stopped". Field: trip tag name (for example Goa 2026). Buttons: Start Trip Mode, End Trip Mode. While active, every new expense gets the tag.

Each flow must show a confirmation, work offline, and be reachable only from inside the app (these sheets are protected from other apps).

## 10. Settings and Sync tab (today, one long screen)

This screen is overloaded and is the main target of the redesign (section 12). Today it contains, top to bottom:

10.1 Google account and ledger
- Connected state with email, buttons Re-run setup and Sign out. Not connected: description and Start setup.
- Backup account entry.

10.2 Bank and payment accounts
- List of accounts (Cash and Other always present; user accounts with optional last four digits). One is marked Default. Tap to make default. Add: name field and Add button. Cash and Other cannot be removed. First account the user adds becomes default.

10.3 Friends and companions
- Friend chips. Tap a chip: dialog "Manage friend" with a name field and buttons Rename, Remove (with delete confirmation), Cancel. Add friend field and Add button.

10.4 Capture
- Display over other apps: status (active or missing), explanation, Enable button.
- Setup health card: rows for Notification access, Display over other apps, Usage access, Notification alerts, Battery optimisation, OEM background autostart, Google account and ledger. Each row shows OK, Missing or Manual check and a Fix button that opens the right Android settings page. OEM row shows brand-specific instructions (Samsung, Xiaomi, Oppo, Vivo, OnePlus, Standard) and asks "Did this open the right screen?" Yes or No. A warning explains that force-stopping the app turns capture off. Listener state: Connected or Disconnected.
- There is no self-test button and no sample-data generator in any build type. A capture comes only from a real notification of a registered app or from manual entry.
- Add expense shortcuts card: Pin 1x1 widget button, Add as app icon button, explanation (and a message when the launcher cannot pin).

10.5 Categories and living burn
- List of categories each with a switch: Included in or Excluded from Living Burn. Add category: name field and Add button.

10.6 Trip and event tags
- Shows the active tag or "No trip tag active". Add tag: field and Add button. Choose or end the active tag.

10.7 Google Sheets and sync
- Spreadsheet ID field and Save ID.
- Buttons: Sync now, Mirror config (push local settings to the sheet's Config tab), Restore config (pull them back), Regenerate dashboard formulas, Repair workbook (recreate missing tabs or the 31-column layout).
- Background settle buffer: slider from 30 to 300 seconds (wait before syncing so edits can be fixed).
- Pending offline transactions count and failed items.
- Sync engine status.

10.8 Reliability and diagnostics
- Crash reports opt-in switch ("Help improve reliability"): sends only app version, phone model, Android version, error location. Never amounts, names or messages. Off by default.
- Test crash button (debug or beta only).
- Capture Health: daily pipeline funnel (alerts seen, parsed, captured, saved), recent trail events (expand or collapse), Share Compatibility Report.
- Advanced Diagnostics: event count, Refresh, Clear, raw Google API errors list with Clear and a notice that it is shared only if the user chooses.
- Compatibility Report dialog: title, optional note field, switch "Include Google error details", Close and Share Report. A second variant is "Report Missed Payment".

## 11. Outside-the-app surfaces

- Capture sheet over other apps: the expense sheet in section 8, in a floating window with a dimmed or blurred background, appears after a payment alert once the payment app returns to the home state. Needs the "display over other apps" permission.
- Heads-up notification (used when the overlay is not allowed or the screen state prevents it): shows merchant and amount with up to two quick category buttons and a Details button. Tapping a category saves immediately.
- Persistent notification "Capture is off - tap to fix" when notification access is lost. Channels: Expense Alerts, Cloud Sync Status, Authentication Alerts (reconnect Google), Health and Setup Alerts.
- Result pill: short toast-like confirmation after saving or voiding. There is no self-test variant.
- Home widget: 1x1 icon, opens the expense sheet.
- Quick Settings tile: "Add expense".
- App shortcut: "Add an expense".
- Reconnect prompt: when Google authorisation expires, a notification asks the user to reconnect and resume queued items.

## 12. What the owner has already decided for the redesign

These are decisions, not questions. The designer should build to them.

12.1 Navigation
- Top bar with a hamburger that opens a navigation drawer for rarely used destinations: Accounts, Categories, People and Trips, Capture and Permissions, Sync and Backup, Preferences, Diagnostics, About.
- Bottom bar with two daily destinations only: Dashboard and Needs Review (badge showing the count).
- One add button that expands to: Expense, Friend settled, Friend paid for me, Repaid a friend, Trip mode, Income or refund.
- Settings is no longer one long screen; each drawer item is its own screen.

12.1b Account avatar
The top bar of Dashboard, Needs Review and Transactions shows a small circular avatar at the right with the first letter of the signed-in Google account (the app only holds the email, not a photo). Content description "Google account". Tapping it opens a small menu with the email address, "Sync and Backup" and "Sign out". Screens opened with a back arrow (Accounts, Categories and the other drawer screens) do not show it. Onboarding has no top bar.

12.2 Dashboard order
Month switcher; living burn with daily average and last month; Owed to me and I owe per person (tap a person to settle); one sync status line (Synced just now, N waiting, N failed; tap opens Sync and Backup); a needs-review chip; categories; recent transactions. No settings controls on this screen.

12.3 Needs Review order
Failed syncs first (money at risk), then captures awaiting confirmation (one-tap confirm with a suggested category, swipe to dismiss, Confirm all), then possible duplicates, then unknown accounts to add ("Add this account"). Clear empty state.

12.4 Accounts
Bank accounts are user-defined: a name plus optional last four digits. Cash and Other are built in and cannot be deleted. No bank is preloaded. Accounts are added by hand and also detected from SMS; an SMS from an unrecognised account offers "Add this account" in Needs Review. The first account the user adds becomes the default.

12.5 In-app versus popup
When the app is open in the foreground, actions happen on in-app screens or sheets, not a floating popup. The floating popup is only for the home-screen widget and for payment captures while another app is in front.

12.6 Template tab
The sheet's Template tab is being removed. The app builds each month tab itself.

12.7 Sheet look
Every tab of the Google Sheet must match the iOS template: fonts, sizes, column widths, row heights, borders, number formats, no extra dropdowns that the iOS version does not have, Dashboard key figures in the same cells. This is verified by a comparison script that must report no differences.

12.8 Android quality bar
Material 3, light and dark themes, edge-to-edge, predictive back, correct keyboard handling (the sheet and Save button never hidden), large-text support, touch targets of at least 48dp, screen-reader labels on every icon and chip, works one-handed, no hardcoded colours (theme tokens only), consistent wording with the iOS prompts.

12.9 Look and feel
Calm utility look, not decorative. Numbers are the hero; KPI first. Wording follows the iOS prompts exactly where they exist (Choose Action, Enter Amount, Select Account, What was this for, Select Category, Split Mode, Just Me, Split Equally, Custom Split, Add New Friend).

12.10 Transactions screen (new, missing today)
Today "See all" under Recent opens Needs Review, which only lists items waiting for a decision, so there is no place to see every entry. Add a Transactions screen reachable from Recent "See all" and from the drawer. It lists every entry of the selected month, newest first, grouped by day, each row showing merchant, category, account, amount, my share, status chip and sync icon. Tap a row for a detail sheet with Edit and Void (Edit is limited for settled rows). Top controls: month switcher, search by merchant or friend, filters for category, account, person, trip tag and status. Empty state and no-results state are required. "Where it went" see-all only expands the category list; tapping a category opens this screen filtered to that category.

12.11 Additions accepted from the design review
- Offline state: when the phone has no connection, the Dashboard shows a banner "You are offline. Your expenses are saved on this phone and will sync when you are back online." and lists the queued items with "Waiting to sync". Needs a connectivity flow in the app.
- Capture status line: Needs Review (including its empty state) shows one line, "Capture is on" or "Capture is off, tap to fix", from the existing listener check. No counts or percentages.
- Empty month: one button "Go to current month".
- No results: buttons "Search all months" and "Clear search". Search has two scopes, this month and all months.
- Accounts list: each row also shows "N transactions this month" (real data, replaces the invented subtitles).
- Capture and Permissions: a small line with the device ("Android 14, Samsung One UI") and the list of payment apps with an independent switch for each (Google Pay, BHIM, Supermoney, PhonePe, Paytm, Amazon Pay, CRED, MobiKwik, Freecharge, Navi, Samsung Wallet, bank SMS), a "Watch another app" picker limited to financial apps, and the line "We read WhatsApp only when it marks a message as a payment. We never read other chats, mail or OTP messages."
- Dashboard: the Savings (SIPs and investments) and Income (total inflows) cards from the first brief come back under "Where it went", showing this month's totals.
- Onboarding: one privacy line, "Your data stays on your phone and your Google Sheet."
Rejected: retroactive expense dates (backlog for a later release), suggestion chips, capture integrity percentages, scrolling month chips.

12.12 Later additions (not in the first release)
- Voice search in Transactions: a microphone icon in the search field that uses Android's system speech prompt (no extra permission needed), filling the search text with the spoken merchant, friend or category.
- Retroactive expense dates (add an expense for a past month), once month-tab and carry-forward handling is safe for past months.
- Receipt scanning (OCR), sync history list.

12.13 Subscriptions (new feature, owner request)
Rules
- No future-dated transactions anywhere: every save path rejects a timestamp later than now (small clock tolerance). Past dates beyond the current month stay a later feature (section 12.12), to be handled properly when the data moves from the sheet to a database.
- A subscription is a recurring payment the user wants to track: name, amount (fixed, or "varies"), how often (weekly, monthly, yearly), due day or date, account, category (default Subscriptions), reminder on or off, active or paused.
Behaviour
- Each day at an end-of-day time (default 9 PM Asia/Kolkata, changeable in Preferences) the app checks subscriptions due today or overdue and not yet resolved. For each it posts one notification: "Did you pay Netflix, 649 rupees?" with three actions: Yes, Not yet, Skip this time.
- Automatic match first: when a captured payment arrives whose amount equals the subscription amount (or any amount if it varies) and whose merchant text contains the subscription name or its saved keywords, within 3 days either side of the due date, the cycle is marked paid, the expense is logged normally (category Subscriptions), and no reminder is sent.
- Yes with no captured payment: the app logs the expense for that due date with the subscription amount, category and account, and marks the cycle paid. Not yet: ask again the next evening, up to 3 times, then move the item to Needs Review. Skip this time: marks the cycle skipped, no expense.
- If a payment alert arrives after the user already answered Yes for that cycle, it is flagged as a possible duplicate instead of being added twice.
- Needs Review shows an overdue unresolved subscription as an item "Subscription due: Netflix 649 rupees, did you pay?" with Yes, Not yet, Skip, in case the notification was missed.
Screens
- Subscriptions (drawer item): list with name, amount, next due date, a pause switch and a three-dot menu; an empty state; Add button.
- Add or edit subscription: name, amount or "varies", frequency, due date, account chips, category (Subscriptions preselected), reminder switch, optional match keywords (for example "NETFLIX").
- Dashboard: an optional small "Due this week" line with total (later).
- Reminder notification as described above.
Data and sync: subscriptions are stored locally and written to the Config tab by Mirror config (new row type "subscription"), so Restore config brings them back. The logged expenses go to the normal expense table.

## 13. Data the screens read and write (for realistic mock data)

- Transaction: id, date and time, merchant, category, account, total amount, my share, owed to me, status (Personal, Pending, Settled, Void, Carried), companions, trip tag, source (UPI app, SMS, manual), review state, sync state (pending, synced, failed).
- Inflow: date, type, receiving account, amount, source or friend, note.
- Payable (money I owe a friend): friend, amount, reason, date, status.
- Person: name. Account: name, last four digits (optional), default flag. Category: name, excluded-from-burn flag. Trip tag: name, active flag.
- Sheet layout per month tab: debits, inflows and payables side by side, plus hidden ID columns.

## 14. States every screen must design

Loading, empty, error (with a plain-language reason and a fix button), offline, partially synced, permission missing, dark theme, large font, small phone (360dp wide), and landscape for the sheet.

## 15. Out of scope for now

Tablet layouts, other languages, widgets beyond the 1x1, web version, in-app purchases, any server of our own. No third-party analytics. Crash reporting stays opt-in.
