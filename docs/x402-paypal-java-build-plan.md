# x402 PayPal API — Java Build Plan

Date: 2026-10-21
Repo: `/Users/I749340/x402-paypal`
Status: planning. Decisions locked. Six items pending confirmation (see last section).

Reference: `docs/paypal-vault-agent-story-detailed.md`, plus the x402, SPT, and PayPal API docs in `docs/`.

## 1. Goal

Build one API that lets an AI agent access premium content and pay per request through PayPal.

Payment source must be agnostic:

- PayPal wallet
- Card

The premium endpoint must not care which source funds the payment. Source binding happens once at enrollment. The request path sees only a server-held vault token.

## 2. Locked decisions

| Area | Decision |
|---|---|
| Goal | Hackathon demo, working sandbox end to end |
| Scope | One Spring Boot service: premium API + x402 + PayPal Vault. No separate facilitator. |
| Charge model | Create and capture a PayPal order per request against a saved vault token |
| Enrollment | Hosted only. Wallet uses approve URL. Card uses PayPal CardFields. |
| Agent identity | API key + HMAC intent signature. Vault token stays server-side. |
| Protocol | x402 v2. `scheme: exact`. `network: paypal:sandbox`. `asset: USD`. |
| Stack | Java 21, Spring Boot 3.5, Gradle, H2 file DB, Flyway |
| v1 includes | Webhooks, SCA fallback, idempotent replay, budget enforcement |

## 3. Hard constraints

### 3.1 Per-request capture has a price floor

PayPal charges a fixed fee per capture. The reference Vault doc shows $10.00 gross and $0.59 fee. That is about 2.99% + $0.30. At $1.00 the fee is about $0.33. Net is about $0.67.

A $0.01 request loses about $0.29 per call. Do not price below the fee floor.

Options rejected for v1:

- Prepaid credit ledger: viable for micro-payments, but it means holding customer funds.
- Batch settlement: complex, and it weakens exact per-request semantics.
- Hybrid ledger plus auto top-up: best product economics, too much work for the demo.

The demo must use a price of at least $1.00.

### 3.2 Card autonomy is conditional

Vault removes card re-entry. It does not remove issuer SCA or OTP. EU PSD2 and many India cards can challenge every charge. The API must expose a human step-up path. Do not claim universal zero-touch card charging.

Only a pre-funded balance or a local ledger is reliably zero-touch. That is out of scope for v1.

### 3.3 No raw PAN in the API

A raw card number in an API request puts the service in PCI scope. The correct flow is:

1. Human enters card data in hosted PayPal fields.
2. PayPal creates or updates a setup token.
3. Server exchanges the setup token for a vault token.
4. Per-request charge uses the vault token.

Card enrollment is therefore a one-time browser action. The agent cannot enter card data autonomously.

### 3.4 A vault token is a bearer charge credential

If the agent sends the vault token in `PAYMENT-SIGNATURE`, theft of that token means charges. Keep the vault token server-side. The agent authenticates with its own API key and signs the 402 challenge.

This is a deliberate deviation from stateless x402. It is necessary for safe fiat delegation.

### 3.5 One service, two surfaces

The service has an agent API and a one-time human enrollment page. The agent API stays a single API. The enrollment page is not part of the per-request flow.

## 4. Architecture

```
Agent  --> GET /v1/premium/quote --> 402 + PAYMENT-REQUIRED
       --> retry + PAYMENT-SIGNATURE --> verify HMAC, nonce, price, budget
                                      --> PayPal Orders create (vault_id)
                                      --> PayPal capture
                                      --> 200 + content + PAYMENT-RESPONSE

Human  --> /enroll/{token} --> PayPal approve or CardFields --> vault token ACTIVE

PayPal --> /webhooks/paypal --> capture reconcile, vault deleted, refunds
```

### 4.1 Source abstraction

```java
interface FundingSource {
    String type();                                  // PAYPAL_WALLET | CARD
    SetupSession createSetup(Principal p);          // setup token + approve URL or page
    VaultToken exchange(String setupToken);         // 8kk... + brand + last4
    ChargeResult charge(VaultToken t, Money m, String idempotencyKey);
}
```

