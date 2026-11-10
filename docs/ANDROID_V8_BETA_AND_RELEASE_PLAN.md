# Finance Automation Android: Beta and Release Plan

Status: draft v1. Covers how up to ten friends receive the app, how failures reach the owner, what the owner must do before the final build, and what happens after it. Build agents implement the app side in Phase 9 of `ANDROID_V8_PHASE_GUIDE.md`.

## 1. Decisions (owner approved)

| Topic | Decision |
| :--- | :--- |
| Audience | At most ten friends, each with their own Google account and their own workbook |
| Distribution | Firebase App Distribution, builds uploaded in the web console (no SDK and no Gradle plugin for distribution). Free |
| Crash and freeze reports | Firebase Crashlytics (one SDK), collection off until the user opts in. Free |
| Compatibility data | A beta-only compatibility report the friend can read and share, plus a discovery scan of installed vendor system apps. No server |
| Phone-brand routes | A JSON data file inside the app, not code |
| Build types | `debug` (developer), `beta` (signed with the release key, discovery scan on), `release` (clean) |
| Paid services | None. The 25 USD Play developer account is not used |

One Firebase project holds the tester list, the builds and the crash reports.

## 2. What the owner does before the final build

These are not for build agents. Do them in this order. Each step has the evidence that it is done. After step 12 the project moves to the live device test (engineering plan section 8).

| # | Action | Evidence it is done |
| :-- | :--- | :--- |
| 1 | Create the release keystore with `keytool` (RSA 2048 or larger, validity 25 years or more). Store the file and both passwords in a password manager and keep two backups in different places. Never put it in git | Keystore file exists; two backups exist |
| 2 | Create `keystore.properties` in the project root (git-ignored) with the keystore path, alias and passwords | `git status` does not list it |
| 3 | Read the release SHA-1 with `keytool -list -v -keystore <file>` | SHA-1 written down |
| 4 | In Google Cloud, create a second Android OAuth client for package `com.financeautomation` with the release SHA-1. Keep the debug client | Second client id exists |
| 5 | OAuth consent screen: the app asks only for the `drive.file` scope (non-sensitive). Testing status expires sign-in about every seven days, so after the first successful test switch the screen to "In production". Check Google's current rules for non-sensitive scopes before you do; if you stay in testing, add each friend as a test user | Status chosen; friends listed if still in testing |
| 6 | Create a Firebase project on the free Spark plan. Add an Android app with package `com.financeautomation` | Project exists |
| 7 | Download `google-services.json` and put it in `app/` (git-ignored). Enable Crashlytics and App Distribution in the console | File in place; both products enabled |
| 8 | Create a tester group in App Distribution with the same Gmail addresses (at most ten) | Group exists; friends accepted the invite |
| 9 | Build `assembleBeta` (signed with the release key). Install it on your Realme through `adb` or the tester app, sign in with a test account and complete one expense end to end | Expense visible in the workbook |
| 10 | In the app turn on "Help improve reliability" on your own phone, trigger the built-in test crash button and confirm the crash appears in the Crashlytics console within a few minutes | Crash visible in the console |
| 11 | Upload the beta build in App Distribution, add release notes and send it to the tester group. Send each friend the short instruction text from section 6 | Friends installed |
| 12 | Tidy up: remove any credential values from `.env` that the app no longer uses; confirm `.env`, `keystore.properties`, `google-services.json` and keystores are not tracked (`git ls-files` shows none) | Command output clean |

Updates after the first beta: increase `versionCode`, build `assembleBeta`, upload it in the console. Friends get a notification and install it over the old build, which only works because every build uses the same release key.

## 3. Build types

| Build type | Signing | Discovery scan | Simulator | Crashlytics |
| :--- | :--- | :--- | :--- | :--- |
| `debug` | debug key | on | on | off |
| `beta` | release key from `keystore.properties`; if the file is missing the build falls back to the debug key so every machine can still build | on | off | opt-in |
| `release` | release key | off | off | opt-in |

