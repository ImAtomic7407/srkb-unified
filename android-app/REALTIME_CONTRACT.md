# Realtime Contract — SRKB Unified

**Status: FROZEN, and the server half is already built, deployed and live.**
The Android app must be built against this document exactly. If something here is wrong or
impossible, **stop and report it — do not improvise a different shape.**

Version 2.1 · 16 Aug 2026 · supersedes v1.0 (WebSocket) — see §1.
_2.1: the 25s heartbeat is now a real `ping` event as well as a comment (§5, §6)._

---

## 1. Why this is SSE and not WebSocket

The original plan was a WebSocket. On inspection that turned out to be the wrong tool **and
unnecessary**, because the calling backend has had a working real-time push hub for months:

- `GET /events` — authenticated, long-lived SSE stream (`src/modules/events/events.router.ts`)
- `src/lib/sse.ts` — client registry, centre-scoped targeting, 25-second heartbeat, proxy-buffering
  disabled, automatic cleanup on disconnect

It was wired to exactly one event (`dropdowns.changed`) and **the Android app never consumed it at
all**. So the transport already existed, already worked through the Cloudflare tunnel, and was
simply never connected to anything that mattered.

What changed on the server (already done, already deployed):
every AttendEase event now fans out to connected devices via `broadcastAeEvent`
(`src/lib/ae-broadcast.ts`), called from the sync router once a handler has succeeded.

Why SSE is the right choice here, not a compromise:

- We only need **server → client** notifications. The client never needs to push over the socket.
- It is plain HTTP, so it traverses the Cloudflare tunnel with no config change — **verified: the
  endpoint answers correctly through `calling.example.com`.**
- No new dependency, no new port, no second transport to operate and monitor.
- The hub is already in production and proven.

A WebSocket would add a bidirectional channel we have no use for, plus a new dependency and a new
failure mode, in exchange for nothing. If a genuine need for client→server push appears later, the
existing hub's public API can be swapped without changing this contract's shape.

**Why the socket lives on the calling backend and not on AttendEase:** AttendEase is Flask served by
**waitress**, which does not support streaming upgrades or long-lived push. Replacing the WSGI
server under a system that is marking live attendance is not a risk worth taking, and it is not
needed — AttendEase already pushes every relevant event to the calling backend over an internal
webhook.

```
Flask (AttendEase) --internal webhook--> Node (Calling Backend) --SSE--> App
     unchanged                            fan-out: DONE            client: TO BUILD
```

---

## 2. The rule that matters most: notification, not data

**An event never carries application data. It carries the fact that something changed.**

The stream is on the calling backend; most of the data the app needs lives on Flask. An event
therefore says *what scope moved*, and the app refetches from whichever backend owns that data,
through the code paths it already has.

Mandatory:

- Never render a screen from an event payload.
- Never treat a payload as authoritative for counts, names, or membership.
- An event's only job is to make the app ask "do I care?" and, if so, bump the refresh counter.

---

## 3. The stream does NOT replace the polling

**The existing checksum poll (`syncServerCacheIfChanged`) stays. It now runs every 5 seconds.**

Not redundancy — different coverage:

- The **poll** hashes server state, so it catches *everything*, including changes that emit no event
  at all. Two confirmed cases: **activating a session** (`set_session` in Flask fires no webhook)
  and **a mark being superseded** by a later capture. A stream-only design would silently miss both.
- The **stream** is fast but only carries events that are actually emitted.

**The poll is the floor, the stream is the accelerator.** Both feed the same counter. Removing the
poll, lengthening its interval, or making it conditional on stream health is a regression. If you
believe otherwise, report it rather than doing it.

---

## 4. Connecting

**URL** — `GET {callingBackendUrl}/events`
Derive from `AppDatabase.callingBackendUrl`; never hardcode the host. In production that resolves to
`https://calling.example.com/events`.

**Auth** — the **calling backend's** JWT, `AppDatabase.jwtToken`:

```
Authorization: Bearer <jwtToken>
```

Not `attendanceJwtToken` — that is Flask's and will be rejected. The server also accepts
`?token=` / `?access_token=` for header-less clients; on Android **use the header**, since a query
parameter would be written to access logs.

**Request headers:** send `Accept: text/event-stream`. Disable OkHttp's read timeout for this call
(`readTimeout(0, …)`) — the connection is meant to stay open indefinitely, and the default timeout
will otherwise tear it down every few seconds.

**Connect only when** `jwtToken != null` and `isOnline` is true. Close on logout, and when the app
has been backgrounded for more than 60 seconds.

**Responses:**

| Status | Meaning | Client action |
|---|---|---|
| 200 + `text/event-stream` | connected | proceed |
| 401 | token missing / invalid / expired | **stop.** Do not retry in a loop; reconnect only after re-auth |
| 403 | authenticated but account not active | stop; report; do not retry |

---

## 5. Wire format

Standard SSE. Frames are separated by a blank line. Two kinds arrive:

**A heartbeat every 25 seconds**, sent as BOTH a comment and a real named event:
```
: ping

event: ping
data: {"ts":1786867521000}
```
The comment is what holds the proxy open. The **named event** is what your SSE parser can actually
see — comments are consumed by the parser and never surfaced to a listener, so a liveness check
built only on "have I received anything?" would fire on a perfectly healthy idle connection and
reconnect every 60 seconds forever. Ignore `ping`'s payload, but **count its arrival as liveness**
(see §6).

