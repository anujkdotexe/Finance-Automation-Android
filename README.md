# Finance Automation Android

Finance Automation Android is the native Android companion and standalone client for the Finance Automation ecosystem. It eliminates manual expense logging friction on Android by combining real-time UPI and banking notification interception with an interactive, 1-tap categorization Bottom Sheet and automated Google Drive and Google Sheets synchronization. All transactions logged strictly adhere to the 28-column master sheet schema and net burn accounting rules established in the ecosystem specification.

---

## Current Status

The app is feature complete for its first release and is in hardening and on-device verification. It is not distributed yet.

- **Implemented:** Google sign in with the minimal `drive.file` scope, one workbook per calendar year (`Finance2026`, `Finance2027`, and so on) with carry-forward between years, an encrypted local database (SQLCipher, Room schema version 14), the notification and SMS capture pipeline with a floating add sheet and quick settings tile, the full accounting engine (equal, custom and just-me splits, netting, settlement and repayment waterfalls, non-destructive voiding, carry-forward), income tracking, trips, subscriptions with reminders, budgets, Insights, CSV export, app lock, a Dashboard with Living burn that nets refunds, and two home screen widgets (quick add and month burn).
- **Source of truth:** the Google Sheet. The app is offline first: every change is saved locally and queued, then synced with retries and a read quota guard.
- **Verification state:** the unit test suite runs on the JVM (about a thousand tests). Room migrations, Google account flows and real notification capture are verified by hand on a physical phone, because there is no instrumented test source set yet.
- **Known open items:** see the independent review in `docs/reviews/` and the audit notes next to it for the full list of findings and their status.

---

## Architecture Overview

The application follows Clean Architecture principles with Model-View-ViewModel (MVVM) and the Repository pattern:

- **presentation:** Jetpack Compose Material 3 screens, floating Bottom Sheet dialogs, ViewModels exposing immutable `UiState` via `StateFlow`.
- **domain:** Pure Kotlin business models, use cases (`CalculateSplitUseCase`, `NettingUseCase`, `SettlementWaterfallUseCase`, `RepaymentWaterfallUseCase`, `CarryForwardUseCase`, `VoidTransactionUseCase`, `YearCloseUseCase`, `SyncConfigUseCase`), and repository interfaces. Contains zero Android framework dependencies.
- **data:** Room encrypted SQLite database (`FinanceDatabase`), Data Access Objects (DAOs), Google Drive and Google Sheets API v4/v3 REST client implementations using OkHttp, and payload mappers.
- **service:** Android system integrations including `NotificationListenerServiceImpl`, `QuickSettingsTileService`, `QuickLogWidgetProvider`, and WorkManager periodic workers (`SyncTransactionsWorker`, `MonthlyRolloverWorker`).

The application operates offline-first: transactions and configuration changes are committed locally to Room and queued into `sync_queue`. The remote Google Sheet serves as the master ledger and single source of truth.

---

## Key Features

- **Automated Financial Notification Interception:** Background listener filters incoming debit and credit alerts from whitelisted UPI apps and bank SMS.
- **28-Column Master Ledger Compliance:** Transactions map directly to the proven master spreadsheet schema with hidden UUID columns (`AC`, `AD`, `AE`) for idempotent syncing.
- **Interactive Split Math:** Supports Just Me, Split Equally across companions, and Custom Split shares with strict rupee rounding (`round2`).
- **Accounting Engine:** Reconciles debts and credits using FIFO settlement waterfalls, repayment offsets, cross-month debt rollover with lineage tracking, and in-place voiding.
- **Durable Two-Way Settings Sync:** Synchronizes accounts, categories, companions, trip tags, and preferences with the workbook `Config` tab.
- **Two-Phase Idempotent Sync:** Pre-computes and persists `planJson` before remote execution to ensure atomic recovery during network drops.

---

## Tech Stack & Versions

- **Target Platform:** Android 9.0+ (API 28 to API 36)
- **Language:** Kotlin 2.1.0 (JVM target 17)
- **Build System:** Android Gradle Plugin 8.8.0, Gradle 8.11.1
- **UI Toolkit:** Jetpack Compose (BOM 2024.12.01), Material 3
- **Database:** AndroidX Room 2.6.1 with KSP 2.1.0-1.0.29
- **Encryption:** SQLCipher for Android 4.6.1 (`net.zetetic:sqlcipher-android`)
- **Networking:** OkHttp 4.12.0
- **Background Execution:** AndroidX WorkManager 2.10.0
- **Serialization:** Kotlinx Serialization JSON 1.7.3
- **Authentication:** Google Play Services Auth 21.3.0 (Google Identity Authorization API)

---

## Privacy & Security

- **Strict Package Whitelist:** Interception is restricted to financial packages: Google Pay (`com.google.android.apps.nbu.paisa.user`), BHIM (`in.org.npci.upiapp`), Supermoney (`com.supermoney.app`), PhonePe (`com.phonepe.app`), Paytm (`net.one97.paytm`), and default SMS clients.
- **Privacy Package Blacklist:** Personal messaging apps (WhatsApp, Telegram, Signal, Messenger, Instagram, Discord) and OTP/2FA verification codes are immediately dropped.
- **Zero Raw Alert Text Stored:** Alerts are parsed in memory. Raw title and body text are never stored in the database, logged, or transmitted off-device. SMS from personal numbers and contact names is rejected on the sender alone.
- **Minimal Google access:** Drive and Sheets access uses only `https://www.googleapis.com/auth/drive.file`, restricting the app to workbooks and files it created itself. Sign in also reads the account email, name and photo to show which account is connected.
- **Encrypted Local Storage:** Database is protected by SQLCipher AES-256 encryption. The database key is generated on the device and wrapped by an Android Keystore AES-GCM key.
- **Zero Secret Commits:** No API keys, client secrets, tokens, or keystores are stored in source code.

