// All event types AttendEase can fire to this server
export type WebhookEventType =
  | "centre.created"
  | "centre.renamed"
  | "centre.status_changed"
  | "centre.deleted"
  | "batch.created"
  | "batch.renamed"
  | "batch.archived"
  | "batch.category_set"
  | "batch.default_set"
  | "session.created"
  | "session.deleted"
  | "registerer.access_granted"
  | "registerer.access_revoked"
  | "registerer.account_created"
  | "registerer.account_approved"
  | "attendance.persisted"
  // Host-side Roster Inject in AttendEase. reconcileRosterMembers already picks
  // roster rows up, but only once every 24h; this event exists so an inject is
  // callable immediately. The reconcile remains the safety net behind it.
  | "roster.injected";

export type WebhookEvent = {
  event_type: WebhookEventType;
  entity_type: string;
  entity_id: string;
  payload: Record<string, unknown>;
  fired_at: string;
};
