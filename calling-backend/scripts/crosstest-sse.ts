// End-to-end test for the real-time dropdown push (SSE).
//
// Starts the real Express app, opens a genuine /events SSE connection as an authenticated
// user, then changes a dropdown via the real service and asserts the connected client
// receives a "dropdowns.changed" event — proving a change on one device pushes to another.
//
//   npx tsx scripts/crosstest-sse.ts
//
// Creates a throwaway GLOBAL dropdown option and deletes it afterward.

import jwt from "jsonwebtoken";
import type { AddressInfo } from "net";
import { DropdownAction, DropdownCategoryScope, DropdownContext } from "@prisma/client";
import { createApp } from "../src/app.js";
import { env } from "../src/config/env.js";
import { rawPrisma } from "../src/config/prisma.js";
import { createDropdown, disableDropdown } from "../src/modules/dropdowns/dropdowns.service.js";

const USER_MOBILE = "krsna123"; // user 258
const TEST_LABEL = `__sse_test_${Math.floor(env.port)}__`; // label varied without Math.random

const sign = () => jwt.sign({ sub: USER_MOBILE }, env.jwtSharedSecret, { expiresIn: "5m" });

const run = async () => {
  const app = createApp();
  const server = app.listen(0);
  await new Promise<void>((r) => server.once("listening", () => r()));
  const port = (server.address() as AddressInfo).port;
  console.log(`test server on ${port}`);

  let received: any = null;
  let createdId: number | undefined;
  const controller = new AbortController();

  // Open the SSE stream and watch for our event in the background.
  const token = sign();
  const streamDone = (async () => {
    const res = await fetch(`http://127.0.0.1:${port}/events?token=${encodeURIComponent(token)}`, {
      headers: { Accept: "text/event-stream" },
      signal: controller.signal,
    });
    if (!res.ok || !res.body) throw new Error(`SSE connect failed: ${res.status}`);
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buf = "";
    let connected = false;
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buf += decoder.decode(value, { stream: true });
      const frames = buf.split("\n\n");
      buf = frames.pop() ?? "";
      for (const frame of frames) {
        if (frame.includes("event: connected")) connected = true;
        if (frame.includes("event: dropdowns.changed")) {
          const dataLine = frame.split("\n").find((l) => l.startsWith("data: "));
          received = dataLine ? JSON.parse(dataLine.slice(6)) : true;
          return;
        }
      }
      // Once connected, trigger the change exactly once.
      if (connected && createdId === undefined) {
        createdId = -1; // guard against re-entry
        const dd = await createDropdown({
          label: TEST_LABEL,
          context: DropdownContext.calling,
          category: DropdownCategoryScope.both,
          action: DropdownAction.continue_calling,
          sortOrder: 999,
          centreId: null, // global → broadcasts to everyone, incl. this client
        });
        createdId = dd.id;
        console.log(`triggered dropdown create id=${createdId}`);
      }
    }
  })();

  // Race the stream against a timeout.
  const timeout = new Promise((_, rej) => setTimeout(() => rej(new Error("timed out waiting for SSE event")), 8000));
  let ok = false;
  try {
    await Promise.race([streamDone, timeout]);
    ok = received && received.change === "created" && received.context === "calling";
    console.log(ok ? "  ✓ received dropdowns.changed:" : "  ✗ unexpected payload:", JSON.stringify(received));
  } catch (e) {
    console.log("  ✗", (e as Error).message);
  } finally {
    controller.abort();
    // Cleanup the throwaway option (+ mapping via the disable path is soft; hard-delete here).
    if (createdId && createdId > 0) {
      await rawPrisma.dropdownMapping.deleteMany({ where: { dropdownId: createdId } }).catch(() => {});
      await rawPrisma.dropdownOption.deleteMany({ where: { id: createdId } }).catch(() => {});
    } else {
      await rawPrisma.dropdownOption.deleteMany({ where: { label: TEST_LABEL } }).catch(() => {});
    }
    server.close();
  }

  console.log(ok ? "\nRESULT: PASS — dropdown change pushed to the connected device in real time." : "\nRESULT: FAIL");
  process.exit(ok ? 0 : 1);
};

run().catch((e) => {
  console.error("sse test error:", e);
  process.exit(1);
});