---

## How to Build

### Prerequisites

- Java Development Kit (JDK) 17
- Android SDK with API 36 platform and build tools installed
- Git

### Configuration

The app reads no environment variables at build or run time. The files below are the only configuration, and none of them is committed:

- `local.properties`: the Android SDK location (`sdk.dir=...`), created by Android Studio.
- `keystore.properties`: release and beta signing (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). A release build refuses to run without it. A beta build without it is signed with the debug key and must never be distributed.
- `app/google-services.json`: optional Firebase configuration for crash reports.
- Google Cloud Console: an Android OAuth client for package `com.financeautomation` with the SHA-1 of each signing key, the Drive and Sheets APIs enabled, and every tester added as a test user while the consent screen is in testing.

The `.env.example` file is a leftover from an earlier design. The app does not read it.

### Build Commands

```bash
# Assemble debug APK
./gradlew assembleDebug

# Run static checks and compilation verification
./gradlew check
```

---

## Project Layout

```
Finance Automation Android/
├── app/
│   ├── schemas/                            # Versioned Room database schema contracts
│   ├── src/
│   │   └── main/
│   │       ├── java/com/financeautomation/
│   │       │   ├── core/                   # Shared models, constants, and date utilities
│   │       │   ├── data/
│   │       │   │   ├── local/              # Room database, entities, DAOs, encryption migrator
│   │       │   │   ├── remote/             # Google Drive/Sheets clients, provisioner, adapters
│   │       │   │   └── repository/         # Repository implementations
│   │       │   ├── di/                     # Dependency injection (AppContainer)
│   │       │   ├── domain/                 # Pure Kotlin models, use cases, repository contracts
│   │       │   ├── presentation/           # Jetpack Compose UI screens, components, ViewModels
│   │       │   └── service/                # Notification listener and WorkManager workers
│   │       └── res/                        # Android resources, themes, dimensions, icons
│   └── build.gradle.kts
├── docs/                                   # Specifications, architecture docs, and sync notes
├── tools/
│   └── handoff-mcp/                        # Inter-agent handoff MCP server
├── build.gradle.kts
├── gradle.properties
└── settings.gradle.kts
```

---

## Documentation Index

- [Product and Architecture Specification](docs/ANDROID_V8_PRODUCT_AND_ARCHITECTURE_SPEC.md): Functional requirements, sheet layouts, and data flows.
- [Engineering and Test Plan](docs/ANDROID_V8_ENGINEERING_AND_TEST_PLAN.md): Technical architecture, device targets, and audit findings.
- [Phase Guide for Build Agents](docs/ANDROID_V8_PHASE_GUIDE.md): Implementation guide detailing phase milestones and quality standards.
- [iOS Backend Sync Notes](docs/IOS_BACKEND_V2_3_0_SYNC_NOTES.md): Reference accounting formulas and edge case behaviors ported from `Code.gs`.
- [Handoff MCP Server Guide](tools/handoff-mcp/README.md): Setup and usage documentation for the handoff protocol.

---

## Development Workflow

Development of this project is conducted through a pair-agent workflow involving two specialized AI agents:

1. **Build Agent (Antigravity IDE):** Responsible for executing code changes, database migrations, Gradle builds, and static analysis within the native development environment.
2. **Reviewer Agent (Claude Code):** Responsible for verifying code against specifications, reviewing architectural compliance, identifying edge cases, and prescribing incremental phases.

### Communication Channel

The agents collaborate asynchronously through a zero-dependency Model Context Protocol (MCP) server located in `tools/handoff-mcp/` (server name: `finance-handoff`).

- **Build Agent Tools:**
  - `read_inbox`: Retrieves latest reviewer instructions from `docs/handoff/TO_AGENT.md`.
  - `post_report`: Posts completed phase engineering reports to `docs/handoff/FROM_AGENT.md`.
  - `wait_for_instructions`: Waits for incoming instructions.
- **Reviewer Agent Tools:**
  - `send_to_agent`: Dispatches phase instructions and corrections to `docs/handoff/TO_AGENT.md`.
  - `read_report`: Reads submitted reports from `docs/handoff/FROM_AGENT.md`.
  - `wait_for_report`: Waits for build agent submissions.

### Iterative Cycle

1. The reviewer agent inspects the codebase against `docs/ANDROID_V8_PHASE_GUIDE.md` and dispatches phase requirements.
2. The build agent reads instructions via `read_inbox`, confirms code conditions, implements changes, and verifies using `./gradlew assembleDebug`.
3. The build agent submits a structured report via `post_report` citing modified files, verification evidence, and edge case handling.
4. The reviewer reviews the implementation directly in the code, issues any required corrections, and authorizes progression to the next phase.

---

## Licence

This project is open source under the MIT License. See the [LICENSE](LICENSE) file. Third party libraries keep their own licences, which are listed inside the app on the Open source licences screen.

---

## Developer

Anuj Kondawar

- Email: kondawaranuj2@gmail.com
- GitHub: https://github.com/anujkdotexe
- Portfolio: https://www.anujkdotexe.in/
- LinkedIn: https://www.linkedin.com/in/anujkondawar/
