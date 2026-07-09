---
name: mirage-mock
description: >
  How to turn a natural-language request into a runtime gRPC mock and apply it to a connected device
  in any app that integrates the Mirage mock engine. Use this whenever someone wants to fake a server
  response to test a screen state — e.g. "show this screen as if a card is registered", "make the
  fake-call result a successful match", "mock ListCards", "give me mock data for X", "the server data
  isn't ready but I want to test this screen". App-agnostic: works in any Android app that has Mirage.
  Reach for this BEFORE grepping proto files.
invocable: true
---

# Mirage Mock Authoring

Mirage **overrides specific gRPC RPC responses at runtime in debug builds**. You inject a mock over a
small HTTP control endpoint the app exposes on the device (loopback only), and from the next call on,
that JSON is returned instead of the real server response — no rebuild. This skill takes you from a
**natural-language request to an applied mock**.

Your job is usually: the user says "show this screen as if it were in state X", and you (1) find which
RPC to mock, (2) get the real shape of that response, (3) write JSON that bends it to the requested
state, (4) inject it over HTTP, and (5) confirm it took effect.

---

## Mirage's fixed rules (the same in every app)

These are the engine's contract, so they hold **in any app** — you don't need to re-read the code to
confirm them.

- **Control endpoint**: a debug-only HTTP server bound to **device loopback `127.0.0.1:8080`**. It is
  reachable only over a USB port-forward (never on Wi-Fi/LAN):
  ```bash
  adb forward tcp:8080 tcp:8080      # Android; iOS parity: iproxy 8080 8080
  ```
  Do this once per session. If a `curl` later fails with connection refused / `HTTP 000`, the forward
  was dropped (device re-plugged, adb restarted) — just run it again.
- **Endpoint key**: the gRPC `fullMethodName`, e.g. `ridergwv1.RiderGw/ListCards`. In a **URL path**
  segment, replace `/` with `__`: `ridergwv1.RiderGw__ListCards`.
- **Corpus (real-response samples)** — real responses captured automatically as each call passes
  through, up to 5 recent distinct samples per endpoint. **This is your primary source for shape and
  real values.** Read it over HTTP:
  ```bash
  curl -s http://localhost:8080/mirage/corpus            # list captured endpoint keys (slash form)
  curl -s http://localhost:8080/mirage/corpus/<key__>    # recent samples, JSONL, most recent first
  ```
- **Inject / remove a mock** (mocks are held **in memory**):
  ```bash
  curl -X PUT    http://localhost:8080/mirage/mock/<key__> -d @mock.json   # turn on
  curl -X DELETE http://localhost:8080/mirage/mock/<key__>                 # turn off → real server
  ```
- **Applies from the next RPC**: a `PUT` takes effect immediately; the user just refreshes the screen
  (pull-to-refresh / re-enter it). No restart needed to apply.
- **In-memory only**: mocks do **not** survive an app restart. If the app is killed/relaunched,
  re-`PUT`. To turn a mock off deliberately, `DELETE` it.
- **Unary only**: only unary RPCs (server sends a single message) are mocked. Server-streaming and
  bidi-streaming **always pass through** and are never mocked. (Streaming is unsupported; polling is
  repeated unary and is handled automatically.)
- **JSON format = protobuf JsonFormat**: field names are camelCase, and **unknown fields are ignored**
  (`ignoringUnknownFields`). So you don't have to fill the whole response — a partial mock containing
  **only the fields you want to change** is valid. Enums are written as their string names.

---

## What you discover per project

The rules above are fixed. Only **which RPC to mock** and **its response shape** vary per app — and
you get both from the corpus over HTTP, so this stays portable (no repo layout, no device paths).

### 1. Which RPC to mock (the target endpoint key)
```bash
curl -s http://localhost:8080/mirage/corpus
# e.g. ridergwv1.RiderGw/ListCards, ridergwv1.RiderGw/GetUser, ...
```
If the user just visited that screen (with no mock active), the RPCs it called are captured here.
Match the natural-language request to an endpoint name. If it's not listed, either run that screen
once against the real server so it gets captured (preferred), or fall back to finding the RPC name in
the repo's proto/code.