Rules: the same `applicationId` for all three. `QUERY_ALL_PACKAGES` is declared only in the manifest of `debug` and `beta` (the release manifest must not contain it). The Crashlytics and Google services plugins are applied only when `google-services.json` exists, so the project still builds without it.

## 4. Failure visibility

### 4.1 Crashlytics (automatic)

- Collection is disabled in the manifest (`firebase_crashlytics_collection_enabled` false) and enabled at runtime only after the user turns on "Help improve reliability" in Settings, with one plain sentence: "Sends crash and freeze reports (app version, phone model, Android version, error location). Never amounts, names or messages."
- Custom keys use an allow-list only: app version, brand, model, Android version, setup health summary (counts per status). No free text.
- Non-fatal errors are recorded only as exception class and code place, never with a message.
- Crash data goes to Google's servers; `AGENTS.md` carries one sentence that allows exactly this and nothing more.

### 4.2 Local crash and exit record (always on, never sent by itself)

- An uncaught exception handler stores the last crash locally: exception class, the stack frames of our own code, the time and the app version. It never stores the message text. It then calls the previous handler.
- At app start the app reads Android's own process exit history (`ApplicationExitInfo`, Android 11 and later) and keeps the last five entries: reason, importance, time and the system's short description. This is the main source for brands that stop the app in the background.

### 4.3 Compatibility report (user shared)

A button on the setup health screen opens a preview of the report; the friend reads it and shares it with the normal share menu (WhatsApp, email, a file). It contains only:

- app version, build type, phone brand, model, Android version, ROM display string;
- the setup health rows with their status;
- for each phone-brand route candidate: whether it resolved on this phone, whether it started, and the friend's answer to "Did this open the right screen? Yes / No";
- the last crash record and the last five exit records;
- the capped diagnostics list (exception class and place only);
- in `debug` and `beta` builds, the discovery result (4.4);
- one optional free-text note from the friend.

It never contains amounts, payees, account names, notification text, sheet ids or e-mail addresses.

### 4.4 Discovery scan (debug and beta only)

The scan looks at installed packages whose names start with known vendor prefixes (`com.oplus.`, `com.coloros.`, `com.oppo.`, `com.miui.`, `com.xiaomi.`, `com.samsung.android.`, `com.vivo.`, `com.iqoo.`, `com.oneplus.`, `com.huawei.`, `com.hihonor.`, `com.transsion.`). For each it lists the exported activities whose names contain one of: autostart, startup, launch, battery, power, permission, protect, sleep, optimi, bgstart, associate. Output: package name and activity name only, capped at 200 lines. The friend sees it in the preview before sharing. This is how new phone brands and ROM versions are learned.

### 4.5 Pipeline trail (the main tool for finding where the system breaks)

Crashes are the smaller part of real-device problems. The common failures are silent: an alert never reaches the app, the app drops it, the prompt never opens, the overlay cannot be added, or the app is stopped in the background. To see those, every stage of the capture pipeline writes one short event to a capped local trail (a ring buffer in a small Room table, at most 500 events or 7 days, whichever is smaller). Events carry a stage name, an outcome code, a time and a few numbers. They never carry notification text, amounts, payees or account names. Package names are recorded only for the whitelisted payment apps.

| Stage | Outcome codes (examples) |
| :--- | :--- |
| `listener` | connected, disconnected, rebind_requested, rebind_ok, heartbeat (time of the last callback of any notification, no package, no content) |
| `alert` | whitelisted_seen, dropped_blacklist, dropped_not_whitelisted_count, dropped_otp, dropped_duplicate, failure_wording, parse_unreadable, parse_ok_debit, parse_ok_credit |
| `capture` | created_waiting, awaiting_success, merged, flagged_duplicate, dismissed, done |
| `watcher` | usage_access_missing_show_now, source_app_foreground, two_polls_ok, screen_locked_wait, unlocked_resume, backoff |
| `prompt` | overlay_permission_missing, overlay_shown, overlay_add_failed, overlay_deferred_busy, heads_up_posted, heads_up_blocked_notifications_off, user_saved, user_dismissed |
| `sync` | queued, success, transport_failure, needs_review |
| `device` (snapshot at every app start and every 6 hours) | listener_access_granted, overlay_permission, usage_access, post_notifications, battery_unrestricted, background_restricted, standby_bucket, doze_state, last_exit_reasons |

