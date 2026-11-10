/**
 * Personal Finance Automation - Google Apps Script Backend
 * Target Workbook: Finance2026.gsheet
 * Version: 2.3.0
 * 
 * Provides automated web endpoints for Apple Shortcuts expense logging,
 * group splits, debt settlement linking, and automated monthly lifecycle.
 */

// ---------------- CONFIGURATION & CONSTANTS ----------------
// Secrets and configuration are loaded securely from Google Apps Script's native Script Properties.
// This ensures sensitive tokens are never committed or exposed in source control.
// Configure via: Apps Script Editor -> Project Settings (gear icon) -> Script Properties -> Add script property:
//   - SECRET_TOKEN: your private secret token
//   - DRIVE_FOLDER_NAME: Personal Finance (optional, defaults to "Personal Finance")
function getSecretToken_() {
  if (typeof PropertiesService !== "undefined" && PropertiesService.getScriptProperties) {
    return PropertiesService.getScriptProperties().getProperty("SECRET_TOKEN");
  }
  return null;
}

function getDriveFolderName_() {
  if (typeof PropertiesService !== "undefined" && PropertiesService.getScriptProperties) {
    return PropertiesService.getScriptProperties().getProperty("DRIVE_FOLDER_NAME") || "Personal Finance";
  }
  return "Personal Finance";
}

function getSpreadsheetId_() {
  if (typeof PropertiesService !== "undefined" && PropertiesService.getScriptProperties) {
    return PropertiesService.getScriptProperties().getProperty("SPREADSHEET_ID");
  }
  return null;
}

/**
 * Resolves the target Google Spreadsheet dynamically.
 * First attempts to find an annual workbook named "Finance" + YYYY (e.g. Finance2026, Finance2027)
 * inside the configured Drive folder ("Personal Finance").
 * Falls back to any Finance workbook in the folder, SPREADSHEET_ID script property,
 * or SpreadsheetApp.getActiveSpreadsheet().
 */
function getTargetSpreadsheet_(dateStr) {
  let targetYear = new Date().getFullYear();
  if (dateStr) {
    try {
      const [normDate] = parseDateTime(dateStr);
      const p = normDate.split("/");
      if (p.length === 3) {
        let yr = parseInt(p[2]);
        if (yr < 100) yr += 2000;
        targetYear = yr;
      }
    } catch (dateErr) {
      const s = dateStr.toString().trim();
      if (s.indexOf("-") !== -1) {
        const p = s.split("-");
        if (p.length === 3 && p[0].length === 4) targetYear = parseInt(p[0]);
      } else if (s.indexOf("/") !== -1) {
        const p = s.split("/");
        if (p.length === 3) {
          let yr = parseInt(p[2]);
          if (yr < 100) yr += 2000;
          targetYear = yr;
        }
      }
    }
  }

  const folderName = getDriveFolderName_();
  let firstAvailableFinanceFile = null;
  let fallbackYear = 0;

  // Fast path: Check cached spreadsheet ID strictly for the exact target year
  // Shaves 1-2 seconds off every request by skipping Drive folder traversal
  const props = (typeof PropertiesService !== "undefined" && PropertiesService.getScriptProperties) ? PropertiesService.getScriptProperties() : null;
  const cacheKey = "CACHE_SPREADSHEET_ID_" + targetYear;
  const cachedId = props ? props.getProperty(cacheKey) : null;

  if (cachedId) {
    try {
      const ss = SpreadsheetApp.openById(cachedId);
      if (ss) return ss;
    } catch (cacheErr) {
      console.warn("Cached spreadsheet ID lookup failed: " + cacheErr.toString());
      if (props) props.deleteProperty(cacheKey);
    }
  }

  try {
    if (typeof DriveApp !== "undefined") {
      const folderIter = DriveApp.getFoldersByName(folderName);
      while (folderIter.hasNext()) {
        const folder = folderIter.next();
        if (folder.isTrashed()) continue;
        
        // Direct match for target year (exact match only is cached)
        const targetFileName = "Finance" + targetYear;
        const fileIter = folder.getFilesByName(targetFileName);
        while (fileIter.hasNext()) {
          const file = fileIter.next();
          if (!file.isTrashed()) {
            const ss = SpreadsheetApp.openById(file.getId());
            if (props && ss) {
              props.setProperty(cacheKey, file.getId());
            }
            return ss;
          }
        }

        // Secondary search: closest earlier annual workbook named exactly FinanceYYYY
        const allFilesIter = folder.getFiles();
        while (allFilesIter.hasNext()) {
          const f = allFilesIter.next();
          const m = f.isTrashed() ? null : f.getName().match(/^Finance(\d{4})$/);
          if (m) {
            const yr = parseInt(m[1], 10);
            if (yr <= targetYear && yr > fallbackYear) {
              firstAvailableFinanceFile = f;
              fallbackYear = yr;
            }
          }
        }
      }
    }
  } catch (err) {
    console.warn("Dynamic annual sheet resolution fallback: " + err.toString());
  }

  // Fallback 1: Any Finance file found in the folder (not cached under targetYear so new workbook can be discovered)
  if (firstAvailableFinanceFile) {
    console.warn("Finance" + targetYear + " not found; falling back to Finance" + fallbackYear);
    return SpreadsheetApp.openById(firstAvailableFinanceFile.getId());
  }

  // Fallback 2: Configured SPREADSHEET_ID script property
  const propId = getSpreadsheetId_();
  if (propId) {
    try {
      return SpreadsheetApp.openById(propId);
    } catch (e) {
      console.warn("Failed to open spreadsheet by SPREADSHEET_ID property: " + e.toString());
    }
  }

  // Fallback 3: Active container-bound spreadsheet
  const activeSS = SpreadsheetApp.getActiveSpreadsheet();
  if (activeSS) {
    return activeSS;
  }

  throw new Error("Target spreadsheet not found for year " + targetYear + ". Configure SPREADSHEET_ID script property or ensure Finance" + targetYear + " exists in Google Drive folder '" + folderName + "'.");
}

// Short month abbreviations matching monthly tab naming (e.g., Nov26, Dec26)
const MONTH_NAMES = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/**
 * Standard Column Schema Mapping
 * All 3 tables reside side-by-side on each monthly tab.
 * Column indices are 0-based for array manipulation, and 1-based for sheet ranges.
 */
const SCHEMA = {
  DEBIT: {
    START_COL: 1,      // Col A (Column 1)
    NUM_COLS: 11,      // Cols A to K
    DATE: 0,           // Col A (Index 0)
    TIME: 1,           // Col B (Index 1)
    ACCOUNT: 2,        // Col C (Index 2)
    ITEM: 3,           // Col D (Index 3)
    TRIP_TAG: 4,       // Col E (Index 4)
    CATEGORY: 5,       // Col F (Index 5)
    TOTAL_AMOUNT: 6,   // Col G (Index 6)
    COMPANIONS: 7,     // Col H (Index 7)
    MY_SHARE: 8,       // Col I (Index 8)
    OWED_TO_ME: 9,     // Col J (Index 9)
    STATUS: 10         // Col K (Index 10)
  },
  SPACER_1: {
    COL: 12            // Col L (Column 12)
  },
  CREDIT: {
    START_COL: 13,     // Col M (Column 13)
    NUM_COLS: 8,       // Cols M to T
    DATE: 0,           // Col M (Index 0)
    TIME: 1,           // Col N (Index 1)
    ACCOUNT: 2,        // Col O (Index 2)
    INFLOW_TYPE: 3,    // Col P (Index 3)
    RECEIVED_FROM: 4,  // Col Q (Index 4)
    REASON: 5,         // Col R (Index 5)
    AMOUNT: 6,         // Col S (Index 6)
    LINKED_ROW: 7      // Col T (Index 7)
  },
  SPACER_2: {
    COL: 21            // Col U (Column 21)
  },
  PAYABLE: {
    START_COL: 22,     // Col V (Column 22)
    NUM_COLS: 7,       // Cols V to AB
    DATE_INCURRED: 0,  // Col V (Index 0)
    TIME: 1,           // Col W (Index 1)
    PAID_BY: 2,        // Col X (Index 2)
    WHAT_FOR: 3,       // Col Y (Index 3)
    AMOUNT_OWED: 4,    // Col Z (Index 4)
    DATE_REPAID: 5,    // Col AA (Index 5)
    STATUS: 6          // Col AB (Index 6)
  }
};

// ---------------- MAIN ROUTER (Single doPost) ----------------
/**
 * Main Webhook POST handler for all Apple Shortcuts actions.
 * Authenticates via SECRET_TOKEN and delegates to internal domain handlers.
 * Concurrency protected via LockService.
 * 
 * @param {Object} e HTTP POST event object
 * @return {TextOutput} JSON response
 */
function doPost(e) {
  let payload = null;

  // Handle incoming data formats: JSON string, URL-encoded form data, or raw file payload
  if (e && e.postData && e.postData.contents) {
    try {
      payload = JSON.parse(e.postData.contents);
    } catch (jsonErr) {
      if (e.parameter && e.parameter.data) {
        try {
          payload = JSON.parse(e.parameter.data);
        } catch (e2) {}
      }
      if (!payload && e.parameter && Object.keys(e.parameter).length > 0) {
        payload = e.parameter;
      }
    }
  } else if (e && e.parameter && Object.keys(e.parameter).length > 0) {
    payload = e.parameter;
  }

  if (!payload) {
    return responseJSON({ status: "error", message: "No payload provided or unparseable" }, 400);
  }

  // Security Authentication Check BEFORE acquiring Lock (prevents unauthorized lock starvation)
  const expectedToken = getSecretToken_();
  const incomingToken = payload.token || (e.parameter ? e.parameter.token : null);
  if (!expectedToken || !incomingToken || incomingToken.toString().trim() !== expectedToken.toString().trim()) {
    return responseJSON({ status: "error", message: "Unauthorized" }, 401);
  }

  // Acquire concurrency lock only for authenticated requests
  const lock = LockService.getScriptLock();
  const hasLock = lock.tryLock(30000);
  if (!hasLock) {
    return responseJSON({ status: "error", message: "Server busy: Could not acquire lock after 30 seconds" }, 503);
  }

  try {
    const txnDate = payload.date || (payload.data ? payload.data.date : null);
    const ss = getTargetSpreadsheet_(txnDate);
    const action = payload.action;

    // Action Dispatcher
    switch (action) {
      case "log_debit":
        return handleLogDebit(payload, ss);
      case "log_credit":
        return handleLogCredit(payload, ss);
      case "log_payable":
        return handleLogPayable(payload, ss);
      case "log_repayment":
        return handleLogRepayment(payload, ss);
      case "sync_queue":
        return handleSyncQueue(payload, ss);
      case "get_pending_debts":
        return handleGetPendingDebts(payload, ss);
      case "get_pending_payables":
        return handleGetPendingPayables(payload, ss);
      case "get_top_friends":
        return handleGetTopFriends(ss);
      case "get_all_people":
        return handleGetAllPeople(ss);
      case "repair_sheets":
        return handleRepairSheets(ss);
      case "inspect_dashboard":
        return handleInspectDashboard(ss);
      case "year_close":
        return handleYearClose(ss);
      default:
        return responseJSON({ status: "error", message: "Unknown action: " + action }, 400);
    }
  } catch (err) {
    console.error("doPost internal error: " + (err.stack || err.toString()));
    return responseJSON({ status: "error", message: err.message || err.toString() }, 500);
  } finally {
    lock.releaseLock();
  }
}

/**
 * Health check GET handler to confirm the Web App is online.
 */
function doGet(e) {
  return responseJSON({
    status: "success",
    version: "2.3.0",
    message: "FinanceAutomation Web App is active and healthy."
  });
}

// ---------------- MONTH TAB PROVISIONING & ROLLOVER ----------------
/**
 * Retrieves the target month sheet based on transaction date.
 * If the sheet does not exist, duplicates the clean Template, sets headers,
 * and carries forward unsettled debts from the preceding month.
 * 
 * @param {Spreadsheet} ss Active Google Spreadsheet
 * @param {string} dateStr DD/MM/YYYY date string
 * @return {Sheet} The month's ledger sheet
 */
function getOrCreateMonthSheet(ss, dateStr) {
  let targetDate = new Date();
  if (dateStr) {
    if (dateStr instanceof Date) {
      targetDate = dateStr;
    } else {
      const [normDate, normTime, normDateObj] = parseDateTime(dateStr);
      if (normDateObj && !isNaN(normDateObj.getTime())) {
        targetDate = normDateObj;
      }
    }
  }

  const monthIdx = targetDate.getMonth();
  const yearShort = targetDate.getFullYear().toString().slice(-2);
  const sheetName = MONTH_NAMES[monthIdx] + yearShort; // e.g., Nov26

  let sheet = ss.getSheetByName(sheetName);
  if (sheet) {
    return sheet;
  }

  // Duplicate from Template
  const template = ss.getSheetByName("Template");
  if (!template) {
    throw new Error("Template sheet not found. Ensure a tab named 'Template' exists.");
  }

  sheet = template.copyTo(ss).setName(sheetName);
  sheet.showSheet();

  // Set Banner
  sheet.getRange("A1").setValue("EXPENSE LEDGER — " + MONTH_NAMES[monthIdx].toUpperCase() + " 20" + yearShort);

  // Clear any existing sample data below header row 3
  const lastRow = sheet.getLastRow();
  if (lastRow > 3) {
    sheet.getRange(4, 1, lastRow - 3, 28).clearContent();
  }

  // Carry forward open balances from the prior month
  carryForwardUnsettled(ss, sheet, monthIdx, targetDate.getFullYear());

  return sheet;
}

/**
 * Automatically carries forward pending Receivables and Payables from previous month.
 * Appended rows are tagged with '[Carried from MmmYY]' for audit tracking.
 */
