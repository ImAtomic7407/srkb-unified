import {
  ActiveQueue,
  CallType,
  AssignmentStatus,
  Category,
  DropdownAction,
  DropdownCategoryScope,
  DropdownContext,
  FollowupStatus,
  RegistrationStage,
  SessionCallStatus,
  SessionCampaignStatus,
  UserRole,
} from "@prisma/client";
import type { Request } from "express";
import { prisma } from "../../config/prisma.js";
import { ApiError } from "../../lib/api-error.js";
import { buildOwnershipPatch } from "../../utils/ownership.js";
import { applyDomainEvent } from "../../lib/events.js";
import { applyDeclaredRemoval, isRemovalAction } from "../../lib/declared-removal.js";
import { logger } from "../../lib/logger.js";
import { normalizeMobile } from "../../utils/mobile.js";
import { renderWhatsappMessage } from "../../lib/whatsapp-template.js";
import { buildPeopleContext, emptyPersonContext, type PersonContext } from "../caller/caller-context.service.js";

type RequestUser = NonNullable<Request["user"]>;

const requireCentreId = (centreId: number | null | undefined) => {
  if (!centreId) {
    throw new ApiError(400, "Centre is not configured for this user");
  }

  return centreId;
};

const resolveFollowupScopeCentreId = (requester: RequestUser) => {
  if (requester.role === UserRole.SUPER_ADMIN) {
    return requester.centreId ?? null;
  }

  return requireCentreId(requester.centreId);
};

const buildFollowupTransition = ({
  action,
  actorId,
  registration,
}: {
  action: DropdownAction;
  actorId: number;
  registration: {
    currentStage: RegistrationStage;
    activeQueue: ActiveQueue;
    originalCallerId: number | null;
    firstConnectedBy: number | null;
    relationshipOwnerId: number | null;
    lastHandledBy: number | null;
    currentAssignedCallerId: number | null;
    currentBatchCallerId: number | null;
    earlierManagedBy: number | null;
  };
}) => {
  const ownershipPatch = buildOwnershipPatch(registration, actorId);

  switch (action) {
    case DropdownAction.stay_in_followup:
    case DropdownAction.continue_calling:
    case DropdownAction.pause_personal_followup:
      return {
        stage: RegistrationStage.followup_active,
        queue: ActiveQueue.followup,
        ownershipPatch,
      } as const;
    case DropdownAction.send_back_to_original_caller:
      return {
        stage: RegistrationStage.sent_back_to_owner,
        queue: ActiveQueue.none,
        ownershipPatch: {
          ...ownershipPatch,
          currentAssignedCallerId: registration.originalCallerId ?? actorId,
        },
      } as const;
    case DropdownAction.archive:
    case DropdownAction.close_from_my_attendance:
      return {
        stage: RegistrationStage.archived,
        queue: ActiveQueue.archived,
        ownershipPatch,
      } as const;
    case DropdownAction.remove_from_running:
    case DropdownAction.mark_invalid:
      // Firm refusal from follow-up ("Won't come anymore") or a bad contact ("Wrong number") —
      // drop them out of the running. Same terminal treatment as the calling flow's removal.
      return {
        stage: RegistrationStage.removed,
        queue: ActiveQueue.removed,
        ownershipPatch,
      } as const;
    case DropdownAction.restore:
      return {
        stage: RegistrationStage.registered,
        queue: ActiveQueue.none,
        ownershipPatch,
      } as const;
    default:
      return {
        stage: registration.currentStage,
        queue: registration.activeQueue,
        ownershipPatch,
      } as const;
  }
};

