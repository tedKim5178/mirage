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

Mirage **overrides specific gRPC RPC responses at runtime in debug builds**. Drop a single JSON file
in one directory on the device and, from the next call on, that JSON is returned instead of the real
server response — no rebuild, no app restart. This skill takes you from a **natural-language request
to an applied mock file**.

Your job is usually: the user says "show this screen as if it were in state X", and you (1) find
which RPC to mock, (2) get the real shape of that response, (3) write JSON that bends it to the
requested state, (4) push it to the device, and (5) confirm it took effect.

---

## Mirage's fixed rules (the same in every app)

These are the engine's contract, so they hold **in any app** — you don't need to re-read the code to
confirm them.

- **Mock directory**: `mirage/` under the app's external files dir.
  On-device path = `/sdcard/Android/data/<applicationId>/files/mirage/`
- **Filename rule**: take the gRPC `fullMethodName`, replace `/` with `__`, append `.json`.
  e.g. `account.v1.AccountService/ListCards` → `account.v1.AccountService__ListCards.json`
- **Corpus (real-response samples) directory**: `.../mirage/corpus/<same key>.jsonl`
  — real responses captured automatically as each call passes through, one compact JSON per line, up
  to 5 recent distinct samples per endpoint. **This is your primary source for shape and real values.**
- **Unary only**: only unary RPCs (server sends a single message) are mocked. Server-streaming and
  bidi-streaming **always pass through** and are never mocked. (Streaming is unsupported; polling is
  repeated unary and is handled automatically.)
- **Per-call read (no restart)**: Mirage reads the file fresh on every call. Pushing or editing a file
  applies **from the next RPC**. The user just refreshes the screen (pull-to-refresh / re-enter it).
- **Turn a mock off = delete the file**. Removing the `.json` returns to the real server immediately.
- **JSON format = protobuf JsonFormat**: field names are camelCase, and **unknown fields are ignored**
  (`ignoringUnknownFields`). So you don't have to fill the whole response — a partial mock containing
  **only the fields you want to change** is valid. Enums are written as their string names.

---

## What you discover per project

The rules above are fixed, but these three things vary per app, so find them each time. Prefer finding
them **on the device** (it doesn't depend on repo layout, which keeps this portable).

### 1. applicationId (to build the device path)
```bash
adb shell pm list packages | grep -i <app-name-hint>
```
Debug builds often carry a `.debug` suffix. If unsure, check the app currently in front:
```bash
adb shell dumpsys activity activities | grep -i mResumedActivity
```
This fixes `MIRAGE_DIR=/sdcard/Android/data/<applicationId>/files/mirage`.

### 2. Which RPC to mock (the target endpoint key)
**Most portable approach — look at the corpus directory.** The filenames *are* the endpoint keys:
```bash
adb shell ls $MIRAGE_DIR/corpus
# e.g. account.v1.AccountService__ListCards.jsonl, catalog.v1.CatalogService__ListItems.jsonl ...
```
If the user just visited that screen (with no mock active), the RPCs it called are captured here.
Match the natural-language request to an endpoint name. If it's not in the corpus, either run that
screen once against the real server so it gets captured (preferred), or fall back to finding the RPC
name in the repo's proto/code.

### 3. The response shape (field names / structure)
**First choice = a corpus sample.** It's a real response, so there's no hallucination risk — copy it
and change only what you need:
```bash
adb shell cat $MIRAGE_DIR/corpus/<key>.jsonl | tail -1
```
Each line is one compact JSON. If there are several, pick the sample closest to the requested state as
your base. **Fallback (no sample at all)**: build a skeleton from the RPC's proto message shape — but
even then, confirm enum values / field conventions against a similar endpoint's corpus if you can.
(Proto files are often vendored and read-only — **never edit them**.)

---

## Workflow (natural language → applied mock)

1. **Resolve applicationId** → fix `MIRAGE_DIR` (step 1 above).
2. **Find the target RPC** → `adb shell ls $MIRAGE_DIR/corpus` to get the endpoint key (step 2).
   - If the corpus is empty and the user hasn't hit that screen yet: ask them to "enter that screen
     once with mocks off so the real response gets captured, then I'll base the mock on it." Corpus
     grounding gives the most accurate result.
3. **Grab a base sample** → `adb shell cat $MIRAGE_DIR/corpus/<key>.jsonl | tail -1` (step 3).
4. **Write the JSON** → copy the base sample and edit only the fields needed to reach the requested
   state.
   - camelCase, enums as string names. Fields you don't need to change can be left as-is or omitted
     (unknown ones are ignored).
   - Saving it locally as `mirage-samples/<key>.json` makes it easy to reuse later.
5. **Push to the device**:
   ```bash
   adb push <local.json> $MIRAGE_DIR/<key>.json
   ```
   (Same `<key>` as the corpus, but the extension is `.json` and it lives directly under `mirage/`,
   not under `corpus/`.)
6. **Apply & verify**: ask the user to refresh / re-enter the screen. Confirm via the log:
   ```bash
   adb logcat -d | grep Mirage
   # "serving mock: <fullMethodName>" means the mock was served
   ```
   Take a screenshot to confirm the on-screen state if useful.
7. **When done / to actually revert**: `adb shell rm $MIRAGE_DIR/<key>.json` → next call hits the real
   server.

---

## Example

**Input (user):** "Show the home screen as if a card is registered."

**Steps:**
1. `adb shell pm list packages | grep <your-app-name>` → resolve applicationId.
2. `adb shell ls $MIRAGE_DIR/corpus` → find `...AccountService__ListCards.jsonl` → target is `ListCards`.
3. `adb shell cat $MIRAGE_DIR/corpus/account.v1.AccountService__ListCards.jsonl | tail -1`
   → a real response with zero cards, used as the base.
4. From the base, fill the `cards` array with one registered card (leave other fields at their real
   values).
5. `adb push ListCards.json $MIRAGE_DIR/account.v1.AccountService__ListCards.json`
6. Refresh home → `adb logcat -d | grep Mirage` shows `serving mock: account.v1.AccountService/ListCards`.

**Key lesson:** don't guess which RPC produces a given screen state — start from the **endpoint and
sample actually captured in the corpus**. (A list on the home screen might be drawn by some RPC other
than the obvious one — the corpus tells you the truth.)

---

## Cautions

- **Debug only.** The Mirage interceptor is attached only in debug builds; release is unaffected.
- **The corpus contains real PII (names / phones / emails).** Treat it as internal debug data only;
  don't send raw corpus contents to an external LLM/service — mask first if it must be shared.
- **Only unary is mocked.** A mock file for a streaming RPC is ignored and passes through. Real-time
  pushes like live vehicle-position updates aren't mockable (though if "polling" is just repeated
  unary, the per-call read serves the same mock each time).
- **Never edit proto files.** Read them for shape if needed, but don't modify them (often externally
  managed). Using corpus samples as the first-choice source means you rarely need to open a proto.
- **Repo grep is the fallback.** For portability, get the endpoint/shape from the device (corpus) when
  you can; grepping proto in the repo is the last resort when the corpus is empty.