function carryForwardUnsettled(ss, currentSheet, currentMonthIdx, currentYear) {
  let prevSheet = null;
  let prevSheetName = "";

  // Search backward across up to 12 previous months for the most recent existing ledger tab
  for (let step = 1; step <= 12; step++) {
    let checkMonthIdx = currentMonthIdx - step;
    let checkYear = currentYear;
    while (checkMonthIdx < 0) {
      checkMonthIdx += 12;
      checkYear -= 1;
    }
    const candidateName = MONTH_NAMES[checkMonthIdx] + checkYear.toString().slice(-2);
    let candidateSheet = ss.getSheetByName(candidateName);

    // Cross-annual rollover bridge: If candidate month belongs to prior year, search prior year workbook if needed
    if (!candidateSheet && checkYear < currentYear) {
      try {
        const priorSS = getTargetSpreadsheet_("31/12/" + checkYear);
        if (priorSS && priorSS.getId() !== ss.getId()) {
          candidateSheet = priorSS.getSheetByName(candidateName);
        }
      } catch (e) {
        console.warn("Could not load prior year workbook for rollover search: " + e.toString());
      }
    }

    if (candidateSheet) {
      prevSheet = candidateSheet;
      prevSheetName = candidateName;
      break;
    }
  }

  if (!prevSheet) return;

  const prevLastRow = prevSheet.getLastRow();
  if (prevLastRow <= 3) return;

  const todayStr = Utilities.formatDate(new Date(), Session.getScriptTimeZone(), "dd/MM/yyyy");

  // 1. Scan Debits (Table 1: Cols A to K) for Status = "Pending"
  const debitData = prevSheet.getRange(4, SCHEMA.DEBIT.START_COL, prevLastRow - 3, SCHEMA.DEBIT.NUM_COLS).getValues();
  debitData.forEach((row, idx) => {
    const status = row[SCHEMA.DEBIT.STATUS];
    const owed = parseFloat(row[SCHEMA.DEBIT.OWED_TO_ME]) || 0;
    if (status === "Pending" && owed > 0) {
      const desc = "[Carried from " + prevSheetName + "] " + row[SCHEMA.DEBIT.ITEM];

      // Preserve original date & time from prior transaction for accurate audit trail
      let origDateObj = new Date();
      const rawDate = row[SCHEMA.DEBIT.DATE];
      if (rawDate instanceof Date) {
        origDateObj = rawDate;
      } else if (rawDate) {
        const [parsedDate, parsedTime, parsedDateObj] = parseDateTime(rawDate);
        if (parsedDateObj && !isNaN(parsedDateObj.getTime())) origDateObj = parsedDateObj;
      }
      const origTime = row[SCHEMA.DEBIT.TIME] || "12:00 AM";

      const nextRow = findNextEmptyRowForCols(currentSheet, SCHEMA.DEBIT.START_COL, SCHEMA.DEBIT.START_COL + SCHEMA.DEBIT.NUM_COLS - 1);
      currentSheet.getRange(nextRow, SCHEMA.DEBIT.START_COL, 1, SCHEMA.DEBIT.NUM_COLS).setValues([[
        origDateObj,
        origTime,
        row[SCHEMA.DEBIT.ACCOUNT],
        desc,
        row[SCHEMA.DEBIT.TRIP_TAG],
        row[SCHEMA.DEBIT.CATEGORY],
        owed,                                // Total Amount = owed so Dashboard A27 (G - I) shows original debt correctly
        row[SCHEMA.DEBIT.COMPANIONS],
        0,                                   // My Share is 0 to prevent double-counting in Net Burn
        owed,                                // Owed To Me
        "Pending"
      ]]);
      currentSheet.getRange(nextRow, SCHEMA.DEBIT.START_COL, 1, SCHEMA.DEBIT.NUM_COLS).setNumberFormats([[
        "dd/mm/yyyy",
        "hh:mm am/pm",
        "@",
        "@",
        "@",
        "@",
        "₹#,##0",
        "@",
        "₹#,##0",
        "₹#,##0",
        "@"
      ]]);

      // Mark previous month record as Carried Fwd to prevent duplicate settlements
      prevSheet.getRange(4 + idx, SCHEMA.DEBIT.START_COL + SCHEMA.DEBIT.STATUS).setValue("Carried Fwd");
    }
  });

  // 2. Scan Payables (Table 3: Cols V to AB) for Status = "Pending" or "Partial"
  const payablesData = prevSheet.getRange(4, SCHEMA.PAYABLE.START_COL, prevLastRow - 3, SCHEMA.PAYABLE.NUM_COLS).getValues();
  payablesData.forEach((row, idx) => {
    const status = row[SCHEMA.PAYABLE.STATUS];
    const amountOwed = parseFloat(row[SCHEMA.PAYABLE.AMOUNT_OWED]) || 0;
    if ((status === "Pending" || status === "Partial") && amountOwed > 0) {
      const whatFor = "[Carried from " + prevSheetName + "] " + row[SCHEMA.PAYABLE.WHAT_FOR];
      let origDateObj = new Date();
      const rawDate = row[SCHEMA.PAYABLE.DATE_INCURRED];
      if (rawDate instanceof Date) {
        origDateObj = rawDate;
      } else if (rawDate) {
        const [parsedDate, parsedTime, parsedDateObj] = parseDateTime(rawDate);
        if (parsedDateObj && !isNaN(parsedDateObj.getTime())) origDateObj = parsedDateObj;
      }
      const origTime = row[SCHEMA.PAYABLE.TIME] || "12:00 AM";
      const nextRow = findNextEmptyRowForCols(currentSheet, SCHEMA.PAYABLE.START_COL, SCHEMA.PAYABLE.START_COL + SCHEMA.PAYABLE.NUM_COLS - 1);
      currentSheet.getRange(nextRow, SCHEMA.PAYABLE.START_COL, 1, SCHEMA.PAYABLE.NUM_COLS).setValues([[
        origDateObj,
        origTime,
        row[SCHEMA.PAYABLE.PAID_BY],
        whatFor,
        amountOwed,
        "",
        "Pending"
      ]]);
      currentSheet.getRange(nextRow, SCHEMA.PAYABLE.START_COL, 1, SCHEMA.PAYABLE.NUM_COLS).setNumberFormats([[
        "dd/mm/yyyy",
        "hh:mm am/pm",
        "@",
        "@",
        "₹#,##0",
        "dd/mm/yyyy",
        "@"
      ]]);

      // Mark previous month payable as Carried Fwd
      prevSheet.getRange(4 + idx, SCHEMA.PAYABLE.START_COL + SCHEMA.PAYABLE.STATUS).setValue("Carried Fwd");
    }
  });
}

/**
 * Utility helper to safely parse numeric amount values, stripping commas, currency symbols, and whitespace.
 * Returns null if invalid, empty, or NaN.
 */
function parseAmount_(val) {
  if (val === null || val === undefined || val === "") return null;
  if (Array.isArray(val) || typeof val === "object") return null;
  if (typeof val === "number") return (isNaN(val) || !isFinite(val)) ? null : Math.round(val * 100) / 100;
  let s = val.toString().trim();
  s = s.replace(/^(?:₹|inr|rs\.?)\s*/i, "").replace(/\s*(?:₹|inr|rs\.?)$/i, "").trim();
  s = s.replace(/,/g, "").trim();
  if (!/^[0-9]+(?:\.[0-9]+)?$/.test(s)) return null;
  const num = parseFloat(s);
  if (isNaN(num) || !isFinite(num)) return null;
  return Math.round(num * 100) / 100;
}

/**
 * Rounds to 2 decimals.
 */
function round2_(n) {
  return Math.round(n * 100) / 100;
}

/**
 * Sanitizes cell text to prevent formula injection in Google Sheets.
 */
function sanitizeCellText_(val) {
  if (val === null || val === undefined) return "";
  const s = val.toString().trim();
  if (/^[=\+\-@]/.test(s)) {
    return "'" + s;
  }
  return s;
}

/**
 * Idempotency guard. A replayed request (offline queue re-flush, retry after a lost response)
 * carries the same payload, including the ISO timestamp with seconds, so it hashes to the same key.
 * Keys live in Script Properties and are pruned after 30 days.
 */
function buildIdemKey_(action, d) {
  let raw = "";
  if (d.request_id) {
    raw = "rid|" + d.request_id;
  } else {
    const when = (d.date || d.date_repaid || "").toString().trim();
    // Only lock when timestamp has explicit hours/minutes (prevents blocking separate same-day purchases)
    if (!when || when.indexOf(":") === -1) return "";
    raw = [action, when, d.total_amount || d.amount || d.amount_owed || "", d.item || d.what_for || "",
           d.person || d.received_from || d.recieved_from || d.paid_by || "", d.account || "",
           d.companions || "", d.linked_debit_row || ""].join("|");
  }
  if (typeof Utilities !== "undefined" && Utilities.computeDigest && Utilities.DigestAlgorithm) {
    const bytes = Utilities.computeDigest(Utilities.DigestAlgorithm.MD5, raw);
    return bytes.map(b => ("0" + (b < 0 ? b + 256 : b).toString(16)).slice(-2)).join("");
  }
  return raw.slice(0, 100);
}

function isDuplicateRequest_(key) {
  if (!key || typeof PropertiesService === "undefined") return false;
  return !!PropertiesService.getScriptProperties().getProperty("idem_" + key);
}

function markRequestProcessed_(key) {
  if (!key || typeof PropertiesService === "undefined") return;
  const props = PropertiesService.getScriptProperties();
  const now = Date.now();
  props.setProperty("idem_" + key, String(now));
  try {
    const all = props.getProperties();
    Object.keys(all).forEach(k => {
      if (k.indexOf("idem_") === 0 && now - parseInt(all[k], 10) > 30 * 24 * 3600 * 1000) props.deleteProperty(k);
    });
  } catch (e) {
    console.warn("Idempotency prune skipped: " + e.toString());
  }
}

/**
 * Month ledger tabs (MmmYY) in chronological order. Used by every FIFO waterfall so "oldest first"
 * is real, not tab-order. Non-month tabs (Dashboard, Template, CarryFwd) are excluded.
 */
function getLedgerSheetsSorted_(ss) {
  const skip = new Set(["Dashboard", "Template", "CarryFwd"]);
  const keyOf = (name) => {
    const m = name.match(/^([A-Z][a-z]{2})(\d{2})$/);
    if (!m) return Infinity;
    const mi = MONTH_NAMES.indexOf(m[1]);
    return mi < 0 ? Infinity : (2000 + parseInt(m[2], 10)) * 12 + mi;
  };
  return ss.getSheets()
    .filter(s => !skip.has(s.getName()))
    .map((s, i) => ({ s: s, i: i, k: keyOf(s.getName()) }))
    .sort((a, b) => (a.k - b.k) || (a.i - b.i))
    .map(x => x.s);
}

/**
 * Follows "Carried Fwd" rows through any number of month rollovers to the live copy.
 * descCol/statusCol are absolute 1-based columns of the table being resolved.
 */
function resolveCarriedRow_(ss, sheet, row, descCol, statusCol) {
  let curSheet = sheet;
  let curRow = row;
  for (let hop = 0; hop < 12; hop++) {
    const status = (curSheet.getRange(curRow, statusCol).getValue() || "").toString().trim();
    if (status !== "Carried Fwd") break;
    const desc = (curSheet.getRange(curRow, descCol).getValue() || "").toString().trim();
    const tag = "[Carried from " + curSheet.getName() + "] " + desc;
    let found = null;
    for (const s of getLedgerSheetsSorted_(ss)) {
      if (s.getName() === curSheet.getName()) continue;
      const lastRow = s.getLastRow();
      if (lastRow <= 3) continue;
      const items = s.getRange(4, descCol, lastRow - 3, 1).getValues();
      const idx = items.findIndex(r => (r[0] || "").toString().trim() === tag);
      if (idx !== -1) { found = { sheet: s, row: 4 + idx }; break; }
    }
    if (!found) break;
    curSheet = found.sheet;
    curRow = found.row;
  }
  return { sheet: curSheet, row: curRow };
}

/**
 * Applies a repayment to one Payables row. For a shared row ("Papa, Mummy") a creditor is only
 * paid up to their own share and is only removed from "Paid By" once that share is covered.
 * Returns { applied, newOwed, status } or null when the row is not an open payable for this creditor.
 */
function applyRepaymentToPayableRow_(sheet, row, cNameClean, remaining, dateOnly) {
  const statusCol = SCHEMA.PAYABLE.START_COL + SCHEMA.PAYABLE.STATUS;
  const owedCol = SCHEMA.PAYABLE.START_COL + SCHEMA.PAYABLE.AMOUNT_OWED;
  const paidByCol = SCHEMA.PAYABLE.START_COL + SCHEMA.PAYABLE.PAID_BY;
  const status = (sheet.getRange(row, statusCol).getValue() || "").toString().trim();
  if (status !== "Pending" && status !== "Partial") return null;

  const currentOwed = parseFloat(sheet.getRange(row, owedCol).getValue()) || 0;
  if (currentOwed <= 0) return null;

  const tokens = (sheet.getRange(row, paidByCol).getValue() || "").toString().trim()
    .replace(/\s+(&|and)\s+/gi, ", ").split(",").map(t => t.trim()).filter(Boolean);
  const matchIdx = cNameClean ? tokens.findIndex(t => t.toLowerCase() === cNameClean) : -1;
  if (cNameClean && matchIdx === -1) return null;

  const isShared = tokens.length > 1 && matchIdx !== -1;
  const share = isShared ? round2_(currentOwed / tokens.length) : currentOwed;
  const applied = round2_(Math.min(remaining, share, currentOwed));
  if (applied <= 0) return null;
  const newOwed = Math.max(0, round2_(currentOwed - applied));

  const dateVal = (dateOnly instanceof Date) ? dateOnly : (parseDateTime(dateOnly)[2] || dateOnly);
  sheet.getRange(row, SCHEMA.PAYABLE.START_COL + SCHEMA.PAYABLE.DATE_REPAID).setValue(dateVal).setNumberFormat("dd/mm/yyyy");
  if (isShared && applied >= share - 0.05) {
    tokens.splice(matchIdx, 1);
    sheet.getRange(row, paidByCol).setValue(tokens.join(", "));
  }
  sheet.getRange(row, owedCol).setValue(newOwed);
  sheet.getRange(row, statusCol).setValue(newOwed > 0 ? "Partial" : "Repaid");
  return { applied: applied, newOwed: newOwed, status: newOwed > 0 ? "Partial" : "Repaid" };
}

/**
 * Splits raw date/time strings into [Date, Time] array.
 * Robustly parses slash dates, ISO timestamps, comma formats, and iOS medium strings ("2 Nov 2026 at 4:30 PM").
 * Cleans non-breaking and narrow spaces to prevent Sheets parsing errors.
 * Defaults to current timestamp if unparseable.
 */
