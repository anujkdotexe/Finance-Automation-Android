# Finance Automation Android

Finance Automation Android is the native Android companion and standalone client for the Finance Automation ecosystem. It eliminates manual expense logging friction on Android by combining real-time UPI and banking notification interception with an interactive, 1-tap categorization Bottom Sheet and automated Google Drive and Google Sheets synchronization. All transactions logged strictly adhere to the 28-column master sheet schema and net burn accounting rules established in the ecosystem specification.

---

## Current Status by Phase

- **Phase 0 (Foundation & Fixes):** Complete. Enforces `RAW` value input option for sheet writes, raw unformatted date/time reads, durable transport retry logic with Needs Review cap, per-year workbook resolution (`WorkbookResolver.resolveForYear`), and safe multi-table sync.
- **Phase 1 (Authentication & Onboarding):** Complete. Uses Google Identity Authorization API with minimal `drive.file` scope, automated folder and workbook provisioning, configuration pull before dashboard regeneration, and debug-only notification simulator.
- **Phase 1.5 - 1.7 (Database Encryption):** Complete. Implements SQLCipher AES-256 local database encryption with Keystore-wrapped key in raw hex form, one-time plaintext SQLite migration with table verification, transient Keystore retry guard, and non-crashing canary check.
- **Phase 2 (Sheet Gateway, Layout & Config Sync):** Complete. Ported iOS master template layout, clustered column and doughnut charts with deduplication, workbook properties (`locale = "en_IN"`, `timeZone = "Asia/Kolkata"`), and durable two-way configuration synchronization (`ConfigSyncCoordinator`, `SyncQueueEntity.OP_CONFIG_PUSH`).
- **Phase 3 (Accounting Engine & Room v4):** Complete. Implemented 3-mode split math, netting, settlement waterfall, repayment waterfall, 12-hop debt carry-forward, non-destructive voiding, cross-month debt rollover lineage tracking, two-phase idempotent planJson execution, monthly rollover triggers, and year-close workbook statement generation.
- **Phase 4 (Capture Pipeline & Room v5):** Planned next.
- **Phase 5 (Overlay & User Flows):** Planned.
- **Phase 6+ (Integration & Verification):** Planned.

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
- **Zero Raw Alert Text Stored:** Alerts are parsed in memory. Raw title and body text are never stored in the database, logged, or transmitted off-device.
- **Minimal OAuth Scope:** Requests only `https://www.googleapis.com/auth/drive.file`, restricting app access strictly to workbooks and files created by the application itself.
- **Encrypted Local Storage:** Database is encrypted at rest using SQLCipher with an AES-256 key wrapped in Android Keystore.
- **Zero Secret Commits:** No API keys, client secrets, tokens, or keystores are stored in source code.

---

## How to Build

### Prerequisites

- Java Development Kit (JDK) 17
- Android SDK with API 36 platform and build tools installed
- Git

### Configuration

Copy `.env.example` to `.env` and configure environment variables as needed:

- `GOOGLE_CLIENT_ID`: OAuth client id for optional backend verification.
- `GOOGLE_DRIVE_FOLDER_NAME`: Drive folder name (default: `Personal Finance`).
- `GOOGLE_SHEET_TITLE_PREFIX`: Sheet title prefix (default: `Finance`).
- `GOOGLE_OAUTH_SCOPES`: OAuth scope (default: `https://www.googleapis.com/auth/drive.file`).
- `DEFAULT_ACCOUNT`: Default funding account name.
- `SETTLE_WINDOW_MILLIS`: Settle delay window in milliseconds.
- `MAX_SYNC_RETRY_COUNT`: Maximum sync retry count before Needs Review.

Specify the Android SDK path in `local.properties`:

```properties
sdk.dir=/path/to/android-sdk
```

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
│   │   ├── debug/                          # Debug-only notification simulator receiver
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

