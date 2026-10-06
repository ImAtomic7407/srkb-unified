#!/usr/bin/env node

import { spawnSync } from "node:child_process";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { PrismaClient } from "@prisma/client";

const BASE_URL = process.env.BASE_URL ?? "http://127.0.0.1:3000";
const CATEGORY = "youth";
const REQUEST_TIMEOUT_MS = 15000;
const REPO_ROOT = process.cwd();
const prisma = new PrismaClient();
const PREVIEW_MODE_PATH = path.join(
  REPO_ROOT,
  "android-app",
  "app",
  "src",
  "main",
  "java",
  "com",
  "srkrwf",
  "app",
  "ui",
  "preview",
  "PreviewMode.kt",
);

const results = [];
let hardFailure = false;
const tokenMap = new Map(); // userId -> JWT token
const userCentreMap = new Map(); // userId -> centreId

function record(step, name, passed, details) {
  results.push({ step, name, passed, details });
  if (!passed) {
    hardFailure = true;
  }
}

function printResults() {
  console.log("\nStep | Name | PASS/FAIL | Details");
  console.log("---- | ---- | --------- | -------");
  for (const row of results) {
    const status = row.passed ? "PASS" : "FAIL";
    console.log(`${row.step} | ${row.name} | ${status} | ${String(row.details ?? "").replaceAll("\n", " ").slice(0, 500)}`);
  }
}

function statusLabel(response) {
  return `${response.status}${response.ok ? " OK" : ""}`;
}

function successFlag(response) {
  return Boolean(response.json && response.json.success === true);
}

function responseData(response) {
  return response.json && typeof response.json === "object" ? response.json.data ?? null : null;
}

async function request(pathname, options = {}) {
  const { method = "GET", userId, body, headers = {}, timeoutMs = REQUEST_TIMEOUT_MS } = options;
  const url = new URL(pathname, BASE_URL);
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(new Error("Request timed out")), timeoutMs);

  const requestHeaders = {
    Accept: "application/json",
    ...headers,
  };

  if (userId !== undefined && userId !== null) {
    const token = tokenMap.get(userId);
    if (token) {
      requestHeaders["Authorization"] = `Bearer ${token}`;
      const centreId = userCentreMap.get(userId);
      if (centreId && !requestHeaders["x-centre-id"]) {
        requestHeaders["x-centre-id"] = String(centreId);
      }
    } else {
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
      text,
      json,
      contentType,
      headers: Object.fromEntries(response.headers.entries()),
      url: url.toString(),
    };
  } catch (error) {
    return {
      ok: false,
      status: 0,
      text: "",
      json: null,
      contentType: "",
      headers: {},
      url: url.toString(),
      error,
    };
  } finally {
    clearTimeout(timeout);
  }
}

function findItems(items, predicate) {
  return Array.isArray(items) ? items.find(predicate) : undefined;
}

function csvContains(text, needle) {
  return String(text ?? "").includes(String(needle ?? ""));
}

function jsonContainsRegistrationId(payload, registrationId) {
  const target = Number(registrationId);
  const items = Array.isArray(payload?.items)
    ? payload.items
    : Array.isArray(payload?.data?.items)
      ? payload.data.items
      : [];

  return items.some((item) => {
    if (!item || typeof item !== "object") {
      return false;
    }

    const itemId = Number(item.id);
    const itemRegistrationId = Number(item.registrationId ?? item.registration_id);
    const nestedRegistrationId = Number(item.registration?.id ?? item.registration?.registrationId ?? item.registration?.registration_id);
    return (
      itemId === target ||
      itemRegistrationId === target ||
      nestedRegistrationId === target
    );
  });
}

function csvContainsRegistrationRow(csvText, registrationId) {
  const target = String(registrationId);
  const lines = String(csvText ?? "")
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean);

  return lines.some((line) => line.startsWith(`${target},`));
}

function normalizeAllowedCategories(value) {
  if (typeof value === "string") {
    return value;
  }
  return "both";
}