Two implementations:

- `PayPalWalletFundingSource`
- `CardVaultFundingSource`

Premium code depends only on `FundingSource`. It never sees card or wallet details.

### 4.2 Request flow

1. Agent calls a premium endpoint.
2. Server returns `402` with a base64 `PAYMENT-REQUIRED` header.
3. Agent signs the challenge and retries with a base64 `PAYMENT-SIGNATURE` header.
4. Server validates the challenge, HMAC, nonce, expiry, price, asset, and budget.
5. Server creates a PayPal order with the saved vault token.
6. Server captures the order.
7. Server returns the content plus a base64 `PAYMENT-RESPONSE` header.
8. A retry of the same attempt returns the stored content and the same receipt. It does not charge again.

If capture returns `422 payer_action_required`, the server returns `402` with a payer action URL. The human completes OTP or 3DS. The agent resumes the attempt.

If the vault token was deleted, the server disables the enrollment and asks for re-enrollment.

## 5. Wire contract

### 5.1 402 response header

`PAYMENT-REQUIRED` is base64 JSON:

```json
{
  "x402Version": 2,
  "error": "Payment required",
  "resource": {
    "url": "https://api.example.com/v1/premium/quote",
    "description": "Premium market quote",
    "mimeType": "application/json"
  },
  "accepts": [{
    "scheme": "exact",
    "network": "paypal:sandbox",
    "amount": "100",
    "asset": "USD",
    "payTo": "merchant@example.com",
    "maxTimeoutSeconds": 120,
    "extra": {
      "paymentFlow": "upfront",
      "amountFormatted": "1.00",
      "challengeId": "ch_...",
      "nonce": "uuid",
      "sourceType": "CARD",
      "enrolled": true
    }
  }]
}
```

Notes:

- `amount` is in minor units. For USD, cents.
- `asset` is ISO 4217 `USD`.
- `network` uses the unregistered namespace `paypal:sandbox`. Document this.
- `extra.paymentFlow` is `upfront` because settlement happens before the handler serves content.
- If the agent is not enrolled, `accepts` has two entries, `PAYPAL_WALLET` and `CARD`, and `extra.enrollmentUrl` is set.
- `payTo` is the merchant business email or the role constant `merchant`. Funds go to our own business account.

### 5.2 Payment payload

`PAYMENT-SIGNATURE` is base64 JSON:

```json
{
  "x402Version": 2,
  "accepted": { "...": "echo of chosen requirement" },
  "payload": {
    "agentId": "agt_...",
    "challengeId": "ch_...",
    "nonce": "uuid",
    "timestamp": 1760000000,
    "signature": "base64url(HMAC-SHA256(agentSecret, canonical))"
  }
}
```

Canonical string:

```
challengeId \n METHOD \n resourceUrl \n amount \n asset \n nonce \n timestamp
```

Server checks:

- HMAC matches, constant time
- challenge exists and is not consumed
- challenge is not expired
- resource URL and method match
- amount and asset match
- nonce is unused
- timestamp is within 120 seconds
- agent is active
- enrollment is active
- budget can cover the price

This is a PayPal network binding of `exact`. The payload proves intent. The server holds the funding credential. Document this deviation.

### 5.3 Receipt

`PAYMENT-RESPONSE` is base64 JSON:

```json
{
  "success": true,
  "transaction": "CAPTURE-ID",
  "network": "paypal:sandbox",
  "payer": "agt_...",
  "extra": {
    "orderId": "ORDER-ID",
    "captureId": "CAPTURE-ID",
    "gross": "1.00",
    "fee": "0.33",
    "net": "0.67",
    "status": "COMPLETED"
  }
}
```

## 6. Endpoints

### 6.1 Bootstrap and agent

- `POST /v1/agents` -> `{agentId, apiKey, hmacSecret, budgetCents}`
- `GET /v1/agents/me`

