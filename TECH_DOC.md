# PayPal Agent Pay — Technical Documentation

A pay-per-request HTTP paywall for autonomous agents, settled with a PayPal Vault token.

- **Runtime:** Java 21, Spring Boot 3.5.16, single deployable jar
- **Persistence:** H2 + Flyway migrations
- **Payments:** PayPal Orders v2, PayPal Vault v3, PayPal CardFields
- **Protocol:** x402 v2 challenge/response over HTTP headers
- **Front end:** static HTML/CSS/JS, no framework, no build step

---

## 1. Scope

This document describes the implemented system: the request lifecycle, the x402
signature scheme, the PayPal integrations, the data model, the security controls, the
configuration surface, and the deployment options.

It also states plainly what is **not** implemented, in [§14 Known limitations](#14-known-limitations).

---

## 2. System context

```
                         ┌──────────────────────────────────────────────┐
                         │              Spring Boot service             │
                         │                                              │
  Agent / judge browser  │  AgentAuthFilter ──► PremiumController       │
        │                │                          │                   │
        │  GET /v1/premium/quote                  │                   │
        ├───────────────►│                          ▼                   │
        │                │                 ChallengeService             │
        │◄── 402 + PAYMENT-REQUIRED ───────────────┤                   │
        │                │                          │                   │
        │  retry + PAYMENT-SIGNATURE               │                   │
        ├───────────────►│                          ▼                   │
        │                │                 PaymentService               │
        │                │                    │        │                │
        │                │            OrdersClient    EnrollmentService │
        │                │                    │                         │
        │◄── 200 + content + PAYMENT-RESPONSE ───────┘                   │
                         └───────────────┬──────────────────────────────┘
                                         │ HTTPS
                                         ▼
                              PayPal sandbox (OAuth, Vault, Orders)
```

The browser in the judge demo *is* the agent: it holds the API key and HMAC secret in
memory and performs the same `402 → sign → retry` exchange an autonomous client would.

---

## 3. Technology stack

| Layer | Choice | Notes |
| --- | --- | --- |
| Language | Java 21 | Records for API payloads, `HttpClient`-free server side |
| Framework | Spring Boot 3.5.16 | Web, Validation, Actuator, Data JPA |
| Database | H2 | File mode locally, in-memory on ephemeral hosts |
| Migrations | Flyway | `src/main/resources/db/migration` |
| HTTP client | Spring `RestClient` | `PayPalApi` wrapper |
| Front end | Vanilla JS | 543 + 1058 + 1475 lines (HTML/JS/CSS) |
| Fonts | Self-hosted WOFF2 | No external font CDN |
| Packaging | Docker | Multi-stage build, JRE 21 runtime |

---

## 4. Domain model

Five tables, created by `V1__init.sql` and extended by `V2__agent_and_enrollment_fields.sql`.

### `principal` — the paying agent

| Column | Type | Purpose |
| --- | --- | --- |
| `id` | VARCHAR(40) PK | `agt_…` |
| `name` | VARCHAR(120) | Display name |
| `api_key_hash` | VARCHAR(255) UNIQUE | SHA-256 hex of the API key; raw key never stored |
| `budget_cents` | BIGINT | Monthly ceiling |
| `spent_cents` | BIGINT | Settled spend in the current period |
| `period_start` | DATE | First day of the current month (UTC) |
| `status` | VARCHAR(20) | `ACTIVE` |
| `created_at` | TIMESTAMP | |

### `enrollment` — the funding source

| Column | Type | Purpose |
| --- | --- | --- |
| `id` | VARCHAR(40) PK | `enr_…` |
| `principal_id` | FK → principal | Owner |
| `paypal_customer_id` | VARCHAR(22) | Stable customer id used for setup tokens |
| `source_type` | VARCHAR(20) | `CARD` or `PAYPAL_WALLET` |
| `vault_token_enc` | VARCHAR(2048) | AES-GCM ciphertext of the PayPal payment token |
| `brand`, `last4` | VARCHAR | Display only; no PAN |
| `status` | VARCHAR(20) | `PENDING` → `ACTIVE` → `REVOKED` |
| `consent_max_cents` | BIGINT | Reserved for consent caps |
| `setup_token_id` | VARCHAR(64) | PayPal vault setup token while pending |
| `approve_url` | VARCHAR(2048) | Wallet approval link |
| `created_at`, `revoked_at` | TIMESTAMP | |

`activeEnrollment(principalId)` selects the **newest `ACTIVE`** row by `created_at`.
That single rule is what makes optional card replacement work: activating a new card
immediately outranks the previous one, with no extra state to keep in sync.

### `challenge` — the 402 offer

| Column | Type | Purpose |
| --- | --- | --- |
| `id` | VARCHAR(40) PK | `ch_…` |
| `principal_id` | FK → principal | Bound to one agent |
| `resource`, `method` | VARCHAR | Exact URL and verb being priced |
| `amount_cents`, `asset` | BIGINT / VARCHAR | Immutable price snapshot |
| `nonce` | VARCHAR(64) UNIQUE | Server-generated, single use |
| `expires_at` | TIMESTAMP | Now + `challenge-ttl` (default 120s) |
| `consumed_at` | TIMESTAMP | Set atomically on settlement |

### `payment_attempt` — the ledger entry

| Column | Type | Purpose |
| --- | --- | --- |
| `id` | VARCHAR(40) PK | `pay_…`; also the PayPal idempotency key prefix |
| `challenge_id` | VARCHAR(40) | Links attempt → challenge → replay lookup |
| `enrollment_id` | FK → enrollment | Which funding source paid |
| `amount_cents` | BIGINT | |
| `status` | VARCHAR(30) | `PROCESSING`, `REQUIRES_ACTION`, `SETTLED`, `FAILED` |
| `order_id`, `capture_id` | VARCHAR | PayPal identifiers |
| `response_json` | CLOB | Raw capture response |
| `content_json`, `content_type` | CLOB / VARCHAR | Cached content returned on replay |
| `error_code`, `payer_action_url` | VARCHAR | Failure / 3DS state |
| `created_at`, `updated_at` | TIMESTAMP | |

### `webhook_event` — deduplicated PayPal events

`event_id` is the primary key, so duplicate deliveries are ignored by construction.
`processed_at` marks successful handling.

---

## 5. The x402 flow

### 5.1 First request — no signature

`PremiumController.paid()` looks up the price, resolves the active enrollment, and finds
no `PAYMENT-SIGNATURE` header. It throws an `X402Exception` whose payload is built by
`PaymentRequiredFactory` and returned as the base64-encoded `PAYMENT-REQUIRED` header on
a `402`:

```json
{
  "x402Version": 2,
  "error": "Payment required",
  "resource": {
    "url": "https://host/v1/premium/quote",
    "description": "Premium market quote",
    "mimeType": "application/json"
  },
  "accepts": [{
    "scheme": "exact",
    "network": "paypal:sandbox",
    "maxAmountRequired": "100",
    "asset": "USD",
    "payTo": "merchant",
    "maxTimeoutSeconds": 120,
    "extra": {
      "paymentFlow": "upfront",
      "amountFormatted": "1.00",
      "challengeId": "ch_…",
      "nonce": "…",
      "sourceType": "CARD",
      "enrolled": true
    }
  }]
}
```

If the agent has **no** active enrollment, the response instead advertises two accepts
(`CARD`, `PAYPAL_WALLET`) with `enrollmentRequired: true` and an `enrollmentUrl`. That is
how an unenrolled client discovers how to get a funding source.

### 5.2 Signing

The canonical string is newline-joined, in this exact order:

```
challengeId
method
resource
amountCents
asset
nonce
timestamp
```

Signature = `HMAC-SHA256(canonical, agentSecret)`, base64url without padding.

The agent secret is **derived, not stored**:

```
agentSecret = base64url(HMAC-SHA256(masterKey, "agent:" + agentId))
```

It is returned once, at agent creation. The server can recompute it at any time, which
is why no per-agent secret column exists.

The client sends:

```json
{
  "x402Version": 2,
  "payload": {
    "agentId": "agt_…",
    "challengeId": "ch_…",
    "nonce": "…",
    "timestamp": 1762940000,
    "signature": "…"
  }
}
```

base64-encoded into `PAYMENT-SIGNATURE`.

### 5.3 Verification

`ChallengeService.verify()` enforces, in order:

1. Payload decodes and `x402Version == 2`
2. Challenge exists
3. Challenge belongs to this principal
4. `consumed_at == null`
5. Not expired
6. Resource, method, amount and asset match the current request
7. Nonce matches
8. Timestamp within ±120s (clock skew)
9. HMAC matches, compared in constant time

Any failure re-issues a fresh `PAYMENT-REQUIRED` header with the specific error code
(`INVALID_SIGNATURE`, `CHALLENGE_EXPIRED`, `PAYMENT_MISMATCH`, …), so a correct client can
recover without guessing.

### 5.4 Replay

Before verification, the controller checks for an existing settled attempt:

```java
Optional<PaymentResult> replay =
        paymentService.replay(principalId, payload.payload().challengeId());
```

If found, it returns the **cached content** with `X-X402-Replay: true` and no PayPal
call. If the challenge was consumed but no settled attempt exists, settlement returns
`REPLAY_DETECTED` instead of charging twice.

Two independent guards, because retries are normal for agents and double-charging is
unacceptable.

### 5.5 Response

```
HTTP/1.1 200 OK
PAYMENT-RESPONSE: <base64 json>
X-X402-Replay: true        (only on replay)
Content-Type: application/json
```

Decoded `PAYMENT-RESPONSE`:

```json
{
  "success": true,
  "transaction": "<captureId>",
  "network": "paypal:sandbox",
  "payer": "agt_…",
  "extra": {
    "orderId": "…",
    "captureId": "…",
    "gross": "1.00",
    "fee": "0.52",
    "net": "0.48",
    "status": "COMPLETED"
  }
}
```

---

## 6. PayPal integration

### 6.1 OAuth 2.0

`PayPalAuthClient` calls `POST /v1/oauth2/token` with client credentials and caches the
access token, refreshing before expiry. Base URL is selected from `paypal.env`
(`sandbox` default).

### 6.2 Vault — setup tokens

For `CARD`, `VaultClient.createCardSetupToken()` calls `POST /v3/vault/setup-tokens` with
`return_url` and `cancel_url` pointing at the hosted page. The card itself is collected
by **CardFields in the browser**, which posts directly to PayPal.

For `PAYPAL_WALLET`, a wallet setup token is created and its `approve` link is stored as
`approveUrl`.

### 6.3 Vault — payment tokens

`POST /v3/vault/payment-tokens` exchanges an approved setup token for a reusable payment
token. That token ID is encrypted and stored; it is the only thing the server can use to
charge.

`DELETE /v3/vault/payment-tokens/{id}` is called on revoke, so revoking in this app also
revokes at PayPal.

### 6.4 CardFields

`/enroll/{token}` serves a static page that:

1. `GET /enroll/{token}/config` → `{ setupTokenId, clientId, environment, brandName }`
2. Loads `https://www.paypal.com/sdk/js?components=card-fields&client-id=…`
3. Renders Name / Number / Expiry / CVV fields into PayPal-hosted iframes
4. On approve, `POST /enroll/{token}/exchange` with `{ vaultSetupToken }`

The server never receives a card number, expiry or CVV.

### 6.5 Orders v2

`POST /v2/checkout/orders` with:

```json
{
  "intent": "CAPTURE",
  "purchase_units": [{
    "reference_id": "pay_…",
    "custom_id": "pay_…",
    "description": "Premium market quote",
    "amount": { "currency_code": "USD", "value": "1.00" }
  }],
  "payment_source": {
    "card": {
      "vault_id": "<payment token>",
      "stored_credential": {
        "payment_initiator": "MERCHANT",
        "payment_type": "UNSCHEDULED",
        "usage": "SUBSEQUENT"
      }
    }
  }
}
```

Then `POST /v2/checkout/orders/{id}/capture`.

`stored_credential` is what tells PayPal this is a merchant-initiated subsequent charge
on a vaulted card, rather than a fresh card payment.

### 6.6 Idempotency

Every mutating PayPal call carries `PayPal-Request-Id`:

| Call | Request id |
| --- | --- |
| Create order | `{attemptId}:create` |
| Capture order | `{attemptId}:capture` |

Because `attemptId` is generated once per settlement attempt, a network retry cannot
create a second order or a second capture.

### 6.7 Webhooks

`POST /webhooks/paypal` accepts PayPal events, then:

1. Parses `id` and `event_type`
2. Returns early if `event_id` already exists (dedupe)
3. Verifies via `POST /v1/notifications/verify-webhook-signature` when
   `PAYPAL_WEBHOOK_ID` is set
4. Persists the raw payload
5. Dispatches:

| Event | Effect |
| --- | --- |
| `PAYMENT.CAPTURE.COMPLETED` | Marks the attempt `SETTLED` if not already |
| `PAYMENT.CAPTURE.DENIED` | Marks failure |
| `PAYMENT.CAPTURE.REFUNDED` | Marks refund |
| `VAULT.PAYMENT-TOKEN.DELETED` | Revokes the matching enrollment |
| `VAULT.PAYMENT-TOKEN.CREATED` | Logged |

If `PAYPAL_WEBHOOK_ID` is blank, verification is **skipped** and a warning is logged.
That is intentional for local development and must not be relied on in production.

---

## 7. Money and budget

Budget is a monthly ceiling per agent, enforced server-side in `PrincipalRepository`:

| Operation | Behaviour |
| --- | --- |
| `resetPeriod` | Rolls `period_start` to the first of the month and zeroes `spent_cents` when the period changed |
| `reserveBudget` | Conditional `UPDATE … WHERE spent_cents + amount <= budget_cents`; returns `0` when it would exceed |
| `releaseBudget` | Compensating decrement on failure |

The reserve happens **before** the PayPal call and is released if the capture does not
complete, so a failed charge never consumes budget.

Amounts are stored as integer cents everywhere; formatting to dollars happens only at
the edges (`BigDecimal.valueOf(cents, 2)`).

---

## 8. Enrollment lifecycle

```
        POST /v1/enrollments {sourceType}
                     │
                     ▼
                 PENDING ──────────────┐
                     │                 │
     hosted CardFields / PayPal approve│
                     │                 │
        exchange setup → payment token │
                     ▼                 │
                  ACTIVE               │
                     │                 │
          DELETE /v1/enrollments/{id}  │
                     ▼                 ▼
                 REVOKED ◄─────────────┘
```

Key rules:

- A `PENDING` enrollment never blocks the previously `ACTIVE` one.
- `exchange` is idempotent: an already `ACTIVE` enrollment returns its view unchanged.
- `revoke` deletes the PayPal payment token first, then marks the row.
- The enrollment link is a signed, 1-hour token, so the hosted page needs no login.

---

## 9. API reference

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `POST` | `/v1/agents` | none | Create agent; returns API key + HMAC secret once |
| `GET` | `/v1/agents/me` | Bearer | Budget, spend, status |
| `POST` | `/v1/enrollments` | Bearer | Create `CARD` or `PAYPAL_WALLET` enrollment |
| `GET` | `/v1/enrollments` | Bearer | List enrollments, newest first |
| `GET` | `/v1/enrollments/{id}` | Bearer | Single enrollment |
| `POST` | `/v1/enrollments/{id}/exchange` | Bearer | Exchange an approved setup token |
| `DELETE` | `/v1/enrollments/{id}` | Bearer | Revoke and delete the vault token |
| `GET` | `/enroll/{token}` | signed link | Hosted card / approval page |
| `GET` | `/enroll/{token}/config` | signed link | CardFields configuration |
| `POST` | `/enroll/{token}/exchange` | signed link | Exchange from the hosted page |
| `GET` | `/enroll/{token}/return` | signed link | Wallet return handler |
| `GET` | `/enroll/{token}/cancel` | signed link | Cancellation page |
| `GET` | `/v1/premium/quote` | Bearer + x402 | $1.00 metered endpoint |
| `GET` | `/v1/premium/report` | Bearer + x402 | $2.00 metered endpoint |
| `GET` | `/v1/premium/health` | Bearer | Liveness for the premium surface |
| `GET` | `/v1/payments/attempts/{id}` | Bearer | Attempt status |
| `POST` | `/v1/payments/attempts/{id}/resume` | Bearer | Resume after payer action |
| `POST` | `/webhooks/paypal` | signature | PayPal events |
| `GET` | `/v1/demo/info` | none | Is the judge demo configured? |
| `POST` | `/v1/demo/session` | none | Fresh agent + active demo enrollment |
| `GET` | `/v1/diagnostics/paypal` | none | Sandbox connectivity probe |
| `GET` | `/actuator/health` | none | Host health check |

### Error shape

```json
{ "error": "BUDGET_EXCEEDED", "message": "Agent budget does not cover this request" }
```

`402` responses additionally carry a `PAYMENT-REQUIRED` header. `429` carries
`Retry-After`. `409` is used for state conflicts (revoked enrollment, attempt not
resumable).

---

## 10. Security model

### Threats and controls

| Threat | Control |
| --- | --- |
| API key theft from the database | Only `SHA-256(apiKey)` is stored, with a unique index |
| Replay of a payment signature | Single-use challenge + `consumed_at` + cached-attempt lookup |
| Tampered amount or resource | Challenge binds resource, method, amount, asset; mismatch → `PAYMENT_MISMATCH` |
| Signature forgery | HMAC-SHA256 with a per-agent derived secret, constant-time compare |
| Card data exposure | CardFields iframes; server stores only an encrypted vault token |
| Vault token theft at rest | AES-GCM, 128-bit tag, 12-byte random IV prepended, base64 |
| Overspend | Conditional SQL budget reservation before the PayPal call |
| Double charge on retry | `PayPal-Request-Id` per attempt + replay cache |
| Forged webhooks | `/v1/notifications/verify-webhook-signature` when configured |
| Duplicate webhook delivery | `event_id` primary key |
| CSRF / cross-site use of the demo | Demo sessions are minted per visit; links are same-origin |

### Key handling

`master-key` and `vault-key` must each decode to exactly 32 bytes; startup fails
otherwise with an actionable message. They are used for different purposes:

- **master key** — agent HMAC secrets, enrollment link tokens
- **vault key** — AES-GCM encryption of PayPal payment tokens

Rotating the vault key makes existing stored tokens undecryptable; rotating the master
key invalidates every agent secret and enrollment link.

### Static-surface allowlist

`AgentAuthFilter` skips authentication only for: `/`, `/index.html`, `/app.css`,
`/app.js`, `/favicon.svg`, `/fonts/`, `/assets/`, `/console.html`, `/error`,
`/actuator`, `/h2-console`, `/webhooks`, `/enroll`, `/v1/diagnostics`, `/v1/demo`, and
`POST /v1/agents`. Everything else requires a Bearer key.

On hosted deployments the H2 console is disabled and health details are hidden.

---

## 11. Configuration reference

| Variable | Default | Purpose |
| --- | --- | --- |
| `PORT` | `8080` | HTTP port |
| `APP_PUBLIC_BASE_URL` | `http://localhost:8080` | Fallback base for generated links |
| `APP_BRAND_NAME` | `x402 Agent Pay` | Shown on hosted pages |
| `PAYPAL_ENV` | `sandbox` | Selects PayPal base URL |
| `PAYPAL_CLIENT_ID` | — | OAuth client id |
| `PAYPAL_CLIENT_SECRET` | — | OAuth client secret |
| `PAYPAL_WEBHOOK_ID` | — | Enables webhook signature verification |
| `X402_MASTER_KEY` | — | Base64, 32 bytes |
| `X402_VAULT_KEY` | — | Base64, 32 bytes |
| `X402_NETWORK` | `paypal:sandbox` | Advertised in challenges |
| `X402_ASSET` | `USD` | Advertised asset |
| `X402_PAY_TO` | `merchant` | Advertised payee |
| `DEMO_ENABLED` | `false` | Turns on `/v1/demo/*` |
| `DEMO_VAULT_TOKEN` | — | Pre-provisioned payment token |
| `DEMO_CARD_BRAND` / `DEMO_CARD_LAST4` | `VISA` / `1111` | Display only |
| `DEMO_BUDGET_CENTS` | `1000` | Per-session budget |
| `DEMO_MAX_SESSIONS_PER_HOUR` | `60` | Per-address rate limit |

Route prices live in `application.yml`:

```yaml
x402:
  routes:
    "[GET /v1/premium/quote]": 100
    "[GET /v1/premium/report]": 200
```

Secrets are read from the environment or an optional `.env` file; `.env` is gitignored
and excluded from the Docker build context.

---

## 12. Judge demo layer

`POST /v1/demo/session` exists so a judge can see real payments without a PayPal login.

1. Rate-limit by client address (in-memory sliding window, 1 hour)
2. `AgentService.create("Judge Agent", budget)` → new `agt_…`, API key, HMAC secret
3. Create an `enrollment` row and `activate()` it with the **pre-provisioned**
   `DEMO_VAULT_TOKEN`, encrypted with the vault key
4. Return `{ agentId, apiKey, hmacSecret, budgetCents, cardBrand, cardLast4, pricing }`

The browser then performs the ordinary x402 exchange against the ordinary endpoints. No
demo-specific payment code path exists — that is the point.

`GET /v1/demo/info` reports availability and the reason when unavailable, so the page can
degrade gracefully instead of failing silently.

---

## 13. Front end

| File | Lines | Role |
| --- | --- | --- |
| `index.html` | 543 | Judge page: hero, how-it-works, transaction theater, funding source, under-the-hood |
| `app.js` | 1058 | Session bootstrap, x402 client, replay, enrollment flow, rendering |
| `app.css` | 1475 | Design system, responsive layout, funding panel |
| `enroll.html` | 527 | Hosted PayPal CardFields page |

The page is a *transaction theater*: it renders the real request, the 402 challenge, the
signed retry, the capture receipt and the raw protocol bytes, so the demo is auditable in
the browser rather than asserted in prose.

Enrollment URLs returned by the API are rewritten to the current origin before opening,
so a tunnel-hosted deployment never sends the judge to `localhost`.

---

## 14. Known limitations

Stated deliberately, because a technical doc that hides these is not useful.

| Limitation | Impact | Path forward |
| --- | --- | --- |
| Premium data is canned (`PremiumContentService`) | The paid content is not a real market feed | Replace with a paid upstream API |
| Card is a PayPal sandbox test card | No real money moves | Live credentials + merchant onboarding |
| Demo rate limiting is in-memory | Resets on restart; not multi-instance safe | Redis or a database-backed counter |
| H2 in production | Not durable on ephemeral hosts | PostgreSQL |
| One vault token per agent | Enterprise fleets repeat provisioning for the same card | Shared entitlement model (see §16) |
| No runtime AI model | The demo agent always pays; there is no reasoning step | Add a decision step before settlement |
| Monthly budget period only | No per-task or per-merchant caps | Policy engine with finer scopes |
| Webhook verification optional | Skipped when `PAYPAL_WEBHOOK_ID` is unset | Require it in production configuration |

---

## 15. Build, run, verify

```bash
# Local
cp .env.example .env      # fill in PayPal sandbox + keys
./gradlew bootRun         # http://localhost:8080

# Tests
./gradlew test

# Container
docker build -t x402-paypal .
docker run --env-file .env -p 8080:8080 x402-paypal
```

### Live deployment

| Item | Value |
| --- | --- |
| URL | `https://x402-paypal-agent-pay.onrender.com/` |
| Host | Render, Docker runtime, blueprint from `render.yaml` |
| Health | `https://x402-paypal-agent-pay.onrender.com/actuator/health` |

Verified against the live deployment:

```
session ................ Live sandbox ready
payment ................ capture 6SF281842S6607129, order 8DY759540V3892025, $10.00 → $9.00
replay ................. X-X402-Replay: true, no second charge, budget unchanged
enrollment host ........ https://x402-paypal-agent-pay.onrender.com
hosted CardFields ...... MASTERCARD •••• 4444 saved
judge status ........... "New card active…"
second charge .......... capture 5R865233TN3440317, budget $9.00 → $8.00
horizontal overflow .... 0 (desktop and mobile)
console errors ......... none
```

Enrollment links resolve to the public Render host without setting
`APP_PUBLIC_BASE_URL`, because `EnrollmentController.externalBaseUrl` derives the base
from `X-Forwarded-Proto` / `X-Forwarded-Host` and the `Host` header.

### Verification performed on this codebase

- `./gradlew test` — passes
- Real sandbox captures end to end, including replay with no second charge
- Optional CardFields enrollment, activation, and a subsequent charge on the new token
- Docker image built and run under a **512 MB** memory cap without OOM
- Public deployment verified on Render and through a tunnel, including enrollment URLs
  resolving to the public host

---

## 16. Extension points

The seams are deliberate:

| Want to change | Touch |
| --- | --- |
| Price or add an endpoint | `application.yml` `x402.routes` + a controller method |
| Different settlement flow | `PaymentService.settle` |
| New funding source type | `EnrollmentService.normalizeSource` + `OrdersClient.createOrder` payment source |
| Different content provider | `PremiumContentService` (single supplier interface) |
| Persistent rate limiting | `DemoSessionService.enforceRateLimit` |
| Shared funding for agent fleets | `EnrollmentEntity` → introduce an entitlement layer above `principal_id` |

The fleet case is the significant one: today `enrollment.principal_id` binds one vault
token to one agent. An enterprise with one card and hundreds of agents would need an
`entitlement` entity between card and agents, with per-agent policy and shared
revocation.

---

## 17. Code map

```
src/main/java/io/x402/paypal/
  agent/          AgentService, AgentAuthFilter, PrincipalEntity/Repository, AgentController
  enrollment/     EnrollmentService/Controller/PageController, EnrollmentEntity/Repository
  payment/        ChallengeEntity/Repository, PaymentAttemptEntity/Repository
  payments/       PaymentService, PaymentResult, PaymentAttemptController/View
  paypal/         PayPalApi, PayPalAuthClient, VaultClient, OrdersClient, records
  premium/        PremiumController, PremiumContentService, PremiumContent
  webhook/        WebhookController/Service, WebhookEventEntity/Repository
  x402/           ChallengeService, PaymentRequiredFactory, PaymentPayloadCodec,
                  X402ReceiptFactory, PaymentRequiredPayload, X402Exception
  security/       CryptoService, Ids
  demo/           DemoSessionController/Service, DemoProperties, DemoAgent, exceptions
  config/         AppProperties, X402Properties, PayPalProperties, PayPalClientConfig
  web/            GlobalExceptionHandler
  dev/            DevEnrollmentController (development-only activation)
```

Front end: `src/main/resources/static/{index.html,app.js,app.css,enroll.html,console.html}`
Migrations: `src/main/resources/db/migration/{V1__init.sql,V2__agent_and_enrollment_fields.sql}`
