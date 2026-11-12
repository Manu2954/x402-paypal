# PayPal Agent Pay

A pay-per-request paywall for AI agents, settled with a saved PayPal Vault card.

---

## Inspiration

AI agents can already call almost any API — but they can't pay for one.

Every payment rail we have assumes a human at a checkout screen: a redirect, a login, a card form, a confirmation. An autonomous agent has none of those. It has an HTTP client and a budget it was given. The moment it hits a paid endpoint, the whole model breaks down and a human has to step in.

We kept coming back to one question: **what does a paywall look like when the customer is a program?**

x402 answers the protocol half — it turns `402 Payment Required` into something a machine can actually settle. PayPal Vault answers the trust half — a card is approved once and turned into a reusable token, so the agent never touches a card number. Put them together and an agent can buy exactly what it needs, one request at a time, without ever holding a wallet.

That's the demo we wanted to build, and the reason it had to be real: a fake payment proves nothing about agent commerce.

## What it does

PayPal Agent Pay puts a real x402 paywall in front of premium data and settles each request with PayPal.

The agent calls a paid endpoint with its API key. Instead of data, it gets a `402` with a signed, single-use challenge: resource, amount, asset, nonce, expiry. The agent HMAC-signs those fields, retries the same request with a `PAYMENT-SIGNATURE` header, and the server verifies the signature, reserves budget, creates and captures a PayPal order against the saved vault token, and returns both the content and a receipt.

The judge-facing demo is a single link with no install and no sign-in. Press **Run agent** and you watch the real exchange: `402 → signed retry → PayPal capture → 200`. The budget drops from `$10.00` to `$9.00`, and the receipt carries the actual PayPal capture and order IDs returned by the sandbox.

Two details matter more than the happy path:

- **Replay protection.** Pressing *Send identical signature* replays the exact same header. The server returns the cached result with `X-X402-Replay: true`, no second charge, and an unchanged budget. Retries are safe by construction.
- **The funding source is already set up.** Each visit provisions a fresh agent with an active, tokenized sandbox card (`VISA •••• 1111`), so a judge can run the whole thing in about ten seconds. There is also an optional PayPal CardFields flow that enrols a different card and swaps it in — the current card stays active while the new one is pending, and the next charge uses the new vault token.

The agent holds a budget and an API key. It never holds a card number, and the server never stores one.

## How we built it

The whole thing is a single Spring Boot service (Java 21, Spring Boot 3.5) that serves both the API and the judge page, written from scratch during the hackathon — 57 Java source files, Flyway migrations, an H2 database, and a hand-written front end in plain HTML/CSS/JS with self-hosted fonts. No front-end framework, no build step, one container.

The core is split by responsibility:

- **x402 layer** — challenge issuance and verification. A challenge is bound to resource, amount, asset, nonce and expiry, then signed with HMAC-SHA256 over a canonical newline-joined payload. Challenges are single-use and stored, which is what makes replay detection possible.
- **PayPal layer** — a small client over OAuth, Orders v2 (create + capture) and Vault (setup token → payment token). Tokens are encrypted at rest with AES-GCM before they touch the database.
- **Enrollment layer** — paywalled endpoints never see card data. Card entry happens on a hosted CardFields page at `/enroll/{token}`, which talks to PayPal directly and returns only a vault token to our server.
- **Payment layer** — budget reservation, order creation, capture, settlement, and a receipt assembled from what PayPal actually returned.
- **Demo layer** — `POST /v1/demo/session` mints a fresh agent and an active enrollment from a pre-provisioned sandbox vault token, so the public link needs no setup. Sessions are rate-limited per client address.

The judge page is deliberately built as a *transaction theater*, not a dashboard: request, challenge, signature, capture and unlock are shown as they happen, alongside the raw bytes the browser sent and received.

For deployment we added a Dockerfile and a Render blueprint. We validated the image under a hard 512 MB memory cap, moved the database to in-memory because container filesystems are ephemeral, and disabled the H2 console and health details so nothing internal is exposed on a public link.

## Challenges we ran into

**Agents don't do redirects.** Every normal PayPal integration ends in a browser. We had to make payment fit inside a single HTTP request, which meant designing a challenge that is self-describing, tamper-evident, and cheap for an agent to sign — and then proving the retry is idempotent.

