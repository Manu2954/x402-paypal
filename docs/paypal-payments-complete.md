# PayPal Payments - Complete Research
Source: https://developer.paypal.com/ (docs, API reference, integration guides)
Date: 2026-10-08
Output dir: ~/paypalHackathon

Note: Subagents unavailable on free tier (OpenCode free tier can only be used from within OpenCode). Research done directly via developer.paypal.com + web search.

## 1. PayPal model in one paragraph

PayPal is a wallet + processor network. Buyer pays with PayPal balance, linked bank, card via PayPal, or guest card, without giving card number to merchant. Merchant integrates via Orders API + JS SDK, buyer approves on PayPal, merchant captures/authorizes server-side. Money lands in merchant PayPal balance, then withdraws to bank. Repeat/agent payments use Vault payment tokens + reference transactions. Mass disbursement uses Payouts. All REST calls use OAuth2 Bearer tokens. Async state changes arrive via Webhooks.

Actors:
- Payer (buyer / personal sandbox account)
- Payee / Merchant (business sandbox account)
- Platform / Partner (marketplace acting for sellers, via Partner Referrals + multiparty checkout)
- PayPal (auth, risk, ledger, settlement)

Environments:
- Sandbox: `https://api-m.sandbox.paypal.com`, `https://www.sandbox.paypal.com`, `https://www.sandbox.paypal.com/web-sdk/v6/core`
- Live: `https://api-m.paypal.com`, `https://www.paypal.com/web-sdk/v6/core`
- Dashboard: Apps & Credentials, Sandbox Accounts (default 1 personal + 1 business), Webhooks simulator, Events dashboard.

## 2. Auth

1. Get Client ID + Secret: Dashboard > Apps & Credentials > Default Application or Create App.
   - Client ID = public username, safe in frontend for JS SDK.
   - Secret = server only.
2. Exchange for access token:
```
curl -v -X POST https://api-m.sandbox.paypal.com/v1/oauth2/token \
 -u "CLIENT_ID:CLIENT_SECRET" \
 -H "Content-Type: application/x-www-form-urlencoded" \
 -d "grant_type=client_credentials"
```
Response: `{access_token: Bearer..., expires_in: ~31668s, scope: ..., app_id, nonce}`.
Use as `-H Authorization: Bearer ACCESS-TOKEN`. Refresh on expiry.

3. JS SDK v6 auth:
- Standard checkout: `window.paypal.createInstance({clientId: YOUR_CLIENT_ID, components: ["paypal-payments"]})`.
- Fastlane guest checkout only: server generates browser-safe client_token via `grant_type=client_credentials + domains[] + response_type=client_token`, frontend uses `{clientToken}`. Token expires ~15 min.

Scopes include payments, refund, vault read/write, payouts, subscriptions, webhooks, etc.

## 3. Core objects: Order vs Authorization vs Capture vs Sale

- Order (`/v2/checkout/orders`): intent to pay between parties. States: CREATED -> APPROVED (payer approved) -> COMPLETED (captured/authorized). Valid 29 days. Holds `purchase_units[]` (amount, items, shipping, payee), `payment_source`, links (`approve`, `capture`, `authorize`).
- Authorization: hold on funds. Valid 29 days. Can make up to 10 basic auths per order (up to 99 via support). Sum open auths <= 115% or $75 over order amount, whichever less. Endpoints: `POST /v2/checkout/orders/{id}/authorize`, then `/v2/payments/authorizations/{auth_id}/capture|reauthorize|void`, `GET .../authorizations/{id}`.
- Capture: actual money movement. `POST /v2/checkout/orders/{id}/capture` or `POST /v2/payments/authorizations/{auth_id}/capture`. Supports partial capture + `is_final_capture=true` to close.
- Sale (legacy view): completed capture. Webhook `PAYMENT.SALE.COMPLETED` still used for subscription cycle etc.
- Refund: `POST /v2/payments/captures/{capture_id}/refund`, `GET /v2/payments/refunds/{refund_id}`.
- Payment Token (Vault v3): saved method `8kk8458...`, reusable without present payer after consent.

Two intents at order create:
- `CAPTURE`: charge immediately. Flow: create -> approve -> capture.
- `AUTHORIZE`: hold then capture later (stock check, ship later). Flow: create -> approve -> authorize -> capture (auth).

## 4. Standard flows