function parseDateTime(raw) {
  const now = new Date();
  const timeZone = Session.getScriptTimeZone();
  const defaultDate = Utilities.formatDate(now, timeZone, "dd/MM/yyyy");
  const defaultTime = Utilities.formatDate(now, timeZone, "hh:mm a");

  const midnightNow = new Date(now.getFullYear(), now.getMonth(), now.getDate(), 0, 0, 0, 0);

  if (!raw) return [defaultDate, defaultTime, midnightNow];

  if (raw instanceof Date) {
    const pureDate = new Date(raw.getFullYear(), raw.getMonth(), raw.getDate(), 0, 0, 0, 0);
    return [
      Utilities.formatDate(raw, timeZone, "dd/MM/yyyy"),
      Utilities.formatDate(raw, timeZone, "hh:mm a"),
      pureDate
    ];
  }

  // Replace narrow no-break space (\u202F) and non-breaking space (\u00A0) with standard ASCII space
  const s = raw.toString().trim().replace(/[\u202F\u00A0]/g, " ");
  let datePart = "";
  let timePart = defaultTime;

  // Case 1: "2 Nov 2026 at 4:30 PM" (iOS medium format with ' at ')
  if (s.indexOf(" at ") !== -1) {
    const atParts = s.split(" at ");
    const parsedDate = new Date(atParts[0].trim());
    if (!isNaN(parsedDate.getTime()) && parsedDate.getFullYear() > 2000) {
      datePart = Utilities.formatDate(parsedDate, timeZone, "dd/MM/yyyy");
    } else {
      datePart = atParts[0].trim();
    }
    timePart = atParts[1].trim();
  }
  // Case 2: Comma separation: "03/11/2026, 09:30 AM" or "Nov 3, 2026, 9:30 AM"
  else if (s.indexOf(",") !== -1) {
    const parts = s.split(",");
    if (parts.length === 2) {
      datePart = parts[0].trim();
      timePart = parts[1].trim();
    } else if (parts.length >= 3) {
      datePart = parts[0].trim() + ", " + parts[1].trim();
      timePart = parts.slice(2).join(",").trim();
    }
  }
  // Case 3: ISO 8601 strings: "2026-11-03T04:00:00.000Z"
  else if (s.indexOf("T") !== -1 && s.indexOf("-") !== -1) {
    const isoDate = new Date(s);
    if (!isNaN(isoDate.getTime())) {
      const pureDate = new Date(isoDate.getFullYear(), isoDate.getMonth(), isoDate.getDate(), 0, 0, 0, 0);
      return [
        Utilities.formatDate(isoDate, timeZone, "dd/MM/yyyy"),
        Utilities.formatDate(isoDate, timeZone, "hh:mm a"),
        pureDate
      ];
    }
  }
  // Case 4: Space-separated: "DD/MM/YYYY hh:mm a"
  else if (s.indexOf(" ") !== -1) {
    const spaceIdx = s.indexOf(" ");
    datePart = s.substring(0, spaceIdx).trim();
    timePart = s.substring(spaceIdx + 1).trim();
  } else {
    datePart = s;
  }

  // Normalize slash dates: handle MM/DD/YYYY vs DD/MM/YYYY and 2-digit years
  if (datePart.indexOf("/") !== -1) {
    const slashParts = datePart.split("/");
    if (slashParts.length === 3) {
      let t1 = parseInt(slashParts[0], 10);
      let t2 = parseInt(slashParts[1], 10);
      let yr = parseInt(slashParts[2], 10);
      if (yr < 100) yr += 2000;

      let day = t1;
      let month = t2;

      if (t1 > 12) {
        // First token > 12: must be day (DD/MM/YYYY)
        day = t1;
        month = t2;
      } else if (t2 > 12) {
        // Second token > 12: must be day (MM/DD/YYYY)
        day = t2;
        month = t1;
      } else {
        // Standard default in India and iOS shortcuts: DD/MM/YYYY
        day = t1;
        month = t2;
      }

      datePart = String(day).padStart(2, "0") + "/" + String(month).padStart(2, "0") + "/" + yr;
    }
  } else if (datePart.indexOf("-") !== -1) {
    const dashParts = datePart.split("-");
    if (dashParts.length === 3 && dashParts[0].length === 4) {
      // YYYY-MM-DD
      const yr = dashParts[0];
      const month = String(parseInt(dashParts[1], 10)).padStart(2, "0");
      const day = String(parseInt(dashParts[2], 10)).padStart(2, "0");
      datePart = day + "/" + month + "/" + yr;
    }
  } else {
    // If datePart is a textual date like "3 Nov 2026"
    const parsed = new Date(datePart);
    if (!isNaN(parsed.getTime()) && parsed.getFullYear() > 2000) {
      datePart = Utilities.formatDate(parsed, timeZone, "dd/MM/yyyy");
    }
  }

  // Ensure timePart has non-breaking spaces removed and is trimmed
  timePart = timePart.replace(/[\u202F\u00A0]/g, " ").trim();

  // Construct native Date object from validated datePart (DD/MM/YYYY) truncated strictly to midnight
  let dateObj = midnightNow;
  if (datePart && datePart.indexOf("/") !== -1) {
    const p = datePart.split("/");
    if (p.length === 3) {
      const d = parseInt(p[0], 10);
      const m = parseInt(p[1], 10) - 1;
      let y = parseInt(p[2], 10);
      if (y < 100) y += 2000;
      dateObj = new Date(y, m, d, 0, 0, 0, 0);
    }
  }

  return [datePart, timePart, dateObj];
}

// ---------------- ACTION HANDLERS ----------------

/**
 * Action: log_debit
 * Logs an expense outflow into Table 1 (Cols A to K).
 */
function handleLogDebit(payload, ss) {
  const d = (payload.data && typeof payload.data === "object") ? payload.data : payload;
  const [dateOnly, timeOnly, dateObj] = parseDateTime(d.date);

  const totalAmount = parseAmount_(d.total_amount);
  if (totalAmount === null || totalAmount <= 0) {
    return responseJSON({ status: "error", message: "Invalid or zero total_amount provided" }, 400);
  }

  // Idempotency check BEFORE any mutation (netting below consumes payables)
  const idemKey = buildIdemKey_("log_debit", d);
  if (isDuplicateRequest_(idemKey)) {
    return responseJSON({ status: "success", message: "Duplicate debit detected; skipped duplicate insertion.", duplicate: true });
  }

  const sheet = getOrCreateMonthSheet(ss, dateObj || dateOnly);

  let myShare = parseAmount_(d.my_share);
  if (myShare === null) {
    myShare = totalAmount;
  }
  // Clamp myShare between 0 and totalAmount (cannot exceed total amount or be negative)
  myShare = Math.min(Math.max(0, myShare), totalAmount);

  let owedToMe = parseAmount_(d.owed_to_me);
  if (owedToMe === null) {
    owedToMe = Math.round((totalAmount - myShare) * 100) / 100;
  }
  // Owed to me can never be negative
  owedToMe = Math.max(0, Math.round((totalAmount - myShare) * 100) / 100);

  let companions = (d.companions || "Personal").toString().trim();

  // Multi-companion equal split handling:
  if (companions.toLowerCase() !== "personal") {
    const normalizedCompanions = companions.replace(/\s+(&|and)\s+/gi, ", ");
    const tokens = normalizedCompanions.split(",").map(c => c.trim()).filter(Boolean);
    if (tokens.length >= 2) {
      companions = tokens.join(", ");
      const totalHeads = tokens.length + 1; // Companions + Me

      // Only auto-correct equal split if split_mode is explicitly 'equal' (or contains 'equal').
      // Never overrule custom splits or unstated split modes with heuristics.
      const isExplicitEqual = (d.split_mode && d.split_mode.toString().toLowerCase().indexOf("equal") !== -1);
      if (isExplicitEqual) {
        myShare = Math.round((totalAmount / totalHeads) * 100) / 100;
        owedToMe = Math.round((totalAmount - myShare) * 100) / 100;
      }
    }
  }

  // Ensure myShare and owedToMe always sum exactly to totalAmount
  if (Math.abs((myShare + owedToMe) - totalAmount) > 0.01) {
    owedToMe = Math.round((totalAmount - myShare) * 100) / 100;
  }

  // Bilateral Debt Netting:
  // If I currently owe money in Table 3 (Payables) to any of the companions in this expense,
  // automatically offset that debt against the new owed_to_me amount!
  let offsetResult = null;
  if (owedToMe > 0 && companions.toLowerCase() !== "personal") {
    offsetResult = offsetDebitsAgainstPayables(ss, companions, owedToMe, dateOnly);
    if (offsetResult && offsetResult.offsetTotal > 0) {
      owedToMe = offsetResult.netOwedToMe;
    }
  }

  // Derive status strictly based on remaining owedToMe amount:
  // If owedToMe is zero (e.g. personal expense or custom split where myShare == totalAmount),
  // status must NEVER be "Pending"! It is "Personal" (or "Settled" if fully offset/settled).
  let derivedStatus = "Personal";
  if (owedToMe > 0.009) {
    derivedStatus = (d.status && d.status !== "Settled" && d.status !== "Personal") ? d.status.toString().trim() : "Pending";
  } else {
    if (offsetResult && offsetResult.offsetTotal > 0) {
      derivedStatus = "Settled";
    } else if (d.status === "Settled") {
      derivedStatus = "Settled";
    } else {
      derivedStatus = "Personal";
    }
  }
  const status = derivedStatus;

  let category = (d.category || "").toString().trim();
  if (category.toLowerCase() === "saving") {
    category = "Savings";
  }

  const cleanAccount = sanitizeCellText_(d.account || "");
  const cleanItem = sanitizeCellText_(d.item || "");

  const rowData = [
    dateObj || dateOnly,
    timeOnly,
    cleanAccount,
    cleanItem,
    sanitizeCellText_(d.trip_tag || ""),
    sanitizeCellText_(category),
    totalAmount,
    sanitizeCellText_(companions),
    myShare,
    owedToMe,
    status
  ];

  const nextRow = findNextEmptyRowForCols(sheet, SCHEMA.DEBIT.START_COL, SCHEMA.DEBIT.START_COL + SCHEMA.DEBIT.NUM_COLS - 1);
  sheet.getRange(nextRow, SCHEMA.DEBIT.START_COL, 1, SCHEMA.DEBIT.NUM_COLS).setValues([rowData]);
  sheet.getRange(nextRow, SCHEMA.DEBIT.START_COL, 1, SCHEMA.DEBIT.NUM_COLS).setNumberFormats([[
    "dd/mm/yyyy",
    "hh:mm am/pm",
    "@",
    "@",
    "@",
    "@",
    "₹#,##0",
    "@",
    "₹#,##0",
    "₹#,##0",
    "@"
  ]]);

  markRequestProcessed_(idemKey);

  let debitMsg = "Debit logged successfully";
  if (offsetResult && offsetResult.offsetTotal > 0) {
    debitMsg += " (₹" + offsetResult.offsetTotal + " offset against existing payables to " + companions + ")";
  }

  return responseJSON({
    status: "success",
    message: debitMsg,
    sheet: sheet.getName(),
    rowIndex: nextRow,
    netOwedToMe: owedToMe,
    offsetTotal: offsetResult ? offsetResult.offsetTotal : 0
  });
}

/**
 * Action: log_credit
 * Logs an incoming money transaction into Table 2 (Cols M to T).
 * Friend settlements run the cascading waterfall: explicitly selected bills first (in the order
 * selected), then the friend's other open debts oldest-first. Surplus is only turned into a payable
 * when at least one real debt of that friend was settled; otherwise the credit stays unlinked.
 */
function handleLogCredit(payload, ss) {
  const d = (payload.data && typeof payload.data === "object") ? payload.data : payload;
  const [dateOnly, timeOnly, dateObj] = parseDateTime(d.date);

  const amountReceived = parseAmount_(d.amount);
  if (amountReceived === null || amountReceived <= 0) {
    return responseJSON({ status: "error", message: "Invalid or zero amount provided" }, 400);
  }

  const idemKey = buildIdemKey_("log_credit", d);
  if (isDuplicateRequest_(idemKey)) {
    return responseJSON({ status: "success", message: "Duplicate credit detected; skipped duplicate insertion.", duplicate: true });
  }

  const sheet = getOrCreateMonthSheet(ss, dateObj || dateOnly);
  const inflowType = (d.inflow_type || "").toString().trim();
  const receivedFrom = (d.received_from || d.recieved_from || "").toString().trim();
  const reason = (d.reason || "").toString().trim();
  const cleanAccount = (d.account || "").toString().trim();
  const rawLinkedRef = d.linked_debit_row || "";
  const selectedRefs = extractAllRowReferences(rawLinkedRef);

  const startCol = SCHEMA.CREDIT.START_COL;
  const numCols = SCHEMA.CREDIT.NUM_COLS;
  const nextRow = findNextEmptyRowForCols(sheet, startCol, startCol + numCols - 1);

  sheet.getRange(nextRow, startCol, 1, numCols).setValues([[
    dateObj || dateOnly,
    timeOnly,
    sanitizeCellText_(cleanAccount),
    sanitizeCellText_(inflowType),
    sanitizeCellText_(receivedFrom),
    sanitizeCellText_(reason),
    amountReceived,
    selectedRefs.join(", ")
  ]]);

  sheet.getRange(nextRow, startCol, 1, numCols).setNumberFormats([[
    "dd/mm/yyyy",
    "hh:mm am/pm",
    "@",
    "@",
    "@",
    "@",
    "₹#,##0",
    "@"
  ]]);

  let excessAmount = 0;
  let waterfallResult = null;
  const isSettlement = (inflowType.toLowerCase() === "friend settlement") || selectedRefs.length > 0;
  if (isSettlement && (receivedFrom || selectedRefs.length > 0)) {
    waterfallResult = reconcileDebitsWaterfall(ss, receivedFrom, amountReceived, rawLinkedRef, dateOnly, timeOnly, sheet, dateObj);
    if (waterfallResult.settledRows.length > 0) {
      // Audit trail: record every row actually settled, not just the first one selected
      sheet.getRange(nextRow, startCol + SCHEMA.CREDIT.LINKED_ROW).setValue(waterfallResult.settledRows.map(r => r.ref).join(", "));
    }
    excessAmount = waterfallResult.remainingPayment > 0 ? waterfallResult.remainingPayment : 0;
  }

  // Waterfall and row mutations completed successfully: mark idempotency guard
  markRequestProcessed_(idemKey);

  let msg = "Credit logged successfully";
  if (waterfallResult && waterfallResult.settledRows.length > 0) {
    msg += " (" + waterfallResult.settledRows.length + " debt(s) reconciled)";
  }
  if (waterfallResult && waterfallResult.excessRecordedInPayableRow > 0) {
    msg += " (Excess of ₹" + excessAmount + " logged to Payables Row " + waterfallResult.excessRecordedInPayableRow + ")";
  }
  if (waterfallResult && waterfallResult.unmatched) {
    msg += " (No open debts found for " + (receivedFrom || "this sender") + "; credit left unlinked)";
  }

  return responseJSON({
    status: "success",
    message: msg,
    sheet: sheet.getName(),
    creditRow: nextRow,
    excess: excessAmount,
    unmatched: waterfallResult ? waterfallResult.unmatched : false,
    settledRows: waterfallResult ? waterfallResult.settledRows : []
  });
}

