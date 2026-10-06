/**
 * Renders an admin's per-calling-session WhatsApp message for one person.
 *
 * The message is authored once per calling session in Directory Setup and reaches the caller
 * already personalised, so the caller never edits text on a phone mid-call.
 *
 * Rendering happens HERE, on the server, and never in the app:
 *   - one implementation instead of one per client;
 *   - the caller's queue cache stores the raw server JSON, so a rendered message survives offline
 *     with no extra work;
 *   - an app build that predates this feature simply ignores the field and opens WhatsApp with no
 *     text, which is the old behaviour rather than a broken one.
 *
 * An unknown placeholder is left VERBATIM rather than blanked. A typo like {naam} then shows up in
 * the message where an admin can see and fix it, instead of silently deleting part of the text.
 */

export type WhatsappTemplateVars = {
  /** The person being messaged. Falls back to their mobile — never to an empty string. */
  name?: string | null;
  /** The calling session's display name ("23rd Aug 2026"), not its generated id. */
  session?: string | null;
  /** The batch the session belongs to ("sunday 4 pm"). */
  batch?: string | null;
  /** When the class is scheduled. */
  date?: Date | string | null;
  /** Used as the name fallback so a nameless record still reads as addressed to someone. */
  mobile?: string | null;
};

const asDate = (value: Date | string | null | undefined): string => {
  if (!value) return "";
  const d = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(d.getTime())) return "";
  // Local date, matching how every other date in these reports is presented.
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
};

/**
 * Substitutes {name} / {session} / {batch} / {date}. Case-insensitive and tolerant of inner spaces
 * ("{ Name }") because these are typed by hand on a phone keyboard.
 *
 * Returns null for a blank template so callers can treat "no message" as one condition.
 */
export const renderWhatsappMessage = (
  template: string | null | undefined,
  vars: WhatsappTemplateVars,
): string | null => {
  if (!template) return null;
  const trimmed = template.trim();
  if (!trimmed) return null;

  const values: Record<string, string> = {
    name: (vars.name ?? "").trim() || (vars.mobile ?? "").trim(),
    session: (vars.session ?? "").trim(),
    batch: (vars.batch ?? "").trim(),
    date: asDate(vars.date),
  };

  const rendered = trimmed.replace(/\{\s*([a-zA-Z_]+)\s*\}/g, (whole, rawKey: string) => {
    const key = rawKey.toLowerCase();
    // `key in values` rather than a truthiness check: a KNOWN placeholder whose value is genuinely
    // empty must collapse to nothing, while an UNKNOWN one is handed back untouched.
    return key in values ? values[key]! : whole;
  });

  // Trimmed: a placeholder that collapsed to nothing at either end ("Class {date}" with no date)
  // would otherwise leave stray whitespace on the message. And a template that renders to nothing
  // at all (only "{name}", for a row with neither name nor mobile) is no message — returning ""
  // would put an empty ?text= on the link.
  const out = rendered.trim();
  return out ? out : null;
};
