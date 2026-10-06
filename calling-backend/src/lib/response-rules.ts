import { DropdownAction } from "@prisma/client";

export type Sentiment = "positive" | "negative" | "not_reached";
export type AnsweredScope = "answered" | "not_answered" | "both";

/**
 * Which meanings each behaviour can legally carry.
 *
 * This table is the whole point of the redesign. Meaning used to be guessed from the label text
 * by regex (CallStatusRules.kt), which scored "No incoming" as will_attend — a person who had
 * blocked incoming calls was recorded as having confirmed attendance, created a follow-up and
 * never took a strike. Meaning is now declared, and the illegal declarations simply do not exist.
 */
/**
 * ORDER MATTERS: the first entry is the fail-safe default that an illegal or missing sentiment
 * coerces to. It must always be the least destructive option available for that action, because
 * a malformed or replayed request must never be able to turn a response into one that strikes.
 * Wrongly striking someone eventually removes them from the programme; wrongly not striking
 * merely leaves them in the calling list, where a human will see them again.
 */
const SENTIMENTS_BY_ACTION: Record<DropdownAction, Sentiment[]> = {
  move_to_followup: ["positive"],
  followup_callback: ["positive"],
  defer_calling: ["positive"],
  continue_calling: ["not_reached", "negative"],
  remove_from_running: ["negative"],
  mark_invalid: ["negative"],
  // Actions outside the calling context keep the permissive set; they are not offered in the
  // centre-admin editor and are listed here only so this map stays exhaustive.
  stay_in_followup: ["positive", "negative", "not_reached"],
  archive: ["negative"],
  restore: ["positive"],
  reassign_calling: ["positive", "negative", "not_reached"],
  request_move_to_batch: ["positive", "negative", "not_reached"],
  move_to_batch_calling: ["positive", "negative", "not_reached"],
  send_back_to_original_caller: ["positive", "negative", "not_reached"],
  assign_to_online_team: ["positive", "negative", "not_reached"],
  pause_personal_followup: ["positive", "negative", "not_reached"],
  close_from_my_attendance: ["positive", "negative", "not_reached"],
  move_to_another_batch: ["positive", "negative", "not_reached"],
  keep_in_batch_calling: ["positive", "negative", "not_reached"],
};

export const allowedSentiments = (action: DropdownAction): Sentiment[] =>
  SENTIMENTS_BY_ACTION[action] ?? ["negative"];

/**
 * Only a refusal can count against someone.
 *
 * Failing to reach them is not their fault, and a response that means they SAID YES cannot
 * simultaneously push them toward removal — that combination is a contradiction, not a
 * configuration. Stated as "is negative" rather than "is not not_reached" because the second form
 * quietly permitted positive + strike, which the editor then offered.
 */
export const strikeAllowed = (sentiment: Sentiment): boolean => sentiment === "negative";

/**
 * Which meanings may carry a "remove after N of these in a row" rule.
 *
 * Deliberately WIDER than strikeAllowed, and the difference is the whole point. A phone that is
 * switched off every single time is not a refusal — it must never inflate the absence tally that
 * other decisions read (session forecasts, tolerance archiving), so it stays `not_reached` and
 * keeps `countsAsStrike` false. But a centre may still decide that N unanswerable calls in a row
 * means stop carrying the person on the roster. That is a separate, explicitly configured rule, not
 * a strike, and this is what lets the two coexist without one corrupting the other.
 *
 * `positive` remains barred: a response that means "they answered and said something encouraging"
 * cannot push anybody toward removal, however many times it repeats.
 */
export const consecutiveRuleAllowed = (sentiment: Sentiment): boolean =>
  sentiment === "negative" || sentiment === "not_reached";

export type ResponseRulesInput = {
  action: DropdownAction;
  sentiment?: Sentiment | null;
  countsAsStrike?: boolean | null;
  removeAfterConsecutive?: number | null;
  answeredScope?: AnsweredScope | null;
};

/**
 * Coerce a requested configuration into the nearest legal one.
 *
 * The editor greys out illegal choices, but the API must not trust that: a stale client, a
 * replayed request or a hand-rolled call could still ask for "positive + keeps calling".
 */
export const normaliseResponseRules = (input: ResponseRulesInput) => {
  const allowed = allowedSentiments(input.action);
  // `?? "not_reached"` is unreachable in practice (every action maps to a non-empty list) but
  // keeps the fail-safe explicit rather than trusting an index, and satisfies the strict index
  // checking this project compiles under.
  const sentiment: Sentiment =
    input.sentiment && allowed.includes(input.sentiment)
      ? input.sentiment
      : (allowed[0] ?? "not_reached");

  const countsAsStrike = strikeAllowed(sentiment) ? Boolean(input.countsAsStrike) : false;

  // A consecutive-removal rule is gated on MEANING, not on whether the response strikes. It used to
  // require countsAsStrike, which made the rule unreachable for exactly the case it is most wanted
  // for — a number that is switched off every time. Those responses are `not_reached` by design and
  // can never strike, so requiring a strike silently barred any "remove after N switched-off calls"
  // policy from being expressible at all.
  const removeAfterConsecutive =
    consecutiveRuleAllowed(sentiment) && input.removeAfterConsecutive && input.removeAfterConsecutive > 0
      ? input.removeAfterConsecutive
      : null;

  // A wrong number is discoverable whether or not anyone picked up.
  const answeredScope: AnsweredScope =
    input.action === DropdownAction.mark_invalid ? "both" : (input.answeredScope ?? "both");

  return { sentiment, countsAsStrike, removeAfterConsecutive, answeredScope };
};