/**
 * Action: log_payable ("Friend Paid For Me")
 * Logs an advance/debt into Table 3 (Cols V to AB), netting it first against anything the creditor owes me.
 */
function handleLogPayable(payload, ss) {
  const d = (payload.data && typeof payload.data === "object") ? payload.data : payload;
  const [dateOnly, timeOnly, dateObj] = parseDateTime(d.date);

  const amountOwed = parseAmount_(d.amount_owed || d.amount);
  if (amountOwed === null || amountOwed <= 0) {
    return responseJSON({ status: "error", message: "Invalid or zero amount provided" }, 400);
  }
  const paidBy = (d.paid_by || "").toString().trim().replace(/\s+(&|and)\s+/gi, ", ");
  if (!paidBy) {
    return responseJSON({ status: "error", message: "paid_by is required" }, 400);
  }
  const whatFor = (d.what_for || "").toString().trim();

  const idemKey = buildIdemKey_("log_payable", d);
  if (isDuplicateRequest_(idemKey)) {
    return responseJSON({ status: "success", message: "Duplicate payable detected; skipped duplicate insertion.", duplicate: true });
  }

  const sheet = getOrCreateMonthSheet(ss, dateObj || dateOnly);
  const startCol = SCHEMA.PAYABLE.START_COL;
  const numCols = SCHEMA.PAYABLE.NUM_COLS;

  // Bilateral Debt Netting: if the creditor already owes me money in Table 1, offset first
  let netAmountOwed = amountOwed;
  const offsetResult = offsetPayablesAgainstDebits(ss, paidBy, amountOwed, dateOnly);
  if (offsetResult && offsetResult.offsetTotal > 0) {
    netAmountOwed = offsetResult.netAmountOwed;
  }

  const payableStatus = (netAmountOwed <= 0 ? "Repaid" : "Pending");
  const dateRepaidVal = (netAmountOwed <= 0 ? (dateObj || dateOnly) : "");

  const nextRow = findNextEmptyRowForCols(sheet, startCol, startCol + numCols - 1);

  sheet.getRange(nextRow, startCol, 1, numCols).setValues([[
    dateObj || dateOnly,
    timeOnly,
    sanitizeCellText_(paidBy),
    sanitizeCellText_(whatFor),
    netAmountOwed,
    dateRepaidVal,
    payableStatus
  ]]);

  sheet.getRange(nextRow, startCol, 1, numCols).setNumberFormats([[
    "dd/mm/yyyy",
    "hh:mm am/pm",
    "@",
    "@",
    "₹#,##0",
    "dd/mm/yyyy",
    "@"
  ]]);

  markRequestProcessed_(idemKey);

  let payableMsg = "Payable logged successfully";
  if (offsetResult && offsetResult.offsetTotal > 0) {
    payableMsg += " (₹" + offsetResult.offsetTotal + " offset against existing debits owed by " + paidBy + ")";
  }

  return responseJSON({
    status: "success",
    message: payableMsg,
    sheet: sheet.getName(),
    payableRow: nextRow,
    netAmountOwed: netAmountOwed,
    offsetTotal: offsetResult ? offsetResult.offsetTotal : 0
  });
}

/**
 * Action: log_repayment
 * Marks a record in Table 3 as Repaid when you settle with a friend/family member.
 * Supports partial repayments if amount is provided, as well as specific linked_payable_row targeting.
 */