export const getFollowupList = async ({
  category,
  callerId,
  adminOverride,
  requester,
}: {
  category: Category;
  callerId?: number;
  adminOverride?: boolean;
  requester: RequestUser;
}) => {
  const scopeCentreId =
    adminOverride && requester.role === UserRole.SUPER_ADMIN ? null : resolveFollowupScopeCentreId(requester);

  const followups = await prisma.followup.findMany({
    where: {
      status: FollowupStatus.active,
      ...(scopeCentreId !== null ? { centreId: scopeCentreId } : {}),
      registration: {
        category,
        currentStage: RegistrationStage.followup_active,
        activeQueue: ActiveQueue.followup,
      },
      ...(adminOverride || !callerId
        ? {}
        : {
            OR: [
              { ownerCallerId: callerId },
              {
                registration: {
                  currentAssignedCallerId: callerId,
                },
              },
              {
                registration: {
                  assignments: {
                    some: {
                      status: AssignmentStatus.active,
                      assignedToUserId: callerId,
                    },
                  },
                },
              },
            ],
          }),
    },
    include: {
      registration: true,
      ownerCaller: true,
    },
    orderBy: {
      createdAt: "asc",
    },
  });

  // Derive the originating calling-session label so the caller UI can group follow-ups under their
  // session name.
  //
  // Read it from the follow-up's OWN campaign link. This used to reverse-match the person by mobile
  // against will-attend assignments only, so a follow-up created by a "Call back later" response —
  // which is positive and does create a follow-up, but is deliberately NOT will_attend — matched
  // nothing and rendered in the app as the literal heading "📢 null".
  const campaignIds = Array.from(
    new Set(followups.map((f) => f.campaignId).filter((id): id is number => id != null)),
  );
  const labelByCampaign = new Map<number, string>();
  // The originating session's WhatsApp message travels with the follow-up. A follow-up IS part of
  // that calling session — ringing the same person again before the same class — so it must send
  // the same message. Only follow-ups WITH a campaign link get one; the pre-link rows below fall
  // back to a session NAME by mobile matching, which is a guess and must never drive what we send.
  const waTemplateByCampaign = new Map<number, { template: string | null; batch: string | null; date: Date }>();
  if (campaignIds.length > 0) {
    const camps = await prisma.sessionCampaign.findMany({
      where: { id: { in: campaignIds } },
      select: {
        id: true,
        aeSessionId: true,
        whatsappMessage: true,
        scheduledAt: true,
        batch: { select: { name: true } },
      },
    });
    const sessionRows = camps.length
      ? await prisma.session.findMany({
          where: { sessionId: { in: camps.map((c) => c.aeSessionId) } },
          select: { sessionId: true, displayName: true },
        })
      : [];
    const dispBy = new Map(sessionRows.map((s) => [s.sessionId, s.displayName]));
    for (const c of camps) {
      labelByCampaign.set(c.id, dispBy.get(c.aeSessionId) ?? c.batch?.name ?? c.aeSessionId);
      waTemplateByCampaign.set(c.id, {
        template: c.whatsappMessage,
        batch: c.batch?.name ?? null,
        date: c.scheduledAt,
      });
    }
  }

  // Follow-ups created before the campaign link existed have no campaignId. Only those fall back to
  // matching the person against their latest positive session response — both positive statuses,
  // not just will_attend, for the same reason as above.
  const normByRegId = new Map<number, string>();
  const wantedNorm = new Set<string>();
  for (const f of followups) {
    if (f.campaignId != null) continue;
    try {
      const n = normalizeMobile(f.registration.mobile);
      normByRegId.set(f.registrationId, n);
      wantedNorm.add(n);
    } catch { /* skip malformed */ }
  }

  const sessionLabelByNorm = new Map<string, string>();
  if (wantedNorm.size > 0) {
    const positive = await prisma.sessionCallAssignment.findMany({
      where: {
        callStatus: { in: [SessionCallStatus.will_attend, SessionCallStatus.call_back_later] },
        removedAt: null,
        campaign: {
          status: SessionCampaignStatus.active,
          ...(scopeCentreId !== null ? { centreId: scopeCentreId } : {}),
        },
      },
      include: {
        batchMember: { select: { mobile: true } },
        campaign: { select: { aeSessionId: true, batch: { select: { name: true } } } },
      },
      orderBy: { calledAt: "desc" },
    });
    const aeIds = Array.from(new Set(positive.map((r) => r.campaign.aeSessionId)));
    const sessionRows = aeIds.length
      ? await prisma.session.findMany({ where: { sessionId: { in: aeIds } }, select: { sessionId: true, displayName: true } })
      : [];
    const dispBy = new Map(sessionRows.map((s) => [s.sessionId, s.displayName]));
    for (const r of positive) {
      let n: string;
      try { n = normalizeMobile(r.batchMember.mobile); } catch { continue; }
      if (!wantedNorm.has(n) || sessionLabelByNorm.has(n)) continue; // desc order → first seen = most recent
      sessionLabelByNorm.set(n, dispBy.get(r.campaign.aeSessionId) ?? r.campaign.batch.name);
    }
  }

  // The answer that PUT this person in follow-ups. There is no `remark` column on a follow-up, so
  // the app was falling back to a hard-coded "Needs followup" on every single card — the same
  // sentence under every name, which reads as a screen frozen on stale data rather than the
  // placeholder it actually was. Send the real outcome label instead. Batched: one query for the
  // latest attempt per registration, no N+1.
  const lastOutcomeByRegId = new Map<number, string>();
  if (followups.length > 0) {
    const attempts = await prisma.callAttempt.findMany({
      where: { registrationId: { in: followups.map((f) => f.registrationId) } },
      select: { registrationId: true, dropdownValue: true, createdAt: true },
      orderBy: { createdAt: "desc" },
    });
    for (const a of attempts) {
      // desc order → first seen for a registration is its most recent answer
      if (!a.dropdownValue || lastOutcomeByRegId.has(a.registrationId)) continue;
      lastOutcomeByRegId.set(a.registrationId, a.dropdownValue);
    }
  }

  // The same per-person context the calling queue attaches (previous responses + remarks,
  // attendance, batches, address) so the Follow-ups tab can render the full calling card instead of
  // a bare name. Built per centre because buildPeopleContext is centre-scoped, and a SUPER_ADMIN
  // override can return follow-ups from several centres at once. Best-effort, like on the queue.
  const contextByCentre = new Map<number, Map<string, PersonContext>>();
  for (const centreId of new Set(followups.map((f) => f.centreId))) {
    try {
      contextByCentre.set(
        centreId,
        await buildPeopleContext(centreId, followups.filter((f) => f.centreId === centreId).map((f) => f.registration.mobile)),
      );
    } catch {
      // leave that centre's cards without context rather than failing the whole list
    }
  }
  const contextFor = (f: (typeof followups)[number]): PersonContext => {
    try {
      return contextByCentre.get(f.centreId)?.get(normalizeMobile(f.registration.mobile)) ?? emptyPersonContext();
    } catch {
      return emptyPersonContext();
    }
  };

  return {
    count: followups.length,
    items: followups.map((f) => ({
      ...f,
      context: contextFor(f),
      session_name:
        (f.campaignId != null ? labelByCampaign.get(f.campaignId) : undefined) ??
        sessionLabelByNorm.get(normByRegId.get(f.registrationId) ?? "") ??
        null,
      // Empty rather than invented when nothing was logged — the app decides what to show.
      last_outcome: lastOutcomeByRegId.get(f.registrationId) ?? "",
      // Rendered per person. Null for a follow-up with no campaign link, and for one whose session
      // has no message set — both mean "open WhatsApp with no text", the behaviour before this.
      whatsapp_message: (() => {
        if (f.campaignId == null) return null;
        const wa = waTemplateByCampaign.get(f.campaignId);
        if (!wa) return null;
        return renderWhatsappMessage(wa.template, {
          name: f.registration.name,
          mobile: f.registration.mobile,
          session: labelByCampaign.get(f.campaignId),
          batch: wa.batch,
          date: wa.date,
        });
      })(),
    })),
  };
};