### 4.1 PayPal Checkout CAPTURE (most common)
1. Buyer adds to cart, clicks PayPal button.
2. Server: `POST /v2/checkout/orders` with `intent=CAPTURE`, `purchase_units[{amount:{currency_code, value}, items, shipping}]`. Returns `id`, `approve` URL.
3. Frontend redirects / popup to approve URL. Buyer picks PayPal balance / card / bank / Pay Later, approves.
4. Buyer returns to `return_url`.
5. Server: `POST /v2/checkout/orders/ORDER-ID/capture`. Returns capture ID, status COMPLETED.
Other ops anytime before completion: `PATCH` update (add/remove/replace, only CREATED/APPROVED), `GET` show details, `POST .../track` add tracking.

### 4.2 AUTHORIZE + CAPTURE
Same but `intent=AUTHORIZE`. After approval: `POST .../authorize` (hold 29 days), later `POST /v2/payments/authorizations/{id}/capture` (full/partial). `reauthorize` to extend, `void` to cancel.

### 4.3 Expanded / card + wallets checkout
Same Orders API, different `payment_source`: card, PayPal vault_id, billing_agreement_id, Apple Pay, Google Pay, Venmo, etc. Single-step create with payment source requires `PayPal-Request-Id` header (stored 6h, extendable to 72h via AM) for idempotency. Then `POST .../confirm-payment-source` where payer confirms intent.

Endpoints summary:
- `POST /v2/checkout/orders` create
- `GET /v2/checkout/orders/{id}` show
- `PATCH /v2/checkout/orders/{id}` update
- `POST /v2/checkout/orders/{id}/confirm-payment-source` confirm
- `POST /v2/checkout/orders/{id}/authorize` auth
- `POST /v2/checkout/orders/{id}/capture` capture
- `POST /v2/checkout/orders/{id}/track`, `PATCH .../trackers/{tracker_id}` shipping

Error example: capturing with `intent=AUTHORIZE` -> 422 Action Does Not Match Intent. PSD2 SCA failure -> 422 Payer Action Required.

## 5. JS SDK v6 frontend + server split

Include:
```html
<script src="https://www.paypal.com/web-sdk/v6/core"></script>
<!-- sandbox: https://www.sandbox.paypal.com/web-sdk/v6/core -->
```

Init:
```js
const sdkInstance = await window.paypal.createInstance({
  clientId: "YOUR_CLIENT_ID",
  components: ["paypal-payments"], // + venmo-payments, googlepay-payments, applepay-payments, fastlane, paypal-messages
  pageType: "checkout",
  locale: "en-US"
});
```

Eligibility first:
```js
const methods = await sdkInstance.findEligibleMethods({currencyCode: "USD"});
if (methods.isEligible("paypal")) configurePayPalButton(sdkInstance);
if (methods.isEligible("paylater")) { /* productCode, countryCode */ }
```

Session pattern:
```js
const session = sdkInstance.createPayPalOneTimePaymentSession({onApprove, onCancel, onError});
paypalButton.addEventListener("click", () => session.start({presentationMode: "auto"}, createOrder()));

function createOrder() {
  return fetch("/paypal-api/checkout/orders/create", {method:"POST", body: JSON.stringify(orderPayload)})
    .then(r=>r.json()).then(data=>({orderId: data.id})); // must return {orderId}
}
async function onApprove(data) { // data.orderId
  await fetch("/paypal-api/checkout/orders/capture", {method:"POST", body: JSON.stringify({orderId: data.orderId})});
}
```

Rules: never trust browser totals, validate amount server-side before capture, handle popup blockers with `presentationMode:auto`, show loading states, cache client tokens.

Server SDKs available for Node/Java/Python/PHP/Ruby/.NET + iOS/Android SDKs + React SDK. Postman public workspace mirrors all payloads.

## 6. Saved payments: Vault v3 + Reference Transactions (agent-relevant)

Vault saves methods so payer does not re-enter or need not be present after consent. Closest PayPal analog to Stripe saved PM + SPT delegation.

Flow:
1. `POST /v3/vault/setup-tokens` from payment source -> temporary setup token (with `verification_method` e.g. 3DS, Smart Dollar Auth, description, usagePattern, usageType).
2. Buyer approves save (PayPal token management page shows description).
3. `POST /v3/vault/payment-tokens` with setup token -> permanent token `id=8kk8458` + links `self`, `delete`. Associated to customer vault.
4. Reuse token for checkout / subscriptions / unscheduled charges.
5. `GET /v3/vault/payment-tokens/{id}`, `GET /v3/vault/payment-tokens` (list by customer), `DELETE .../{id}` revoke.

Fields: `usage_type=MERCHANT`, `customer_type=CONSUMER`, `permit_multiple_payment_tokens` (multiple tokens same payer/merchant to distinguish sub-customers sharing PayPal account), `usage_pattern=UNSCHEDULED_PREPAID` etc.

