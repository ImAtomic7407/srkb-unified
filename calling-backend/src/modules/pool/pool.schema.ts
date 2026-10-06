import { z } from "zod";
import { Category } from "@prisma/client";

const categoryQuery = z.object({
  category: z.nativeEnum(Category),
  search: z.string().trim().optional(),
});

export const newRegistrationsPoolSchema = categoryQuery.extend({
  date_from: z.string().trim().optional(),
  date_to: z.string().trim().optional(),
  assigned_status: z.enum(["assigned", "unassigned", "all"]).optional().default("all"),
});

// "New Registered" tab — registrations in a centre+category by createdAt window, tagged with types[].
export const registeredByDateSchema = categoryQuery.extend({
  date_from: z.string().trim().optional(),
  date_to: z.string().trim().optional(),
});

export const remainingFollowupsPoolSchema = categoryQuery.extend({
  last_response: z.string().trim().optional(),
  caller_id: z.coerce.number().int().positive().optional(),
});

export const batchCallingPoolSchema = categoryQuery.extend({
  batch_id: z.coerce.number().int().positive().optional(),
  assigned_status: z.enum(["assigned", "unassigned", "all"]).optional().default("all"),
});

export const poolSummarySchema = z.object({
  category: z.nativeEnum(Category),
});

export const batchMembersPoolSchema = z.object({
  category: z.nativeEnum(Category),
  batch_id: z.coerce.number().int().positive().optional(),
  session_id: z.string().trim().optional(),
  date: z.string().trim().regex(/^\d{4}-\d{2}-\d{2}$/, "date must be YYYY-MM-DD").optional(),
  // attended (default) | responded | both | need_check — see PeopleFilterInclude. Defaulting to
  // "attended" keeps every existing app build on exactly the behaviour it has today; only a build
  // that sends the parameter sees positive calling responses.
  //
  // "both" stays accepted even though the app no longer offers it as a chip: updates are offered,
  // not forced, so phones still on an older build keep sending it and must not start 422-ing.
  include: z.enum(["attended", "responded", "both", "need_check"]).optional().default("attended"),
  // The calling session the admin is assigning into. Being in THAT session is not "busy elsewhere".
  exclude_campaign_id: z.coerce.number().int().positive().optional(),
});

// Every list param below accepts a string OR an already-parsed array, and never_called accepts a
// string OR a boolean. The controller parses the query a second time (see pool.controller), and
// whether validateQuery's Object.assign sticks depends on the Express version — so both parses
// must accept both shapes or one of them 422s/500s.

// Session ids are admin-typed free text and may contain commas ("BG, 27-07-26" is a real shape),
// so they are sent as REPEATED params and never split.
const idList = z
  .union([z.string(), z.array(z.string())])
  .optional()
  .transform((v) => (v === undefined ? [] : Array.isArray(v) ? v : [v]).map((s) => s.trim()).filter(Boolean));

// Values that cannot contain a comma (sentiments, numeric ids): a comma list or repeated params.
const csv = z
  .union([z.string(), z.array(z.string())])
  .optional()
  .transform((v) =>
    (v === undefined ? [] : Array.isArray(v) ? v : [v])
      .flatMap((s) => s.split(","))
      .map((s) => s.trim())
      .filter(Boolean),
  );

// GET /pool/session-responses — the responses given in these sessions, grouped, with counts.
export const sessionResponsesPoolSchema = z.object({
  category: z.nativeEnum(Category),
  session_ids: idList.refine((v) => v.length > 0 && v.length <= 50, "session_ids: 1 to 50 session ids"),
});

// GET /pool/session-people — people from these sessions filtered by response AND attendance.
// Replaces /pool/batch-members?include= for new app builds; that endpoint stays for older ones.
export const sessionPeoplePoolSchema = sessionResponsesPoolSchema.extend({
  sentiments: csv.pipe(z.array(z.enum(["positive", "negative", "not_reached"]))),
  response_ids: csv.pipe(z.array(z.coerce.number().int().positive())),
  never_called: z
    .union([z.boolean(), z.enum(["0", "1", "true", "false"])])
    .optional()
    .transform((v) => v === true || v === "1" || v === "true"),
  attendance: z.enum(["present", "absent", "any"]).optional().default("any"),
  exclude_campaign_id: z.coerce.number().int().positive().optional(),
});

export const poolSessionsSchema = z.object({
  category: z.nativeEnum(Category).optional(),
});

export const batchCallingAssignSchema = z.object({
  batch_member_ids: z.array(z.number().int().positive()).min(1),
  caller_id: z.number().int().positive(),
  active_days: z.number().int().min(1).max(3650),
});
