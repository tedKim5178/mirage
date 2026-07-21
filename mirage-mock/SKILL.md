---
name: mirage-mock
description: >
  How to turn a natural-language request into a runtime gRPC mock and apply it to a connected device
  in any app that integrates the Mirage mock engine. Use this whenever someone wants to fake a server
  response to test a screen state — e.g. "show this screen as if a card is registered", "make the
  fake-call result a successful match", "mock ListCards", "give me mock data for X", "the server data
  isn't ready but I want to test this screen" — or to fake an ERROR state: "make this call fail",
  "카드번호 에러 띄워줘", "trigger the maintenance popup", "simulate a server error on X".
  App-agnostic: works in any Android app that has Mirage. Reach for this BEFORE grepping proto files.
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
- **Error mocks**: a mock file whose JSON is an *error envelope* makes the RPC **fail** instead of
  succeed. Same filename rule, same directory — only the content differs:

  ```json
  {
    "$mirageError": {
      "code": "INVALID_ARGUMENT",
      "message": "human-readable text",
      "details": [
        { "@type": "type.googleapis.com/<app's error proto full name>",
          "...": "fields of that proto, e.g. type / metadata" }
      ]
    }
  }
  ```
  - `code` (required): gRPC status code name (any case) or number. `code`-only → the app sees a plain
    `StatusRuntimeException` and takes its generic fallback path (timeout, unavailable, …).
  - `details` (optional): delivered on the standard `grpc-status-details-bin` trailer exactly as a
    real server sends them — the app's **typed** error handling (custom exception mapping) fires.
    Needs the detail proto registered once (see discovery step 4). `google.rpc.*` standard types are
    pre-registered.
  - `detailsBin` (optional, instead of `details`): base64 of a raw serialized `google.rpc.Status` —
    replays a captured real error with **no registration needed**.
  - `$mirageError` is reserved (`$` can't appear in proto field names); JSON without it is a normal
    success mock, unchanged.
- **Error corpus**: real **failed** responses are auto-captured to
  `.../mirage/corpus/<key>.errors.jsonl`, each line already in envelope form — **copy-paste ready as
  a mock file.** This is your primary source for the app's real error shape.

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

### 4. The app's error detail type (only for typed error mocks)

Apps using the rich error model unpack a **custom proto** from the error details and map an enum
inside it to typed exceptions/UI. Discover it in this order:

a. **Error corpus first**: `adb shell cat $MIRAGE_DIR/corpus/<key>.errors.jsonl | tail -1` — a
   captured real error shows the exact `@type`, enum value, and metadata keys. Copy and edit it.
b. **Repo grep fallback**: find the app's error converter —
   `grep -rn "StatusProto.fromThrowable\|unpack(" --include='*.kt' <repo>` — and read its
   `when(type)`/switch: that block is the **complete catalog** of enum value ↔ exception ↔ which
   metadata keys each one reads. Pick the enum matching the state the user asked for, and use the
   converter's imported proto as the `@type` (e.g. `commonv1.ErrorInfo` →
   `type.googleapis.com/commonv1.ErrorInfo`).
c. **Check registration**: typed `details` require a one-time registration line in the app —
   `Mirage.registerErrorDetailTypes(<ErrorProto>.getDefaultInstance())`, debug-guarded, next to
   where the interceptor is attached. Grep for `registerErrorDetailTypes`; if missing, add it and
   rebuild once. (`code`-only and `detailsBin` mocks need no registration.)

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

**If the request is an ERROR state** ("make it fail", "카드번호 에러", "점검중 팝업"):

1. Decide typed vs plain: does the app show a *specific* UI for this error (a dedicated dialog,
   field highlight)? → typed envelope (discovery step 4). Just a generic failure/timeout? →
   `code`-only envelope.
2. Write the envelope JSON and push it with the **same filename rule** as a success mock.
3. Verify: trigger the RPC → the error UI must appear; the log shows
   `serving mock error: <method> -> <code>`. If you see `INTERNAL` instead, your enum value or
   `@type` is wrong — re-check against the error corpus / converter.
4. Revert = delete the file, same as success mocks.

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

## Error example

**Input (user):** "카드 등록할 때 카드번호 오류 나는 상황 만들어줘."

**Steps:**
1. Corpus shows the target: `account.v1.AccountService__CreateCreditCard.jsonl` → `CreateCreditCard`.
2. No `.errors.jsonl` for it yet → grep finds the app's converter
   (`GrpcErrorConverter.kt`): `Type.PG_CARDNUM -> PgCardNumException()` ← the state we want; the
   converter unpacks `commonv1.ErrorInfo`.
3. Confirm `Mirage.registerErrorDetailTypes(ErrorInfo.getDefaultInstance())` exists (add if missing).
4. Write & push `account.v1.AccountService__CreateCreditCard.json`:
   ```json
   {"$mirageError":{"code":"INVALID_ARGUMENT","details":[
     {"@type":"type.googleapis.com/commonv1.ErrorInfo","type":"PG_CARDNUM"}]}}
   ```
5. Try registering a card → the card-number error UI appears; log shows
   `serving mock error: .../CreateCreditCard -> INVALID_ARGUMENT`. Delete the file when done.

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
- **Error-mock typos fail loudly, not silently.** A wrong enum value or unregistered `@type` closes
  the call with `INTERNAL` (visible in the log) instead of faking success — treat `INTERNAL` during
  verification as "fix the envelope", not as the intended error.
- **The error corpus contains real server messages (possible PII).** Same handling rules as the
  success corpus.