Reference Transactions (vault + approval) enable flexible renewals/variable amounts. Required for Woo-style subscriptions with vaulting.

For agentic use: agent vaults buyer PayPal wallet/card once, then charges via token per seller order without redirect, unless SCA/issuer step-up requires payer action (422). Revoke via DELETE.

Payment Method Tokens API docs: Create/Retrieve/List/Delete payment-tokens + Create/Get setup-tokens.

## 7. Payment methods

- PayPal wallet (balance/bank/cards behind PayPal login) - default `paypal` component
- Pay Later / PayPal Credit (country + eligibility gated, needs productCode/countryCode)
- Venmo (US only, USD)
- Cards (standard fields, vaultable)
- Apple Pay / Google Pay (device-bound, separate components)
- Fastlane accelerated guest checkout (clientToken auth, iframe)
- Bank / SEPA Debit via vault (EU)
- `POST /v2/payments/find-eligible-methods` returns eligible methods for input params.

`findEligibleMethods({currencyCode})` client-side mirrors this (location, currency, device, account status).

## 8. Other money APIs

- Subscriptions v1: Product -> Plan (fixed/volume/tier, trial, upgrade/downgrade) -> Subscription via JS button -> buyer agrees -> API creates subscription -> cycles auto-charge, recovery on fail. Manage: activate/suspend/cancel/revise/update/capture outstanding, list transactions, test in sandbox autopay pages (`sandbox.paypal.com/myaccount/autopay/connect`, `/billing/subscriptions`). Webhook `PAYMENT.SALE.COMPLETED` per cycle.
- Invoicing, Payment Links/Buttons, Donations (Growth section).
- Payouts (`/v1/payments/payouts`): send to up to 15,000 recipients/call to EMAIL/PHONE/USER_HANDLE/PAYPAL_ID, wallet PAYPAL/VENMO (Venmo US USD only, needs note + US mobile). Funded from PayPal balance (top up + fees first). Flow: validate -> status report (`payout_batch_id`, `batch_status=ACKNOWLEDGED...`) -> notify recipients (create account if needed, claim in 30d else RETURNED). Track: `GET ...?fields=batch_header`, `GET /v1/payments/payouts-item/{payout_item_id}`. Needs access request, confirmed identity/email/bank. Currency conversion supported per-item (separate call per currency).
- Platforms/Marketplaces: Partner Referrals onboarding, multiparty checkout, split payments, `partnerAttributionId (BN_CODE)`, Commerce solutions. SPT analogy: platform provides network ID; here platform onboards sellers then creates multiparty orders.
- Disputes, Reporting/Transaction Search, Shipment Tracking (`.../track`), Agent Tools (catalog, dispute, reporting/insights, shipment, subscription management via AI tool refs).

Agentic Commerce Services section exists (About agentic commerce, Agent ready, Store Sync) + Agent tools reference (list_transaction, create_shipment_tracking, subscription tools). No SPT equivalent; delegation via Vault + Payouts + multiparty.

## 9. Webhooks

Reverse API: PayPal POSTs JSON to your URL on events (subscription cycle, refund from dashboard, APM approved ready for capture, payout status, etc.).

Setup: subscribe up to 10 URLs per REST app, specific event types or `*`. Events scoped to app (payments on App A do not fire App B listeners; legacy NVP/SOAP app separate).

Handling:
1. Receive: respond 2xx fast. Non-2xx retries up to 25x over 3 days, then Failed (resend manually from Events dashboard).
2. Verify: CRC32+signature check locally OR post message + webhook ID + headers to PayPal verify-signature endpoint. Never trust unverified.
Key pages: Webhooks overview, Event names, Integrate webhooks, Webhooks API ref, Events dashboard, Simulator.

Relevant events: `PAYMENT.SALE.COMPLETED`, order capture/authorize events, subscription events, payout item events, refund/dispute events. Full list at `/api/rest/webhooks/event-names`.

## 10. Security, SCA, idempotency

- OAuth2 Bearer for all REST. Never expose secret in frontend.
- Server-side order create/capture, validate totals.
- `PayPal-Request-Id` for idempotency on single-step order create with payment source (and payouts). Server stores keys 3h (orders) / per docs.
- 3DS/SCA: EU PSD2 may return 422 Payer Action Required. Buyer must complete challenge. Vault setup can opt into 3DS verification.
- Webhook signature verification mandatory.
- Seller Protection, fraud/risk scoring, Level 2/3 data to lower costs/risks.
- Security Guidelines + Application Guidelines + Rate Limiting + Troubleshooting pages apply.

