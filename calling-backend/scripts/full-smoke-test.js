#!/usr/bin/env node

const BASE_URL = process.env.BASE_URL ?? "http://127.0.0.1:3000";
const CATEGORY = "youth";
const ADMIN_USER_ID = 1;
const CALLER_A_USER_ID = 2;
const CALLER_B_CANDIDATES = [3, 4, 5, 6, 7, 8, 9, 10];
const REQUEST_TIMEOUT_MS = 15000;

// Populated at the start of run() via JWT logins.
// Maps legacy userId constants → Bearer token for that user.
const _tokenMap = new Map();
// centreId injected into domain requests (x-centre-id header) after discovery
let _defaultCentreId = null;
// Actual DB id of callerA, resolved from /me after login
let _resolvedCallerAId = null;

const results = [];
const sectionStatus = new Map([
  ["Backend registration", true],
  ["Pool", true],
  ["Assignment visibility", true],
  ["Calling flow", true],
  ["Follow-up flow", true],
  ["Notifications", true],
  ["Dropdowns", true],
  ["Downloads", true],
]);

const stamp = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
const uniqueMobile = `${Math.floor(7000000000 + (Date.now() % 2000000000)).toString().slice(0, 10)}`;

let hardFailure = false;

function markSection(section, passed) {
  if (!sectionStatus.has(section)) {
    sectionStatus.set(section, true);
  }
  if (!passed) {
    sectionStatus.set(section, false);
  }
}

function record(step, name, passed, details, section) {
  results.push({
    step,
    name,
    passed,
    details,
  });
  markSection(section, passed);
  if (!passed) {
    hardFailure = true;
  }
}

function formatValue(value) {
  if (value === undefined) {
    return "";
  }
  if (value === null) {
    return "null";
  }
  if (typeof value === "string") {
    return value;
  }
  if (typeof value === "number" || typeof value === "boolean") {
    return String(value);
  }
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}

function bodyPreview(response) {
  if (response.json !== null && response.json !== undefined) {
    return JSON.stringify(response.json, null, 2);
  }
  return response.text || "<empty body>";
}

function httpStatusLabel(response) {
  return `${response.status}${response.ok ? " OK" : ""}`;
}

