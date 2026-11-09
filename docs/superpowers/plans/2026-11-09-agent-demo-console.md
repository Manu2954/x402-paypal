# Agent Demo Console Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a single-page "Agent Demo Console" served by the app that runs the full x402 + PayPal card-rail flow with buttons and in-browser state, so a hackathon video shows an autonomous agent paying per request with a real PayPal sandbox charge — no copy-paste, no terminal.

**Architecture:** One new static HTML file (`static/console.html`) with inline CSS + vanilla JS, served same-origin by the existing app. It orchestrates existing endpoints only. The JS acts as the agent: bootstraps an agent, drives card enrollment, performs the 402 → HMAC-sign → retry → receipt flow, and replays. One minimal backend change: add the console path to the auth filter allowlist so the page can load without a Bearer token. No payment/x402/PayPal logic is touched.

**Tech Stack:** Vanilla HTML/CSS/JS (no build step, matches existing `enroll.html`); Web Crypto `SubtleCrypto` for HMAC-SHA256; Spring Boot static resource serving; Playwright (via the MCP browser tools available in this session) for verification.

**Spec:** `docs/superpowers/specs/2026-11-09-agent-demo-console-design.md`

## Global Constraints

- No change to payment, x402, enrollment, or PayPal Java logic. The only permitted backend edit is adding the console path to `AgentAuthFilter.shouldNotFilter`. (Spec anti-goal: do not destabilize proven backend.)
- Card rail only; wallet rail out of scope. Hero buy is `$1` `GET /v1/premium/quote`.
- Zero copy-paste / zero terminal in the demo flow. All state in browser memory.
- Served same-origin (`http://localhost:8080`) → no CORS config; custom response headers readable from same-origin `fetch`.
- Canonical HMAC string, newline-joined, exact: `challengeId, "GET", resource, amount, asset, nonce, timestamp(epoch seconds)`. HMAC-SHA256, **base64url without padding**. Sign over the **verbatim decoded 402 fields** — never reconstruct `resource` from `window.location` or hardcode `amount`.
- Agent create body: `name:"Demo Agent"` (non-blank, ≤120), `budgetCents: 10000`.
- Replay = resend the **identical** cached `PAYMENT-SIGNATURE` header byte-for-byte; never sign a fresh payload for the replay.
- Receipt fields `orderId/captureId/gross/fee/net/status` are nested under `extra`; `transaction/network/payer` are top-level.
- Existing tests must still pass: `./gradlew test`.

## Review Focus

Failure modes the spec implies but that no happy-path step exercises; each gets a check in the owning task:
- **Auth filter blocks the page** — `GET /console.html` without a Bearer token must return `200` HTML, not `401`. (Task 1.)
- **Signature verification fails on a field mismatch** — wrong `resource`/`amount`/encoding → the server returns `402 INVALID_SIGNATURE` and the UI must show that honestly, not a fake success. (Task 4 + Task 5.)
- **PayPal declines / payer action required** — a non-`200`, non-replay settle outcome (`PAYER_ACTION_REQUIRED`, `INSTRUMENT_DECLINED`) must render as a visible failure state, never a silent success. (Task 5.)
- **Replay charges twice** — second send must carry `X-X402-Replay: true` and budget must NOT drop again. (Task 6.)
- **Enrollment not yet active at run time** — if the agent has no active enrollment, the 402's `extra.enrolled=false` path must tell the operator to enroll, not spin. (Task 3 + Task 5.)

---

### Task 1: Serve the console page past the auth filter

**Files:**
- Create: `src/main/resources/static/console.html` (stub for this task: `<!doctype html><title>Console</title><body>ok</body>`)
- Modify: `src/main/java/io/x402/paypal/agent/AgentAuthFilter.java:39-47` (`shouldNotFilter`)

**Interfaces:**
- Produces: a reachable `GET /console.html` returning `200 text/html` with no auth.

- [ ] **Step 1: Write the failing test** — a shell check (no JUnit harness for static serving; this is an HTTP smoke test). With the app running:

```bash
curl -sS -o /dev/null -w "%{http_code}" http://localhost:8080/console.html
```
Expected after implementation: `200`. Before: `401` (auth filter blocks it).

- [ ] **Step 2: Run it to confirm it currently fails** — start app (`./gradlew bootRun &`, wait for "Started"), run the curl, observe `401`.

- [ ] **Step 3: Add the allowlist entry.** In `AgentAuthFilter.shouldNotFilter`, add `|| path.equals("/console.html")` to the existing prefix check so the console loads without a token. Create the `console.html` stub.

- [ ] **Step 4: Rebuild + rerun** — restart app, run curl, observe `200`.