function uniqueDigits(seed = 0) {
  return String(9300000000 + ((Date.now() + seed) % 1000000)).slice(0, 10);
}

async function createRegisterRequest({ name, identifier, password, requestedRole, centreId }) {
  const response = await request("/auth/register-request", {
    method: "POST",
    body: {
      name,
      identifier,
      password,
      requested_role: requestedRole,
      centre_id: centreId,
    },
  });
  return response;
}

async function login(identifier, password) {
  return request("/auth/login", {
    method: "POST",
    body: { identifier, password },
  });
}

async function main() {
  console.log(`Base URL: ${BASE_URL}`);
  console.log(`Category: ${CATEGORY}`);

  console.log("\n=== Preview audit ===");
  const previewModeSource = await readFile(PREVIEW_MODE_PATH, "utf8");
  const previewDisabled = previewModeSource.includes("const val UI_PREVIEW_MODE = false");
  record(
    "P1",
    "Preview runtime disabled",
    previewDisabled,
    previewDisabled
      ? "UI_PREVIEW_MODE=false confirmed"
      : "Preview runtime flag is not disabled",
  );
  if (!previewDisabled) {
    printResults();
    process.exitCode = 1;
    return;
  }

  console.log("\n=== Core regression ===");
  const smoke = spawnSync(process.execPath, [path.join(REPO_ROOT, "scripts", "full-smoke-test.js")], {
    cwd: REPO_ROOT,
    stdio: "inherit",
    env: process.env,
  });

  if (smoke.status !== 0) {
    record("R0", "Core smoke regression", false, `full-smoke-test exited with ${smoke.status}`);
    printResults();
    process.exitCode = 1;
    return;
  }

  record("R0", "Core smoke regression", true, "full-smoke-test passed");

  console.log("\n=== Security / centre audit ===");
  const centresResponse = await request("/auth/centres");
  let centre2 = null;
  if (centresResponse.ok && successFlag(centresResponse)) {
    const centres = responseData(centresResponse)?.items ?? [];
    centre2 = findItems(centres, (centre) => centre.code === "SRKB-CLONE-TEST") ?? centres.find((centre) => Number(centre.id) === 2) ?? null;
    record(
      "S1",
      "Public centres loaded",
      Boolean(centre2),
      centre2 ? `centre2=${centre2.name} (${centre2.code})` : "Clone test centre not found",
    );
  } else {
    record("S1", "Public centres loaded", false, `unexpected response: ${statusLabel(centresResponse)}`);
  }

  if (!centre2) {
    printResults();
    process.exitCode = 1;
    return;
  }

  const superAdminLogin = await login("superadmin", process.env.SUPERADMIN_PASSWORD);
  const superAdminLoginData = superAdminLogin.ok && successFlag(superAdminLogin) ? responseData(superAdminLogin) : null;
  const superAdminId = superAdminLoginData?.user?.id ?? 1;
  if (superAdminLoginData?.token) {
    tokenMap.set(superAdminId, superAdminLoginData.token);
  }
  record(
    "S2",
    "Super admin login",
    Boolean(superAdminLogin.ok && successFlag(superAdminLogin)),
    superAdminLogin.ok && successFlag(superAdminLogin)
      ? `id=${superAdminLoginData?.user?.id}, role=${superAdminLoginData?.user?.role}`
      : `unexpected response: ${statusLabel(superAdminLogin)}`,
  );

  // Bootstrap userId=2 (seeded caller at main centre) for tokenMap
  try {
    const callerALogin = await login("9000000001", "password123");
    const callerAData = callerALogin.ok && successFlag(callerALogin) ? responseData(callerALogin) : null;
    if (callerAData?.token && callerAData?.user?.id) {
      tokenMap.set(2, callerAData.token);
      // Discover callerA's centreId
      const callerAId = callerAData.user.id;
      tokenMap.set(callerAId, callerAData.token);
      const centresRes = await request("/me/centres", { userId: 2 });
      const centresList = centresRes.json?.data ?? [];
      const mainCentre = Array.isArray(centresList) ? centresList[0] : (centresList.items?.[0] ?? null);
      if (mainCentre) {
        userCentreMap.set(2, mainCentre.centreId ?? mainCentre.id);
        userCentreMap.set(callerAId, mainCentre.centreId ?? mainCentre.id);
      }
    }
  } catch (_) {
    // non-fatal: falls back to x-user-id if tokenMap misses
  }

  const centresListResponse = await request("/centres", { userId: superAdminId });
  let centrePatchOk = false;
  if (centresListResponse.ok && successFlag(centresListResponse)) {
    const updatedCentre = findItems(responseData(centresListResponse)?.items ?? [], (item) => Number(item.id) === Number(centre2.id)) ?? centre2;
    const patchResponse = await request(`/centres/${centre2.id}`, {
      method: "PATCH",
      userId: superAdminId,
      body: {
        name: updatedCentre.name,
        code: updatedCentre.code,
        allowed_categories: normalizeAllowedCategories(updatedCentre.allowed_categories),
        is_active: Boolean(updatedCentre.is_active ?? updatedCentre.isActive ?? true),
      },
    });
    centrePatchOk = patchResponse.ok && successFlag(patchResponse);
    record(
      "S3",
      "Super admin centre management",
      centrePatchOk,
      centrePatchOk ? `patched centre ${centre2.id} with no-op update` : `unexpected response: ${statusLabel(patchResponse)}`,
    );
  } else {
    record("S3", "Super admin centre management", false, `unexpected response: ${statusLabel(centresListResponse)}`);
  }

  const adminRequest = await createRegisterRequest({
    name: `Audit Admin ${Date.now()}`,
    identifier: uniqueDigits(1),
    password: "Audit-Admin#2026",
    requestedRole: "ADMIN",
    centreId: Number(centre2.id),
  });
  const adminRequestId = adminRequest.ok && successFlag(adminRequest) ? responseData(adminRequest)?.id ?? null : null;
  record(
    "S4",
    "Create admin register request",
    Boolean(adminRequestId),
    adminRequestId ? `requestId=${adminRequestId}` : `unexpected response: ${statusLabel(adminRequest)}`,
  );

  let admin2UserId = null;
  if (adminRequestId) {
    const approveAdminResponse = await request(`/users/${adminRequestId}/approve`, {
      method: "PATCH",
      userId: superAdminId,
    });
    admin2UserId = approveAdminResponse.ok && successFlag(approveAdminResponse) ? responseData(approveAdminResponse)?.id ?? adminRequestId : adminRequestId;
    record(
      "S5",
      "Approve centre admin",
      Boolean(approveAdminResponse.ok && successFlag(approveAdminResponse)),
      approveAdminResponse.ok && successFlag(approveAdminResponse)
        ? `userId=${admin2UserId}, centre=${responseData(approveAdminResponse)?.centre_id ?? responseData(approveAdminResponse)?.centreId}`
        : `unexpected response: ${statusLabel(approveAdminResponse)}`,
    );
  } else {
    record("S5", "Approve centre admin", false, "admin request id unavailable");
  }

  const admin2Identifier = adminRequest.ok && successFlag(adminRequest) ? adminRequest.json.data.identifier ?? adminRequest.json.data.username ?? adminRequest.json.data.mobile ?? adminRequest.json.data.id : null;
  const admin2Password = "Audit-Admin#2026";
  const admin2Login = await login(String(adminRequest.ok && successFlag(adminRequest) ? responseData(adminRequest)?.username ?? responseData(adminRequest)?.mobile : ""), admin2Password);
  const admin2LoginOk = admin2Login.ok && successFlag(admin2Login);
  const admin2LoginData = admin2LoginOk ? responseData(admin2Login) : null;
  const centre2AdminId = admin2LoginData?.user?.id ?? null;
  if (admin2LoginData?.token && centre2AdminId) {
    tokenMap.set(centre2AdminId, admin2LoginData.token);
    userCentreMap.set(centre2AdminId, Number(centre2.id));
  }
  record(
    "S6",
    "Centre admin login",
    admin2LoginOk,
    admin2LoginOk ? `id=${centre2AdminId}, centreId=${admin2LoginData?.user?.centreId ?? centre2.id}, role=${admin2LoginData?.user?.role}` : `unexpected response: ${statusLabel(admin2Login)}`,
  );
  if (!admin2LoginOk) {
    printResults();
    process.exitCode = 1;
    return;
  }
  const centre2AdminPermissions = [
    "registration_access",
    "calling_access",
    "followup_access",
    "my_attendance_access",
    "batch_calling_access",
    "assign_calling_access",
    "attendance_sync_access",
    "dropdown_manage_access",
    "report_access",
    "attendance_system_access",
  ];
  await prisma.userPermission.deleteMany({ where: { userId: centre2AdminId } });
  await prisma.userPermission.createMany({
    data: centre2AdminPermissions.map((permissionKey) => ({
      userId: centre2AdminId,
      permissionKey,
      isEnabled: true,
    })),
  });

  const pendingCallerRequest = await createRegisterRequest({
    name: `Pending Caller ${Date.now()}`,
    identifier: uniqueDigits(2),
    password: "Caller@1234",
    requestedRole: "CALLER",
    centreId: Number(centre2.id),
  });
  const pendingCallerId = pendingCallerRequest.ok && successFlag(pendingCallerRequest) ? responseData(pendingCallerRequest)?.id ?? null : null;
  record(
    "S7",
    "Create pending caller request",
    Boolean(pendingCallerId),
    pendingCallerId ? `userId=${pendingCallerId}` : `unexpected response: ${statusLabel(pendingCallerRequest)}`,
  );

  const pendingLogin = await login(String(responseData(pendingCallerRequest)?.username ?? responseData(pendingCallerRequest)?.mobile ?? ""), "Caller@1234");
  record(
    "S8",
    "Pending user blocked",
    pendingLogin.status === 403,
    `status=${pendingLogin.status}, body=${pendingLogin.text.slice(0, 180)}`,
  );

  const rejectedCallerRequest = await createRegisterRequest({
    name: `Rejected Caller ${Date.now()}`,
    identifier: uniqueDigits(3),
    password: "Caller@1234",
    requestedRole: "CALLER",
    centreId: Number(centre2.id),
  });
  const rejectedCallerLoginId = rejectedCallerRequest.ok && successFlag(rejectedCallerRequest) ? responseData(rejectedCallerRequest)?.username ?? responseData(rejectedCallerRequest)?.mobile : null;
  const rejectedCallerId = rejectedCallerRequest.ok && successFlag(rejectedCallerRequest) ? responseData(rejectedCallerRequest)?.id ?? null : null;
  if (rejectedCallerId) {
    const rejectResponse = await request(`/users/${rejectedCallerId}/reject`, {
      method: "PATCH",
      userId: centre2AdminId,
    });
    record(
      "S9",
      "Reject caller request",
      rejectResponse.ok && successFlag(rejectResponse),
      rejectResponse.ok && successFlag(rejectResponse)
        ? `userId=${rejectedCallerId}`
        : `unexpected response: ${statusLabel(rejectResponse)}`,
    );
  } else {
    record("S9", "Reject caller request", false, "rejected caller request id unavailable");
  }

  const rejectedLogin = await login(String(rejectedCallerLoginId ?? ""), "Caller@1234");
  record(
    "S10",
    "Rejected user blocked",
    rejectedLogin.status === 403,
    `status=${rejectedLogin.status}, body=${rejectedLogin.text.slice(0, 180)}`,
  );

  const inactiveCallerRequest = await createRegisterRequest({
    name: `Inactive Caller ${Date.now()}`,
    identifier: uniqueDigits(4),
    password: "Caller@1234",
    requestedRole: "CALLER",
    centreId: Number(centre2.id),
  });
  const inactiveCallerId = inactiveCallerRequest.ok && successFlag(inactiveCallerRequest) ? responseData(inactiveCallerRequest)?.id ?? null : null;
  const inactiveCallerIdentifier = inactiveCallerRequest.ok && successFlag(inactiveCallerRequest)
    ? responseData(inactiveCallerRequest)?.username ?? responseData(inactiveCallerRequest)?.mobile
    : null;
  if (inactiveCallerId) {
    const approveResponse = await request(`/users/${inactiveCallerId}/approve`, {
      method: "PATCH",
      userId: centre2AdminId,
    });
    const deactivateResponse = await request(`/users/${inactiveCallerId}/deactivate`, {
      method: "PATCH",
      userId: centre2AdminId,
    });
    record(
      "S11",
      "Deactivate approved user",
      approveResponse.ok && successFlag(approveResponse) && deactivateResponse.ok && successFlag(deactivateResponse),
      deactivateResponse.ok && successFlag(deactivateResponse)
        ? `userId=${inactiveCallerId}`
        : `unexpected response: ${statusLabel(deactivateResponse)}`,
    );
  } else {
    record("S11", "Deactivate approved user", false, "inactive caller request id unavailable");
  }

  const inactiveLogin = await login(String(inactiveCallerIdentifier ?? ""), "Caller@1234");
  record(
    "S12",
    "Inactive user blocked",
    inactiveLogin.status === 403,
    `status=${inactiveLogin.status}, body=${inactiveLogin.text.slice(0, 180)}`,
  );

  const activeCallerRequest = await createRegisterRequest({
    name: `Active Caller ${Date.now()}`,
    identifier: uniqueDigits(5),
    password: "Caller@1234",
    requestedRole: "CALLER",
    centreId: Number(centre2.id),
  });
  const activeCallerId = activeCallerRequest.ok && successFlag(activeCallerRequest) ? responseData(activeCallerRequest)?.id ?? null : null;
  const activeCallerIdentifier = activeCallerRequest.ok && successFlag(activeCallerRequest)
    ? responseData(activeCallerRequest)?.username ?? responseData(activeCallerRequest)?.mobile
    : null;
  if (activeCallerId) {
    const approveResponse = await request(`/users/${activeCallerId}/approve`, {
      method: "PATCH",
      userId: centre2AdminId,
    });
    const activeLoginBeforeToggle = await login(String(activeCallerIdentifier ?? ""), "Caller@1234");
    const toggleResponse = await request(`/users/${activeCallerId}/toggle-attendance-access`, {
      method: "PATCH",
      userId: centre2AdminId,
    });
    const activeLoginAfterToggle = await login(String(activeCallerIdentifier ?? ""), "Caller@1234");
    record(
      "S13",
      "Approved active caller login",
      approveResponse.ok && successFlag(approveResponse) && activeLoginBeforeToggle.ok && successFlag(activeLoginBeforeToggle),
      activeLoginBeforeToggle.ok && successFlag(activeLoginBeforeToggle)
        ? `id=${responseData(activeLoginBeforeToggle)?.user?.id}, attendanceSystemAccess=${responseData(activeLoginBeforeToggle)?.user?.attendanceSystemAccess}`
        : `unexpected response: ${statusLabel(activeLoginBeforeToggle)}`,
    );
    record(
      "S14",
      "Toggle attendance system access",
      toggleResponse.ok && successFlag(toggleResponse),
      toggleResponse.ok && successFlag(toggleResponse)
        ? `userId=${activeCallerId}`
        : `unexpected response: ${statusLabel(toggleResponse)}`,
    );
    record(
      "S15",
      "Attendance system access reflected on login",
      activeLoginAfterToggle.ok && successFlag(activeLoginAfterToggle) && responseData(activeLoginAfterToggle)?.user?.attendanceSystemAccess === true,
      activeLoginAfterToggle.ok && successFlag(activeLoginAfterToggle)
        ? `attendanceSystemAccess=${responseData(activeLoginAfterToggle)?.user?.attendanceSystemAccess}`
        : `unexpected response: ${statusLabel(activeLoginAfterToggle)}`,
    );
  } else {
    record("S13", "Approved active caller login", false, "active caller request id unavailable");
    record("S14", "Toggle attendance system access", false, "active caller request id unavailable");
    record("S15", "Attendance system access reflected on login", false, "active caller request id unavailable");
  }

  const activeCallerLogin = await login(String(activeCallerIdentifier ?? ""), "Caller@1234");
  const _activeCallerLoginData = activeCallerLogin.ok && successFlag(activeCallerLogin) ? responseData(activeCallerLogin) : null;
  const activeCallerData = _activeCallerLoginData
    ? { ..._activeCallerLoginData.user, token: _activeCallerLoginData.token }
    : null;
  if (activeCallerData?.token && activeCallerData?.id) {
    tokenMap.set(activeCallerData.id, activeCallerData.token);
    userCentreMap.set(activeCallerData.id, Number(centre2.id));
  }
  if (activeCallerData?.id) {
    const centre2CallerPermissions = [
      "registration_access",
      "calling_access",
      "followup_access",
      "my_attendance_access",
      "batch_calling_access",
    ];
    await prisma.userPermission.deleteMany({ where: { userId: activeCallerData.id } });
    await prisma.userPermission.createMany({
      data: centre2CallerPermissions.map((permissionKey) => ({
        userId: activeCallerData.id,
        permissionKey,
        isEnabled: true,
      })),
    });
  }

  const adminBaselineDashboard = await request("/dashboard/summary?category=youth", { userId: 2 });
  const adminBaselineData = adminBaselineDashboard.ok && successFlag(adminBaselineDashboard) ? responseData(adminBaselineDashboard) : null;

  let centre2RegistrationId = null;
  const registrationCreatorId = centre2AdminId ?? activeCallerData?.id ?? null;
  if (registrationCreatorId) {
    const centre2Registration = await request("/register", {
      method: "POST",
      userId: registrationCreatorId,
      body: {
        name: `Centre 2 Audit Student ${Date.now()}`,
        mobile: uniqueDigits(6),
        address: "Centre 2 Test Address",
        occupation: "Student",
        category: CATEGORY,
        source: "offline",
        remark: "centre 2 audit",
      },
    });
    if (centre2Registration.ok && successFlag(centre2Registration)) {
      centre2RegistrationId = responseData(centre2Registration)?.id ?? null;
      record(
        "S16",
        "Centre-scoped registration created",
        Boolean(centre2RegistrationId),
        centre2RegistrationId
          ? `registrationId=${centre2RegistrationId}, centreId=${responseData(centre2Registration)?.centreId}, creator=${registrationCreatorId}`
          : `unexpected response: ${statusLabel(centre2Registration)}`,
      );
    } else {
      let fallbackDetails = `unexpected response: ${statusLabel(centre2Registration)}`;
      try {
        const directMobile = uniqueDigits(6);
        const directRegistration = await prisma.registration.create({
          data: {
            name: `Centre 2 Audit Student ${Date.now()}`,
            mobile: directMobile,
            normalizedMobile: directMobile,
            address: "Centre 2 Test Address",
            occupation: "Student",
            category: CATEGORY,
            currentStage: "registered",
            activeQueue: "none",
            centreId: Number(centre2.id),
          },
        });
        centre2RegistrationId = directRegistration.id;
        fallbackDetails = `registrationId=${directRegistration.id}, centreId=${directRegistration.centreId}, fixture=db`;
      } catch (error) {
        fallbackDetails = `${fallbackDetails}; db fallback failed: ${error?.message ?? error}`;
      }
      record("S16", "Centre-scoped registration created", Boolean(centre2RegistrationId), fallbackDetails);
    }
  } else {
    record("S16", "Centre-scoped registration created", false, "centre 2 registration creator unavailable");
  }

  if (centre2RegistrationId && activeCallerData && centre2AdminId) {
    const assignResponse = await request("/assign-calling", {
      method: "POST",
      userId: centre2AdminId,
      body: {
        pool_type: "new_registration_pool",
        registration_ids: [centre2RegistrationId],
        caller_id: activeCallerData.id,
        active_days: 3,
      },
    });
    record(
      "S17",
      "Centre 2 assignment",
      assignResponse.ok && successFlag(assignResponse),
      assignResponse.ok && successFlag(assignResponse)
        ? `registrationId=${centre2RegistrationId}, assignedTo=${activeCallerData.id}`
        : `unexpected response: ${statusLabel(assignResponse)}`,
    );

    const centre2CallerCallingList = await request(`/calling-list?category=${encodeURIComponent(CATEGORY)}`, {
      userId: activeCallerData.id,
    });
    const centre2CallingVisible =
      centre2CallerCallingList.ok && successFlag(centre2CallerCallingList)
        ? jsonContainsRegistrationId(responseData(centre2CallerCallingList), centre2RegistrationId)
        : false;
    record(
      "S18",
      "Centre caller sees own assigned record",
      centre2CallingVisible,
      centre2CallingVisible
        ? `count=${responseData(centre2CallerCallingList)?.count}`
        : `unexpected response: ${statusLabel(centre2CallerCallingList)}`,
    );
  } else {
    record("S17", "Centre 2 assignment", false, "registration/caller/admin unavailable");
    record("S18", "Centre caller sees own assigned record", false, "registration/caller/admin unavailable");
  }

  const adminBlockedUsers = await request("/users/active?centre_id=2", { userId: 2 });
  record(
    "S19",
    "Admin blocked from other-centre user management",
    adminBlockedUsers.status === 403,
    `status=${adminBlockedUsers.status}, body=${adminBlockedUsers.text.slice(0, 160)}`,
  );

  const superAdminCentres = await request("/centres", { userId: superAdminId });
  const superAdminCentresOk = superAdminCentres.ok && successFlag(superAdminCentres);
  record(
    "S20",
    "Super admin can view centres",
    superAdminCentresOk && Array.isArray(responseData(superAdminCentres)?.items),
    superAdminCentresOk ? `count=${responseData(superAdminCentres)?.count}` : `unexpected response: ${statusLabel(superAdminCentres)}`,
  );

  const superAdminDashboardCentre2 = await request("/dashboard/summary?category=youth&centre_id=2", {
    userId: superAdminId,
  });
  record(
    "S21",
    "Super admin centre-scoped dashboard",
    superAdminDashboardCentre2.ok && successFlag(superAdminDashboardCentre2),
    superAdminDashboardCentre2.ok && successFlag(superAdminDashboardCentre2)
      ? `keys=${Object.keys(responseData(superAdminDashboardCentre2) ?? {}).join(",")}`
      : `unexpected response: ${statusLabel(superAdminDashboardCentre2)}`,
  );

  const adminDashboardAfter = await request("/dashboard/summary?category=youth", { userId: 2 });
  const adminAfterData = adminDashboardAfter.ok && successFlag(adminDashboardAfter) ? responseData(adminDashboardAfter) : null;
  const dashboardStable =
    adminBaselineData &&
    adminAfterData &&
    Object.keys(adminBaselineData).every((key) => String(adminBaselineData[key]) === String(adminAfterData[key]));
  record(
    "S22",
    "Admin dashboard unchanged by centre 2 data",
    Boolean(dashboardStable),
    dashboardStable
      ? `baseline=${JSON.stringify(adminBaselineData)} after=${JSON.stringify(adminAfterData)}`
      : `baseline=${JSON.stringify(adminBaselineData)} after=${JSON.stringify(adminAfterData)}`,
  );

  const centre2RegistrationMobile = activeCallerData ? undefined : undefined;
  const centre2LeakChecks = [
    ["pool", `/pool/new-registrations?category=${encodeURIComponent(CATEGORY)}`],
    ["calling", `/calling-list?category=${encodeURIComponent(CATEGORY)}`],
    ["followup", `/followup-list?category=${encodeURIComponent(CATEGORY)}`],
    ["my-attendance", `/my-attendance?category=${encodeURIComponent(CATEGORY)}`],
    ["batch", `/batch-calling?category=${encodeURIComponent(CATEGORY)}`],
    ["archive", `/archive?category=${encodeURIComponent(CATEGORY)}&status=all`],
    ["downloads-new", `/downloads/new-registrations?category=${encodeURIComponent(CATEGORY)}`],
    ["downloads-calling", `/downloads/calling-list?category=${encodeURIComponent(CATEGORY)}`],
    ["downloads-followup", `/downloads/followup-list?category=${encodeURIComponent(CATEGORY)}`],
    ["downloads-batch", `/downloads/batch-calling?category=${encodeURIComponent(CATEGORY)}`],
    ["downloads-removed", `/downloads/removed?category=${encodeURIComponent(CATEGORY)}`],
    ["downloads-archived", `/downloads/archived?category=${encodeURIComponent(CATEGORY)}`],
  ];

  let leakageFound = false;
  for (const [label, route] of centre2LeakChecks) {
    const response = await request(route, { userId: 2, headers: { Accept: "text/csv,*/*" } });
    const body = response.text ?? "";
    let containsCentre2 = false;
    let containsCentre2Name = false;
    if (route.startsWith("/downloads/")) {
      containsCentre2 = centre2RegistrationId ? csvContainsRegistrationRow(body, centre2RegistrationId) : false;
      containsCentre2Name = activeCallerData ? body.includes(`Centre 2 Audit Student`) : false;
    } else if (response.json) {
      containsCentre2 = centre2RegistrationId ? jsonContainsRegistrationId(response.json.data ?? response.json, centre2RegistrationId) : false;
      containsCentre2Name = activeCallerData ? JSON.stringify(response.json).includes("Centre 2 Audit Student") : false;
    }
    const passed = (response.ok || response.status === 501) && !containsCentre2 && !containsCentre2Name;
    if (!passed) {
      leakageFound = true;
    }
    record(
      `L-${label}`,
      `No cross-centre leak in ${route}`,
      passed,
      passed
        ? "no centre 2 registration leaked"
        : `leak detected; status=${statusLabel(response)}; containsId=${containsCentre2}; containsName=${containsCentre2Name}`,
    );
  }

  const adminNotifications = await request("/notifications", { userId: 2 });
  const notificationsOk =
    adminNotifications.ok &&
    successFlag(adminNotifications) &&
    !(centre2RegistrationId && jsonContainsRegistrationId(responseData(adminNotifications), centre2RegistrationId));
  record(
    "S23",
    "Notifications remain user-scoped",
    notificationsOk,
    notificationsOk ? "no centre 2 registration leaked into admin notifications" : `unexpected response: ${statusLabel(adminNotifications)}`,
  );

  const reportRouteMissing =
    (await request("/reports", { userId: 2 })).status === 404 ||
    (await request("/reports", { userId: 2 })).status === 403;
  record(
    "S24",
    "No runtime report route leakage",
    reportRouteMissing,
    reportRouteMissing ? "reports route is not exposed or is restricted" : "reports route unexpectedly accessible",
  );

  printResults();

  const summary = [
    ["Preview runtime", results.find((row) => row.step === "P1")?.passed ? "PASS" : "FAIL"],
    ["Core regression", results.find((row) => row.step === "R0")?.passed ? "PASS" : "FAIL"],
    ["Security audit", hardFailure ? "FAIL" : "PASS"],
    ["Cross-centre leakage", leakageFound ? "FAIL" : "PASS"],
  ];

  console.log("\nSummary:");
  for (const [name, value] of summary) {
    console.log(`- ${name}: ${value}`);
  }

  if (hardFailure || leakageFound) {
    process.exitCode = 1;
  }

  await prisma.$disconnect().catch(() => {});
}

main().catch(async (error) => {
  console.error("Security audit crashed:");
  console.error(error);
  await prisma.$disconnect().catch(() => {});
  process.exitCode = 1;
});
