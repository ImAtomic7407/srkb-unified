// Client data-dump collector — every app instance uploads the stuck / failed /
// corrupted data sitting in its offline queue here, tagged with the authenticated
// user's username (mobile) + role. Stored as JSON files on the server so an admin
// can recover a user's un-synced data centrally, WITHOUT adb or touching the phone.
//
// Storage: <cwd>/client-dumps/<username>/<timestamp>__<role>.json  (+ latest.json).
// Deliberately file-based (not a DB table) so it needs no schema migration and the
// raw payload survives even if it's malformed JSON the app couldn't parse.
import { Router } from "express";
import { UserRole } from "@prisma/client";
import { asyncHandler } from "../../lib/async-handler.js";
import { promises as fs } from "fs";
import path from "path";

const DUMP_DIR = path.resolve(process.cwd(), "client-dumps");
const sanitize = (s: string) => (s || "").replace(/[^a-zA-Z0-9._-]/g, "_").slice(0, 60) || "unknown";
const isAdmin = (role: UserRole) => role === UserRole.ADMIN || role === UserRole.SUPER_ADMIN;

export const clientDumpRouter = Router();

// POST /client/data-dump — the app pushes its raw offline-queue JSON here.
clientDumpRouter.post(
  "/data-dump",
  asyncHandler(async (req, res) => {
    const user = req.user!;
    // Accept either a raw JSON string (`raw_json`) or an already-parsed `queue`.
    const rawJson =
      typeof req.body?.raw_json === "string" ? req.body.raw_json : JSON.stringify(req.body?.queue ?? []);
    let parsed: unknown;
    try {
      parsed = JSON.parse(rawJson);
    } catch {
      parsed = null; // corrupted — keep raw_json regardless so nothing is lost
    }
    const record = {
      username: user.mobile,
      name: user.name,
      role: user.role,
      centreId: user.centreId,
      uploaded_at: new Date().toISOString(),
      reason: String(req.body?.reason ?? "manual"),
      pending: Number(req.body?.pending ?? 0),
      failed: Number(req.body?.failed ?? 0),
      app_version: String(req.body?.app_version ?? ""),
      // Device identity. The record is only useful in an emergency if it says which phone
      // this came off — a shared login means the username cannot tell you that. Kept
      // permissive (whatever the client sends, stringified) so an older build that sends
      // none of these still stores cleanly rather than failing the upload.
      device_id: String(req.body?.device_id ?? ""),
      device_model: String(req.body?.device_model ?? ""),
      android_release: String(req.body?.android_release ?? ""),
      android_sdk: req.body?.android_sdk ?? null,
      version_code: req.body?.version_code ?? null,
      client_centre_id: String(req.body?.centre_id ?? ""),
      client_category: String(req.body?.category ?? ""),
      item_count: Array.isArray(parsed) ? parsed.length : null,
      parse_ok: parsed !== null,
      queue: parsed,
      raw_json: rawJson,
    };
    const userDir = path.join(DUMP_DIR, sanitize(user.mobile));
    await fs.mkdir(userDir, { recursive: true });
    const ts = record.uploaded_at.replace(/[:.]/g, "-");
    const file = path.join(userDir, `${ts}__${sanitize(user.role)}.json`);
    const body = JSON.stringify(record, null, 2);
    await fs.writeFile(file, body, "utf8");
    await fs.writeFile(path.join(userDir, "latest.json"), body, "utf8"); // quick-access pointer
    res.json({ ok: true, stored: path.relative(process.cwd(), file), item_count: record.item_count });
  }),
);

// GET /client/data-dumps — admin: one row per user who has uploaded, newest first.
clientDumpRouter.get(
  "/data-dumps",
  asyncHandler(async (req, res) => {
    if (!isAdmin(req.user!.role)) {
      res.status(403).json({ ok: false, message: "Admin only" });
      return;
    }
    await fs.mkdir(DUMP_DIR, { recursive: true });
    const users = await fs.readdir(DUMP_DIR).catch(() => [] as string[]);
    const items: Record<string, unknown>[] = [];
    for (const u of users) {
      try {
        const latest = JSON.parse(await fs.readFile(path.join(DUMP_DIR, u, "latest.json"), "utf8"));
        const files = (await fs.readdir(path.join(DUMP_DIR, u))).filter((f) => f !== "latest.json");
        items.push({
          username: latest.username,
          name: latest.name,
          role: latest.role,
          centreId: latest.centreId,
          latest_uploaded_at: latest.uploaded_at,
          latest_item_count: latest.item_count,
          pending: latest.pending,
          failed: latest.failed,
          parse_ok: latest.parse_ok,
          dump_count: files.length,
        });
      } catch {
        /* skip unreadable user dir */
      }
    }
    items.sort((a, b) =>
      String(b.latest_uploaded_at ?? "").localeCompare(String(a.latest_uploaded_at ?? "")),
    );
    res.json({ ok: true, count: items.length, items });
  }),
);

// GET /client/data-dumps/:username — admin: full latest dump (or ?all=1 for history).
clientDumpRouter.get(
  "/data-dumps/:username",
  asyncHandler(async (req, res) => {
    if (!isAdmin(req.user!.role)) {
      res.status(403).json({ ok: false, message: "Admin only" });
      return;
    }
    const dir = path.join(DUMP_DIR, sanitize(String(req.params.username ?? "")));
    try {
      if (req.query.all === "1") {
        const files = (await fs.readdir(dir)).filter((f) => f !== "latest.json").sort().reverse();
        const dumps: unknown[] = [];
        for (const f of files.slice(0, 50)) {
          dumps.push(JSON.parse(await fs.readFile(path.join(dir, f), "utf8")));
        }
        res.json({ ok: true, count: dumps.length, dumps });
      } else {
        const latest = JSON.parse(await fs.readFile(path.join(dir, "latest.json"), "utf8"));
        res.json({ ok: true, dump: latest });
      }
    } catch {
      res.status(404).json({ ok: false, message: "No dumps for this user" });
    }
  }),
);