- [ ] **Step 5: Confirm no regression** — `./gradlew test` passes.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/static/console.html src/main/java/io/x402/paypal/agent/AgentAuthFilter.java
git commit -m "feat: serve demo console past auth filter"
```

---

### Task 2: Agent bootstrap + three-zone shell

**Files:**
- Modify: `src/main/resources/static/console.html`

**Interfaces:**
- Consumes: `POST /v1/agents` (no auth) body `{"name":"Demo Agent","budgetCents":10000}` → `{agentId, apiKey, hmacSecret, budgetCents}`.
- Produces: JS module-level state `agent = {agentId, apiKey, hmacSecret, budgetCents}`; a `log(zone, msg)` helper; three DOM zones with ids `#zone-agent`, `#zone-resource`, `#zone-money`; a budget display `#budget`.

- [ ] **Step 1: Build the shell + bootstrap.** Dark three-zone layout (Agent / Resource / Money), matching `enroll.html`'s inline-style approach. On load, `fetch POST /v1/agents`, store the response in `agent`, render `agentId` (truncated) and budget `$100.00` in the Agent zone. API key and hmac secret held in memory, never rendered.

- [ ] **Step 2: Verify in a browser** — load `http://localhost:8080/console.html` with the Playwright MCP browser tool; snapshot the page; confirm the Agent zone shows an `agt_...` id and `$100.00`, and the browser console has no errors.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/console.html
git commit -m "feat: console shell + agent bootstrap"
```

---

### Task 3: Card enrollment trigger

**Files:**
- Modify: `src/main/resources/static/console.html`

**Interfaces:**
- Consumes: `POST /v1/enrollments` (Bearer `agent.apiKey`) body `{"sourceType":"CARD"}` → `{enrollmentId, enrollmentUrl}`; the hosted page `GET /enroll/{token}`.
- Produces: an "Enroll card" button in the Agent zone; opens `enrollmentUrl` in a new tab (per spec: new tab avoids fighting CardFields' redirects); a "Check enrollment" affordance that calls `GET /v1/enrollments` and shows active CARD status.

- [ ] **Step 1: Wire enrollment.** Button → authed `POST /v1/enrollments {sourceType:"CARD"}` → open returned `enrollmentUrl` in a new tab. Add a "Refresh status" button → `GET /v1/enrollments` (Bearer) → render whether an ACTIVE CARD enrollment exists.

- [ ] **Step 2: Verify the trigger (not the full PayPal UI).** With Playwright, click "Enroll card"; confirm the authed POST fires and a URL is produced (assert a new tab/target opens to `/enroll/`). Full CardFields entry is the presenter's manual step on camera and is not automatable here — note this in the step log text.

- [ ] **Step 3: Review-focus check (no enrollment).** Assert that before any enrollment, "Refresh status" renders a clear "no active card — enroll first" message, not a spinner or error.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/static/console.html
git commit -m "feat: console card enrollment trigger"
```

---

### Task 4: x402 sign-and-pay core (the agent logic)

**Files:**
- Modify: `src/main/resources/static/console.html`

**Interfaces:**
- Consumes: `GET /v1/premium/quote` (Bearer) → `402` + `PAYMENT-REQUIRED` header; same path + `PAYMENT-SIGNATURE` header → `200` + content + `PAYMENT-RESPONSE`.
- Produces: `async function buyQuote()`; stored `lastSignatureHeader` (string) for Task 6; a rendered receipt object. Helper `hmacBase64Url(secret, canonical)` using `crypto.subtle` (import key HMAC SHA-256, sign, base64url-encode **without padding**).

- [ ] **Step 1: Implement the signing helper + first leg.** `buyQuote()`:
  1. `fetch GET /v1/premium/quote` with Bearer → expect `402`; read `PAYMENT-REQUIRED`, base64-decode, JSON-parse.
  2. If `accepts[0].extra.enrolled === false` → render "enroll first" and stop (review-focus).
  3. Build canonical string from **verbatim** fields: `challengeId`, `"GET"`, `resource.url`, `accepts[0].amount`, `accepts[0].asset`, `nonce`, `timestamp` (= `Math.floor(Date.now()/1000)`), joined by `"\n"`.
  4. `signature = hmacBase64Url(agent.hmacSecret, canonical)`.
  5. Assemble the payload object exactly as `DemoAgent` does (`x402Version:2`, `accepted: accepts[0]`, `payload:{agentId, challengeId, nonce, timestamp, signature}`), JSON-stringify, base64-encode → `lastSignatureHeader`.

- [ ] **Step 2: Implement the paid leg.** `fetch GET /v1/premium/quote` with Bearer + `PAYMENT-SIGNATURE: lastSignatureHeader`. Return `{status, body, receiptHeader, replayHeader}`.

