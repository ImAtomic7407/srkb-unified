// Server-Sent Events hub — one-way, real-time server→client push.
//
// Why SSE (not WebSocket): we only need the server to push small "something changed, refetch"
// signals to connected devices. SSE is plain HTTP (works through the Cloudflare tunnel), needs
// no extra deps, auto-reconnects in the browser/WebView, and is trivial to consume with
// EventSource. WebSocket's bidirectional channel would be overkill here.
//
// Scope: pm2 runs this backend as a SINGLE process (fork mode), so an in-memory client registry
// reaches every connected device. If this is ever moved to pm2 cluster mode or multiple hosts,
// replace the in-memory fan-out with a shared bus (e.g. Redis pub/sub) — the public API here
// (registerSseClient / broadcastSse) can stay the same.

import type { Response } from "express";

export type SseClientMeta = {
  userId: number;
  centreId: number | null; // the user's home centre (null for host with no centre)
  isHost: boolean;
};

type SseClient = SseClientMeta & {
  id: number;
  res: Response;
};

// Targeting filter for a broadcast. Omit to send to everyone.
//   centreId === null  → a GLOBAL change: deliver to all clients.
//   centreId === <num> → a centre-specific change: deliver to that centre's clients (+ hosts,
//                        who may be viewing any centre).
export type SseTarget = { centreId?: number | null };

const clients = new Set<SseClient>();
let nextId = 1;

const HEARTBEAT_MS = 25_000; // keep idle connections alive through proxies/Cloudflare

// Write one SSE frame. Comments (": …") are ignored by clients and used as heartbeats.
const writeEvent = (res: Response, event: string, data: unknown): void => {
  res.write(`event: ${event}\n`);
  res.write(`data: ${JSON.stringify(data)}\n\n`);
};

/**
 * Register a long-lived SSE connection. Sets the streaming headers, emits an initial
 * "connected" event, starts a heartbeat, and tears everything down when the client
 * disconnects. Returns nothing — the response stays open until the client closes it.
 */
export const registerSseClient = (res: Response, meta: SseClientMeta): void => {
  res.status(200);
  res.setHeader("Content-Type", "text/event-stream");
  res.setHeader("Cache-Control", "no-cache, no-transform");
  res.setHeader("Connection", "keep-alive");
  res.setHeader("X-Accel-Buffering", "no"); // disable proxy buffering so events flush immediately
  res.flushHeaders?.();

  const client: SseClient = { id: nextId++, res, ...meta };
  clients.add(client);

  writeEvent(res, "connected", { clientId: client.id, ts: Date.now() });

  const heartbeat = setInterval(() => {
    try {
      // A COMMENT alone is not enough, and the reason is subtle enough to be worth recording.
      //
      // ": ping" keeps proxies from idling the socket out, but it is invisible to a client that
      // reads the stream through an SSE parser: comments are consumed by the parser and never
      // surfaced as an event. Android's okhttp-sse behaves exactly that way, so a client whose
      // liveness check is "have I received anything lately?" sees nothing on a perfectly healthy
      // but idle connection, decides the link is dead, and reconnects — every 60 seconds,
      // forever, each time forcing a full refresh.
      //
      // So send a real named event too. Clients ignore unknown event names by contract (§5), and
      // the delivery itself is what proves the connection is alive. The comment stays because it
      // is what actually holds the proxy open.
      res.write(": ping\n\n");
      writeEvent(res, "ping", { ts: Date.now() });
    } catch {
      // Write after close — cleanup below will handle it.
    }
  }, HEARTBEAT_MS);

  const cleanup = () => {
    clearInterval(heartbeat);
    clients.delete(client);
  };

  res.req.on("close", cleanup);
  res.on("error", cleanup);
};

const matchesTarget = (client: SseClient, target?: SseTarget): boolean => {
  if (!target || target.centreId === undefined || target.centreId === null) {
    return true; // no target / global change → everyone
  }
  return client.centreId === target.centreId || client.isHost;
};

/**
 * Push an event to all connected clients that match `target`. Never throws — a dead
 * connection is dropped silently. Returns the number of clients the event was sent to.
 */
export const broadcastSse = (event: string, data: unknown, target?: SseTarget): number => {
  let sent = 0;
  for (const client of clients) {
    if (!matchesTarget(client, target)) continue;
    try {
      writeEvent(client.res, event, data);
      sent++;
    } catch {
      clients.delete(client);
    }
  }
  return sent;
};

/** Current number of live SSE connections (diagnostics). */
export const sseClientCount = (): number => clients.size;
