import { DropdownAction } from "@prisma/client";
import {
  allowedSentiments,
  strikeAllowed,
  consecutiveRuleAllowed,
  normaliseResponseRules,
} from "../src/lib/response-rules";

let failed = 0;
const check = (name: string, cond: boolean) => {
  if (!cond) { console.error("FAIL:", name); failed++; } else { console.log("ok:", name); }
};

// A response that keeps the person in the queue cannot assert they are coming.
check("continue_calling excludes positive",
  !allowedSentiments(DropdownAction.continue_calling).includes("positive"));
check("move_to_followup allows positive",
  allowedSentiments(DropdownAction.move_to_followup).includes("positive"));
check("remove_from_running excludes not_reached",
  !allowedSentiments(DropdownAction.remove_from_running).includes("not_reached"));

// Not reaching someone is never their fault, so it can never cost them a strike.
check("not_reached cannot strike", strikeAllowed("not_reached") === false);
check("negative can strike", strikeAllowed("negative") === true);

// The regression that started all of this: "No incoming" must never be positive.
const noIncoming = normaliseResponseRules({
  action: DropdownAction.continue_calling,
  sentiment: "positive",           // caller of the API asks for something illegal
  countsAsStrike: true,
  removeAfterConsecutive: 3,
  answeredScope: "not_answered",
});
check("illegal positive is coerced away from positive", noIncoming.sentiment !== "positive");
check("not_reached forces strike off", noIncoming.countsAsStrike === false);
// A consecutive-removal rule is gated on MEANING, not on striking. An unreachable response keeps
// its rule even though it can never strike — that combination is the whole point of the setting,
// and requiring a strike made "remove after N switched-off calls" impossible to express.
check("not_reached KEEPS its consecutive rule", noIncoming.removeAfterConsecutive === 3);

// …but a positive one can never carry it, however often it repeats.
check("positive cannot carry a consecutive rule",
  normaliseResponseRules({
    action: DropdownAction.move_to_followup, sentiment: "positive", countsAsStrike: false,
    removeAfterConsecutive: 2, answeredScope: "answered",
  }).removeAfterConsecutive === null);
check("consecutive rule allowed for not_reached", consecutiveRuleAllowed("not_reached") === true);
check("consecutive rule allowed for negative", consecutiveRuleAllowed("negative") === true);
check("consecutive rule barred for positive", consecutiveRuleAllowed("positive") === false);

// A legal combination must survive untouched.
const willAttend = normaliseResponseRules({
  action: DropdownAction.move_to_followup,
  sentiment: "positive",
  countsAsStrike: false,
  removeAfterConsecutive: null,
  answeredScope: "answered",
});
check("legal positive survives", willAttend.sentiment === "positive");
check("answered scope survives", willAttend.answeredScope === "answered");

// mark_invalid is discoverable whether or not they picked up.
check("mark_invalid forces both",
  normaliseResponseRules({
    action: DropdownAction.mark_invalid, sentiment: "negative", countsAsStrike: false,
    removeAfterConsecutive: null, answeredScope: "answered",
  }).answeredScope === "both");

console.log(failed === 0 ? "\nALL PASS" : `\n${failed} FAILED`);
process.exit(failed === 0 ? 0 : 1);