function handleLogRepayment(payload, ss) {
  const d = (payload.data && typeof payload.data === "object") ? payload.data : payload;
  const personRaw = (d.person || "").toString().trim();
  const person = personRaw.toLowerCase();
  const [dateRepaid, timeRepaid, dateRepaidObj] = parseDateTime(d.date_repaid);
  const repAmount = parseAmount_(d.amount || d.amount_repaid);

  const idemKey = buildIdemKey_("log_repayment", d);
  if (isDuplicateRequest_(idemKey)) {
    return responseJSON({ status: "success", message: "Duplicate repayment detected; skipped.", duplicate: true });
  }
  const rawTargetRef = d.linked_payable_row || d.ref || "";
  const targetRef = extractRowReference(rawTargetRef);

  // If explicit repayment amount is provided, execute cascading waterfall reconciliation
  if (repAmount !== null && repAmount > 0) {
    const targetMonthSheet = getOrCreateMonthSheet(ss, dateRepaidObj || dateRepaid);
    const waterfallResult = reconcilePayablesWaterfall(ss, personRaw, repAmount, targetRef, dateRepaid, timeRepaid, targetMonthSheet, dateRepaidObj);

    const settledCount = waterfallResult.settledRows ? waterfallResult.settledRows.length : 0;
    const isFound = settledCount > 0 || waterfallResult.excessRecordedInDebitRow > 0;

    let msg = isFound ? ("Repaid " + settledCount + " payable(s)") : ("No pending payable found for " + (personRaw || "specified person"));
    if (waterfallResult.excessRecordedInDebitRow > 0) {
      msg += " (Overpayment of ₹" + waterfallResult.remainingRepayment + " logged to Debits Row " + waterfallResult.excessRecordedInDebitRow + ")";
    }

    if (isFound) markRequestProcessed_(idemKey);

    return responseJSON({
      status: isFound ? "success" : "not_found",
      message: msg,
      settledRows: waterfallResult.settledRows,
      excess: waterfallResult.remainingRepayment,
      excessDebitRow: waterfallResult.excessRecordedInDebitRow
    });
  }

  const isPartialSupported = false;
  let found = false;
  let remainingDebt = 0;

  // Option A: Direct row reference targeting (e.g. "Nov26!Row4" or "Row4")
  if (targetRef) {
    let sName = "";
    let targetRow = 0;
    if (targetRef.indexOf("!") !== -1) {
      const parts = targetRef.split("!");
      sName = parts[0].replace(/'/g, "");
      const rowMatch = parts[1].match(/\d+/);
      if (rowMatch) targetRow = parseInt(rowMatch[0], 10);
    } else {
      const rowMatch = targetRef.match(/\d+/);
      if (rowMatch) {
        targetRow = parseInt(rowMatch[0], 10);
        const curMonth = MONTH_NAMES[new Date().getMonth()] + new Date().getFullYear().toString().slice(-2);
        sName = ss.getSheetByName(curMonth) ? curMonth : "";
      }
    }

    let targetSheet = sName ? ss.getSheetByName(sName) : null;
    if (targetSheet && targetRow >= 4) {
      // Forward-resolve if payable was rolled over across any number of months
      const resolved = resolveCarriedRow_(ss, targetSheet, targetRow,
        SCHEMA.PAYABLE.START_COL + SCHEMA.PAYABLE.WHAT_FOR, SCHEMA.PAYABLE.START_COL + SCHEMA.PAYABLE.STATUS);
      targetSheet = resolved.sheet;
      targetRow = resolved.row;

      const currentOwed = parseFloat(targetSheet.getRange(targetRow, SCHEMA.PAYABLE.START_COL + SCHEMA.PAYABLE.AMOUNT_OWED).getValue()) || 0;
      targetSheet.getRange(targetRow, SCHEMA.PAYABLE.START_COL + SCHEMA.PAYABLE.DATE_REPAID).setValue(dateRepaidObj || dateRepaid).setNumberFormat("dd/mm/yyyy");

      // Handle multi-person payable creditor removal
      const paidByCol = SCHEMA.PAYABLE.START_COL + SCHEMA.PAYABLE.PAID_BY;
      const currentPaidBy = (targetSheet.getRange(targetRow, paidByCol).getValue() || "").toString().trim();
      let paidByTokens = currentPaidBy.replace(/\s+(&|and)\s+/gi, ", ").split(",").map(t => t.trim()).filter(Boolean);
      const initialCreditorsCount = paidByTokens.length;

      let personMatched = false;
      if (person && initialCreditorsCount > 1) {
        const matchIdx = paidByTokens.findIndex(t => t.toLowerCase() === person);
        if (matchIdx !== -1) {
          paidByTokens.splice(matchIdx, 1);
          targetSheet.getRange(targetRow, paidByCol).setValue(paidByTokens.join(", "));
          personMatched = true;
        }
      }

      if (isPartialSupported && repAmount < currentOwed) {
        remainingDebt = Math.max(0, Math.round((currentOwed - repAmount) * 100) / 100);
        targetSheet.getRange(targetRow, SCHEMA.PAYABLE.START_COL + SCHEMA.PAYABLE.AMOUNT_OWED).setValue(remainingDebt);
        targetSheet.getRange(targetRow, SCHEMA.PAYABLE.START_COL + SCHEMA.PAYABLE.STATUS).setValue("Partial");
      } else if (personMatched && paidByTokens.length > 0 && !isPartialSupported) {
        // Shared payable settled for one person without explicit amount: deduct their proportional share
        const perPersonShare = Math.round((currentOwed / initialCreditorsCount) * 100) / 100;
        remainingDebt = Math.max(0, Math.round((currentOwed - perPersonShare) * 100) / 100);
        targetSheet.getRange(targetRow, SCHEMA.PAYABLE.START_COL + SCHEMA.PAYABLE.AMOUNT_OWED).setValue(remainingDebt);
        targetSheet.getRange(targetRow, SCHEMA.PAYABLE.START_COL + SCHEMA.PAYABLE.STATUS).setValue(remainingDebt > 0 ? "Partial" : "Repaid");
      } else {
        targetSheet.getRange(targetRow, SCHEMA.PAYABLE.START_COL + SCHEMA.PAYABLE.AMOUNT_OWED).setValue(0);
        targetSheet.getRange(targetRow, SCHEMA.PAYABLE.START_COL + SCHEMA.PAYABLE.STATUS).setValue("Repaid");
        remainingDebt = 0;
      }
      found = true;
    }
  }

  // Option B: Search first matching pending payable for person in chronological order
  if (!found) {
    const sheets = getLedgerSheetsSorted_(ss);

    for (let s of sheets) {
      const lastRow = s.getLastRow();
      if (lastRow <= 3) continue;

      const values = s.getRange(4, SCHEMA.PAYABLE.START_COL, lastRow - 3, SCHEMA.PAYABLE.NUM_COLS).getValues();
      for (let i = 0; i < values.length; i++) {
        const paidByRaw = (values[i][SCHEMA.PAYABLE.PAID_BY] || "").toString().trim().toLowerCase();
        const tokens = paidByRaw.replace(/\s+(&|and)\s+/gi, ", ").split(",").map(t => t.trim()).filter(Boolean);
        const status = values[i][SCHEMA.PAYABLE.STATUS];
        if ((paidByRaw === person || tokens.includes(person)) && (status === "Pending" || status === "Partial")) {
          const targetRow = 4 + i;
          const currentOwed = parseFloat(values[i][SCHEMA.PAYABLE.AMOUNT_OWED]) || 0;
          s.getRange(targetRow, SCHEMA.PAYABLE.START_COL + SCHEMA.PAYABLE.DATE_REPAID).setValue(dateRepaidObj || dateRepaid).setNumberFormat("dd/mm/yyyy");

          // Handle multi-person payable creditor removal
          const paidByCol = SCHEMA.PAYABLE.START_COL + SCHEMA.PAYABLE.PAID_BY;
          let currentTokens = (values[i][SCHEMA.PAYABLE.PAID_BY] || "").toString().trim().replace(/\s+(&|and)\s+/gi, ", ").split(",").map(t => t.trim()).filter(Boolean);
          const initialCreditorsCount = currentTokens.length;

          let personMatched = false;
          if (person && initialCreditorsCount > 1) {
            const matchIdx = currentTokens.findIndex(t => t.toLowerCase() === person);
            if (matchIdx !== -1) {
              currentTokens.splice(matchIdx, 1);
              s.getRange(targetRow, paidByCol).setValue(currentTokens.join(", "));
              personMatched = true;
            }
          }

          if (isPartialSupported && repAmount < currentOwed) {
            remainingDebt = Math.max(0, Math.round((currentOwed - repAmount) * 100) / 100);
            s.getRange(targetRow, SCHEMA.PAYABLE.START_COL + SCHEMA.PAYABLE.AMOUNT_OWED).setValue(remainingDebt);
            s.getRange(targetRow, SCHEMA.PAYABLE.START_COL + SCHEMA.PAYABLE.STATUS).setValue("Partial");
          } else if (personMatched && currentTokens.length > 0 && !isPartialSupported) {
            const perPersonShare = Math.round((currentOwed / initialCreditorsCount) * 100) / 100;
            remainingDebt = Math.max(0, Math.round((currentOwed - perPersonShare) * 100) / 100);
            s.getRange(targetRow, SCHEMA.PAYABLE.START_COL + SCHEMA.PAYABLE.AMOUNT_OWED).setValue(remainingDebt);
            s.getRange(targetRow, SCHEMA.PAYABLE.START_COL + SCHEMA.PAYABLE.STATUS).setValue(remainingDebt > 0 ? "Partial" : "Repaid");
          } else {
            s.getRange(targetRow, SCHEMA.PAYABLE.START_COL + SCHEMA.PAYABLE.AMOUNT_OWED).setValue(0);
            s.getRange(targetRow, SCHEMA.PAYABLE.START_COL + SCHEMA.PAYABLE.STATUS).setValue("Repaid");
            remainingDebt = 0;
          }
          found = true;
          break;
        }
      }
      if (found) break;
    }
  }

  if (found) markRequestProcessed_(idemKey);

  return responseJSON({
    status: found ? "success" : "not_found",
    message: found ? (remainingDebt > 0 ? "Partial repayment recorded. Remaining: ₹" + remainingDebt : "Repayment marked as Repaid") : "No pending payable found for " + (d.person || "specified person"),
    remainingDebt: remainingDebt
  });
}

/**
 * Action: sync_queue
 * Batch processes an array of buffered offline transactions in a single Apps Script execution.
 */
function handleSyncQueue(payload, ss) {
  const queue = Array.isArray(payload.queue) ? payload.queue : [];
  const results = [];

  for (let i = 0; i < queue.length; i++) {
    const item = queue[i];
    if (!item || !item.action) continue;

    try {
      const itemDate = item.date || (item.data ? item.data.date : null);
      const targetSS = itemDate ? getTargetSpreadsheet_(itemDate) : ss;
      let resOutput = null;

      if (item.action === "log_debit") {
        resOutput = handleLogDebit(item, targetSS);
      } else if (item.action === "log_credit") {
        resOutput = handleLogCredit(item, targetSS);
      } else if (item.action === "log_payable") {
        resOutput = handleLogPayable(item, targetSS);
      } else if (item.action === "log_repayment") {
        resOutput = handleLogRepayment(item, targetSS);
      }

      if (!resOutput) {
        results.push({ index: i, action: item.action, status: "error", error: "Unsupported or unknown action: " + (item.action || "none") });
        continue;
      }

      let resJson = null;
      if (resOutput && typeof resOutput.getContent === "function") {
        try {
          resJson = JSON.parse(resOutput.getContent());
        } catch (jsonErr) {}
      }

      if (resJson && resJson.status === "error") {
        results.push({ index: i, action: item.action, status: "error", error: resJson.message });
      } else if (resJson && resJson.status === "not_found") {
        results.push({ index: i, action: item.action, status: "not_found", message: resJson.message });
      } else {
        results.push({ index: i, action: item.action, status: "success", result: resJson });
      }
    } catch (err) {
      results.push({ index: i, action: item.action, status: "error", error: err.toString() });
    }
  }

  return responseJSON({
    status: "success",
    message: "Queue processed",
    processedCount: results.length,
    results: results
  });
}

/**
 * Action: get_pending_debts
 * Returns all unresolved friend debts across all monthly tabs.
 * Uses exact companion token matching to avoid substring pollution.
 */
function handleGetPendingDebts(payload, ss) {
  const person = (payload.person || "").trim().toLowerCase();
  const pendingItems = [];
  const sheets = ss.getSheets();
  const skipTabs = new Set(["Dashboard", "Template", "CarryFwd"]);

  for (let s of sheets) {
    const sName = s.getName();
    if (skipTabs.has(sName)) continue;

    const lastRow = s.getLastRow();
    if (lastRow <= 3) continue;

    const dataRange = s.getRange(4, SCHEMA.DEBIT.START_COL, lastRow - 3, SCHEMA.DEBIT.NUM_COLS);
    const data = dataRange.getValues();
    const displayData = dataRange.getDisplayValues();

    for (let i = 0; i < data.length; i++) {
      const compRaw = (data[i][SCHEMA.DEBIT.COMPANIONS] || "").toString().trim().toLowerCase();
      const status = data[i][SCHEMA.DEBIT.STATUS];
      const owed = parseFloat(data[i][SCHEMA.DEBIT.OWED_TO_ME]) || 0;

      // Tokenize companions for strict exact match (e.g. 'Dan' vs 'Dan K' vs 'Daniel')
      const compTokens = compRaw.replace(/\s+(&|and)\s+/gi, ", ").split(",").map(c => c.trim()).filter(Boolean);
      const isPersonMatched = !person || compTokens.includes(person);

      if (status === "Pending" && owed > 0 && isPersonMatched) {
        const itemDesc = data[i][SCHEMA.DEBIT.ITEM] || "Expense";
        const compDesc = data[i][SCHEMA.DEBIT.COMPANIONS] || "Personal";

        let itemDate = displayData[i][SCHEMA.DEBIT.DATE];
        let itemTime = displayData[i][SCHEMA.DEBIT.TIME];

        if (!itemDate && data[i][SCHEMA.DEBIT.DATE] instanceof Date) {
          itemDate = Utilities.formatDate(data[i][SCHEMA.DEBIT.DATE], Session.getScriptTimeZone(), "dd/MM/yyyy");
        }
        if (!itemTime && data[i][SCHEMA.DEBIT.TIME] instanceof Date) {
          itemTime = Utilities.formatDate(data[i][SCHEMA.DEBIT.TIME], Session.getScriptTimeZone(), "hh:mm a");
        }

        pendingItems.push({
          title: itemDesc + " (₹" + owed + " - " + compDesc + ")",
          sheet: sName,
          rowNumber: 4 + i,
          ref: sName + "!Row" + (4 + i),
          date: itemDate || "N/A",
          time: itemTime || "N/A",
          item: itemDesc,
          owed: owed,
          companions: compDesc
        });
      }
    }
  }

  const pendingTitles = pendingItems.length > 0 ? [
    "Auto-Settle (All / Oldest First)",
    ...pendingItems.map(p => p.item + " (₹" + p.owed + " - " + p.companions + ") [" + p.ref + "]")
  ] : [];

  return responseJSON({
    status: "success",
    person: payload.person || "all",
    pendingDebts: pendingTitles,
    pendingDebtDetails: pendingItems
  });
}

/**
 * Action: get_pending_payables
 * Returns all unresolved payables (money I owe others) across all monthly tabs.
 */
function handleGetPendingPayables(payload, ss) {
  const person = (payload.person || "").trim().toLowerCase();
  const pendingItems = [];
  const sheets = ss.getSheets();
  const skipTabs = new Set(["Dashboard", "Template", "CarryFwd"]);

  for (let s of sheets) {
    const sName = s.getName();
    if (skipTabs.has(sName)) continue;

    const lastRow = s.getLastRow();
    if (lastRow <= 3) continue;

    const dataRange = s.getRange(4, SCHEMA.PAYABLE.START_COL, lastRow - 3, SCHEMA.PAYABLE.NUM_COLS);
    const data = dataRange.getValues();
    const displayData = dataRange.getDisplayValues();

    for (let i = 0; i < data.length; i++) {
      const paidByRaw = (data[i][SCHEMA.PAYABLE.PAID_BY] || "").toString().trim().toLowerCase();
      const status = data[i][SCHEMA.PAYABLE.STATUS];
      const owed = parseFloat(data[i][SCHEMA.PAYABLE.AMOUNT_OWED]) || 0;

      // Tokenize paid_by for strict exact match
      const tokens = paidByRaw.replace(/\s+(&|and)\s+/gi, ", ").split(",").map(c => c.trim()).filter(Boolean);
      const isPersonMatched = !person || tokens.includes(person);

      if ((status === "Pending" || status === "Partial") && owed > 0 && isPersonMatched) {
        const paidBy = data[i][SCHEMA.PAYABLE.PAID_BY] || "Friend";
        const whatFor = data[i][SCHEMA.PAYABLE.WHAT_FOR] || "Advance";

        let itemDate = displayData[i][SCHEMA.PAYABLE.DATE_INCURRED];
        let itemTime = displayData[i][SCHEMA.PAYABLE.TIME];

        if (!itemDate && data[i][SCHEMA.PAYABLE.DATE_INCURRED] instanceof Date) {
          itemDate = Utilities.formatDate(data[i][SCHEMA.PAYABLE.DATE_INCURRED], Session.getScriptTimeZone(), "dd/MM/yyyy");
        }
        if (!itemTime && data[i][SCHEMA.PAYABLE.TIME] instanceof Date) {
          itemTime = Utilities.formatDate(data[i][SCHEMA.PAYABLE.TIME], Session.getScriptTimeZone(), "hh:mm a");
        }

        pendingItems.push({
          title: whatFor + " (₹" + owed + " to " + paidBy + ")",
          sheet: sName,
          rowNumber: 4 + i,
          ref: sName + "!Row" + (4 + i),
          date: itemDate || "N/A",
          time: itemTime || "N/A",
          paidBy: paidBy,
          whatFor: whatFor,
          amountOwed: owed,
          status: status
        });
      }
    }
  }

  const pendingTitles = pendingItems.map(p => p.whatFor + " (₹" + p.amountOwed + " to " + p.paidBy + ") [" + p.ref + "]");

  return responseJSON({
    status: "success",
    person: payload.person || "all",
    pendingPayables: pendingTitles,
    pendingPayableDetails: pendingItems
  });
}

/**
 * Action: get_top_friends
 * Dynamically scans the current and preceding month tabs for companions, counting frequency.
 * Scans both Table 1 (Companions) and Table 3 (Paid By) so creditors are discoverable.
 * If fewer than 4 companions are found in recent months, scans historical monthly tabs.
 * Formats directly as a people.json structure: { people: [ { name: "Rohit" }, ... ] }
 */
function handleGetTopFriends(ss) {
  const today = new Date();
  const curName = MONTH_NAMES[today.getMonth()] + today.getFullYear().toString().slice(-2);
  const prevMonthIdx = (today.getMonth() + 11) % 12;
  const prevYear = today.getMonth() === 0 ? today.getFullYear() - 1 : today.getFullYear();
  const prevName = MONTH_NAMES[prevMonthIdx] + prevYear.toString().slice(-2);

  const sheetsToScan = [ss.getSheetByName(curName), ss.getSheetByName(prevName)].filter(Boolean);
  const frequency = {};

  sheetsToScan.forEach(sheet => {
    const lastRow = sheet.getLastRow();
    if (lastRow > 3) {
      // Table 1: Companions
      const debitData = sheet.getRange(4, 1, lastRow - 3, SCHEMA.DEBIT.NUM_COLS).getValues();
      debitData.forEach(row => {
        const val = (row[SCHEMA.DEBIT.COMPANIONS] || "").toString().trim();
        if (val && val.toLowerCase() !== "personal") {
          const names = val.replace(/\s+(&|and)\s+/gi, ", ").split(",").map(n => n.trim()).filter(Boolean);
          names.forEach(name => {
            frequency[name] = (frequency[name] || 0) + 1;
          });
        }
      });

      // Table 3: Creditors (Paid By)
      const payData = sheet.getRange(4, SCHEMA.PAYABLE.START_COL, lastRow - 3, SCHEMA.PAYABLE.NUM_COLS).getValues();
      payData.forEach(row => {
        const val = (row[SCHEMA.PAYABLE.PAID_BY] || "").toString().trim();
        if (val && val.toLowerCase() !== "friend") {
          const names = val.replace(/\s+(&|and)\s+/gi, ", ").split(",").map(n => n.trim()).filter(Boolean);
          names.forEach(name => {
            frequency[name] = (frequency[name] || 0) + 1;
          });
        }
      });
    }
  });

  // If fewer than 4 companions found in recent months, supplement with historical companions across all sheets
  if (Object.keys(frequency).length < 4) {
    const skipTabs = new Set(["Dashboard", "Template", "CarryFwd", curName, prevName]);
    ss.getSheets().forEach(sheet => {
      if (skipTabs.has(sheet.getName())) return;
      const lastRow = sheet.getLastRow();
      if (lastRow > 3) {
        const comps = sheet.getRange(4, SCHEMA.DEBIT.START_COL + SCHEMA.DEBIT.COMPANIONS, lastRow - 3, 1).getValues();
        comps.forEach(row => {
          const val = (row[0] || "").toString().trim();
          if (val && val.toLowerCase() !== "personal") {
            val.replace(/\s+(&|and)\s+/gi, ", ").split(",").map(n => n.trim()).filter(Boolean).forEach(name => {
              frequency[name] = (frequency[name] || 0) + 0.5;
            });
          }
        });

        const creditors = sheet.getRange(4, SCHEMA.PAYABLE.START_COL + SCHEMA.PAYABLE.PAID_BY, lastRow - 3, 1).getValues();
        creditors.forEach(row => {
          const val = (row[0] || "").toString().trim();
          if (val && val.toLowerCase() !== "friend") {
            val.replace(/\s+(&|and)\s+/gi, ", ").split(",").map(n => n.trim()).filter(Boolean).forEach(name => {
              frequency[name] = (frequency[name] || 0) + 0.5;
            });
          }
        });
      }
    });
  }

  // Sort by highest frequency
  const sorted = Object.keys(frequency).sort((a, b) => frequency[b] - frequency[a]);
  const topNames = sorted.slice(0, 6);
  const peopleArray = topNames.map(name => ({ name: name }));

  return responseJSON({
    status: "success",
    people: peopleArray
  });
}

/**
 * Action: get_all_people
 * Scans all monthly tabs, deduplicating and returning all historical companion and creditor names A to Z.
 */
function handleGetAllPeople(ss) {
  const namesSet = new Set();
  const sheets = ss.getSheets();
  const skipTabs = new Set(["Dashboard", "Template", "CarryFwd"]);

  sheets.forEach(sheet => {
    if (skipTabs.has(sheet.getName())) return;
    const lastRow = sheet.getLastRow();
    if (lastRow <= 3) return;

    // Scan Table 1 Companions
    const companions = sheet.getRange(4, SCHEMA.DEBIT.START_COL + SCHEMA.DEBIT.COMPANIONS, lastRow - 3, 1).getValues();
    companions.forEach(row => {
      const val = (row[0] || "").toString().trim();
      if (val && val.toLowerCase() !== "personal") {
        val.replace(/\s+(&|and)\s+/gi, ", ").split(",").map(n => n.trim()).filter(Boolean).forEach(n => namesSet.add(n));
      }
    });

    // Scan Table 3 Creditors (Paid By)
    const creditors = sheet.getRange(4, SCHEMA.PAYABLE.START_COL + SCHEMA.PAYABLE.PAID_BY, lastRow - 3, 1).getValues();
    creditors.forEach(row => {
      const val = (row[0] || "").toString().trim();
      if (val && val.toLowerCase() !== "friend") {
        val.replace(/\s+(&|and)\s+/gi, ", ").split(",").map(n => n.trim()).filter(Boolean).forEach(n => namesSet.add(n));
      }
    });
  });

  const allPeople = Array.from(namesSet).sort((a, b) => a.localeCompare(b));
  return responseJSON({
    status: "success",
    allPeople: allPeople
  });
}

/**
 * Action: repair_sheets
 * Formats Template and Nov26 headers, enforces 28-column layout,
 * and fixes Dashboard KPI formulas including J5 (I Owe People).
 */
function handleRepairSheets(ss) {
  const templateHeaders = [
    "Date", "Time", "Account", "Item / Description", "Trip / Event Tag", "Category", 
    "Total Amount (₹)", "Paid For / Companions", "My Share (₹)", "Owed To Me (₹)", "Status",
    "", // Spacer Col L (12)
    "Date", "Time", "Account", "Inflow Type", "Received From", "Reason / Note", "Amount Received (₹)", "Linked Debit Row",
    "", // Spacer Col U (21)
    "Date Incurred", "Time", "Paid By", "What For", "Amount I Owe (₹)", "Date Repaid", "Repayment Status"
  ];

  // Strictly target master Template tab. Active/historical monthly sheets (e.g. Nov26) are never mutated programmatically.
  let templateSheet = ss.getSheetByName("Template");
  if (!templateSheet) {
    templateSheet = ss.insertSheet("Template");
  }
  const sheetsToUpdate = [templateSheet];
  const updatedSheets = [];

  for (let sheet of sheetsToUpdate) {
    if (!sheet) continue;
    const sName = sheet.getName();

    // Row 1 Title
    sheet.getRange("A1").setValue("EXPENSE LEDGER — TEMPLATE");
    
    // Row 2 Group Labels
    sheet.getRange("A2:K2").merge().setValue("EXPENSES (MONEY OUT)").setBackground("#C62828").setFontColor("#FFFFFF").setFontWeight("bold").setHorizontalAlignment("center");
    sheet.getRange("L2").setValue("").setBackground("#ECEFF1");
    sheet.getRange("M2:T2").merge().setValue("INFLOWS (MONEY IN)").setBackground("#2E7D32").setFontColor("#FFFFFF").setFontWeight("bold").setHorizontalAlignment("center");
    sheet.getRange("U2").setValue("").setBackground("#ECEFF1");
    sheet.getRange("V2:AB2").merge().setValue("I OWE PEOPLE").setBackground("#F57F17").setFontColor("#FFFFFF").setFontWeight("bold").setHorizontalAlignment("center");

    // Row 3 Column Headers
    sheet.getRange(3, 1, 1, templateHeaders.length).setValues([templateHeaders])
      .setBackground("#37474F").setFontColor("#FFFFFF").setFontWeight("bold").setFontSize(9).setHorizontalAlignment("center");
    
    // Spacers Col L (12) and Col U (21)
    sheet.getRange(1, SCHEMA.SPACER_1.COL, sheet.getMaxRows(), 1).setBackground("#ECEFF1");
    sheet.setColumnWidth(SCHEMA.SPACER_1.COL, 10);
    sheet.getRange(1, SCHEMA.SPACER_2.COL, sheet.getMaxRows(), 1).setBackground("#ECEFF1");
    sheet.setColumnWidth(SCHEMA.SPACER_2.COL, 10);

    sheet.setFrozenRows(3);

    // Enforce clean date, time, currency, and text formats across all 28 columns
    if (sheet.getMaxRows() > 3) {
      const numRows = sheet.getMaxRows() - 3;

      // Table 1: Expenses (Cols A to K)
      sheet.getRange(4, 1, numRows, 1).setNumberFormat("dd/mm/yyyy").setHorizontalAlignment("center");
      sheet.getRange(4, 2, numRows, 1).setNumberFormat("hh:mm am/pm").setHorizontalAlignment("center");
      sheet.getRange(4, 3, numRows, 1).setNumberFormat("@").setHorizontalAlignment("center");
      sheet.getRange(4, 4, numRows, 1).setNumberFormat("@").setHorizontalAlignment("left");
      sheet.getRange(4, 5, numRows, 1).setNumberFormat("@").setHorizontalAlignment("center");
      sheet.getRange(4, 6, numRows, 1).setNumberFormat("@").setHorizontalAlignment("left");
      sheet.getRange(4, 7, numRows, 1).setNumberFormat("₹#,##0").setHorizontalAlignment("right");
      sheet.getRange(4, 8, numRows, 1).setNumberFormat("@").setHorizontalAlignment("left");
      sheet.getRange(4, 9, numRows, 2).setNumberFormat("₹#,##0").setHorizontalAlignment("right");
      sheet.getRange(4, 11, numRows, 1).setNumberFormat("@").setHorizontalAlignment("center");

      // Table 2: Inflows (Cols M to T)
      sheet.getRange(4, 13, numRows, 1).setNumberFormat("dd/mm/yyyy").setHorizontalAlignment("center");
      sheet.getRange(4, 14, numRows, 1).setNumberFormat("hh:mm am/pm").setHorizontalAlignment("center");
      sheet.getRange(4, 15, numRows, 1).setNumberFormat("@").setHorizontalAlignment("center");
      sheet.getRange(4, 16, numRows, 3).setNumberFormat("@").setHorizontalAlignment("left");
      sheet.getRange(4, 19, numRows, 1).setNumberFormat("₹#,##0").setHorizontalAlignment("right");
      sheet.getRange(4, 20, numRows, 1).setNumberFormat("@").setHorizontalAlignment("center");

      // Table 3: Payables (Cols V to AB)
      sheet.getRange(4, 22, numRows, 1).setNumberFormat("dd/mm/yyyy").setHorizontalAlignment("center");
      sheet.getRange(4, 23, numRows, 1).setNumberFormat("hh:mm am/pm").setHorizontalAlignment("center");
      sheet.getRange(4, 24, numRows, 2).setNumberFormat("@").setHorizontalAlignment("left");
      sheet.getRange(4, 26, numRows, 1).setNumberFormat("₹#,##0").setHorizontalAlignment("right");
      sheet.getRange(4, 27, numRows, 1).setNumberFormat("dd/mm/yyyy").setHorizontalAlignment("center");
      sheet.getRange(4, 28, numRows, 1).setNumberFormat("@").setHorizontalAlignment("center");
    }

    updatedSheets.push(sName);
  }

  // Auto-wire dynamic Dashboard formulas
  const dash = ss.getSheetByName("Dashboard");
  if (dash) {
    // Card 4 header label fix
    dash.getRange("J4:L4").merge().setValue("I OWE PEOPLE").setBackground("#283593").setFontColor("#FFFFFF").setFontWeight("bold");

    // Dynamic KPI formulas referencing active month tab automatically (excludes Savings and Transfers)
    dash.getRange("A5:C5").setFormula('=IFERROR(SUMIFS(INDIRECT("\'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "\'!I4:I"), INDIRECT("\'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "\'!F4:F"), "<>Savings", INDIRECT("\'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "\'!F4:F"), "<>Transfer"), 0)');
    dash.getRange("D5:F5").setFormula('=IFERROR(SUMIFS(INDIRECT("\'" & TEXT(EDATE(TODAY(), -1), "mmm") & RIGHT(YEAR(EDATE(TODAY(), -1)), 2) & "\'!I4:I"), INDIRECT("\'" & TEXT(EDATE(TODAY(), -1), "mmm") & RIGHT(YEAR(EDATE(TODAY(), -1)), 2) & "\'!F4:F"), "<>Savings", INDIRECT("\'" & TEXT(EDATE(TODAY(), -1), "mmm") & RIGHT(YEAR(EDATE(TODAY(), -1)), 2) & "\'!F4:F"), "<>Transfer"), 0)');
    dash.getRange("G5:I5").setFormula('=IFERROR(SUMIF(INDIRECT("\'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "\'!K4:K"), "Pending", INDIRECT("\'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "\'!J4:J")), 0)');
    dash.getRange("J5:L5").setFormula('=IFERROR(SUMIF(INDIRECT("\'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "\'!AB4:AB"), "Pending", INDIRECT("\'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "\'!Z4:Z")) + SUMIF(INDIRECT("\'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "\'!AB4:AB"), "Partial", INDIRECT("\'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "\'!Z4:Z")), 0)');
    dash.getRange("M5:O5").setFormula('=IFERROR(SUMIF(INDIRECT("\'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "\'!F4:F"), "Subscriptions", INDIRECT("\'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "\'!I4:I")), 0)');
    dash.getRange("P5:R5").setFormula('=IFERROR(A5 / DAY(TODAY()), 0)');
    dash.getRange("A5:R5").setNumberFormat("₹#,##0");

    // Active Settlements Table: Configure Row 26 headers and auto-wire dynamic grouped QUERY formulas
    dash.getRange("A26:F26").setValues([["Person", "Original (₹)", "Paid Back (₹)", "Net Owed (₹)", "Oldest Date", "Txn Count"]])
      .setBackground("#1A237E").setFontColor("#FFFFFF").setFontWeight("bold").setFontSize(9).setHorizontalAlignment("center");
    dash.getRange("H26:K26").setValues([["Person", "Txn Count", "Amount I Owe (₹)", "Oldest Date"]])
      .setBackground("#1A237E").setFontColor("#FFFFFF").setFontWeight("bold").setFontSize(9).setHorizontalAlignment("center");
    dash.getRange("L26:L40").clearContent().clearFormat();

    // Clear spill area
    dash.getRange("A27:F40").clearContent();
    dash.getRange("H27:K40").clearContent();

    // Data formatting (Currency, Dates, and Text alignment)
    dash.getRange("A27:A40").setNumberFormat("@").setHorizontalAlignment("left");
    dash.getRange("B27:D40").setNumberFormat("₹#,##0").setHorizontalAlignment("right");
    dash.getRange("E27:E40").setNumberFormat("dd/mm/yyyy").setHorizontalAlignment("center");
    dash.getRange("F27:F40").setNumberFormat("0").setHorizontalAlignment("center");

    dash.getRange("H27:H40").setNumberFormat("@").setHorizontalAlignment("left");
    dash.getRange("I27:I40").setNumberFormat("0").setHorizontalAlignment("center");
    dash.getRange("J27:J40").setNumberFormat("₹#,##0").setHorizontalAlignment("right");
    dash.getRange("K27:K40").setNumberFormat("dd/mm/yyyy").setHorizontalAlignment("center");

    // Grouped active settlement queries: Consolidates all open balances per individual into a single row with total count
    dash.getRange("A27").setFormula('=IFERROR(QUERY(QUERY(INDIRECT("\'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "\'!A4:K"), "SELECT H, G - I, G - I - J, J, A WHERE K = \'Pending\' AND J > 0 AND H != \'Personal\' LABEL G - I \'\', G - I - J \'\'", 0), "SELECT Col1, SUM(Col2), SUM(Col3), SUM(Col4), MIN(Col5), COUNT(Col1) GROUP BY Col1 LABEL Col1 \'\', SUM(Col2) \'\', SUM(Col3) \'\', SUM(Col4) \'\', MIN(Col5) \'\', COUNT(Col1) \'\'", 0), "")');
    dash.getRange("H27").setFormula('=IFERROR(QUERY(QUERY(INDIRECT("\'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "\'!V4:AB"), "SELECT X, Z, V WHERE (AB = \'Pending\' OR AB = \'Partial\') AND Z > 0", 0), "SELECT Col1, COUNT(Col1), SUM(Col2), MIN(Col3) GROUP BY Col1 LABEL Col1 \'\', COUNT(Col1) \'\', SUM(Col2) \'\', MIN(Col3) \'\'", 0), "")');

    // Auto-wire Monthly Summary formulas (U6:W17) - excludes Carried Fwd from Gross Outflow
    for (let r = 6; r <= 17; r++) {
      dash.getRange("V" + r).setFormula('=IFERROR(SUMIFS(INDIRECT("\'" & $U' + r + ' & RIGHT(YEAR(TODAY()), 2) & "\'!I4:I"), INDIRECT("\'" & $U' + r + ' & RIGHT(YEAR(TODAY()), 2) & "\'!F4:F"), "<>Savings", INDIRECT("\'" & $U' + r + ' & RIGHT(YEAR(TODAY()), 2) & "\'!F4:F"), "<>Transfer"), 0)');
      dash.getRange("W" + r).setFormula('=IFERROR(SUMIFS(INDIRECT("\'" & $U' + r + ' & RIGHT(YEAR(TODAY()), 2) & "\'!G4:G"), INDIRECT("\'" & $U' + r + ' & RIGHT(YEAR(TODAY()), 2) & "\'!D4:D"), "<>[Carried*"), 0)');
    }

    // Auto-wire Category Breakdown Table (Y6:Z15) - all 10 categories including Savings
    const standardCategories = [
      "Food & Drink", "Groceries", "Transit", "Subscriptions",
      "Shopping", "Trip/Travel", "Health", "Family", "Savings", "Other"
    ];
    for (let c = 0; c < standardCategories.length; c++) {
      const rowIdx = 6 + c;
      dash.getRange("Y" + rowIdx).setValue(standardCategories[c]);
      dash.getRange("Z" + rowIdx).setFormula('=IFERROR(SUMIF(INDIRECT("\'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "\'!F4:F"), Y' + rowIdx + ', INDIRECT("\'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "\'!I4:I")), 0)');
      dash.getRange("Z" + rowIdx).setNumberFormat("₹#,##0");
    }
  }

  return responseJSON({
    status: "success",
    message: "Template and monthly sheets repaired with standard formats and formulas.",
    updatedSheets: updatedSheets
  });
}

/**
 * Action: inspect_dashboard
 * Reads and returns live Dashboard KPI values and formulas.
 * Read-only inspection does NOT create monthly tabs or trigger rollover side effects.
 */
function handleInspectDashboard(ss) {
  const dash = ss.getSheetByName("Dashboard");
  if (!dash) {
    return responseJSON({ status: "error", message: "Dashboard tab not found" }, 404);
  }

  const kpis = {
    current_burn: { cell: "A5", value: dash.getRange("A5").getValue(), formula: dash.getRange("A5").getFormula() },
    prev_month: { cell: "D5", value: dash.getRange("D5").getValue(), formula: dash.getRange("D5").getFormula() },
    owed_to_me: { cell: "G5", value: dash.getRange("G5").getValue(), formula: dash.getRange("G5").getFormula() },
    i_owe_people: { cell: "J5", value: dash.getRange("J5").getValue(), formula: dash.getRange("J5").getFormula() },
    subscriptions: { cell: "M5", value: dash.getRange("M5").getValue(), formula: dash.getRange("M5").getFormula() },
    daily_average: { cell: "P5", value: dash.getRange("P5").getValue(), formula: dash.getRange("P5").getFormula() }
  };

  const categoryCalc = dash.getRange("Y5:Z15").getValues();
  const categoryFormulas = dash.getRange("Y5:Z15").getFormulas();
  const monthlySummary = dash.getRange("U5:W17").getValues();
  const settlementsTheyOweMe = dash.getRange("A27:F35").getValues();
  const settlementsIOwe = dash.getRange("H27:K35").getValues();

  const now = new Date();
  const currentMonthName = MONTH_NAMES[now.getMonth()] + now.getFullYear().toString().slice(-2);
  const activeMonthSheet = ss.getSheetByName(currentMonthName);
  let activeMonthRows = [];
  let activeMonthName = "";
  if (activeMonthSheet) {
    activeMonthName = activeMonthSheet.getName();
    const lastRow = activeMonthSheet.getLastRow();
    if (lastRow >= 4) {
      activeMonthRows = activeMonthSheet.getRange(4, 1, lastRow - 3, 28).getValues();
    }
  }

  return responseJSON({
    status: "success",
    kpis: kpis,
    categoryCalc: categoryCalc,
    categoryFormulas: categoryFormulas,
    monthlySummary: monthlySummary,
    settlementsTheyOweMe: settlementsTheyOweMe,
    settlementsIOwe: settlementsIOwe,
    activeMonthName: activeMonthName,
    activeMonthRows: activeMonthRows
  });
}

/**
 * Action: year_close
 * Generates CarryFwd tab and JSON backup in Google Drive.
 */
function handleYearClose(ss) {
  const unresolvedReceivables = [];
  const unresolvedPayables = [];
  const sheets = ss.getSheets();
  const skipTabs = new Set(["Dashboard", "Template", "CarryFwd"]);

  for (let s of sheets) {
    const sName = s.getName();
    if (skipTabs.has(sName)) continue;

    const lastRow = s.getLastRow();
    if (lastRow <= 3) continue;

    // Check Debits
    const debits = s.getRange(4, SCHEMA.DEBIT.START_COL, lastRow - 3, SCHEMA.DEBIT.NUM_COLS).getValues();
    debits.forEach((r, idx) => {
      if (r[SCHEMA.DEBIT.STATUS] === "Pending" && (parseFloat(r[SCHEMA.DEBIT.OWED_TO_ME]) || 0) > 0) {
        const rawDate = r[SCHEMA.DEBIT.DATE];
        const cleanDate = (rawDate instanceof Date) ? Utilities.formatDate(rawDate, Session.getScriptTimeZone(), "dd/MM/yyyy") : (rawDate || "").toString();
        unresolvedReceivables.push({
          sourceSheet: sName,
          rowNumber: 4 + idx,
          date: cleanDate,
          time: r[SCHEMA.DEBIT.TIME],
          item: r[SCHEMA.DEBIT.ITEM],
          tripTag: r[SCHEMA.DEBIT.TRIP_TAG] || "",
          companions: r[SCHEMA.DEBIT.COMPANIONS],
          amountOwed: r[SCHEMA.DEBIT.OWED_TO_ME]
        });
      }
    });

    // Check Payables
    const payables = s.getRange(4, SCHEMA.PAYABLE.START_COL, lastRow - 3, SCHEMA.PAYABLE.NUM_COLS).getValues();
    payables.forEach((r, idx) => {
      if ((r[SCHEMA.PAYABLE.STATUS] === "Pending" || r[SCHEMA.PAYABLE.STATUS] === "Partial") && (parseFloat(r[SCHEMA.PAYABLE.AMOUNT_OWED]) || 0) > 0) {
        const rawDate = r[SCHEMA.PAYABLE.DATE_INCURRED];
        const cleanDate = (rawDate instanceof Date) ? Utilities.formatDate(rawDate, Session.getScriptTimeZone(), "dd/MM/yyyy") : (rawDate || "").toString();
        unresolvedPayables.push({
          sourceSheet: sName,
          rowNumber: 4 + idx,
          date: cleanDate,
          time: r[SCHEMA.PAYABLE.TIME],
          paidBy: r[SCHEMA.PAYABLE.PAID_BY],
          whatFor: r[SCHEMA.PAYABLE.WHAT_FOR],
          amountOwed: r[SCHEMA.PAYABLE.AMOUNT_OWED]
        });
      }
    });
  }

  let carrySheet = ss.getSheetByName("CarryFwd");
  if (!carrySheet) {
    carrySheet = ss.insertSheet("CarryFwd");
  } else {
    carrySheet.clear();
  }

  carrySheet.appendRow(["YEAR-END UNRESOLVED BALANCES STATEMENT"]);
  carrySheet.appendRow(["Generated at:", new Date().toISOString()]);
  carrySheet.appendRow([]);
  carrySheet.appendRow(["--- RECEIVABLES (PEOPLE OWE ME) ---"]);
  carrySheet.appendRow(["Source Tab", "Date", "Item", "Trip Tag", "Companions", "Amount Owed"]);
  unresolvedReceivables.forEach(r => {
    carrySheet.appendRow([r.sourceSheet, r.date, r.item, r.tripTag, r.companions, r.amountOwed]);
  });

  carrySheet.appendRow([]);
  carrySheet.appendRow(["--- PAYABLES (I OWE PEOPLE) ---"]);
  carrySheet.appendRow(["Source Tab", "Date", "Paid By", "What For", "Amount Owed"]);
  unresolvedPayables.forEach(r => {
    carrySheet.appendRow([r.sourceSheet, r.date, r.paidBy, r.whatFor, r.amountOwed]);
  });

  const currentYear = new Date().getFullYear();
  const jsonContent = JSON.stringify({
    carry_year: currentYear.toString(),
    generated_at: new Date().toISOString(),
    receivables: unresolvedReceivables,
    payables: unresolvedPayables
  }, null, 2);

  const folderName = getDriveFolderName_();
  let targetFolder = null;
  if (typeof DriveApp !== "undefined") {
    const folderIter = DriveApp.getFoldersByName(folderName);
    while (folderIter.hasNext()) {
      const f = folderIter.next();
      if (!f.isTrashed()) {
        targetFolder = f;
        break;
      }
    }
    if (!targetFolder) targetFolder = DriveApp.getRootFolder();
  }
  const fileName = "carry_forward_" + currentYear + ".json";
  const existingFiles = targetFolder.getFilesByName(fileName);
  if (existingFiles.hasNext()) {
    existingFiles.next().setContent(jsonContent);
  } else {
    targetFolder.createFile(fileName, jsonContent, MimeType.PLAIN_TEXT);
  }

  return responseJSON({
    status: "success",
    message: "Year close complete. CarryFwd sheet created and JSON backup saved to Drive.",
    receivablesCount: unresolvedReceivables.length,
    payablesCount: unresolvedPayables.length
  });
}

// ---------------- HELPER UTILITIES ----------------

/**
 * Finds the next empty row for a specific table column slice.
 * Prevents writing over other side-by-side tables.
 */
function findNextEmptyRowForCols(sheet, startCol, endCol) {
  const maxRows = sheet.getMaxRows();
  if (maxRows <= 3) {
    sheet.insertRowsAfter(maxRows, 4 - maxRows);
    return 4;
  }

  const vals = sheet.getRange(4, startCol, maxRows - 3, endCol - startCol + 1).getValues();
  for (let i = 0; i < vals.length; i++) {
    const isBlank = vals[i].every(cell => cell === "" || cell === null);
    if (isBlank) {
      return 4 + i;
    }
  }
  sheet.insertRowAfter(maxRows);
  return maxRows + 1;
}

/**
 * Safely extracts a clean row reference (e.g. "Nov26!Row12") from:
 * 1. A clean reference string: "Nov26!Row12"
 * 2. A title/menu item: "Nov26!Row12: Dinner with team (₹450 - Dan,Alwx)"
 * 3. A JSON-serialized object string: '{"sheet":"Nov26","ref":"Nov26!Row12",...}'
 * 4. A JavaScript object: { sheet: "Nov26", ref: "Nov26!Row12", rowNumber: 12 }
 * 
 * @param {*} raw Input reference
 * @return {string} Standardized row reference (e.g. "Nov26!Row12") or empty string
 */
function extractRowReference(raw) {
  if (!raw) return "";

  let candidate = raw;

  // Case 1: If candidate is a JSON-serialized string, parse it
  if (typeof candidate === "string") {
    const trimmed = candidate.trim();
    if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
      try {
        candidate = JSON.parse(trimmed);
      } catch (e) {
        // Fall back to regex parsing on candidate string
      }
    }
  }

  // Case 2: If candidate is an object
  if (typeof candidate === "object" && candidate !== null) {
    if (candidate.ref && typeof candidate.ref === "string") {
      candidate = candidate.ref;
    } else if (candidate.sheet && candidate.rowNumber) {
      return candidate.sheet + "!Row" + candidate.rowNumber;
    } else {
      candidate = JSON.stringify(candidate);
    }
  }

  // Case 3: Regex match "SheetName!RowX" or "SheetName!X"
  const str = candidate.toString().trim();
  const match = str.match(/(?:'([^']+)'|([A-Za-z0-9_]+))!(?:Row)?(\d+)/i);
  if (match) {
    const sheetName = match[1] || match[2];
    const rowNum = match[3];
    return sheetName + "!Row" + rowNum;
  }

  // Case 4: Standalone "RowX" or just number
  const simpleMatch = str.match(/^(?:Row)?(\d+)$/i);
  if (simpleMatch) {
    return "Row" + simpleMatch[1];
  }

  return "";
}