- [ ] **Step 3: Verify the signature is accepted end-to-end.** With the app running and an enrollment active (use the dev-profile activate endpoint from the README, or a real prior enroll), drive `buyQuote()` via Playwright and assert the paid leg returns `200` (not `402 INVALID_SIGNATURE`). This proves the browser HMAC matches `DemoAgent` byte-for-byte. If it returns `402`, the canonical string or encoding is wrong — fix before proceeding.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/static/console.html
git commit -m "feat: console x402 sign-and-pay core"
```

---

### Task 5: Render content, receipt, and money panel

**Files:**
- Modify: `src/main/resources/static/console.html`

**Interfaces:**
- Consumes: Task 4's `buyQuote()` return; `PAYMENT-RESPONSE` base64 receipt (`transaction`, `network`, `payer`, `extra{orderId,captureId,gross,fee,net,status}`).
- Produces: rendered Resource zone (AlphaSignal tool card + returned content) and Money zone (budget before → −$1 → after, PayPal `orderId`/`captureId`, gross/fee/net).

- [ ] **Step 1: Render success + failure honestly.** On `200`: show content in Resource zone; decode `PAYMENT-RESPONSE`, show `extra.orderId`, `extra.captureId`, `extra.gross/fee/net`, status in Money zone; decrement displayed budget by the quote price. On `402`/error (`INVALID_SIGNATURE`, `PAYER_ACTION_REQUIRED`, `INSTRUMENT_DECLINED`, `PAYMENT_FAILED`): render the error code and message as a visible failure state — never a fake success (review-focus: declines + signature-mismatch).

- [ ] **Step 2: Verify the full hero beat.** With an active enrollment, drive the "Run market report" button via Playwright; snapshot; assert the Resource zone shows content AND the Money zone shows a non-empty `captureId` and the budget reads `$99.00`. Look at the screenshot — a blank frame is a failure.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/console.html
git commit -m "feat: console content + money panel"
```

---

### Task 6: Replay beat

**Files:**
- Modify: `src/main/resources/static/console.html`

**Interfaces:**
- Consumes: Task 4's `lastSignatureHeader`.
- Produces: a "Replay (same signature)" button that resends `lastSignatureHeader` verbatim.

- [ ] **Step 1: Implement replay.** Button → `fetch GET /v1/premium/quote` with Bearer + `PAYMENT-SIGNATURE: lastSignatureHeader` (the exact cached header). Render `X-X402-Replay` header value and the returned content.

- [ ] **Step 2: Verify no double charge (review-focus).** After one successful buy (budget `$99.00`), drive replay via Playwright; assert response has `X-X402-Replay: true`, content matches, and the Money-zone budget still reads `$99.00` (no second decrement).

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/console.html
git commit -m "feat: console replay beat"
```

---

### Task 7: Reset / re-run affordance + final whole-flow verification

**Files:**
- Modify: `src/main/resources/static/console.html`

**Interfaces:**
- Produces: a "New take" button that re-bootstraps a fresh agent and clears all zones.

- [ ] **Step 1: Add reset.** "New take" → re-runs Task 2 bootstrap, clears Resource/Money zones and `lastSignatureHeader`, so the presenter can do multiple recorded takes from a cold start.

- [ ] **Step 2: Full cold-start verification.** With Playwright, from a clean load: bootstrap shows fresh agent + `$100.00` → (enrollment active via the agreed method) → run quote → `200` + content + real `captureId` + `$99.00` → replay → `X-X402-Replay: true` + still `$99.00` → "New take" → fresh `agt_...` + `$100.00`, zones cleared. Read every screenshot.

- [ ] **Step 3: Confirm backend untouched** — `./gradlew test` passes.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/static/console.html
git commit -m "feat: console reset + final verification"
```

---

## Notes for the executor

- **Enrollment in verification:** Tasks 4-7 need an *active card enrollment* to exercise the paid path, and real CardFields entry is a manual browser step. For automated verification, use the README's `dev`-profile activate endpoint (`POST /v1/dev/enrollments/{id}/activate` with a vault token) to put an agent into the enrolled state without the browser, OR perform one real sandbox-card enrollment manually and reuse that agent for the take. The console itself must still support the real on-camera enroll (Task 3) — the dev endpoint is a test shortcut only, never wired into the console UI.
- **Playwright:** use the browser MCP tools present in this session (`browser_navigate`, `browser_snapshot`, `browser_click`, `browser_evaluate`, `browser_take_screenshot`). "Read the screen" means snapshot/screenshot and actually inspect it, not just assert a 200.