## 11. Settlement, fees, currency, refunds/disputes

- Capture -> PayPal business balance (near-instant post-capture). Withdraw to bank via standard/instant (timing varies).
- Braintree settlement models referenced for reconciliation: Gross (full amount daily, fees debited month-end) vs Net (fees deducted per txn, daily deposit net). For PayPal balance, think gross in balance minus fees shown in transaction.
- Fees deducted from business balance; need prefund for Payouts including fees. Cross-currency payouts auto-convert with conversion fees; response shows funding source/fees/details.
- Multi-currency: set `currency_code` per purchase_unit/payout item, separate calls per currency for payouts. Check country/feature support (Payouts 96 countries/24 currencies, Venmo US only).
- Refunds: full/partial via capture refund, 422/validation errors documented. Unclaimed payouts RETURNED in 30d.
- Disputes/chargebacks via Disputes API + dashboard; tracking info (`track`) helps seller protection.

Test with sandbox personal (buyer approve at `sandbox.paypal.com` checkout popup) + business (see money in), Postman collection, negative testing (Subscriptions test values), Webhooks simulator.

## 12. Minimal end-to-end example (sandbox)

```bash
# 1. token
curl -X POST https://api-m.sandbox.paypal.com/v1/oauth2/token \
 -u "CLIENT_ID:SECRET" -d "grant_type=client_credentials"

# 2. create order CAPTURE
curl -X POST https://api-m.sandbox.paypal.com/v2/checkout/orders \
 -H "Authorization: Bearer ACCESS" -H "Content-Type: application/json" \
 -d '{"intent":"CAPTURE","purchase_units":[{"amount":{"currency_code":"USD","value":"10.00"}}]}'
# -> {id: ORDER-ID, links: [approve...]}

# 3. buyer approves via approve link (sandbox personal login)

# 4. capture
curl -X POST https://api-m.sandbox.paypal.com/v2/checkout/orders/ORDER-ID/capture \
 -H "Authorization: Bearer ACCESS" -H "Content-Type: application/json" -d '{}'

# vault save (2-step)
# POST /v3/vault/setup-tokens -> setup token
# POST /v3/vault/payment-tokens {setup_token} -> permanent token 8kk8458
# payout batch
# POST /v1/payments/payouts {sender_batch_header:{sender_batch_id, recipient_type}, items:[{receiver, amount, sender_item_id}]}
```

## 13. Hackathon mapping (PayPal vs Stripe SPT discussion)

- Stripe SPT = scoped single-use grant (seller/amount/expiry) from saved `pm_xxx`, seller clones method and charges. PayPal has no single-use scoped grant; use Vault token (reusable) + per-order amount checks in your code + `DELETE` token to revoke.
- OTP/3DS: same rule. Saving Vault token does not bypass issuer/SCA. 422 payer-action-required = PayPal equivalent of SPT `requires_action`. Need `handleNextAction`-like approval UI (PayPal redirect/confirm-payment-source).
- Loaded wallet to avoid per-charge OTP: same pattern. Fund PayPal balance once, then Payouts/balance spends avoid per-charge card auth. Payouts = disbursement primitive for agent->many sellers (up to 15k/call by email/phone/ID).
- Biometric replay: same impossibility. Apple/Google Pay cryptograms one-time.
- For marketplace agent: use Partner Referrals + multiparty Orders (platform creates order for seller) + Vault for buyer + Payouts for sellers, vs Stripe Connect network ID + SPT.

## 14. Key URLs

- https://developer.paypal.com/
- https://developer.paypal.com/api/rest/ (get started, token, sandbox)
- https://developer.paypal.com/api/rest/integration/orders-api/ (flows)
- https://developer.paypal.com/docs/api/orders/v2 (reference, use .md / schema.json)
- https://developer.paypal.com/docs/api/payments/v2/ (auth/capture/refund)
- https://developer.paypal.com/docs/api/payment-tokens/v3 (vault)
- https://developer.paypal.com/sdk/js/set-up (JS v6)
- https://developer.paypal.com/api/rest/webhooks/ (overview, event-names, integrate)
- https://developer.paypal.com/docs/payouts/standard (+ integrate-api, customize)
- https://developer.paypal.com/docs/subscriptions (+ test-subscriptions)
- Postman: https://www.postman.com/paypal/paypal-public-api-workspace
- GitHub: https://github.com/paypal, v6 sample: https://github.com/paypal-examples/v6-web-sdk-sample-integration
- llms index: https://developer.paypal.com/llms.txt, AI docs: https://docs.paypal.ai/