/**
 * Safely extracts ALL row references from a string, array, or JSON payload.
 * Supports multiple comma-separated references like "Nov26!Row4, Dec26!Row5".
 * Returns an array of clean reference strings, e.g. ["Nov26!Row4", "Dec26!Row5"].
 */
function extractAllRowReferences(raw) {
  if (!raw) return [];
  if (Array.isArray(raw)) {
    const res = [];
    raw.forEach(item => {
      const all = extractAllRowReferences(item);
      res.push(...all);
    });
    return res;
  }

  const str = raw.toString().trim();

  // The "Auto-Settle (All / Oldest First)" menu title carries no row reference, so it simply yields none.
  const matches = str.matchAll(/(?:'([^']+)'|([A-Za-z0-9_]+))!(?:Row)?(\d+)/gi);
  const refs = [];
  for (const m of matches) {
    const sName = m[1] || m[2];
    const rNum = m[3];
    refs.push(sName + "!Row" + rNum);
  }

  if (refs.length === 0) {
    const single = extractRowReference(raw);
    if (single) refs.push(single);
  }

  return refs;
}

/**
 * Settles one Debits row against a payment.
 * - Follows Carried Fwd chains to the live copy (any number of months).
 * - Only Pending rows with owed > 0 can be settled; anything else returns applied 0.
 * - On a shared row ("Papa, Mummy") a name-matched payment is capped at that person's share unless
 *   allowFull is set (the row was explicitly selected). The settler leaves the companions list once
 *   their share is covered.
 * Returns { remaining, excess, applied, ref } or null when the reference is unusable.
 */