`POST /v1/agents` is a demo bootstrap. In production it is admin-only.

### 6.2 Enrollment

- `POST /v1/enrollments` body `{sourceType: PAYPAL_WALLET|CARD}` -> `{enrollmentId, status, approveUrl?, enrollmentUrl?}`
- `GET /enroll/{signedToken}` -> hosted card page
- `POST /v1/enrollments/{id}/exchange` body `{vaultSetupToken?}` -> `{status, brand, last4}`
- `GET /v1/enrollments`
- `GET /v1/enrollments/{id}`
- `DELETE /v1/enrollments/{id}`
- `GET /enroll/{token}/return` -> wallet approval callback

Enrollment page uses PayPal CardFields with the client ID. A standalone seller integration does not pass `merchant-id`; PayPal derives it from the client ID. The page creates a setup token server-side, renders fields, submits the card, and posts `vaultSetupToken` back to the server. No PAN reaches the server.

Wallet enrollment returns a PayPal approve URL. The human approves. PayPal redirects to the return URL. The server reads the setup token, exchanges it, and marks the enrollment `ACTIVE`.

### 6.3 Premium

- `GET /v1/premium/quote` -> $1.00
- `GET /v1/premium/report` -> $2.00
- `GET /v1/premium/health` -> free

Route prices are configuration, not code.

### 6.4 Payments

- `GET /v1/payments/attempts/{id}` -> status
- `POST /v1/payments/attempts/{id}/resume` -> resume after SCA

### 6.5 Webhooks

- `POST /webhooks/paypal`

### 6.6 Error mapping

| Condition | Status | Error |
|---|---|---|
| No payment header | 402 | `PAYMENT_SIGNATURE_REQUIRED` |
| Invalid HMAC | 402 | `INVALID_SIGNATURE` |
| Amount or asset mismatch | 402 | `PAYMENT_MISMATCH` |
| Expired challenge | 402 | `CHALLENGE_EXPIRED` |
| Reused nonce | 402 | `REPLAY_DETECTED` |
| No active enrollment | 402 | `ENROLLMENT_REQUIRED` |
| Budget exceeded | 402 | `BUDGET_EXCEEDED` |
| SCA required | 402 | `PAYER_ACTION_REQUIRED` |
| Instrument declined | 402 | `INSTRUMENT_DECLINED` |
| Replay of settled attempt | 200 | none, plus `X-X402-Replay: true` |

## 7. Persistence

H2 file database. Flyway migrations. No Postgres-specific SQL.

### 7.1 Tables

`principal`

- `id`
- `api_key_hash`
- `budget_cents`
- `spent_cents`
- `period_start`
- `status`

`enrollment`

- `id`
- `principal_id`
- `paypal_customer_id` (max 22 chars, `[0-9a-zA-Z_-]`)
- `source_type` (`PAYPAL_WALLET` or `CARD`)
- `vault_token_enc` (AES-GCM)
- `brand`
- `last4`
- `status`
- `consent_max_cents`
- `created_at`
- `revoked_at`

`challenge`

- `id`
- `principal_id`
- `resource`
- `amount_cents`
- `nonce`
- `expires_at`
- `consumed_at`

`payment_attempt`

- `id`
- `challenge_id`
- `principal_id`
- `enrollment_id`
- `amount_cents`
- `status`
- `order_id`
- `capture_id`
- `response_json`
- `error_code`
- `payer_action_url`
- `created_at`
- `updated_at`

`webhook_event`

- `event_id` primary key
- `type`
- `payload`
- `processed_at`

### 7.2 Budget enforcement

One atomic statement:

```sql
UPDATE principal
SET spent_cents = spent_cents + :price
WHERE id = :id AND spent_cents + :price <= budget_cents
```

Zero rows means budget block. A compensating decrement runs on PayPal failure. This works on H2 and does not depend on `SELECT FOR UPDATE`.

Idempotency rule: if an attempt already reserved budget, do not reserve again.

### 7.3 Payment attempt state machine