What this answers: "How many payment alerts reached the app, how many became captures, how many prompts were shown, how many were saved?" (a funnel per day), and "At which stage and with which code did it stop on this phone?". Example findings it will show: the listener disconnects after about forty minutes on one brand (device and listener events), the overlay is blocked on another (prompt overlay_add_failed), the prompt never opens because usage access is missing and the watcher shows immediately.

### 4.6 Report a missed payment

A button on the home screen and in the setup health screen: "A payment did not show a prompt". The friend taps it right after the problem. The report then contains the funnel for today, the trail for the last 30 minutes, the device snapshot and the exit reasons, plus a one-line note. This is the primary way a friend's real-world failure reaches the owner. It uses the same preview and share flow as 4.3, and with crash reporting opted in, the trail summary (counts only) is also attached to Crashlytics as custom keys.

## 5. Phone-brand routes as data

`app/src/main/assets/oem_routes.json`, versioned, parsed and validated at start. A broken file falls back to the generic battery settings and records a diagnostics entry.

```json
{
  "schema": 1,
  "routes": [
    {
      "id": "realme_oplus_battery",
      "match": { "manufacturer": ["realme", "oppo", "oneplus"], "minSdk": 28 },
      "candidates": [
        { "component": "com.oplus.battery/com.oplus.startupapp.view.AssociateStartActivity" },
        { "component": "com.oplus.battery/com.oplus.powermanager.fuelgaue.PowerUsageModelActivity" },
        { "action": "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS" }
      ],
      "instructionsKey": "oem_instructions_oppo"
    }
  ]
}
```

Adding support for a phone means adding or editing one entry; the compatibility report uses the same ids, so a friend's report maps straight to a route. Later the same file can be fetched from a server or remote config without code changes.

## 6. Message to send each friend (template)

"Install the Finance Automation app from the link Firebase emailed you. On first start follow the setup screen: allow notification access, display over other apps, usage access, notifications and battery. If something does not work, open Settings, Setup health, Send compatibility report, read it and send it to me. Turn on 'Help improve reliability' if you are happy to share crash reports; they never contain amounts or names."

## 7. After the final build

1. Live device test on the owner's Realme (engineering plan section 8): back up the old app's data first, then install over it, check the database migration, run the scenarios, grant special permissions with `adb`, send simulated payment alerts from the debug receiver.
2. Fix what the test finds in one round per finding batch, with the same git workflow.
3. Invite the friends in groups of two or three, read their reports, update the routes file, ship the next beta.

## 8. Optional Firebase extras

All free on the Spark plan. None is required for the first beta.

| Extra | What it gives | Cost to the app | When |
| :--- | :--- | :--- | :--- |
| App Distribution release notes, tester groups, invite link | Friends read what changed; a group per wave of testers; anyone with the link joins | none (console only) | first beta |
| App Distribution Gradle or CLI upload | One command uploads and notifies | none in the app (build tool only) | when uploads become frequent |
| Firebase Hosting | A static privacy page and a hosted copy of `oem_routes.json` | none in the SDK sense: the app fetches the file with its existing HTTP client, validates it and falls back to the bundled copy | later, when updating routes without a new build matters |
| Remote Config | Feature switches, a minimum-version nudge, remote routes | adds one SDK | later, wider audience |
| Test Lab (Robo test) | Automatic crawl on real devices with crash report, screenshots and video; a small free daily quota | none | optional, after sign-in can be bypassed by a test account |
| App Distribution in-app alerts and tester feedback | "New build available" inside the app; feedback with screenshot | adds one SDK | skipped; email notifications are enough for ten users |

Not used: Analytics, Performance Monitoring, Cloud Messaging, Firestore, Storage, Authentication (the app signs in with Google directly and needs none of them).

## 9. Privacy summary

Financial data never leaves the phone except to the user's own Google Sheet. Crash reports (only when opted in) and the shared compatibility report contain technical data only. The release build contains no discovery scan and no all-packages permission.
