# Privacy Policy for Finance Automation

Last updated: November 2026

Finance Automation is built on a local-first architecture. We believe your financial data belongs exclusively to you. This Privacy Policy details how data is handled by the application.

---

## 1. Local-First Processing

All financial notifications and messages are processed locally on your Android device:
- Parsing of transaction details occurs in on-device memory.
- Transaction records are written directly to your designated Google Sheets ledger via the Google Drive and Google Sheets APIs using the minimal `drive.file` OAuth scope.
- Transactions, payees, amounts, account numbers, and categories never pass through any proprietary or third-party servers.

---

## 2. Notification Interception

The app's `NotificationListenerService` operates with strict filtering:
- Only alerts from a hardcoded registry of verified Indian payment apps (e.g., Google Pay, PhonePe, Paytm, BHIM, Supermoney, Amazon Pay, CRED, Navi) and system SMS packages are evaluated.
- SMS debit alerts are accepted only from recognized financial institution sender headers (DLT-compliant uppercase sender formats or registered billers). Messages from personal contacts or phone numbers are dropped immediately based on sender headers without inspecting body text.
- For messaging applications carrying payment notifications (WhatsApp and WhatsApp Business), only notifications specifically categorized by Android notification channels as payment alerts are inspected. Personal chat messages, group messages, calls, media alerts, and OTPs are rejected immediately without parsing or logging their contents.
- Messaging apps without payment categories (e.g., Signal, Telegram, Discord, Instagram) are never read.

---

## 3. Opt-in Technical Diagnostics

Anonymous diagnostic reporting is strictly optional and disabled by default:
- Crashlytics: When opted in, captures crash traces, Android SDK level, device model, and error classification.
- Usage Analytics: When opted in, records high-level event counts (such as onboarding completion, sync error categories, or feature usage) from a strict allow-list.
- Advertising identifiers are completely disabled and omitted from the application manifest.
- Diagnostic events never contain financial transaction amounts, merchant or payee names, custom categories, account numbers, spreadsheet IDs, or notification/SMS message content.

---

## 4. User-Initiated Problem Reports

You may choose to submit a technical problem report to assist in troubleshooting:
- Reports are sent only when you explicitly tap the "Send" button after reviewing the exact technical payload displayed on your screen.
- Reports are never submitted automatically or in background tasks.
- A report includes only:
  - Application version name and version code
  - Device manufacturer, model, and Android SDK level
  - A random 36-character installation identifier (stored locally on device, not associated with your Google account)
  - The name of the screen where the report was initiated
  - A reason code
  - An optional string of diagnostic drop counters (reason=count pairs)
  - An optional description typed by you, with a reminder not to type sensitive personal data
- Reports never contain financial amounts, merchant names, account digits, spreadsheet IDs, or message text.
- Reports are stored securely in Google Cloud Firebase Firestore and are accessible only to the project developer for debugging purposes.

---

## 5. Google API Data & Permissions

The application requests only the `https://www.googleapis.com/auth/drive.file` scope:
- This scope restricts the application to creating and editing only the files it generates.
- The application cannot view, access, or modify other spreadsheets, documents, or files stored in your Google Drive.
- Google user data is not transferred to any external servers or shared with any third parties.

---

## 6. Contact

For technical questions or concerns regarding privacy, please open an issue in the project repository.
