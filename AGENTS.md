# Agent Operational Manual — Finance Automation Android

This document is the authoritative operating manual for AI agents operating on the `Finance Automation Android` codebase. It establishes the architectural principles, security boundaries, coding standards, and operational guidelines required to develop and maintain the Android application safely.

---

## 1. System Mission & Context

This project is the native Android companion and standalone client for the **Finance Automation** ecosystem. 

* **The Goal:** Eliminate manual expense logging friction on Android by combining real-time UPI/banking notification interception with a native, 1-tap categorization Bottom Sheet and automated Google Drive/Sheets provisioning.
* **The Target Platform:** Android 9.0+ (API 28 to API 35), specifically optimized for Samsung Galaxy devices (One UI Side Key, Quick Settings Tiles, Edge Panels) while remaining universally compatible with all Android devices.
* **Master Ledger Specification:** All transactions logged by this app must strictly comply with the proven 28-column master sheet schema and net burn calculation rules established in the iOS predecessor.

---

## 2. Core Architectural & Engineering Standards

Agents developing on this codebase must adhere strictly to these engineering standards:

### A. Android Architecture & Code Quality
* **Architecture Pattern:** Clean Architecture with MVVM (Model-View-ViewModel) + Repository Pattern.
  * `presentation`: Jetpack Compose UI screens, ViewModels, Bottom Sheet Dialogs, StateHolders.
  * `domain`: Pure Kotlin UseCases (`ParseNotificationUseCase`, `CalculateSplitUseCase`, `LogExpenseUseCase`), Models, and Repository interfaces. Zero Android framework dependencies in domain layer.
  * `data`: Room Database, DAOs, Google Sheets/Drive API client implementations, `NotificationListenerService`.
* **UI & Styling Standards:**
  * Build UI exclusively with **Jetpack Compose** and **Material 3**.
  * Use design tokens (MaterialTheme colors, typography, shapes) defined in `ui/theme/`.
  * Never use hardcoded colors or magic numbers; reference theme resources.
  * Provide native Light and Dark theme support.
* **Concurrency & Background Execution:**
  * Use Kotlin Coroutines and `StateFlow` / `SharedFlow` for reactive state.
  * Never perform disk I/O or network requests on the Main (UI) thread (`Dispatchers.IO`).
  * Use Android Jetpack **`WorkManager`** for reliable offline synchronization with network constraints (`NetworkType.CONNECTED`).

### B. Notification Interception & Privacy Protection
* **Target Package Filtering (Whitelist Only):**
  * The `NotificationListenerService` must **strictly** filter incoming notifications by known financial package IDs:
    * Google Pay (`com.google.android.apps.nbu.paisa.user`)
    * BHIM (`in.org.npci.upiapp`)
    * Supermoney (`com.supermoney.app`)
    * PhonePe (`com.phonepe.app`)
    * Paytm (`net.one97.paytm`)
    * System SMS / Bank debit alerts
* **Strict Privacy Rule:** Under no circumstances should the service read, log, cache, or inspect notifications from personal messaging apps (WhatsApp, Telegram, Signal) or OTP authentication messages.
* **Data Transit:** Financial notification payloads must only be parsed locally on device and committed to the user's private Google Sheet. Never transmit parsed data to third-party servers. When the user has explicitly opted in, crash and freeze reports containing technical data only (app version, device model, Android OS level, error location) may be sent to Firebase Crashlytics; such reports must never include transaction amounts, payees, account names, or notification message text.

### C. Split Logic & Accounting Mathematics
The application must faithfully reproduce the proven iOS split calculations:
1. **`Just Me`:**
   * `Total Amount` = Extracted / Input Amount
   * `My Share` = Total Amount
   * `Owed To Me` = `0`
   * `Companions` = `"Personal"`
   * `Status` = `"Personal"` (iOS wins; `Settled` is used when netting absorbs the full owed amount)
2. **`Split Equally`:**
   * Multi-select companion chips from local `people` table + `+ Add New Friend`.
   * Let $N$ be the count of selected friends.
   * `My Share` = $\text{Total Amount} / (N + 1)$
   * `Owed To Me` = $\text{Total Amount} - \text{My Share}$
   * `Companions` = Comma-separated list of friend names
   * `Status` = `"Pending"`
3. **`Custom Split`:**
   * User inputs numeric personal share.
   * `My Share` = User input value
   * `Owed To Me` = $\text{Total Amount} - \text{My Share}$
   * `Status` = `"Pending"`
4. **Savings Keyword Rule:**
   * The category for investments and SIPs is standardized strictly on the single plural word **`Savings`**. Never use singular `Saving` or wildcards.

---

## 3. Strict Security & Credential Protection (Zero Leak Rule)

1. **Never Hardcode Secrets:**
   * Real OAuth client IDs, client secrets, Google API service account keys, or signing keystore credentials must **NEVER** be committed to Git.
   * Use `local.properties` (gitignored) or environment variables for development credentials.
2. **No Keystores in Git:**
   * Release keystore files (`*.keystore`, `*.jks`) must never be added to version control.
3. **Google Sign-In Scopes:**
   * Request minimal necessary OAuth scopes (`drive.file` and `spreadsheets`) so the app only accesses files it creates, preserving user privacy.

---

## 4. Repository & Hygiene Rules for All Agents

1. **Zero Emojis Everywhere:**
   * Do not include emojis anywhere in source code, Kotlin comments, strings, documentation, commit messages, or PR descriptions.
2. **No Local Machine Paths in Repo Docs:**
   * Never write hardcoded local machine paths (e.g. `c:\...`, `file:///...`) into repository documentation. Always use repository-relative markdown paths.
3. **Version Control Protocol:**
   * The user will explicitly instruct when to initialize git, commit, and push.
   * Do not commit or push without explicit user authorization.
4. **No Mock or Stub APIs:**
   * When integrating the Google Drive and Sheets APIs, write real, functional client calls with proper network execution, error handling, and authentication. Never leave mock fallback generators.
5. **Reviewer Handoff Channel:**
   * At the start of every task, call `read_inbox` on the `finance-handoff` MCP server (or read `docs/handoff/TO_AGENT.md` if the tool is unavailable) and follow it.
   * At the end of every task, call `post_report` with the report (or overwrite `docs/handoff/FROM_AGENT.md`). Setup is in `tools/handoff-mcp/README.md`.
6. **Pull Request Merging Rule:**
   * Agents must never merge a pull request without explicit approval from the reviewer. Creating and pushing a PR is permitted, but merging is strictly forbidden until approved.
7. **Destructive Git Commands Rule:**
   * Agents must never run `git restore .`, `git restore` on paths with uncommitted changes, `git checkout -- <path>`, `git reset --hard`, `git clean`, `git stash drop`, or any command that discards uncommitted work, without explicit approval from the user in the same conversation.
   * If the working tree has unexpected uncommitted changes, stop, list them with `git status` and `git diff --stat`, and ask the user. Do not assume they are stale or disposable.
   * Before any approved destructive command, save the changes first on a throwaway branch with `git stash push -u` or a commit, and report the reference so the work can be recovered.