**Two-step payments are a state machine.** Card enrolment isn't one call: a setup token is created, the card is approved on PayPal's side, and a payment token only exists after that. Modelling pending versus active, polling without blocking the UI, and keeping the old card working during a swap took more design than we expected.

**Replay is the whole ballgame.** A payment protocol that can double-charge on a retry is worse than no protocol. Getting single-use challenges, cached responses and budget reservation to agree — so a retry returns content but no second capture — was the part we tested hardest.

**Public links broke our own URLs.** Enrollment links were built from `APP_PUBLIC_BASE_URL`, so behind a tunnel they pointed at `localhost` and judges got a dead page. We now derive the public base URL from the incoming request and forwarded headers, so the same code returns correct links locally, behind a tunnel, and on a host.

**A demo that's honest is harder than a demo that looks good.** Judges shouldn't need a PayPal login or a card, but the payment still has to be real. The compromise was a pre-provisioned sandbox vault token that mints a fresh agent per visit — real captures, zero setup.

## Accomplishments that we're proud of

- **A real payment in about ten seconds**, from one link, with no install and no sign-in — and the receipt shows the actual sandbox capture and order IDs.
- **Replay that provably can't double-charge.** Same signature, cached content, `X-X402-Replay: true`, budget unchanged.
- **Zero card numbers on the server.** Card entry happens entirely inside PayPal's hosted fields; we store an encrypted vault token and nothing else.
- **A funding source that swaps cleanly.** An optional CardFields enrolment activates a new card mid-session, keeps the old one working while pending, and the very next charge uses the new token — verified end to end.
- **A protocol demo with no mock plumbing.** The 402 challenge, the HMAC signature, the budget ledger, the capture and the replay path are all the production code paths, not a scripted animation.
- **A public link that survives real conditions**, including a laptop-hosted tunnel and a 512 MB container, without leaking the database console.

## What we learned

**Agents need budgets and receipts, not wallets.** The interesting primitive isn't "an agent can pay" — it's "an agent can be given a hard, observable limit and produce evidence for every charge." That's what makes autonomy safe to hand over.

**Payment UX for machines is protocol design.** Every affordance we normally give a human — retry, cancel, confirm — has to become a status code, a header, and a deterministic rule about what happens twice.

**Idempotency is the hard part, not charging.** Capturing money is a few API calls. Making the same request safe to send twice is where the real engineering lives.

**Sandbox APIs still have real state machines.** Setup tokens, payment tokens, pending approvals and expiry windows don't disappear because it's a sandbox.

**Honesty is a feature in a payments demo.** Saying plainly "the premium data is canned, the card is a sandbox card, the capture is real" earned more trust than any polished animation would have.

## What's next for PayPal Agent Pay

The current architecture binds **one vault token to one agent**. That's fine for a demo, but it doesn't survive contact with an enterprise, where a fleet of agents shares a handful of cards and every agent ends up minting its own token for the same funding source. Provisioning, rotating and reconciling that at scale is heavy lifting nobody wants to do by hand.

So the next step is **the swarm**, not another endpoint.

- **Many agents, one funding source.** Model entitlements so a card can back a population of agents through shared, revocable credentials instead of one token per agent.
- **Policy instead of provisioning.** Per-agent and per-task budgets, spend ceilings, allowed merchants and time windows — issued centrally and enforced server-side on every request.
- **Payment analysis in real time.** Live visibility into which agents are spending, on what, and why — anomaly detection on spend spikes, and automatic cut-offs when an agent drifts outside its mandate.
- **Reconciliation that matches how finance works.** Every charge already produces a receipt; the next layer aggregates them into statements a finance team can actually audit.
- **Production rails.** Postgres, a real host instead of a tunnel, live merchant onboarding, and a small client SDK so any agent framework can pay without reimplementing the protocol.

The long-term goal is simple to state: an agent should be able to buy what it needs as smoothly as a person tapping a card — and the organisation behind it should have more control, not less.

---

*Built during the hackathon. Premium data is canned and the card is a PayPal sandbox test card; the x402 flow, the vault tokens, the captures and the receipts are real.*