async function request(path, options = {}) {
  const {
    method = "GET",
    userId,
    body,
    headers = {},
    timeoutMs = REQUEST_TIMEOUT_MS,
  } = options;

  const url = new URL(path, BASE_URL);
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(new Error("Request timed out")), timeoutMs);

  const requestHeaders = {
    Accept: "application/json",
    ...headers,
  };

  // Use JWT Bearer token if available for this userId (new auth), otherwise fall through
  if (userId !== undefined && userId !== null) {
    const token = _tokenMap.get(userId);
    if (token) {
      requestHeaders["Authorization"] = `Bearer ${token}`;
      // Inject default centreId for domain requests (unless caller already set it)
      if (_defaultCentreId && !requestHeaders["x-centre-id"]) {
        requestHeaders["x-centre-id"] = String(_defaultCentreId);
      }
    } else {
      // Legacy fallback (only works in dev if auth still supports x-user-id)
      requestHeaders["x-user-id"] = String(userId);
    }
  }

  let requestBody;
  if (body !== undefined) {
    requestHeaders["Content-Type"] = "application/json";
    requestBody = JSON.stringify(body);
  }

  try {
    const response = await fetch(url, {
      method,
      headers: requestHeaders,
      body: requestBody,
      signal: controller.signal,
    });

    const text = await response.text();
    const contentType = response.headers.get("content-type") ?? "";
    let json = null;

    if (text && (contentType.includes("json") || /^[\[{]/.test(text.trim()))) {
      try {
        json = JSON.parse(text);
      } catch {
        json = null;
      }
    }

    return {
      ok: response.ok,
      status: response.status,
      url: url.toString(),
      text,
      json,
      contentType,
      headers: Object.fromEntries(response.headers.entries()),
    };
  } catch (error) {
    return {
      ok: false,
      status: 0,
      url: url.toString(),
      text: "",
      json: null,
      contentType: "",
      headers: {},
      error,
    };
  } finally {
    clearTimeout(timeout);
  }
}

function extractData(response) {
  if (!response.json || typeof response.json !== "object") {
    return null;
  }
  return response.json.data ?? null;
}

function getSuccessFlag(response) {
  return Boolean(response.json && response.json.success === true);
}

function ensureShape(response, pathDescription, requiredKeys = []) {
  if (!getSuccessFlag(response)) {
    return `Missing success=true for ${pathDescription}`;
  }
  const data = extractData(response);
  if (!data || typeof data !== "object") {
    return `Missing data object for ${pathDescription}`;
  }
  for (const key of requiredKeys) {
    if (!(key in data)) {
      return `Missing data.${key} for ${pathDescription}`;
    }
  }
  return null;
}

function findItemByRegistrationId(items, registrationId) {
  return items.find((item) => itemContainsRegistrationId(item, registrationId));
}

function itemContainsRegistrationId(value, registrationId) {
  if (!value || typeof value !== "object") {
    return false;
  }

  const candidates = [
    value.id,
    value.registrationId,
    value.registration_id,
    value.registration?.id,
    value.registration?.registrationId,
    value.registration?.registration_id,
    value.profile?.id,
    value.myAttendance?.registrationId,
    value.batchMember?.registrationId,
    value.batch_member?.registration_id,
  ];

  if (candidates.some((candidate) => Number(candidate) === Number(registrationId))) {
    return true;
  }

  for (const nested of Object.values(value)) {
    if (Array.isArray(nested)) {
      if (nested.some((entry) => itemContainsRegistrationId(entry, registrationId))) {
        return true;
      }
    } else if (nested && typeof nested === "object") {
      if (itemContainsRegistrationId(nested, registrationId)) {
        return true;
      }
    }
  }

  return false;
}

function getCallAttempts(details) {
  return (
    details.call_attempts ??
    details.callAttempts ??
    details.last_2_call_attempts ??
    details.last2CallAttempts ??
    []
  );
}

function pickDropdownLabel(dropdowns, preferredAction, fallbackLabel) {
  const active = dropdowns.filter((item) => item.is_active !== false);
  const exact = active.find((item) => item.action === preferredAction);
  if (exact) {
    return exact;
  }

  const labelMatch = active.find(
    (item) => String(item.label).toLowerCase() === String(fallbackLabel).toLowerCase(),
  );
  if (labelMatch) {
    return labelMatch;
  }

  return active[0] ?? null;
}

function logSection(title) {
  console.log(`\n=== ${title} ===`);
}

function printResponseOnFailure(label, response) {
  console.log(`\n[${label}] response body:`);
  console.log(bodyPreview(response));
}

async function getMe(userId) {
  return request("/me", { userId });
}

function isMePayloadValid(response) {
  if (!response.ok || !response.json || typeof response.json !== "object") {
    return false;
  }

  const permissions = response.json.permissions;
  return (
    typeof response.json.id === "number" &&
    typeof response.json.name === "string" &&
    Array.isArray(permissions)
  );
}

async function run() {
  console.log(`Base URL: ${BASE_URL}`);
  console.log(`Category: ${CATEGORY}`);
  console.log(`Unique mobile: ${uniqueMobile}`);
  console.log(`Test stamp: ${stamp}`);

  // ── JWT bootstrap ─────────────────────────────────────────────────────────
  // Login with seeded users and populate _tokenMap so legacy userId: X calls
  // transparently get JWT Bearer tokens.
  //   ADMIN_USER_ID  (1) → Admin A  (mobile 9000000001)
  //   CALLER_A_USER_ID (2) → Caller Both (mobile 9000000002)
  const seedCredentials = [
    { userId: ADMIN_USER_ID, mobile: "9000000001", password: "password123" },
    { userId: CALLER_A_USER_ID, mobile: "9000000002", password: "password123" },
    // Caller B — Centre A only; its actual DB id will be discovered below
    { userId: null, mobile: "9000000003", password: "password123" },
  ];
  for (const cred of seedCredentials) {
    try {
      const loginRes = await request("/auth/login", {
        method: "POST",
        body: { identifier: cred.mobile, password: cred.password },
      });
      const token = loginRes.json?.data?.token;
      if (token) {
        if (cred.userId !== null) {
          _tokenMap.set(cred.userId, token);
        } else {
          // Caller B: discover actual DB id from /me, store in _tokenMap at that id
          const _tempHeaders = { Authorization: `Bearer ${token}` };
          const meRes = await fetch(`${BASE_URL}/me`, { headers: _tempHeaders });
          const meJson = await meRes.json();
          const actualId = meJson?.id;
          if (actualId) {
            _tokenMap.set(actualId, token);
            // Also register at a candidate slot so the CALLER_B_CANDIDATES scan finds it
            if (!CALLER_B_CANDIDATES.includes(actualId)) {
              const firstSlot = CALLER_B_CANDIDATES.find((id) => id !== CALLER_A_USER_ID && !_tokenMap.has(id));
              if (firstSlot) _tokenMap.set(firstSlot, token);
            }
            console.log(`[bootstrap] callerB (${cred.mobile}) DB id=${actualId}`);
          }
        }
      } else {
        console.warn(`[bootstrap] Login failed for ${cred.mobile}: ${loginRes.status} ${loginRes.text?.slice(0, 100)}`);
      }
    } catch (e) {
      console.warn(`[bootstrap] Login error for ${cred.mobile}: ${e}`);
    }
  }

  // Discover centreId and callerA's actual DB id from /me/centres
  if (_tokenMap.has(ADMIN_USER_ID)) {
    const meRes = await request("/me", { userId: ADMIN_USER_ID });
    if (meRes.ok && meRes.json?.id) {
      // /me doesn't need centreId; use it just for user info
    }
    const centresRes = await request("/me/centres", { userId: ADMIN_USER_ID });
    const centresList = centresRes.json?.data ?? [];
    const mainCentre = centresList.find((c) => c.centreCode === "SRKB-MAIN") ?? centresList[0];
    if (mainCentre) {
      _defaultCentreId = mainCentre.centreId;
      console.log(`[bootstrap] Using centreId=${_defaultCentreId} (${mainCentre.centreCode})`);
    } else {
      console.warn("[bootstrap] Could not discover centreId — domain requests will likely fail");
    }
  }

  if (_tokenMap.has(CALLER_A_USER_ID)) {
    // Resolve actual DB id for callerA so the assign-calling check works
    const callerMeRes = await request("/me", { userId: CALLER_A_USER_ID });
    if (callerMeRes.ok && callerMeRes.json?.id) {
      _resolvedCallerAId = callerMeRes.json.id;
      console.log(`[bootstrap] callerA DB id=${_resolvedCallerAId}`);
    }
  }

  let callerBUserId = null;

  logSection("1. /me and permissions");
  const adminMe = await getMe(ADMIN_USER_ID);
  if (isMePayloadValid(adminMe)) {
    const adminData = adminMe.json;
    const permissions = adminData.permissions ?? [];
    const required = [
      "assign_calling_access",
      "attendance_sync_access",
      "dropdown_manage_access",
      "report_access",
    ];
    const missing = required.filter((perm) => !permissions.includes(perm));
    const passed = missing.length === 0;
    const details = `admin permissions: ${permissions.join(", ")}`;
    record(
      "1",
      "GET /me as admin",
      passed,
      passed ? details : `${details}; missing: ${missing.join(", ")}`,
      "Backend registration",
    );
    if (!passed) {
      printResponseOnFailure("GET /me as admin", adminMe);
    }
  } else {
    record(
      "1",
      "GET /me as admin",
      false,
      `unexpected response: ${httpStatusLabel(adminMe)}`,
      "Backend registration",
    );
    printResponseOnFailure("GET /me as admin", adminMe);
  }

  const callerAMe = await getMe(CALLER_A_USER_ID);
  if (isMePayloadValid(callerAMe)) {
    const callerData = callerAMe.json;
    const permissions = callerData.permissions ?? [];
    const required = ["calling_access", "followup_access"];
    const missing = required.filter((perm) => !permissions.includes(perm));
    const passed = missing.length === 0;
    const details = `caller A permissions: ${permissions.join(", ")}`;
    record("2", "GET /me as caller A", passed, passed ? details : `${details}; missing: ${missing.join(", ")}`, "Backend registration");
    if (!passed) {
      printResponseOnFailure("GET /me as caller A", callerAMe);
    }
  } else {
    record(
      "2",
      "GET /me as caller A",
      false,
      `unexpected response: ${httpStatusLabel(callerAMe)}`,
      "Backend registration",
    );
    printResponseOnFailure("GET /me as caller A", callerAMe);
  }

  for (const candidate of CALLER_B_CANDIDATES) {
    if (candidate === CALLER_A_USER_ID) {
      continue;
    }
    const candidateMe = await getMe(candidate);
    if (isMePayloadValid(candidateMe)) {
      const permissions = candidateMe.json.permissions ?? [];
      if (permissions.includes("calling_access")) {
        callerBUserId = candidate;
        const details = `caller B userId=${candidate}, permissions: ${permissions.join(", ")}`;
        record("3", "GET /me as caller B", true, details, "Backend registration");
        break;
      }
    }
  }

  if (!callerBUserId) {
    record(
      "3",
      "GET /me as caller B",
      false,
      "No seeded caller B with calling_access was found in user ids 3-10",
      "Backend registration",
    );
  }

  logSection("2. Registration and pool");
  const registerResponse = await request("/register", {
    method: "POST",
    userId: ADMIN_USER_ID,
    body: {
      name: "CLI Full Test Student",
      mobile: uniqueMobile,
      address: "Patna Test",
      occupation: "Student",
      category: CATEGORY,
      source: "offline",
      remark: "full smoke test",
    },
  });

  let registrationId = null;
  let registrationStageText = "";
  if (registerResponse.ok && getSuccessFlag(registerResponse)) {
    const data = registerResponse.json.data ?? {};
    registrationId = data.id ?? data.registrationId ?? null;
    registrationStageText = `registrationId=${registrationId}, currentStage=${data.currentStage ?? data.current_stage ?? "unknown"}, activeQueue=${data.activeQueue ?? data.active_queue ?? "unknown"}`;
    const passed = Boolean(registrationId) &&
      ["registered", "calling_active", "followup_active", "attended_unassigned", "batch_calling", "sent_back_to_owner", "removed", "archived"].includes(
        String(data.currentStage ?? data.current_stage ?? ""),
      ) &&
      String(data.activeQueue ?? data.active_queue ?? "none") === "none";

    record("4", "POST /register", passed, registrationStageText, "Backend registration");
    if (!passed) {
      printResponseOnFailure("POST /register", registerResponse);
    }
  } else {
    record(
      "4",
      "POST /register",
      false,
      `unexpected response: ${httpStatusLabel(registerResponse)}`,
      "Backend registration",
    );
    printResponseOnFailure("POST /register", registerResponse);
  }

  if (!registrationId) {
    throw new Error("Registration id could not be determined; aborting smoke test.");
  }

  const poolResponse = await request(`/pool/new-registrations?category=${encodeURIComponent(CATEGORY)}`, {
    userId: ADMIN_USER_ID,
  });

  let poolPassed = false;
  let poolDetails = "";
  if (poolResponse.ok && getSuccessFlag(poolResponse)) {
    const shapeError = ensureShape(poolResponse, "GET /pool/new-registrations", ["count", "items"]);
    if (!shapeError) {
      const data = poolResponse.json.data;
      const found = findItemByRegistrationId(data.items ?? [], registrationId);
      poolPassed = Boolean(found);
      poolDetails = `count=${data.count}, foundRegistration=${Boolean(found)}`;
      if (!poolPassed) {
        poolDetails += `, response items=${formatValue(data.items)}`;
      }
    } else {
      poolPassed = false;
      poolDetails = shapeError;
    }
  } else {
    poolPassed = false;
    poolDetails = `unexpected response: ${httpStatusLabel(poolResponse)}`;
  }
  record("5", "GET /pool/new-registrations", poolPassed, poolDetails, "Pool");
  if (!poolPassed) {
    printResponseOnFailure("GET /pool/new-registrations", poolResponse);
  }

  logSection("3. Assign calling and visibility");
  const assignResponse = await request("/assign-calling", {
    method: "POST",
    userId: ADMIN_USER_ID,
    body: {
      pool_type: "new_registration_pool",
      registration_ids: [registrationId],
      caller_id: _resolvedCallerAId ?? CALLER_A_USER_ID,
      active_days: 3,
    },
  });

  let assignPassed = false;
  if (assignResponse.ok && getSuccessFlag(assignResponse)) {
    const data = assignResponse.json.data ?? {};
    let assignmentItem = null;
    if (Array.isArray(data.items)) {
      assignmentItem = findItemByRegistrationId(data.items, registrationId);
    } else if (Array.isArray(data.assignments)) {
      assignmentItem = findItemByRegistrationId(data.assignments, registrationId);
    } else if (data.registration || data.assignment) {
      assignmentItem = data;
    }

    const maybeRegistration = assignmentItem?.registration ?? assignmentItem?.registration_data ?? assignmentItem?.registrationDetail ?? data.registration;
    const maybeAssignment = assignmentItem?.assignment ?? assignmentItem?.callingAssignment ?? data.assignment;

    const stage = maybeRegistration?.currentStage ?? maybeRegistration?.current_stage ?? maybeRegistration?.currentStageName ?? maybeRegistration?.stage;
    const queue = maybeRegistration?.activeQueue ?? maybeRegistration?.active_queue ?? maybeRegistration?.queue;
    const assignedBy =
      maybeAssignment?.assignedBy ?? maybeAssignment?.assigned_by ?? assignmentItem?.assignedBy ?? assignmentItem?.assigned_by;
    const assignedTo =
      maybeAssignment?.assignedToUserId ??
      maybeAssignment?.assigned_to_user_id ??
      maybeAssignment?.assignedTo ??
      maybeRegistration?.currentAssignedCallerId ??
      maybeRegistration?.current_assigned_caller_id;

    assignPassed =
      Number(assignedTo) === (_resolvedCallerAId ?? CALLER_A_USER_ID) &&
      String(stage) === "calling_active" &&
      String(queue) === "calling" &&
      ["System Admin", "System Super Admin", "superadmin", "Centre Admin", "Admin A"].includes(String(assignedBy));
    const details = `assignedTo=${assignedTo}, assignedBy=${assignedBy}, currentStage=${stage}, activeQueue=${queue}`;
    record("6", "POST /assign-calling", assignPassed, details, "Assignment visibility");
    if (!assignPassed) {
      printResponseOnFailure("POST /assign-calling", assignResponse);
    }
  } else {
    record(
      "6",
      "POST /assign-calling",
      false,
      `unexpected response: ${httpStatusLabel(assignResponse)}`,
      "Assignment visibility",
    );
    printResponseOnFailure("POST /assign-calling", assignResponse);
  }

  const poolAfterAssignResponse = await request(`/pool/new-registrations?category=${encodeURIComponent(CATEGORY)}`, {
    userId: ADMIN_USER_ID,
  });
  let poolAfterAssignPassed = false;
  if (poolAfterAssignResponse.ok && getSuccessFlag(poolAfterAssignResponse)) {
    const shapeError = ensureShape(poolAfterAssignResponse, "GET /pool/new-registrations", ["count", "items"]);
    if (!shapeError) {
      const items = poolAfterAssignResponse.json.data.items ?? [];
      const found = findItemByRegistrationId(items, registrationId);
      poolAfterAssignPassed = !found;
      const details = `registration visible after assign=${Boolean(found)}`;
      record("7", "GET /pool/new-registrations after assign", poolAfterAssignPassed, details, "Pool");
      if (!poolAfterAssignPassed) {
        printResponseOnFailure("GET /pool/new-registrations after assign", poolAfterAssignResponse);
      }
    } else {
      record("7", "GET /pool/new-registrations after assign", false, shapeError, "Pool");
      printResponseOnFailure("GET /pool/new-registrations after assign", poolAfterAssignResponse);
    }
  } else {
    record(
      "7",
      "GET /pool/new-registrations after assign",
      false,
      `unexpected response: ${httpStatusLabel(poolAfterAssignResponse)}`,
      "Pool",
    );
    printResponseOnFailure("GET /pool/new-registrations after assign", poolAfterAssignResponse);
  }

  const callingListAResponse = await request(`/calling-list?category=${encodeURIComponent(CATEGORY)}`, {
    userId: CALLER_A_USER_ID,
  });
  let callingAFound = false;
  if (callingListAResponse.ok && getSuccessFlag(callingListAResponse)) {
    const shapeError = ensureShape(callingListAResponse, "GET /calling-list", ["count", "items"]);
    if (!shapeError) {
      const items = callingListAResponse.json.data.items ?? [];
      callingAFound = Boolean(findItemByRegistrationId(items, registrationId));
      const details = `count=${callingListAResponse.json.data.count}, foundRegistration=${callingAFound}`;
      record("8", "GET /calling-list as caller A", callingAFound, details, "Assignment visibility");
      if (!callingAFound) {
        printResponseOnFailure("GET /calling-list as caller A", callingListAResponse);
      }
    } else {
      record("8", "GET /calling-list as caller A", false, shapeError, "Assignment visibility");
      printResponseOnFailure("GET /calling-list as caller A", callingListAResponse);
    }
  } else {
    record(
      "8",
      "GET /calling-list as caller A",
      false,
      `unexpected response: ${httpStatusLabel(callingListAResponse)}`,
      "Assignment visibility",
    );
    printResponseOnFailure("GET /calling-list as caller A", callingListAResponse);
  }
  if (!callingAFound) {
    console.log("ASSIGNMENT VISIBILITY BUG");
  }

  if (callerBUserId !== null) {
    const callingListBResponse = await request(`/calling-list?category=${encodeURIComponent(CATEGORY)}`, {
      userId: callerBUserId,
    });
    let callerBHidden = false;
    if (callingListBResponse.ok && getSuccessFlag(callingListBResponse)) {
      const shapeError = ensureShape(callingListBResponse, "GET /calling-list", ["count", "items"]);
      if (!shapeError) {
        const items = callingListBResponse.json.data.items ?? [];
        callerBHidden = !Boolean(findItemByRegistrationId(items, registrationId));
        const details = `count=${callingListBResponse.json.data.count}, registrationVisible=${!callerBHidden}`;
        record("9", "GET /calling-list as caller B", callerBHidden, details, "Assignment visibility");
        if (!callerBHidden) {
          printResponseOnFailure("GET /calling-list as caller B", callingListBResponse);
        }
      } else {
        record("9", "GET /calling-list as caller B", false, shapeError, "Assignment visibility");
        printResponseOnFailure("GET /calling-list as caller B", callingListBResponse);
      }
    } else {
      record(
        "9",
        "GET /calling-list as caller B",
        false,
        `unexpected response: ${httpStatusLabel(callingListBResponse)}`,
        "Assignment visibility",
      );
      printResponseOnFailure("GET /calling-list as caller B", callingListBResponse);
    }
  } else {
    record("9", "GET /calling-list as caller B", false, "caller B user id not found", "Assignment visibility");
  }

  logSection("4. Notifications");
  const notificationResponse = await request("/notifications", {
    userId: CALLER_A_USER_ID,
  });
  let notificationPassed = false;
  if (notificationResponse.ok && getSuccessFlag(notificationResponse)) {
    const shapeError = ensureShape(notificationResponse, "GET /notifications", ["count", "items"]);
    if (!shapeError) {
      const items = notificationResponse.json.data.items ?? [];
      const matching = items.filter((item) => {
        const title = String(item.title ?? "").toLowerCase();
        const message = String(item.message ?? "").toLowerCase();
        return (
          title.includes("calling assigned") ||
          title.includes("new calling assigned") ||
          message.includes("calling assigned") ||
          message.includes("new calling")
        );
      });
      notificationPassed = matching.length > 0;
      const details = notificationPassed
        ? `found assignment notification(s): ${matching.length}`
        : "Assignment notification missing. Backend should create notification on assign-calling.";
      record("10", "GET /notifications as caller A", notificationPassed, details, "Notifications");
      if (!notificationPassed) {
        printResponseOnFailure("GET /notifications as caller A", notificationResponse);
      }
    } else {
      record("10", "GET /notifications as caller A", false, shapeError, "Notifications");
      printResponseOnFailure("GET /notifications as caller A", notificationResponse);
    }
  } else {
    record(
      "10",
      "GET /notifications as caller A",
      false,
      `unexpected response: ${httpStatusLabel(notificationResponse)}`,
      "Notifications",
    );
    printResponseOnFailure("GET /notifications as caller A", notificationResponse);
  }

  logSection("5. Calling -> Follow-up");
  const dropdownsCallingResponse = await request(`/dropdowns?context=calling&category=${encodeURIComponent(CATEGORY)}`, {
    userId: ADMIN_USER_ID,
  });
  let callingDropdown = null;
  if (dropdownsCallingResponse.ok && getSuccessFlag(dropdownsCallingResponse)) {
    const shapeError = ensureShape(dropdownsCallingResponse, "GET /dropdowns", ["count", "items"]);
    if (!shapeError) {
      const items = dropdownsCallingResponse.json.data.items ?? [];
      callingDropdown = pickDropdownLabel(items, "move_to_followup", "Will Come");
      const passed = Boolean(callingDropdown);
      const details = passed
        ? `selected dropdown id=${callingDropdown.id}, label=${callingDropdown.label}, action=${callingDropdown.action}`
        : "No calling dropdown found";
      record("11", "GET /dropdowns for calling", passed, details, "Dropdowns");
      if (!passed) {
        printResponseOnFailure("GET /dropdowns for calling", dropdownsCallingResponse);
      }
    } else {
      record("11", "GET /dropdowns for calling", false, shapeError, "Dropdowns");
      printResponseOnFailure("GET /dropdowns for calling", dropdownsCallingResponse);
    }
  } else {
    record(
      "11",
      "GET /dropdowns for calling",
      false,
      `unexpected response: ${httpStatusLabel(dropdownsCallingResponse)}`,
      "Dropdowns",
    );
    printResponseOnFailure("GET /dropdowns for calling", dropdownsCallingResponse);
  }

  const callingDoneResponse = await request("/calling-done", {
    method: "POST",
    userId: CALLER_A_USER_ID,
    body: {
      registration_id: registrationId,
      dropdown_id: callingDropdown?.id ?? undefined,
      dropdown_value: callingDropdown?.label ?? "Will Come",
      remark: "CLI call done",
    },
  });

  let callingDonePassed = false;
  if (callingDoneResponse.ok && getSuccessFlag(callingDoneResponse)) {
    const data = callingDoneResponse.json.data ?? {};
    const details = `status=${callingDoneResponse.status}, payloadKeys=${Object.keys(data).join(", ")}`;
    callingDonePassed = true;
    record("12", "POST /calling-done", callingDonePassed, details, "Calling flow");
  } else {
    record(
      "12",
      "POST /calling-done",
      false,
      `unexpected response: ${httpStatusLabel(callingDoneResponse)}`,
      "Calling flow",
    );
    printResponseOnFailure("POST /calling-done", callingDoneResponse);
  }

  const callingListAAfterDone = await request(`/calling-list?category=${encodeURIComponent(CATEGORY)}`, {
    userId: CALLER_A_USER_ID,
  });
  let callingRemoved = false;
  if (callingListAAfterDone.ok && getSuccessFlag(callingListAAfterDone)) {
    const shapeError = ensureShape(callingListAAfterDone, "GET /calling-list", ["count", "items"]);
    if (!shapeError) {
      const items = callingListAAfterDone.json.data.items ?? [];
      callingRemoved = !Boolean(findItemByRegistrationId(items, registrationId));
      record(
        "13",
        "GET /calling-list after calling-done",
        callingRemoved,
        `registrationVisible=${!callingRemoved}`,
        "Calling flow",
      );
      if (!callingRemoved) {
        printResponseOnFailure("GET /calling-list after calling-done", callingListAAfterDone);
      }
    } else {
      record("13", "GET /calling-list after calling-done", false, shapeError, "Calling flow");
      printResponseOnFailure("GET /calling-list after calling-done", callingListAAfterDone);
    }
  } else {
    record(
      "13",
      "GET /calling-list after calling-done",
      false,
      `unexpected response: ${httpStatusLabel(callingListAAfterDone)}`,
      "Calling flow",
    );
    printResponseOnFailure("GET /calling-list after calling-done", callingListAAfterDone);
  }

  const followupListAResponse = await request(`/followup-list?category=${encodeURIComponent(CATEGORY)}`, {
    userId: CALLER_A_USER_ID,
  });
  let followupAFound = false;
  if (followupListAResponse.ok && getSuccessFlag(followupListAResponse)) {
    const shapeError = ensureShape(followupListAResponse, "GET /followup-list", ["count", "items"]);
    if (!shapeError) {
      const items = followupListAResponse.json.data.items ?? [];
      followupAFound = Boolean(findItemByRegistrationId(items, registrationId));
      record(
        "14",
        "GET /followup-list as caller A",
        followupAFound,
        `count=${followupListAResponse.json.data.count}, foundRegistration=${followupAFound}`,
        "Calling flow",
      );
      if (!followupAFound) {
        printResponseOnFailure("GET /followup-list as caller A", followupListAResponse);
      }
    } else {
      record("14", "GET /followup-list as caller A", false, shapeError, "Calling flow");
      printResponseOnFailure("GET /followup-list as caller A", followupListAResponse);
    }
  } else {
    record(
      "14",
      "GET /followup-list as caller A",
      false,
      `unexpected response: ${httpStatusLabel(followupListAResponse)}`,
      "Calling flow",
    );
    printResponseOnFailure("GET /followup-list as caller A", followupListAResponse);
  }

  const dropdownsFollowupResponse = await request(`/dropdowns?context=followup&category=${encodeURIComponent(CATEGORY)}`, {
    userId: ADMIN_USER_ID,
  });
  let followupDropdown = null;
  if (dropdownsFollowupResponse.ok && getSuccessFlag(dropdownsFollowupResponse)) {
    const shapeError = ensureShape(dropdownsFollowupResponse, "GET /dropdowns", ["count", "items"]);
    if (!shapeError) {
      const items = dropdownsFollowupResponse.json.data.items ?? [];
      followupDropdown = pickDropdownLabel(items, "stay_in_followup", "Reminder Done");
      const passed = Boolean(followupDropdown);
      record(
        "15.1",
        "GET /dropdowns for follow-up",
        passed,
        passed
          ? `selected dropdown id=${followupDropdown.id}, label=${followupDropdown.label}, action=${followupDropdown.action}`
          : "No follow-up dropdown found",
        "Calling flow",
      );
      if (!passed) {
        printResponseOnFailure("GET /dropdowns for follow-up", dropdownsFollowupResponse);
      }
    } else {
      record("15.1", "GET /dropdowns for follow-up", false, shapeError, "Calling flow");
      printResponseOnFailure("GET /dropdowns for follow-up", dropdownsFollowupResponse);
    }
  } else {
    record(
      "15.1",
      "GET /dropdowns for follow-up",
      false,
      `unexpected response: ${httpStatusLabel(dropdownsFollowupResponse)}`,
      "Calling flow",
    );
    printResponseOnFailure("GET /dropdowns for follow-up", dropdownsFollowupResponse);
  }

  if (callerBUserId !== null) {
    const followupListBResponse = await request(`/followup-list?category=${encodeURIComponent(CATEGORY)}`, {
      userId: callerBUserId,
    });
    let followupHidden = false;
    if (followupListBResponse.ok && getSuccessFlag(followupListBResponse)) {
      const shapeError = ensureShape(followupListBResponse, "GET /followup-list", ["count", "items"]);
      if (!shapeError) {
        const items = followupListBResponse.json.data.items ?? [];
        followupHidden = !Boolean(findItemByRegistrationId(items, registrationId));
        record(
          "15",
          "GET /followup-list as caller B",
          followupHidden,
          `registrationVisible=${!followupHidden}`,
          "Follow-up flow",
        );
        if (!followupHidden) {
          printResponseOnFailure("GET /followup-list as caller B", followupListBResponse);
        }
      } else {
        record("15", "GET /followup-list as caller B", false, shapeError, "Follow-up flow");
        printResponseOnFailure("GET /followup-list as caller B", followupListBResponse);
      }
    } else {
      record(
        "15",
        "GET /followup-list as caller B",
        false,
        `unexpected response: ${httpStatusLabel(followupListBResponse)}`,
        "Follow-up flow",
      );
      printResponseOnFailure("GET /followup-list as caller B", followupListBResponse);
    }
  } else {
    record("15", "GET /followup-list as caller B", false, "caller B user id not found", "Follow-up flow");
  }

  const followupDoneResponse = await request("/followup-done", {
    method: "POST",
    userId: CALLER_A_USER_ID,
    body: {
      registration_id: registrationId,
      dropdown_id: followupDropdown?.id ?? undefined,
      dropdown_value: followupDropdown?.label ?? "Reminder Done",
      remark: "CLI follow-up done",
    },
  });

  if (followupDoneResponse.ok && getSuccessFlag(followupDoneResponse)) {
    record("16", "POST /followup-done", true, `status=${followupDoneResponse.status}`, "Follow-up flow");
  } else {
    record(
      "16",
      "POST /followup-done",
      false,
      `unexpected response: ${httpStatusLabel(followupDoneResponse)}`,
      "Follow-up flow",
    );
    printResponseOnFailure("POST /followup-done", followupDoneResponse);
  }

  const followupListAAfterDone = await request(`/followup-list?category=${encodeURIComponent(CATEGORY)}`, {
    userId: CALLER_A_USER_ID,
  });
  let followupStillVisible = false;
  if (followupListAAfterDone.ok && getSuccessFlag(followupListAAfterDone)) {
    const shapeError = ensureShape(followupListAAfterDone, "GET /followup-list", ["count", "items"]);
    if (!shapeError) {
      const items = followupListAAfterDone.json.data.items ?? [];
      followupStillVisible = Boolean(findItemByRegistrationId(items, registrationId));
      record(
        "17",
        "GET /followup-list after followup-done",
        followupStillVisible,
        `registrationVisible=${followupStillVisible}`,
        "Follow-up flow",
      );
      if (!followupStillVisible) {
        printResponseOnFailure("GET /followup-list after followup-done", followupListAAfterDone);
      }
    } else {
      record("17", "GET /followup-list after followup-done", false, shapeError, "Follow-up flow");
      printResponseOnFailure("GET /followup-list after followup-done", followupListAAfterDone);
    }
  } else {
    record(
      "17",
      "GET /followup-list after followup-done",
      false,
      `unexpected response: ${httpStatusLabel(followupListAAfterDone)}`,
      "Follow-up flow",
    );
    printResponseOnFailure("GET /followup-list after followup-done", followupListAAfterDone);
  }

  const detailsResponse = await request(`/registrations/${registrationId}/details`, {
    userId: ADMIN_USER_ID,
  });
  let detailsPassed = false;
  if (detailsResponse.ok && getSuccessFlag(detailsResponse)) {
    const data = detailsResponse.json.data ?? {};
    const profile = data.profile ?? data.registration ?? null;
    const attempts = getCallAttempts(data);
    const stage = profile?.current_stage ?? profile?.currentStage;
    const queue = profile?.active_queue ?? profile?.activeQueue;
    const hasCallingAttempt = Array.isArray(attempts) && attempts.some((attempt) => {
      const callType = String(attempt.call_type ?? attempt.callType ?? "").toLowerCase();
      return callType.includes("calling");
    });
    const hasFollowupAttempt = Array.isArray(attempts) && attempts.some((attempt) => {
      const callType = String(attempt.call_type ?? attempt.callType ?? "").toLowerCase();
      return callType.includes("followup");
    });
    detailsPassed = Boolean(profile) && String(stage) === "followup_active" && String(queue) === "followup" && hasCallingAttempt && hasFollowupAttempt;
    record(
      "18",
      "GET /registrations/:id/details",
      detailsPassed,
      `profile=${Boolean(profile)}, current_stage=${stage}, active_queue=${queue}, callingAttempt=${hasCallingAttempt}, followupAttempt=${hasFollowupAttempt}`,
      "Calling flow",
    );
    if (!detailsPassed) {
      printResponseOnFailure("GET /registrations/:id/details", detailsResponse);
    }
  } else {
    record(
      "18",
      "GET /registrations/:id/details",
      false,
      `unexpected response: ${httpStatusLabel(detailsResponse)}`,
      "Calling flow",
    );
    printResponseOnFailure("GET /registrations/:id/details", detailsResponse);
  }

  logSection("6. Dropdowns");
  const dropdownListResponse = await request(`/dropdowns?context=calling&category=${encodeURIComponent(CATEGORY)}`, {
    userId: ADMIN_USER_ID,
  });
  let dropdownListOk = false;
  if (dropdownListResponse.ok && getSuccessFlag(dropdownListResponse)) {
    const shapeError = ensureShape(dropdownListResponse, "GET /dropdowns", ["count", "items"]);
    dropdownListOk = !shapeError;
    record("19", "GET /dropdowns", dropdownListOk, shapeError ?? `count=${dropdownListResponse.json.data.count}`, "Dropdowns");
    if (!dropdownListOk) {
      printResponseOnFailure("GET /dropdowns", dropdownListResponse);
    }
  } else {
    record(
      "19",
      "GET /dropdowns",
      false,
      `unexpected response: ${httpStatusLabel(dropdownListResponse)}`,
      "Dropdowns",
    );
    printResponseOnFailure("GET /dropdowns", dropdownListResponse);
  }

  const createLabel = `CLI Test Move Followup ${stamp}`;
  const createDropdownResponse = await request("/dropdowns", {
    method: "POST",
    userId: ADMIN_USER_ID,
    body: {
      label: createLabel,
      context: "calling",
      category: CATEGORY,
      action: "move_to_followup",
      sort_order: 999,
    },
  });

  let createdDropdownId = null;
  if (createDropdownResponse.ok && getSuccessFlag(createDropdownResponse)) {
    const data = createDropdownResponse.json.data ?? {};
    createdDropdownId = data.id ?? null;
    const passed = Boolean(createdDropdownId);
    record(
      "20",
      "POST /dropdowns",
      passed,
      passed ? `created dropdown id=${createdDropdownId}` : "Dropdown id missing in create response",
      "Dropdowns",
    );
    if (!passed) {
      printResponseOnFailure("POST /dropdowns", createDropdownResponse);
    }
  } else {
    record(
      "20",
      "POST /dropdowns",
      false,
      `unexpected response: ${httpStatusLabel(createDropdownResponse)}`,
      "Dropdowns",
    );
    printResponseOnFailure("POST /dropdowns", createDropdownResponse);
  }

  const dropdownListAfterCreate = await request(`/dropdowns?context=calling&category=${encodeURIComponent(CATEGORY)}`, {
    userId: ADMIN_USER_ID,
  });
  let createdVisible = false;
  if (dropdownListAfterCreate.ok && getSuccessFlag(dropdownListAfterCreate)) {
    const shapeError = ensureShape(dropdownListAfterCreate, "GET /dropdowns", ["count", "items"]);
    if (!shapeError) {
      const items = dropdownListAfterCreate.json.data.items ?? [];
      createdVisible = Boolean(items.find((item) => Number(item.id) === Number(createdDropdownId)));
      record(
        "21",
        "GET /dropdowns after create",
        createdVisible,
        `createdVisible=${createdVisible}`,
        "Dropdowns",
      );
      if (!createdVisible) {
        printResponseOnFailure("GET /dropdowns after create", dropdownListAfterCreate);
      }
    } else {
      record("21", "GET /dropdowns after create", false, shapeError, "Dropdowns");
      printResponseOnFailure("GET /dropdowns after create", dropdownListAfterCreate);
    }
  } else {
    record(
      "21",
      "GET /dropdowns after create",
      false,
      `unexpected response: ${httpStatusLabel(dropdownListAfterCreate)}`,
      "Dropdowns",
    );
    printResponseOnFailure("GET /dropdowns after create", dropdownListAfterCreate);
  }

  if (createdDropdownId) {
    const disableDropdownResponse = await request(`/dropdowns/${createdDropdownId}/disable`, {
      method: "PATCH",
      userId: ADMIN_USER_ID,
    });

    let disableOk = false;
    if (disableDropdownResponse.ok && getSuccessFlag(disableDropdownResponse)) {
      const data = disableDropdownResponse.json.data ?? {};
      const inactive = data.is_active === false || data.isActive === false;
      disableOk = inactive;
      record(
        "22",
        "PATCH /dropdowns/:id/disable",
        disableOk,
        `is_active=${data.is_active ?? data.isActive ?? "unknown"}`,
        "Dropdowns",
      );
      if (!disableOk) {
        printResponseOnFailure("PATCH /dropdowns/:id/disable", disableDropdownResponse);
      }
    } else {
      record(
        "22",
        "PATCH /dropdowns/:id/disable",
        false,
        `unexpected response: ${httpStatusLabel(disableDropdownResponse)}`,
        "Dropdowns",
      );
      printResponseOnFailure("PATCH /dropdowns/:id/disable", disableDropdownResponse);
    }
  } else {
    record("22", "PATCH /dropdowns/:id/disable", false, "created dropdown id unavailable", "Dropdowns");
  }

  logSection("7. Downloads (optional)");
  const downloads = [
    ["/downloads/new-registrations?category=youth", "new-registrations", ["id", "name", "mobile"]],
    ["/downloads/calling-list?category=youth", "calling-list", ["id", "name", "mobile"]],
    ["/downloads/followup-list?category=youth", "followup-list", ["id", "name", "mobile"]],
    ["/downloads/dropdowns?context=calling&category=youth", "dropdowns", ["id", "label", "context"]],
  ];

  let downloadMissing = false;
  let downloadFail = false;

  for (const [path, label, expectedHeaders] of downloads) {
    const response = await request(path, { userId: ADMIN_USER_ID, headers: { Accept: "text/csv,*/*" } });
    if (response.status === 404) {
      console.log(`Download endpoint missing: ${path}`);
      downloadMissing = true;
      continue;
    }
    if (!response.ok) {
      record(`D-${label}`, `GET ${path}`, false, `unexpected response: ${httpStatusLabel(response)}`, "Downloads");
      printResponseOnFailure(`GET ${path}`, response);
      downloadFail = true;
      continue;
    }
    const contentType = response.contentType.toLowerCase();
    const nonEmpty = (response.text ?? "").trim().length > 0;
    const csvOk = contentType.includes("text/csv");
    const headerLine = (response.text ?? "").trim().split(/\r?\n/)[0] ?? "";
    const headerOk = expectedHeaders.every((header) => headerLine.includes(header));
    const passed = csvOk && nonEmpty && headerOk;
    record(
      `D-${label}`,
      `GET ${path}`,
      passed,
      `content-type=${response.contentType}, nonEmpty=${nonEmpty}, headerOk=${headerOk}`,
      "Downloads",
    );
    if (!passed) {
      printResponseOnFailure(`GET ${path}`, response);
      downloadFail = true;
    }
  }

  await runMultiCentreTests();

  printResults();

  const summary = [
    ["Backend registration", sectionStatus.get("Backend registration") ? "PASS" : "FAIL"],
    ["Pool", sectionStatus.get("Pool") ? "PASS" : "FAIL"],
    ["Assignment visibility", sectionStatus.get("Assignment visibility") ? "PASS" : "FAIL"],
    ["Calling flow", sectionStatus.get("Calling flow") ? "PASS" : "FAIL"],
    ["Follow-up flow", sectionStatus.get("Follow-up flow") ? "PASS" : "FAIL"],
    ["Notifications", sectionStatus.get("Notifications") ? "PASS" : "FAIL"],
    ["Dropdowns", sectionStatus.get("Dropdowns") ? "PASS" : "FAIL"],
    [
      "Downloads",
      downloadMissing && !downloadFail
        ? "MISSING"
        : sectionStatus.get("Downloads") && !downloadFail
          ? "PASS"
          : "FAIL",
    ],
    ["Multi-centre", sectionStatus.get("Multi-centre") ? "PASS" : "FAIL"],
  ];

  console.log("\nSummary:");
  for (const [name, value] of summary) {
    console.log(`- ${name}: ${value}`);
  }

  if (hardFailure) {
    process.exitCode = 1;
  }
}

// ── Multi-centre invariant tests (Phase 9) ────────────────────────────────────
// These tests use JWT auth (Bearer token) rather than the legacy x-user-id header.
// They require the seed data from prisma/seed.ts: two centres + three seeded users.

async function jwtLogin(mobile, password = "password123") {
  const res = await request("/auth/login", {
    method: "POST",
    body: { identifier: mobile, password },
  });
  const token = res.json?.data?.token;
  if (!token) throw new Error(`JWT login failed for ${mobile}: ${res.status} ${res.text?.slice(0, 200)}`);
  return token;
}

async function authedRequest(path, opts = {}) {
  const { token, centreId, ...rest } = opts;
  const headers = opts.headers ?? {};
  if (token) headers["Authorization"] = `Bearer ${token}`;
  if (centreId) headers["x-centre-id"] = String(centreId);
  return request(path, { ...rest, headers, userId: undefined });
}

async function runMultiCentreTests() {
  sectionStatus.set("Multi-centre", true);
  logSection("Multi-Centre Invariants");

  let hostToken, callerToken, adminAToken;
  try {
    hostToken = await jwtLogin("superadmin", process.env.SUPERADMIN_PASSWORD);
    adminAToken = await jwtLogin("9000000001");
    callerToken = await jwtLogin("9000000002");
  } catch (e) {
    record("MC-0", "JWT login for seed users", false, String(e), "Multi-centre");
    return;
  }
  record("MC-0", "JWT login for all three seed users", true, "tokens acquired", "Multi-centre");

  // Discover centre IDs from /me/centres
  const centresRes = await authedRequest("/me/centres", { token: callerToken });
  const callerCentres = centresRes.json?.data ?? [];
  const centreA = callerCentres.find((c) => c.centreCode === "SRKB-MAIN");
  const centreB = callerCentres.find((c) => c.centreCode === "SRKB-BRANCH");
  if (!centreA || !centreB) {
    record("MC-1", "Caller sees both centres in /me/centres", false, `got codes: ${callerCentres.map((c) => c.centreCode).join(", ")}`, "Multi-centre");
    return;
  }
  record("MC-1", "Caller sees both centres in /me/centres", true, `centreA=${centreA.centreId} centreB=${centreB.centreId}`, "Multi-centre");

  const adminACentresRes = await authedRequest("/me/centres", { token: adminAToken });
  const adminCentres = adminACentresRes.json?.data ?? [];
  record("MC-1b", "Admin A sees only Centre A", adminCentres.length === 1 && adminCentres[0]?.centreCode === "SRKB-MAIN", `codes=${adminCentres.map((c) => c.centreCode).join(",")}`, "Multi-centre");

  // 2. Same mobile at two centres succeeds (J1)
  const testMobile = `9${Date.now().toString().slice(-9)}`;
  const regA = await authedRequest("/register", { method: "POST", token: callerToken, centreId: centreA.centreId, body: { name: "J1 Test", mobile: testMobile, category: "youth" } });
  const regB = await authedRequest("/register", { method: "POST", token: callerToken, centreId: centreB.centreId, body: { name: "J1 Test", mobile: testMobile, category: "youth" } });
  record("MC-2a", "Same mobile at Centre A → 201", regA.status === 201, `status=${regA.status}`, "Multi-centre");
  record("MC-2b", "Same mobile at Centre B → 201", regB.status === 201, `status=${regB.status}`, "Multi-centre");

  // 3. Profile suggestion returns cross-centre entry
  const suggRes = await authedRequest(`/registrations/profile-suggestion?mobile=9111111111`, { token: callerToken, centreId: centreB.centreId });
  const suggestions = suggRes.json?.data?.suggestions ?? [];
  record(
    "MC-3",
    "Profile suggestion includes Centre A entry for shared mobile",
    suggRes.status === 200 && suggestions.some((s) => s.centreId === centreA.centreId),
    `status=${suggRes.status} suggestions=${suggestions.length}`,
    "Multi-centre",
  );

  // 4. Centre isolation — lists don't overlap
  const listA = await authedRequest("/registrations", { token: callerToken, centreId: centreA.centreId });
  const listB = await authedRequest("/registrations", { token: callerToken, centreId: centreB.centreId });
  const idsA = new Set((listA.json?.data?.registrations ?? listA.json?.data?.items ?? []).map((r) => r.id));
  const idsB = new Set((listB.json?.data?.registrations ?? listB.json?.data?.items ?? []).map((r) => r.id));
  const overlap = [...idsA].filter((id) => idsB.has(id));
  record("MC-4", "No registration ID overlap between Centre A and B lists", overlap.length === 0, `overlap=${JSON.stringify(overlap)}`, "Multi-centre");

  // 5. Admin A cannot access Centre B
  const forbiddenRes = await authedRequest("/registrations", { token: adminAToken, centreId: centreB.centreId });
  record("MC-5", "Admin-A-only user gets 403 at Centre B", forbiddenRes.status === 403, `status=${forbiddenRes.status}`, "Multi-centre");

  // 6. Host stats summary lists both centres
  const summaryRes = await authedRequest("/host/stats/summary", { token: hostToken });
  const centreCodes = (summaryRes.json?.data?.centres ?? []).map((c) => c.centreCode);
  record("MC-6a", "Host stats summary returns 200", summaryRes.status === 200, `status=${summaryRes.status}`, "Multi-centre");
  record("MC-6b", "Summary includes SRKB-MAIN", centreCodes.includes("SRKB-MAIN"), `codes=${centreCodes}`, "Multi-centre");
  record("MC-6c", "Summary includes SRKB-BRANCH", centreCodes.includes("SRKB-BRANCH"), `codes=${centreCodes}`, "Multi-centre");

  // 7. Non-host cannot access /host/stats
  const callerHostRes = await authedRequest("/host/stats/summary", { token: callerToken });
  record("MC-7", "Caller user gets 403 on /host/stats/summary", callerHostRes.status === 403, `status=${callerHostRes.status}`, "Multi-centre");

  // 8. Webhook — centre.renamed updates the centre name
  const newName = `Branch Renamed ${Date.now()}`;
  const webhookRes = await request("/internal/attendease-event", {
    method: "POST",
    body: { event_type: "centre.renamed", entity_type: "centre", entity_id: String(centreB.centreId), payload: { centre_id: centreB.centreCode, new_name: newName }, fired_at: new Date().toISOString() },
    headers: { "x-internal-token": process.env.INTERNAL_TOKEN },
  });
  record("MC-8a", "Webhook centre.renamed returns 200", webhookRes.status === 200, `status=${webhookRes.status}`, "Multi-centre");
  if (webhookRes.status === 200) {
    // Give the async handler a moment (it's synchronous but Prisma writes may lag)
    await new Promise((r) => setTimeout(r, 200));
    const centreDetail = await authedRequest(`/host/stats/centres/${centreB.centreId}`, { token: hostToken });
    const gotName = centreDetail.json?.data?.centre?.name;
    record("MC-8b", "Centre name updated after webhook", gotName === newName, `got="${gotName}" expected="${newName}"`, "Multi-centre");
    // Restore
    await request("/internal/attendease-event", {
      method: "POST",
      body: { event_type: "centre.renamed", entity_type: "centre", entity_id: String(centreB.centreId), payload: { centre_id: centreB.centreCode, new_name: "Sri Radha Kunj Bihari Branch Centre" }, fired_at: new Date().toISOString() },
      headers: { "x-internal-token": process.env.INTERNAL_TOKEN },
    });
  }

  // 9. Webhook with wrong token is rejected
  const badWebhookRes = await request("/internal/attendease-event", {
    method: "POST",
    body: { type: "centre.renamed", entityType: "centre", entityId: 1, payload: {} },
    headers: { "x-internal-token": "wrong-token-definitely-invalid" },
  });
  record("MC-9", "Webhook with wrong token returns 401", badWebhookRes.status === 401, `status=${badWebhookRes.status}`, "Multi-centre");

  // 10. Hybrid dropdown — global + centre-specific both visible
  const dropdownRes = await authedRequest("/dropdowns?context=batch_calling&category=youth", { token: callerToken, centreId: centreA.centreId });
  const labels = (dropdownRes.json?.data?.items ?? []).map((i) => i.label);
  record("MC-10a", "Dropdown endpoint returns 200", dropdownRes.status === 200, `status=${dropdownRes.status}`, "Multi-centre");
  record("MC-10b", "Global 'Will Attend' (or centre override) present", labels.some((l) => l.includes("Will Attend")), `labels=${labels.join(", ")}`, "Multi-centre");
}

function printResults() {
  console.log("\nStep | Name | PASS/FAIL | Details");
  console.log("---- | ---- | --------- | -------");
  for (const row of results) {
    const status = row.passed ? "PASS" : "FAIL";
    console.log(
      `${row.step} | ${row.name} | ${status} | ${String(row.details ?? "").replaceAll("\n", " ").slice(0, 500)}`,
    );
  }
}

run().catch((error) => {
  console.error("Smoke test crashed:");
  console.error(error);
  process.exitCode = 1;
});