```
CREATED -> VERIFIED -> ORDER_CREATED -> CAPTURED -> SERVED
                          |
                          +--> REQUIRES_ACTION -> CAPTURED -> SERVED
                          +--> FAILED (budget released)
```

Replay of `SERVED` returns the stored content and receipt.

## 8. Idempotency and retries

- `PayPal-Request-Id` for order create = attempt ID.
- `PayPal-Request-Id` for capture = attempt ID + `:capture`.
- Persist the attempt before the first PayPal call.
- On timeout, call `GET /v2/checkout/orders/{id}` and reconcile. Never blind re-POST.
- Store the response snapshot before returning content.
- A duplicate request with the same attempt returns the snapshot and the same capture receipt.
- A new request needs a new challenge and a new attempt.
- On startup, reconcile attempts left in `ORDER_CREATED` or `REQUIRES_ACTION`.

## 9. PayPal integration

### 9.1 Auth

- OAuth client credentials.
- Cache the bearer token until `expires_in - 60s`.
- Single-flight refresh.
- Sandbox host: `api-m.sandbox.paypal.com`.

### 9.2 Vault

Wallet setup:

```json
{
  "payment_source": {
    "paypal": {
      "description": "Pay per request for Premium API",
      "usage_pattern": "UNSCHEDULED_PREPAID",
      "usage_type": "MERCHANT",
      "experience_context": {
        "brand_name": "Premium API",
        "locale": "en-US",
        "return_url": "https://.../enroll/{token}/return",
        "cancel_url": "https://.../enroll/{token}/cancel"
      }
    }
  },
  "customer": { "id": "<short agent/owner id>" }
}
```

Without `experience_context`, the setup token has no `approve` link. The agent cannot send the buyer to PayPal.

Card setup uses an empty card object plus:

```json
{
  "verification_method": "SCA_WHEN_REQUIRED",
  "experience_context": {
    "brand_name": "Premium API",
    "locale": "en-US",
    "return_url": "https://.../enroll/{token}/return",
    "cancel_url": "https://.../enroll/{token}/cancel"
  }
}
```

Exchange:

```json
{ "payment_source": { "setup_token": "SETUP-ID" } }
```

Result: `8kk...` vault token, `brand`, `last4`, `status: ACTIVE`.

Store the source type. Never send a `card.vault_id` for a wallet token or the reverse.

### 9.3 Charge

Order create uses the vault token:

```json
{
  "intent": "CAPTURE",
  "purchase_units": [{
    "amount": { "currency_code": "USD", "value": "1.00" },
    "custom_id": "agt_...|attempt-id|quote"
  }],
  "payment_source": {
    "card": { "vault_id": "8kk..." }
  }
}
```

Wallet variant uses `payment_source.paypal.vault_id`.

Omit `payee` for self-payment. `payTo` in the 402 response is informational.

Then capture:

```
POST /v2/checkout/orders/{id}/capture
```

A card vault order can return `COMPLETED` with a capture at create time. If the order already has a capture, do not capture again. Read `seller_receivable_breakdown` for gross, fee, and net.

### 9.4 Error handling

- `422 PAYER_ACTION_REQUIRED`: store the payer action link. Mark `REQUIRES_ACTION`. Return 402 with the link. Resume by reading the order and capturing.
- `422 INSTRUMENT_DECLINED`: mark `FAILED`. Release budget. Return 402.
- `422` amount or currency mismatch: log `debug_id`. Do not retry.
- Vault token `404`: disable enrollment. Ask for re-enrollment.

### 9.5 Webhooks

Verify with `POST /v1/notifications/verify-webhook-signature`. Dedupe by event ID. Handle:

- `PAYMENT.CAPTURE.COMPLETED`: reconcile attempt by capture ID.
- `PAYMENT.CAPTURE.DENIED`: mark failed.
- `PAYMENT.CAPTURE.REFUNDED`: mark refunded.
- `VAULT.PAYMENT-TOKEN.DELETED`: disable enrollment.
- `VAULT.PAYMENT-TOKEN.CREATED`: audit only.

