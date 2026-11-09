# Design: Agent Demo Console

Date: 2026-11-09
Status: Approved pending builder-check

## Intent and the why

The project is a working x402 + PayPal implementation: an autonomous agent hits a
`402 Payment Required` paywall on a premium API, pays per request with a PayPal vault
token, and receives the content. It is a real contribution — payment settles on live
PayPal sandbox rails, not a custom balance/credit ledger.

Today the only way to exercise it is curl and a CLI, copying agent keys between
commands. That is fatal for a hackathon **video** submission to the PayPal AI Hackathon:

- It hides the novelty. The thesis is *the agent pays by itself*. A human pasting keys
  in a terminal makes it look like the human is doing the buying.
- It is fragile on camera. Copy-paste and multi-terminal flows break on stage.

This design adds **a face**: a single-page "Agent Demo Console" served by the app that
runs the whole flow with buttons and in-browser state — no copy-paste, no terminal.
The judge watches an agent spend its own money to finish a job, and sees the real
PayPal charge land.

Reference point (north star, NOT a thing to clone): monid.ai — agents discover and pay
per call for tools (ElevenLabs, Exa, etc.) from a unified balance. Our differentiator
to put on camera: **we settle on real PayPal rails per request with a visible sandbox
transaction**, where Monid abstracts the money away. The money is the hero.

## Audience and format (decided with user)

- **Audience A**: hackathon judges watching the presenter's recorded video. NOT
  self-serve judges clicking a deployed link. The console must look good and run
  reliably when driven by the presenter; it does not need to be a hardened product.
- **Format**: recorded video submission.
- **Hero rail**: CARD (PayPal hosted CardFields + sandbox test card). The wallet rail
  bounces to a PayPal sandbox login and looks clunky; it is out.

## The story on camera (one screen, scripted buttons)

1. **Agent exists.** On page load the console auto-creates an agent via
   `POST /v1/agents` (unauthenticated endpoint) and holds `agentId`, `apiKey`,
   `hmacSecret` in browser memory. No human sees or pastes a key. Budget is shown.
   The body IS required and validated: `name` non-blank (max 120), `budgetCents`
   `@Min(100)`. The page hardcodes `name:"Demo Agent"` and `budgetCents: 10000`
   ($100 — large enough for ~100 takes at $1 so the agent never runs dry mid-record).
2. **Enroll card.** The console first calls `POST /v1/enrollments {sourceType:"CARD"}`
   with the agent's API key (Bearer, held in browser memory, same-origin) to obtain the
   returned `enrollmentUrl` / link token. It then opens that hosted CardFields page
   (`GET /enroll/{token}`); presenter enters a sandbox test card. CardFields creates the
   vault setup token and the server exchanges it. No PAN reaches our server.
   **Enrollment model (user decision B):** page load ALWAYS creates a fresh agent and
   enrollment happens on camera for that same agent. "Pre-done" is an editing decision
   (trim the enroll clip), NOT a code path — there is no loading of a pre-existing
   agent's credentials, because enrollments are per-agent and a fresh agent has none.
   This keeps every run a real cold start and avoids any hidden-credential loader.
3. **Agent runs its job → pays.** One button, "Run market report" (hero buy is the
   **$1 `/v1/premium/quote`**, framed as a premium tool call). The page, acting as the
   agent, performs the real x402 flow and renders each step live:
   - calls `GET /v1/premium/quote` with the API key → **402**, reads `PAYMENT-REQUIRED`
   - decodes the challenge (`challengeId`, `nonce` from `accepts[].extra`)
   - HMAC-signs the canonical string and retries with `PAYMENT-SIGNATURE`
   - renders the returned premium content
4. **Money panel (the hero).** From the `PAYMENT-RESPONSE` receipt, show real PayPal
   `orderId`, `captureId`, gross/fee/net, status; and budget before → −$1 → after.
   Scripted line: "that charge is real — it's in the PayPal sandbox dashboard."
5. **Replay beat.** Re-send **the exact same** `PAYMENT-SIGNATURE` header from step 3
   (same `challengeId`/`timestamp`/`signature`, byte-for-byte — the console caches the
   first successful signature header and resends it verbatim) → content returns with
   `X-X402-Replay: true` and **no second charge**. Proves metering integrity. Do NOT
   sign a fresh payload for the replay.

## Framing of the canned resource

The premium endpoints return canned content (decided: canned, not a live external API —
bulletproof on camera, keeps attention on payment). Dress `/v1/premium/quote` in the UI
as a named metered tool (e.g. "AlphaSignal — premium market quote, $1/call") so it reads
like a Monid-style pay-per-call API rather than a mystery endpoint. The content itself is
whatever the server already returns; no server change to the content.

## Goals

- Zero copy-paste, zero terminal, single screen, driven by buttons.
- Runs the **real** x402 + PayPal flow against the existing backend — not a mock.
- Makes the autonomous payment and the **real PayPal transaction** visibly the point.
- Reliable when driven by the presenter for a recorded take.
- Works whether enrollment happens on camera or was done before recording.

## Non-goals

- Not a marketplace; no multi-tool discovery or 2,800-tool catalog.
- Not a real external API behind the paywall (canned content, by decision).
- Not the wallet rail.
- Not a hardened, unattended, self-serve public product.
- Not a reusable SDK/widget.

## Anti-goals (failure even if it technically works)

- A UI that looks like a human e-commerce checkout — buries the agent-pays thesis.
- UI theater (fake catalog/balance) that pushes the real payment off screen.
- Any change to backend payment/x402/PayPal logic. If the build "needs" a backend
  change to work, stop and bring it back — the backend is proven and must not be
  destabilized before a submission.