export const markFollowupDone = async ({
  registrationId,
  dropdownValue,
  remark,
  requester,
}: {
  registrationId: number;
  dropdownValue: string;
  remark?: string;
  requester: RequestUser;
}) => {
  const scopeCentreId =
    requester.role === UserRole.SUPER_ADMIN ? requester.centreId ?? null : requireCentreId(requester.centreId);

  const result = await prisma.$transaction(async (tx) => {
    const registration = await tx.registration.findUnique({
      where: { id: registrationId },
      include: {
        centre: true,
      },
    });

    if (!registration) {
      throw new ApiError(404, "Registration not found");
    }

    if (scopeCentreId !== null && registration.centreId !== scopeCentreId) {
      throw new ApiError(403, "Registration is outside your centre");
    }

    const followup = await tx.followup.findUnique({
      where: { registrationId },
    });

    if (!followup || followup.status !== FollowupStatus.active) {
      throw new ApiError(409, "No active follow-up exists for this registration");
    }

    if (followup.centreId !== registration.centreId) {
      throw new ApiError(409, "Follow-up centre does not match registration centre");
    }

    // Retrieval (closeSessionCampaign) locks this follow-up's editability — the entry stays
    // fully visible/callable, but closing it out requires an admin to unlock it first.
    if (followup.isLocked) {
      throw new ApiError(409, followup.lockedReason ?? "This follow-up is locked — contact an admin to unlock it before closing it");
    }

    const option = await tx.dropdownOption.findFirst({
      where: {
        label: dropdownValue,
        context: DropdownContext.followup,
        isActive: true,
        OR: [{ category: DropdownCategoryScope.both }, { category: registration.category }],
      },
      include: {
        mapping: true,
      },
    });

    if (!option || !option.mapping) {
      throw new ApiError(400, "Invalid followup dropdown_value");
    }

    const attempt = await tx.callAttempt.create({
      data: {
        registrationId,
        callerId: followup.ownerCallerId,
        centreId: registration.centreId,
        callType: CallType.followup,
        dropdownValue: option.label,
        remark: remark?.trim() || null,
      },
    });

    const transition = buildFollowupTransition({
      action: option.mapping.action,
      actorId: followup.ownerCallerId,
      registration,
    });

    const nextFollowupOwnerId =
      transition.stage === RegistrationStage.followup_active
        ? followup.ownerCallerId
        : transition.ownershipPatch.currentAssignedCallerId;

    const updatedRegistration = await tx.registration.update({
      where: { id: registrationId },
      data: {
        currentStage: transition.stage,
        activeQueue: transition.queue,
        ...transition.ownershipPatch,
      },
    });

    if (transition.stage === RegistrationStage.followup_active) {
      // Staying in follow-up ("Still coming" / "Call again before class") — keep the row active,
      // just refresh its owner.
      await tx.followup.update({
        where: { registrationId },
        data: {
          ownerCallerId: nextFollowupOwnerId ?? followup.ownerCallerId,
          status: FollowupStatus.active,
          centreId: registration.centreId,
        },
      });
    } else {
      // ANY transition OUT of the follow-up queue closes the follow-up row, so it can never linger
      // as an orphaned `active` row after the person has left. Previously only
      // archive/close_from_my_attendance/remove_from_running/mark_invalid closed it, which left
      // `send_back_to_original_caller` (and a theoretical `restore`) with a stale active follow-up:
      // it vanished from getFollowupList (filtered on currentStage=followup_active) yet the DB row
      // stayed active — the "visible bug".
      await tx.followup.update({
        where: { registrationId },
        data: {
          status: FollowupStatus.closed,
          centreId: registration.centreId,
        },
      });
    }

    // Close any active calling assignment when the student leaves the followup queue.
    // A remaining-followup-pool re-assignment creates a CallingAssignment that is never
    // closed by markCallingDone; without this, it stays active after archive/restore/send-back.
    if (transition.stage !== RegistrationStage.followup_active) {
      await tx.callingAssignment.updateMany({
        where: { registrationId, status: AssignmentStatus.active },
        data: { status: AssignmentStatus.done },
      });
    }

    // Real-time read-model refresh: follow-up result may move this person to my_attendance,
    // archive, or removed (pending_followups, my_attendance, archived, removed shift).
    await applyDomainEvent(tx, {
      type: "followup.done",
      centreId: registration.centreId,
      category: registration.category,
    });

    return {
      attempt,
      followup,
      registration: updatedRegistration,
      action: option.mapping.action,
    };
  });

  // "Won't come anymore" / "Wrong number": the transaction above only moved the stage. Take them
  // out of their batches too, and record WHEN, so the Archive page can list them by date.
  if (isRemovalAction(result.action)) {
    try {
      await applyDeclaredRemoval({
        centreId: result.registration.centreId,
        action: result.action,
        label: result.attempt.dropdownValue,
        mobile: result.registration.normalizedMobile || result.registration.mobile,
        registrationId: result.registration.id,
        where: "follow-up call",
        now: new Date(),
      });
    } catch (e) {
      logger.error(`declared-removal (follow-up) failed reg=${registrationId}: ${(e as Error).message}`);
    }
  }
  return result;
};