### 2. The response shape (field names / structure)
**First choice = a corpus sample.** It's a real response, so there's no hallucination risk — copy it
and change only what you need. The URL segment uses the `__` form of the key:
```bash
curl -s http://localhost:8080/mirage/corpus/ridergwv1.RiderGw__ListCards | head -1
```
Each line is one compact JSON, most recent first. If there are several, pick the sample closest to the
requested state as your base. **Fallback (no sample at all)**: build a skeleton from the RPC's proto
message shape — but even then, confirm enum values / field conventions against a similar endpoint's
corpus if you can. (Proto files are often vendored and read-only — **never edit them**.)

---

## Workflow (natural language → applied mock)

1. **Open the tunnel** → `adb forward tcp:8080 tcp:8080` (once per session).
2. **Find the target RPC** → `curl -s localhost:8080/mirage/corpus` to get the endpoint key.
   - If the corpus is empty and the user hasn't hit that screen yet: ask them to "enter that screen
     once with mocks off so the real response gets captured, then I'll base the mock on it." Corpus
     grounding gives the most accurate result.
3. **Grab a base sample** → `curl -s localhost:8080/mirage/corpus/<key__> | head -1`.
4. **Write the JSON** → copy the base sample and edit only the fields needed to reach the requested
   state.
   - camelCase, enums as string names. Fields you don't need to change can be left as-is or omitted
     (unknown ones are ignored).
   - Saving it locally as `mirage-samples/<key>.json` makes it easy to reuse later.
5. **Inject it**:
   ```bash
   curl -X PUT http://localhost:8080/mirage/mock/<key__> -d @<local.json>
   # → {"stored":"<fullMethodName>"}
   ```
6. **Apply & verify**: ask the user to refresh / re-enter the screen. Confirm via the log:
   ```bash
   adb logcat -d | grep Mirage
   # "serving mock: <fullMethodName>" means the mock was served
   ```
   Take a screenshot to confirm the on-screen state if useful.
7. **When done / to revert**: `curl -X DELETE localhost:8080/mirage/mock/<key__>` → next call hits the
   real server. (A restart also clears it, since mocks are in memory.)

---

## Example

**Input (user):** "Show the payment screen as if no card is registered."

**Steps:**
1. `adb forward tcp:8080 tcp:8080`.
2. `curl -s localhost:8080/mirage/corpus` → find `ridergwv1.RiderGw/ListCards` → target is `ListCards`.
3. `curl -s localhost:8080/mirage/corpus/ridergwv1.RiderGw__ListCards | head -1`
   → a real response with one card, used as the base to learn the shape (`{"cards":[{...}]}`).
4. To show "no cards", the mock is just the empty list: `{"cards":[]}`.
5. `curl -X PUT localhost:8080/mirage/mock/ridergwv1.RiderGw__ListCards -d '{"cards":[]}'`
6. Refresh payment → `adb logcat -d | grep Mirage` shows `serving mock: ridergwv1.RiderGw/ListCards`.
7. `curl -X DELETE localhost:8080/mirage/mock/ridergwv1.RiderGw__ListCards` to restore real cards.

**Key lesson:** don't guess which RPC produces a given screen state — start from the **endpoint and
sample actually captured in the corpus**. (A list on a screen might be drawn by some RPC other than
the obvious one — the corpus tells you the truth.)

---

## Cautions

- **Debug only.** The Mirage interceptor is attached only in debug builds, and the control server
  ships in the `mirage-debug` artifact (added via `debugImplementation`) — release is unaffected and
  never even contains it.
- **Loopback only.** The server binds to `127.0.0.1`, so it's reachable only through the USB
  port-forward — not over Wi-Fi. Always `adb forward` first.
- **The corpus contains real PII (names / phones / emails).** Treat it as internal debug data only;
  don't send raw corpus contents to an external LLM/service — mask first if it must be shared.
- **Only unary is mocked.** A mock for a streaming RPC is ignored and passes through. Real-time pushes
  like live vehicle-position updates aren't mockable (though if "polling" is just repeated unary, the
  same mock is served each time).
- **Mocks are in-memory.** They vanish on app restart — re-`PUT` if the app was relaunched.
- **Never edit proto files.** Read them for shape if needed, but don't modify them (often externally
  managed). Using corpus samples as the first-choice source means you rarely need to open a proto.