Respond 200 fast. Process idempotently.

## 10. Security

- API key shown once. Store PBKDF2 hash.
- Agent HMAC secret derived as `HMAC-SHA256(X402_MASTER_KEY, agentId)`. Server recomputes. The agent receives it once. No per-agent secret storage.
- Vault token encrypted at rest with AES-GCM using `X402_VAULT_KEY`.
- Enrollment page uses a signed, single-use, short-lived link token.
- Constant-time comparisons for keys and HMAC.
- No PAN stored or logged. Card data stays in PayPal fields.
- Webhook signature verification and event dedupe.
- Rate limit enrollment and premium endpoints. Optional for the demo.
- Log `attemptId`, `orderId`, `captureId`, and PayPal `debug_id`. Never log secrets or card data.

## 11. Project layout

```
build.gradle.kts
settings.gradle.kts
gradlew
src/main/java/io/x402/paypal/
  X402PaypalApplication.java
  config/        PayPalProperties, X402Properties, RestClientConfig
  agent/         AgentController, AgentService, ApiKeyService, HmacService
  enrollment/    EnrollmentController, EnrollmentPageController, EnrollmentService
  x402/          X402Filter, PaymentRequiredFactory, PaymentPayloadCodec, ChallengeService
  payment/       PaymentOrchestrator, BudgetService, AttemptRepository, ReceiptFactory
  paypal/        PayPalAuthClient, OrdersClient, VaultClient, WebhookVerifier, dto/
  premium/       PremiumController, PremiumContentService
  webhook/       PayPalWebhookController
src/main/resources/
  application.yml
  db/migration/V1__init.sql
  static/enroll.html
src/test/java/...
```

No Lombok. Java records for DTOs. `RestClient` with timeouts. Spring `Filter`, not an interceptor, so the proof header survives on `@RestController`.

## 12. Build phases

### Phase 0 — prerequisites (0.5 day)

- Sandbox business account and personal buyer.
- App with Accept Payments, Vault, and Advanced Cards enabled.
- Client ID and secret. Merchant ID is not required for a standalone seller integration; PayPal derives it from the client ID. If needed for reference, find it in Testing Tools > Sandbox Accounts > business account > Account Info.
- Public HTTPS URL for return and webhooks.
- Price and content decisions.

Acceptance: PayPal token fetch succeeds.

### Phase 1 — skeleton (0.5 day)

- Gradle wrapper, Boot 3.5, Java 21.
- H2 file DB, Flyway, config, actuator, OpenAPI.
- PayPal auth client and token cache.
- Structured logging.

Acceptance: `/actuator/health` is up and the PayPal token call works.

### Phase 2 — enrollment (1 to 1.5 days)

- Agent bootstrap and API key.
- Wallet setup, approve URL, return callback, exchange.
- Card setup, hosted page, CardFields, exchange.
- List and delete enrollments.
- Vault webhooks.

Acceptance: wallet and card both reach `ACTIVE`. Delete revokes.

### Phase 3 — x402 core (0.75 day)

- Route pricing from config.
- 402 header factory.
- Challenge storage.
- HMAC canonicalization and verification.
- Expiry, nonce, amount, asset, and resource checks.
- Replay rejection.

Acceptance: wrong HMAC, wrong amount, expired challenge, and reused nonce are rejected. A valid payload reaches settlement.

### Phase 4 — settlement (1 day)

- Atomic budget reservation.
- Order create and capture.
- Receipt header.
- Content snapshot.
- Idempotent retry.
- Timeout reconcile.
- Failure release.
- Parallel budget test.

Acceptance: one charge per attempt. Retry returns cached content. Twenty concurrent requests never exceed the cap.

### Phase 5 — SCA and webhooks (0.75 day)

- 422 mapping and payer action URL.
- Resume endpoint.
- Capture webhook reconcile.
- Vault deleted revoke.
- Event dedupe.

Acceptance: simulated 422 surfaces a link. After approval, resume succeeds. Duplicate webhooks are no-ops.

### Phase 6 — demo and hardening (0.75 day)

