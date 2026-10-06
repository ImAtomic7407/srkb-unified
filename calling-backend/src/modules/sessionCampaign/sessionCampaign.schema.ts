import { z } from "zod";

export const createCampaignSchema = z.object({
  ae_session_id: z.string().trim().min(1),
  batch_id: z.number().int().positive(),
  scheduled_at: z.string().datetime({ offset: true }),
});

export const assignSessionCallersSchema = z.object({
  assignments: z
    .array(
      z.object({
        batch_member_id: z.number().int().positive(),
        caller_id: z.number().int().positive(),
      })
    )
    .min(1),
  // Where this allotment came from, one entry per caller named in `assignments`. Entirely
  // optional: builds from before allotment provenance existed send only `assignments`, and their
  // rows keep NULL provenance and group under the caller alone, exactly as they always did.
  //
  // `replace` is the destructive branch of the "this caller already has an allotment here" prompt:
  // it soft-removes that caller's other live rows in this campaign before writing the new ones.
  // Soft, so outcomes already recorded against those people are never destroyed.
  allotments: z
    .array(
      z.object({
        caller_id: z.number().int().positive(),
        key: z.string().trim().min(1).max(80),
        label: z.string().trim().max(500).optional(),
        filter: z.string().max(4000).optional(),
        replace: z.boolean().optional(),
      })
    )
    .optional()
    // NEVER let provenance fail an assignment. `allotments` rides in the same POST as
    // `assignments`, so without this a label one character too long would 400 the whole request:
    // nobody gets allotted, and the app reports it as a connection problem. Provenance is a
    // heading on a card; the allotment is the job. A malformed one is dropped and the assignment
    // proceeds with no provenance, which is exactly how an older app build already behaves.
    .catch(undefined),
});

export const listCampaignsSchema = z.object({
  status: z.enum(["active", "closed", "completed", "done", "all"]).optional().default("active"),
  batch_id: z.coerce.number().int().positive().optional(),
  // OPTIONAL, and it must stay optional. session_campaigns has no category column — a campaign's
  // category is implied by its batch — so this list used to return BOTH categories, and the app
  // kept the previous category's calling session selected after a Youth<->Congregation switch.
  // The roster then loaded for the new category while the session belonged to the old one, and
  // every row reported "aren't in any batch yet" (2026-08-23).
  // Optional because app builds up to v38 do not send it: omitting it must keep the old
  // both-categories behaviour, or every device in the field loses its session list mid-rollout.
  category: z.enum(["youth", "congregation"]).optional(),
});

export const setCallerNoteSchema = z.object({
  note: z.string().trim().max(2000),
});

// The per-calling-session WhatsApp message. Same shape as the caller note deliberately: an empty
// string is the documented way to CLEAR it, so the app needs no separate delete call.
export const setCampaignWhatsappMessageSchema = z.object({
  message: z.string().trim().max(2000),
});

export const updateCallStatusSchema = z
  .object({
    // Legacy quick-status path (still supported)…
    call_status: z.enum(["called", "will_attend", "wont_attend", "no_answer"]).optional(),
    // …or the configurable calling-outcome label (same list as pool calls). Backend derives the
    // coarse status from it and stores the label verbatim in call history.
    outcome: z.string().min(1).max(200).optional(),
    remark: z.string().max(1000).optional(),
    // Step 1 of the caller's two-step capture. Optional: app builds from before the two-step
    // shipped simply omit it, and those attempts store NULL like every pre-existing row.
    answered: z.boolean().optional(),
    // WHICH response was tapped. Optional, like `answered`: older builds send only the label.
    // Sending it makes the outcome immune to a rename that happens between the tap and the sync —
    // the label alone stops matching, and the server then has to guess.
    dropdown_id: z.number().int().positive().optional(),
  })
  .refine((d) => Boolean(d.call_status || d.outcome), {
    message: "Provide either call_status or outcome",
  });
// Export one finished calling session as a sheet. One mode per client criterion; see
// sessionCampaignExport.service.ts for what each one means.
//
// `order` and `cohort` are the sub-menu choices. Both optional at the schema level even though the
// UI always supplies one, because a missing sub-choice must still produce a usable sheet rather
// than a 422 the operator cannot interpret:
//   - order  defaults to ascending (and, for new_old, to a per-cohort default)
//   - cohort defaults to "new"
// direct_called deliberately takes no sub-choice at all — its order is fixed.
export const exportSessionReportSchema = z.object({
  field: z.enum(["by_caller", "classes_attended", "new_old", "direct_called", "batch"]),
  order: z.enum(["asc", "desc"]).optional(),
  cohort: z.enum(["new", "old"]).optional(),
});