function updateLinkedDebitSettlement(ss, refStr, paidAmount, settlerName, allowFull) {
  try {
    const cleanRef = extractRowReference(refStr);
    if (!cleanRef) return null;

    let sheetName = "";
    let rowNum = 0;
    if (cleanRef.indexOf("!") !== -1) {
      const parts = cleanRef.split("!");
      sheetName = parts[0].replace(/'/g, "");
      const rowMatch = parts[1].match(/\d+/);
      if (rowMatch) rowNum = parseInt(rowMatch[0], 10);
    } else {
      const match = cleanRef.match(/\d+/);
      if (match) rowNum = parseInt(match[0], 10);
      const now = new Date();
      sheetName = MONTH_NAMES[now.getMonth()] + now.getFullYear().toString().slice(-2);
    }

    let targetSheet = ss.getSheetByName(sheetName);
    if (!targetSheet || rowNum < 4) {
      console.warn("Target sheet or row invalid for settlement: " + cleanRef);
      return null;
    }

    const owedCol = SCHEMA.DEBIT.START_COL + SCHEMA.DEBIT.OWED_TO_ME;
    const statusCol = SCHEMA.DEBIT.START_COL + SCHEMA.DEBIT.STATUS;
    const compCol = SCHEMA.DEBIT.START_COL + SCHEMA.DEBIT.COMPANIONS;
    const itemCol = SCHEMA.DEBIT.START_COL + SCHEMA.DEBIT.ITEM;

    const resolved = resolveCarriedRow_(ss, targetSheet, rowNum, itemCol, statusCol);
    targetSheet = resolved.sheet;
    rowNum = resolved.row;
    const liveRef = targetSheet.getName() + "!Row" + rowNum;

    const status = (targetSheet.getRange(rowNum, statusCol).getValue() || "").toString().trim();
    const currentOwed = parseFloat(targetSheet.getRange(rowNum, owedCol).getValue()) || 0;
    if (status !== "Pending" || currentOwed <= 0) {
      return { remaining: currentOwed, excess: round2_(paidAmount), applied: 0, ref: liveRef };
    }

    const compList = (targetSheet.getRange(rowNum, compCol).getValue() || "").toString().trim()
      .replace(/\s+(&|and)\s+/gi, ", ").split(",").map(c => c.trim()).filter(Boolean);
    const sNameClean = (settlerName || "").toString().trim().toLowerCase();
    // Strict exact match only (treats 'Dan' and 'Dan K' as distinct people)
    const matchIdx = (sNameClean && compList.length > 1) ? compList.findIndex(c => c.toLowerCase() === sNameClean) : -1;
    const isShared = matchIdx !== -1;
    const share = isShared ? round2_(currentOwed / compList.length) : currentOwed;

    let applied = Math.min(paidAmount, currentOwed);
    if (isShared) {
      // If explicitly picked by the user (allowFull) and payment covers the whole row balance,
      // allow settling the entire bill (e.g. Dan pays for both himself and Rohit).
      // Otherwise (partial payment or automatic waterfall), cap settlement at settler's own share
      // so one person's payment never shrinks another companion's debt balance.
      if (!allowFull || paidAmount < currentOwed - 0.01) {
        applied = Math.min(applied, share);
      }
    }
    applied = round2_(applied);

    if (isShared) {
      if (applied >= currentOwed - 0.05) {
        // Full row settled
        targetSheet.getRange(rowNum, compCol).setValue(settlerName);
      } else if (applied >= share - 0.05) {
        // Individual share settled
        compList.splice(matchIdx, 1);
        targetSheet.getRange(rowNum, compCol).setValue(compList.join(", "));
      }
    }

    const remaining = Math.max(0, round2_(currentOwed - applied));
    const excess = Math.max(0, round2_(paidAmount - applied));

    targetSheet.getRange(rowNum, statusCol).setValue(remaining === 0 ? "Settled" : "Pending");
    targetSheet.getRange(rowNum, owedCol).setValue(remaining);

    return { remaining: remaining, excess: excess, applied: applied, ref: liveRef };
  } catch (e) {
    console.error("Failed to update linked debit row: " + e.toString());
    return null;
  }
}

/**
 * Cascading Debt Settlement (Waterfall Reconciler).
 * 1. Explicitly selected rows, in the order selected (full row amounts).
 * 2. The friend's other open debts, oldest month first.
 * 3. Any surplus becomes a payable ("I owe them") ONLY if at least one real debt was settled.
 *    A payment from a name with no open debts (typo, stranger) is left unlinked and flagged.
 */
function reconcileDebitsWaterfall(ss, settlerName, paymentAmount, initialTargetRef, dateOnly, timeOnly, currentSheet, dateObj) {
  let remainingPayment = round2_(paymentAmount);
  const settledRows = [];
  const sNameClean = (settlerName || "").toString().trim().toLowerCase();

  const prioritizedRefs = extractAllRowReferences(initialTargetRef);
  const handled = new Set();

  for (let ref of prioritizedRefs) {
    if (remainingPayment <= 0.009) break;
    const res = updateLinkedDebitSettlement(ss, ref, remainingPayment, settlerName, true);
    if (!res) continue;
    handled.add(res.ref);
    if (res.applied > 0) {
      settledRows.push({ ref: res.ref, settled: res.applied, remainingOnRow: res.remaining });
      remainingPayment = res.excess;
    }
  }

  if (remainingPayment > 0.009 && sNameClean) {
    for (let s of getLedgerSheetsSorted_(ss)) {
      const lastRow = s.getLastRow();
      if (lastRow <= 3) continue;
      const values = s.getRange(4, SCHEMA.DEBIT.START_COL, lastRow - 3, SCHEMA.DEBIT.NUM_COLS).getValues();

      for (let i = 0; i < values.length; i++) {
        if (remainingPayment <= 0.009) break;
        const currentRef = s.getName() + "!Row" + (4 + i);
        if (handled.has(currentRef)) continue;

        const status = (values[i][SCHEMA.DEBIT.STATUS] || "").toString().trim();
        const owed = parseFloat(values[i][SCHEMA.DEBIT.OWED_TO_ME]) || 0;
        if (status !== "Pending" || owed <= 0) continue;

        const compList = (values[i][SCHEMA.DEBIT.COMPANIONS] || "").toString().trim()
          .replace(/\s+(&|and)\s+/gi, ", ").split(",").map(c => c.trim().toLowerCase()).filter(Boolean);
        if (!compList.includes(sNameClean)) continue;

        const res = updateLinkedDebitSettlement(ss, currentRef, remainingPayment, settlerName, false);
        if (res && res.applied > 0) {
          handled.add(res.ref);
          settledRows.push({ ref: res.ref, settled: res.applied, remainingOnRow: res.remaining });
          remainingPayment = res.excess;
        }
      }
      if (remainingPayment <= 0.009) break;
    }
  }

  let excessPayableRow = 0;
  const unmatched = remainingPayment > 0.009 && settledRows.length === 0;
  if (remainingPayment > 0.009 && sNameClean && settledRows.length > 0) {
    const targetSheet = currentSheet || getOrCreateMonthSheet(ss, dateObj || dateOnly);
    const startCol = SCHEMA.PAYABLE.START_COL;
    const numCols = SCHEMA.PAYABLE.NUM_COLS;
    const nextRow = findNextEmptyRowForCols(targetSheet, startCol, startCol + numCols - 1);

    targetSheet.getRange(nextRow, startCol, 1, numCols).setValues([[
      dateObj || dateOnly,
      timeOnly,
      settlerName,
      "Excess settlement credit",
      remainingPayment,
      "",
      "Pending"
    ]]);
    targetSheet.getRange(nextRow, startCol, 1, numCols).setNumberFormats([[
      "dd/mm/yyyy",
      "hh:mm am/pm",
      "@",
      "@",
      "₹#,##0",
      "dd/mm/yyyy",
      "@"
    ]]);
    excessPayableRow = nextRow;
  }

  return {
    remainingPayment: remainingPayment,
    settledRows: settledRows,
    excessRecordedInPayableRow: excessPayableRow,
    unmatched: unmatched
  };
}

/**
 * Cascading Repayment Waterfall Reconciler (money I repay to a creditor).
 * 1. Optional explicit payable row. 2. The creditor's other open payables, oldest month first.
 * 3. Surplus becomes a Debits row (they owe me) ONLY if at least one real payable was repaid.
 */
function reconcilePayablesWaterfall(ss, creditorName, repaymentAmount, initialTargetRef, dateOnly, timeOnly, currentSheet, dateObj) {
  let remainingRepayment = round2_(repaymentAmount);
  const settledRows = [];
  const cNameClean = (creditorName || "").toString().trim().toLowerCase();
  const handled = new Set();

  const cleanRef = extractRowReference(initialTargetRef);
  if (cleanRef) {
    let sName = "";
    let targetRow = 0;
    if (cleanRef.indexOf("!") !== -1) {
      const parts = cleanRef.split("!");
      sName = parts[0].replace(/'/g, "");
      const rowMatch = parts[1].match(/\d+/);
      if (rowMatch) targetRow = parseInt(rowMatch[0], 10);
    } else {
      const rowMatch = cleanRef.match(/\d+/);
      if (rowMatch) {
        targetRow = parseInt(rowMatch[0], 10);
        const now = new Date();
        sName = MONTH_NAMES[now.getMonth()] + now.getFullYear().toString().slice(-2);
      }
    }

    let targetSheet = sName ? ss.getSheetByName(sName) : null;
    if (targetSheet && targetRow >= 4) {
      const resolved = resolveCarriedRow_(ss, targetSheet, targetRow,
        SCHEMA.PAYABLE.START_COL + SCHEMA.PAYABLE.WHAT_FOR, SCHEMA.PAYABLE.START_COL + SCHEMA.PAYABLE.STATUS);
      const liveRef = resolved.sheet.getName() + "!Row" + resolved.row;
      const res = applyRepaymentToPayableRow_(resolved.sheet, resolved.row, cNameClean, remainingRepayment, dateObj || dateOnly);
      handled.add(liveRef);
      if (res) {
        settledRows.push({ ref: liveRef, settled: res.applied, remaining: res.newOwed, status: res.status });
        remainingRepayment = round2_(remainingRepayment - res.applied);
      }
    }
  }

  if (remainingRepayment > 0.009 && cNameClean) {
    for (let s of getLedgerSheetsSorted_(ss)) {
      const lastRow = s.getLastRow();
      if (lastRow <= 3) continue;
      const values = s.getRange(4, SCHEMA.PAYABLE.START_COL, lastRow - 3, SCHEMA.PAYABLE.NUM_COLS).getValues();

      for (let i = 0; i < values.length; i++) {
        if (remainingRepayment <= 0.009) break;
        const targetRow = 4 + i;
        const currentRef = s.getName() + "!Row" + targetRow;
        if (handled.has(currentRef)) continue;

        const status = values[i][SCHEMA.PAYABLE.STATUS];
        if (status !== "Pending" && status !== "Partial") continue;
        const tokens = (values[i][SCHEMA.PAYABLE.PAID_BY] || "").toString().trim().toLowerCase()
          .replace(/\s+(&|and)\s+/gi, ", ").split(",").map(t => t.trim()).filter(Boolean);
        if (!tokens.includes(cNameClean)) continue;

        const res = applyRepaymentToPayableRow_(s, targetRow, cNameClean, remainingRepayment, dateObj || dateOnly);
        if (res) {
          handled.add(currentRef);
          settledRows.push({ ref: currentRef, settled: res.applied, remaining: res.newOwed, status: res.status });
          remainingRepayment = round2_(remainingRepayment - res.applied);
        }
      }
      if (remainingRepayment <= 0.009) break;
    }
  }

  let excessDebitRow = 0;
  if (remainingRepayment > 0.009 && cNameClean && settledRows.length > 0) {
    const targetSheet = currentSheet || getOrCreateMonthSheet(ss, dateObj || dateOnly);
    const nextRow = findNextEmptyRowForCols(targetSheet, SCHEMA.DEBIT.START_COL, SCHEMA.DEBIT.START_COL + SCHEMA.DEBIT.NUM_COLS - 1);

    targetSheet.getRange(nextRow, SCHEMA.DEBIT.START_COL, 1, SCHEMA.DEBIT.NUM_COLS).setValues([[
      dateObj || dateOnly,
      timeOnly,
      "",
      "[Carried Over] Excess repayment balance",
      "",
      "Other",
      remainingRepayment,
      creditorName,
      0,
      remainingRepayment,
      "Pending"
    ]]);
    targetSheet.getRange(nextRow, SCHEMA.DEBIT.START_COL, 1, SCHEMA.DEBIT.NUM_COLS).setNumberFormats([[
      "dd/mm/yyyy",
      "hh:mm am/pm",
      "@",
      "@",
      "@",
      "@",
      "₹#,##0",
      "@",
      "₹#,##0",
      "₹#,##0",
      "@"
    ]]);
    excessDebitRow = nextRow;
  }

  return {
    remainingRepayment: remainingRepayment,
    settledRows: settledRows,
    excessRecordedInDebitRow: excessDebitRow
  };
}

/**
 * Bilateral Debt Netting:
 * If you owe money in Table 3 (Payables) to any of the companions in a new expense,
 * automatically offsets your debt against the new owed_to_me amount.
 * - Deducts from open payables in Table 3 (marking them Repaid or Partial).
 * - Deducts from owedToMe in Table 1.
 * - If owedToMe drops to 0, status becomes "Settled".
 * - If money is still owed to you, status remains "Pending" for the net balance.
 */
function offsetDebitsAgainstPayables(ss, companionsStr, owedToMeTotal, dateStr) {
  let remainingOwedToMe = owedToMeTotal;
  const companionTokens = companionsStr.replace(/\s+(&|and)\s+/gi, ", ").split(",").map(c => c.trim()).filter(Boolean);
  if (companionTokens.length === 0 || remainingOwedToMe <= 0) {
    return { netOwedToMe: remainingOwedToMe, offsetTotal: 0, offsetDetails: [] };
  }

  const perCompanionCap = round2_(owedToMeTotal / companionTokens.length);
  let totalOffset = 0;
  const offsetDetails = [];
  const sheets = getLedgerSheetsSorted_(ss);

  for (let comp of companionTokens) {
    const compClean = comp.toLowerCase();
    let compRemainingCap = perCompanionCap;

    for (let s of sheets) {
      const lastRow = s.getLastRow();
      if (lastRow <= 3) continue;
      const values = s.getRange(4, SCHEMA.PAYABLE.START_COL, lastRow - 3, SCHEMA.PAYABLE.NUM_COLS).getValues();

      for (let i = 0; i < values.length; i++) {
        if (compRemainingCap <= 0.009 || remainingOwedToMe <= 0.009) break;
        const status = values[i][SCHEMA.PAYABLE.STATUS];
        if (status !== "Pending" && status !== "Partial") continue;
        const tokens = (values[i][SCHEMA.PAYABLE.PAID_BY] || "").toString().trim().toLowerCase()
          .replace(/\s+(&|and)\s+/gi, ", ").split(",").map(t => t.trim()).filter(Boolean);
        if (!tokens.includes(compClean)) continue;

        const res = applyRepaymentToPayableRow_(s, 4 + i, compClean, Math.min(compRemainingCap, remainingOwedToMe), dateStr);
        if (!res) continue;

        compRemainingCap = round2_(compRemainingCap - res.applied);
        remainingOwedToMe = round2_(remainingOwedToMe - res.applied);
        totalOffset = round2_(totalOffset + res.applied);
        offsetDetails.push({
          sheet: s.getName(),
          row: 4 + i,
          companion: comp,
          offsetAmount: res.applied,
          remainingPayable: res.newOwed
        });
      }
      if (compRemainingCap <= 0.009) break;
    }
  }

  return {
    netOwedToMe: remainingOwedToMe,
    offsetTotal: totalOffset,
    offsetDetails: offsetDetails
  };
}

/**
 * Bilateral Debt Netting for Payables:
 * When a friend pays for you (logging a payable/debt in Table 3),
 * checks if that friend already owes you money in Table 1 (Debits).
 * If so, automatically offsets your debt against their open debits in Table 1 (FIFO):
 * - Deducts from open debits in Table 1 (marking them Settled or Pending).
 * - Deducts from amountOwed in Table 3.
 * - If amountOwed drops to 0, payable status becomes "Repaid".
 * - If you still owe them after consuming their debits, payable status remains "Pending" for the net balance.
 */
function offsetPayablesAgainstDebits(ss, creditorName, amountOwedTotal, dateStr) {
  let remainingPayable = amountOwedTotal;
  const cNameClean = (creditorName || "").toString().trim().toLowerCase();
  if (!cNameClean || remainingPayable <= 0) {
    return { netAmountOwed: remainingPayable, offsetTotal: 0, offsetDetails: [] };
  }

  let totalOffset = 0;
  const offsetDetails = [];

  for (let s of getLedgerSheetsSorted_(ss)) {
    const lastRow = s.getLastRow();
    if (lastRow <= 3) continue;
    const values = s.getRange(4, SCHEMA.DEBIT.START_COL, lastRow - 3, SCHEMA.DEBIT.NUM_COLS).getValues();

    for (let i = 0; i < values.length; i++) {
      if (remainingPayable <= 0.009) break;
      const rowNum = 4 + i;
      const status = (values[i][SCHEMA.DEBIT.STATUS] || "").toString().trim();
      const owedToMe = parseFloat(values[i][SCHEMA.DEBIT.OWED_TO_ME]) || 0;
      if (status !== "Pending" || owedToMe <= 0) continue;

      const compList = (values[i][SCHEMA.DEBIT.COMPANIONS] || "").toString().trim()
        .replace(/\s+(&|and)\s+/gi, ", ").split(",").map(c => c.trim().toLowerCase()).filter(Boolean);
      if (!compList.includes(cNameClean)) continue;

      const res = updateLinkedDebitSettlement(ss, s.getName() + "!Row" + rowNum, Math.min(owedToMe, remainingPayable), creditorName, false);
      if (res && res.applied > 0) {
        remainingPayable = round2_(remainingPayable - res.applied);
        totalOffset = round2_(totalOffset + res.applied);
        offsetDetails.push({
          sheet: s.getName(),
          row: rowNum,
          creditor: creditorName,
          offsetAmount: res.applied,
          remainingDebitOwed: res.remaining
        });
      }
    }
    if (remainingPayable <= 0.009) break;
  }

  return {
    netAmountOwed: remainingPayable,
    offsetTotal: totalOffset,
    offsetDetails: offsetDetails
  };
}

/**
 * Standard JSON response helper.
 * Injects HTTP status code into payload body.
 */
function responseJSON(data, statusCode) {
  if (data && typeof data === "object") {
    data.code = statusCode || 200;
  }
  return ContentService.createTextOutput(JSON.stringify(data))
    .setMimeType(ContentService.MimeType.JSON);
}