- Demo agent.
- README with curl.
- Both funding sources.
- Budget block, revoke, and SCA scenarios.
- Unit and integration tests.

Acceptance: one script runs the full story.

Total estimate: 5 to 5.5 focused days. Wallet-only demo in about 2 days. Card page adds about 1 day.

## 13. Tests

- Header codec round-trip. Wrong base64. Wrong version.
- HMAC canonicalization. Constant-time compare.
- Amount, asset, and resource mismatch.
- Expired challenge. Reused nonce.
- Concurrent budget reservation on H2 with an executor.
- WireMock: setup token, exchange, order create, capture, 422, decline.
- Capture timeout, then GET reconcile, no double charge.
- Webhook invalid signature, duplicate event, vault deleted.
- Replay returns identical content and receipt.
- Enrollment page token tampering and expiry.

## 14. Demo script

1. Create agent with a $10.00 budget.
2. Enroll wallet. Human approves. Enrollment becomes `ACTIVE`.
3. `GET /v1/premium/quote` -> 402.
4. Agent signs and retries -> 200, content plus receipt. Budget drops to $9.00.
5. Repeat -> 200, budget $8.00.
6. Replay the same attempt -> 200, no second charge.
7. Charge until the budget blocks -> 402 `BUDGET_EXCEEDED`.
8. Enroll card via hosted page. Repeat steps 3 and 4.
9. Force SCA -> 402 `PAYER_ACTION_REQUIRED`. Human approves. Resume -> 200.
10. Delete the vault token in PayPal -> enrollment disabled. Next request returns `ENROLLMENT_REQUIRED`.

## 15. Risks

1. Card path requires PayPal business approval for Advanced Cards and Vault. If not enabled, wallet is the fallback.
2. Return URL and webhooks need public HTTPS. Use ngrok or cloudflared. Without it, use the Webhooks Simulator and a static success page.
3. Issuer SCA can appear on every charge. The demo must show the step-up path. Do not claim universal zero-touch.
4. H2 plus Flyway requires simple SQL. No Postgres-specific features.
5. PayPal setup-token payload shapes may vary by account and product. Verify both wallet and card bodies against the sandbox in Phase 2 before building on them.
6. Per-request capture cannot support micro-pricing. Keep the price at or above $1.00 for the demo.

## 16. Confirmation status

1. Price: confirmed. $1.00 for `/quote` and $2.00 for `/report`.
2. Content: confirmed. Mock JSON first, then a real data source.
3. Sandbox: the user has a PayPal sandbox account. Must verify the app has Accept Payments, Vault, and Advanced Credit/Debit Card Payments enabled. If Advanced Cards is not enabled, start wallet-only and add card later.
4. Public URL: not available yet. Build with `APP_PUBLIC_BASE_URL` configurable, default `http://localhost:8080`. Use setup-token polling for wallet so the redirect is not required. Implement webhooks and test them locally with simulated signed POSTs. Add a cloudflared or ngrok tunnel only for the live webhook or 3DS demo.
5. Demo agent: confirmed. Small Java CLI in this repo.
6. Payee: confirmed. Our own business account only. USD only. No marketplace or Payouts.

## 16.1 Public URL purpose and fallback

A sandbox account does not provide a public URL. PayPal needs one for two separate things:

- `return_url`: where the human lands after wallet approval or card 3DS.
- Webhook URL: where PayPal posts capture, refund, and vault events.

Fallback plan:

- Wallet enrollment polls `GET /v3/vault/setup-tokens/{id}` until `APPROVED`, then exchanges. The redirect is optional.
- Card enrollment starts with a non-3DS sandbox card. If 3DS is required, a public HTTPS return URL is needed.
- Webhooks are implemented and tested with local simulated POSTs. For live PayPal webhooks, run a free cloudflared or ngrok tunnel and set `APP_PUBLIC_BASE_URL`.
- The demo can run end to end without a tunnel. The only capabilities lost are live PayPal webhook delivery and 3DS redirects.

## 17. Out of scope for v1