**A named event with a JSON payload:**
```
event: connected
data: {"clientId":7,"ts":1786867521000}

event: data.changed
data: {"kind":"attendance.persisted","centreCode":"centre_a_Base","centreId":2,
       "category":"youth","scope":{"sessionId":"2026-08-15 CTRA BG","batchId":18},
       "ts":1786867521000}

event: dropdowns.changed
data: {"change":"created","centreId":2,"context":"address","category":"all","ts":…}
```

### Events

| `event:` | Meaning |
|---|---|
| `connected` | sent once on open. Carries `clientId` and `ts`. Use as the signal to force the initial full refresh. |
| `data.changed` | something in AttendEase moved. **This is the one that matters.** |
| `dropdowns.changed` | an address/remark list changed. Pre-existing; refetch dropdown options. |
| `ping` | 25-second heartbeat. Carries only `ts`. Do nothing except record that bytes arrived. |

**Unknown event names must be ignored, not treated as errors.** That is how this will be extended.

### `data.changed` payload

| Field | Type | Notes |
|---|---|---|
| `kind` | string | see the table below |
| `centreCode` | string \| null | Flask centre id, e.g. `centre_a_Base` — matches `selectedFlaskCentreId` |
| `centreId` | number \| null | calling backend's numeric id — matches `selectedCentreId` |
| `category` | string \| null | **lowercase** (`youth`/`congregation`), or null when not category-specific |
| `scope` | object | optional `sessionId`, `batchId`. Treat every field as optional. |
| `ts` | number | server epoch ms |

Both centre identifiers are sent because different screens key on different ones. `category` is
lowercase on the wire while the app's state is title case — normalise on the app side, exactly as
`mergeServerRosterInto` already does.

### `kind` values

`attendance.persisted` · `session_attendance_recorded` · `roster.injected` ·
`batch.created` · `batch.renamed` · `batch.deleted` · `batch.archived` · `batch.category_set` ·
`batch.default_set` · `session.created` · `session.renamed` · `session.deleted` ·
`centre.renamed` · `centre.status_changed`

**Deliberately not emitted by anything today** — this is exactly why §3 exists:
session **activation** (`set_session`), mark **supersession**, and host-side attendance repairs.

---

## 6. Reliability

**Every connect and reconnect forces a full refresh.** Events fired while disconnected are gone —
there is no replay buffer and no `Last-Event-ID` support. So on receiving `connected`, bump the
counter as though everything changed, before trusting any screen.

**Liveness.** The server sends a `ping` **event** every 25 seconds (§5). If nothing arrives for 60
seconds — no `ping`, no `data.changed` — treat the connection as dead, close it, and reconnect. Do
not rely on TCP to notice; mobile networks hold dead sockets open for minutes. Note that the
liveness timestamp must be refreshed by **every** frame including `ping`, not only by events you
act on.

**Backoff.** 1s, 2s, 5s, 10s, 30s, then 30s forever, each with ±20% jitter. Reset to 1s after the
connection has been healthy for 60 seconds. Never reconnect faster than 1s. Never reconnect at all
after a 401 or 403 (§4).

**Targeting is generous, so filter on the client.** When the server cannot resolve a centre it
broadcasts to everyone rather than risk dropping a notification. Expect events for centres and
categories you are not showing, and ignore them — see §7.

**The stream is never required.** Every screen must work correctly with it permanently unavailable.
That is the app's current state, and the poll carries it.

---

## 7. What the app does with an event — the whole algorithm

```
on "connected":
    AppDatabase.bumpDataRevision()        // reconnect ⇒ assume you missed something

on "data.changed" e:
    if e.centreCode != null && e.centreCode != selectedFlaskCentreId:            ignore
    if e.category   != null && !e.category.equalsIgnoreCase(selectedCategory):   ignore
    AppDatabase.bumpDataRevision()

on "dropdowns.changed":
    refetch dropdown options (existing path)
```

That is all of it. **No screen subscribes to the stream directly. No screen parses an event.** Every
screen observes the shared counter, which the 5-second poll also bumps — so a screen cannot tell,
and must not care, whether a refresh came from the stream or the poll.

---

## 8. Definition of done

- With the stream **blocked at the network level**, the app behaves exactly as it does today on the
  poll alone. No screen breaks, no error is shown to the operator.
- With the stream connected, a mark on phone A moves the count on phone B in **under two seconds**,
  with no interaction on B.
- Killing the backend mid-session reconnects with backoff and forces one full refresh on recovery.
- Airplane mode on/off cycles the connection cleanly — no leaked connections, no reconnect storm.
- An expired token closes the stream **without** a reconnect loop, and it recovers after re-auth.
- The app is backgrounded for ten minutes and foregrounded: one clean reconnect, one full refresh.

---

## 9. Server-side reference (already built — read, don't modify)

| File | Role |
|---|---|
| `src/modules/events/events.router.ts` | `GET /events` — auth + registration |
| `src/lib/sse.ts` | client registry, targeting, heartbeat, `broadcastSse` |
| `src/lib/ae-broadcast.ts` | **new** — maps AttendEase events to `data.changed` |
| `src/modules/sync/sync.router.ts` | calls `broadcastAeEvent` after a handler succeeds |

Verified live on 16 Aug 2026: `/events` returns 401 without a token both locally and through
`https://calling.example.com`, confirming the tunnel passes the stream through untouched.

One operational note, relevant only if the deployment changes: `sse.ts` keeps its client registry
**in memory**, which is correct because pm2 runs this backend as a single fork-mode process. If it
is ever moved to cluster mode or multiple hosts, the fan-out needs a shared bus (Redis pub/sub or
similar). The public API would not change.