## Constraints (verified against the code on 2026-11-09)

- `POST /v1/agents` is unauthenticated → page can bootstrap an agent. (README + code.)
- 402 response sets header `PAYMENT-REQUIRED` (base64 JSON); `accepts[].extra` carries
  `challengeId`, `nonce`, `enrolled`, `enrollmentRequired`, `enrollmentUrl`,
  `sourceType`. (Decoded live; matches `PaymentRequiredFactory` output.)
- Paid retry: header `PAYMENT-SIGNATURE` (base64 of a JSON payload containing
  `x402Version`, `accepted`, and `payload{agentId,challengeId,nonce,timestamp,signature}`).
  Canonical signed string, newline-joined, is:
  `challengeId, "GET", resource, amount, asset, nonce, timestamp(epoch seconds)`.
  HMAC-SHA256, base64url **without padding**. (Exact match to `DemoAgent.java`.)
  **The browser MUST sign over the verbatim 402 fields**: `resource` = the decoded
  `resource.url` string (full scheme/host/port as the server returned it, e.g.
  `http://localhost:8080/v1/premium/quote`), `amount` = `accepts[0].amount` as the cents
  string (`"100"`), `asset` = `accepts[0].asset`, plus `challengeId`/`nonce` from
  `accepts[0].extra`. Do NOT reconstruct `resource` from `window.location` or hardcode
  `amount`; echo the decoded 402 fields byte-for-byte or every signature fails.
- **Enrollment requires a prior authenticated create call.** `GET /enroll/{token}` only
  works after `POST /v1/enrollments {sourceType:"CARD"}` (Bearer apiKey) returns the
  `enrollmentUrl`/link token. The console makes that authed call itself using the
  in-memory apiKey (same-origin), then opens the returned URL. (Confirmed in
  `EnrollmentController` + `EnrollmentService`.)
- **Agent create body is mandatory and validated**: `name` `@NotBlank @Size(max=120)`,
  `budgetCents` `@Min(100)`. (Confirmed in `AgentController.CreateAgentRequest`.)
- Success: `200` + header `PAYMENT-RESPONSE` (base64 receipt with `transaction`,
  `network`, `payer`, `extra{orderId,captureId,gross,fee,net,status}`), body = content.
- Replay: same signature → `200` + `X-X402-Replay: true`, no new charge. (Code path in
  `PremiumController.paid` → `paymentService.replay`.)
- Card enroll page already exists at `GET /enroll/{token}`, served from
  `static/enroll.html`, with `/enroll/{token}/config` and `/enroll/{token}/exchange`.
- Prices: quote `$1.00` = `"100"` cents, report `$2.00`. Configurable.
- Receipt exposes real PayPal `orderId`/`captureId`/`gross`/`fee`/`net`. (Confirmed in
  `X402ReceiptFactory`.)

## The how — decided calls (mine unless marked user)

- **(user) Card rail is the hero; wallet out. $1 quote is the hero buy; single purchase.
  Enrollment stays in the video, cuttable later.**
- New artifact is a **single new static HTML file** served by the app (alongside the
  existing `enroll.html`), reusing every existing endpoint. **No new backend logic, no
  new Java, no schema change.** The only possible backend touch is one trivial static
  resource mapping if the file does not serve automatically from the static path — and
  if that proves to need a controller, it is one GET returning the file, mirroring
  `EnrollmentPageController.page`. (My call; flagged because it is the one place a
  backend file might be touched.)
- The page does the HMAC signing **in the browser** using the `hmacSecret` returned at
  agent creation, replicating `DemoAgent`'s canonical string and base64url-no-pad
  encoding. (My call. Rationale: keeps it a pure face over real endpoints; the secret
  is already handed to the client by the create call, so this exposes nothing new. This
  is a demo console, not a production agent — the agent legitimately holds its secret.)
- CORS / same-origin: the page is served from the same origin as the API (`:8080`), so
  browser `fetch` calls need no CORS config. (My call, relies on same-origin.)
- Reading the `PAYMENT-REQUIRED` / `PAYMENT-RESPONSE` **custom response headers** from
  `fetch` requires they be exposed. Same-origin reads are allowed by default, so no
  `Access-Control-Expose-Headers` is needed while served same-origin. (My call; a
  builder must confirm the headers are readable from same-origin `fetch` — if a proxy
  or filter strips them, surface it rather than work around it.)
- Visual style: dark, single screen, three zones — left "Agent" (identity + the run
  button + live step log), center "Resource" (the AlphaSignal tool card + returned
  content), right "Money" (budget meter, receipt, PayPal IDs). (My call; a mockup will
  be shown before build if the user wants one.)

## What is left to the builder

- Exact DOM/layout and styling within the three-zone intent.
- The step-by-step live log wording as the agent runs.
- Error display if PayPal declines or payer action is required (show the state
  honestly; do not fake success).
- Whether enrollment is embedded (iframe) or opened in a tab, chosen for the cleanest
  on-camera result given the hosted-page redirects.
- A reset/re-run affordance so the presenter can do multiple takes.

## Verification bar

- App serves the console page; page loads with no console errors.
- From a clean state the console drives: create agent → (enroll card OR detect existing
  enrollment) → 402 → signed retry → 200 + content → receipt with real PayPal IDs →
  budget decrement → replay with `X-X402-Replay: true` and no second charge.
- Drive it with a browser automation tool and read the rendered screen (not just HTTP)
  before claiming it works. A blank frame is a failure to launch.
- No backend behavior changed: existing tests still pass (`./gradlew test`).