- Prepaid ledger and auto top-up
- Batch settlement
- Marketplace payouts and multiparty
- Subscriptions
- Refund UI
- Raw PAN acceptance
- Multi-currency
- Production KYC or licensing
- Separate facilitator service
- Stablecoin or PYUSD

## 18. Implementation status

As built in this repository:

| Area | Status | Notes |
|---|---|---|
| Gradle + Boot + Java 21 | Done | Gradle 8.14.3 wrapper, Boot 3.5.16 |
| H2 + Flyway | Done | `V1__init.sql`, `V2__agent_and_enrollment_fields.sql` |
| PayPal OAuth | Done | Cached token, single-flight refresh, diagnostics endpoint |
| Agent bootstrap | Done | API key shown once, HMAC secret derived |
| Wallet enrollment | Done | Approve URL, return callback, exchange, polling |
| Card enrollment | Done | CardFields hosted page, setup token, exchange |
| x402 402 header | Done | Includes challenge ID and nonce |
| HMAC verification | Done | Resource, method, amount, asset, nonce, expiry, timestamp |
| Budget | Done | Atomic reserve, release on failure, monthly reset |
| Settlement | Done | Order create, capture, auto-capture handling, receipt |
| Replay | Done | Same content, `X-X402-Replay: true`, no second charge |
| SCA | Done | `PAYER_ACTION_REQUIRED`, attempt status, resume endpoint |
| Webhooks | Done | Verify when configured, dedupe, capture reconcile, vault delete |
| Demo agent | Done | `./gradlew demoAgent` |
| Unit tests | Done | Crypto, payload codec, challenge verification, 402 shape, content |
| WireMock integration tests | Not done | Sandbox calls were verified manually |
| Concurrent budget stress test | Not done | Atomic SQL is in place |
| OpenAPI | Not done | Not required for the demo |

### 18.1 Deviations and changes from the plan

1. `extra.nonce` is included in the `PAYMENT-REQUIRED` response. The agent must know the nonce to sign the canonical string.
2. API keys are stored as SHA-256 hashes, not PBKDF2. The key is 32 random bytes, so SHA-256 is sufficient. PBKDF2 is for low-entropy passwords.
3. A card vault order can return `COMPLETED` with a capture at create time. The service handles both auto-captured and create-then-capture orders.
4. A `dev` profile adds a local-only endpoint to activate an enrollment from a vault token. It skips the hosted exchange for local testing. It is absent unless `--spring.profiles.active=dev` is set.
5. Webhook signature verification is skipped when `PAYPAL_WEBHOOK_ID` is empty. This is for local demos only. Set the ID to enforce verification.
6. `PAYPAL_MERCHANT_ID` is not read by the app and is not required for a standalone seller integration.

### 18.2 Verified locally

- PayPal OAuth token fetch against sandbox.
- Wallet setup token creation and approve link.
- Card setup token creation through the app.
- CardFields page and setup-token config endpoint.
- Direct sandbox card vault token used only to exercise settlement.
- `$1.00` quote charged and captured: order `1RV70160N7925781X`, capture `0WP54617VW883582V`.
- Receipt: gross `1.00`, fee `0.52`, net `0.48`.
- Replay of the same signature returned `200` with `X-X402-Replay: true`.
- Wrong HMAC returned `402 INVALID_SIGNATURE` with a fresh `PAYMENT-REQUIRED` header.
- Unenrolled agent received both `PAYPAL_WALLET` and `CARD` accepts.
- Simulated capture webhook returned `200`; duplicate event was a no-op.
- Payment attempt lookup returned `SETTLED` with the order and capture IDs.

### 18.3 Remaining work before a live demo

1. Verify wallet enrollment end to end with a real sandbox buyer approval.
2. Verify card enrollment through CardFields in a browser.
3. Force an SCA case and verify `resume`.
4. Set `PAYPAL_WEBHOOK_ID` and test a real PayPal webhook through a tunnel.
5. Decide whether to keep the `dev` profile helper in the shipped branch.
