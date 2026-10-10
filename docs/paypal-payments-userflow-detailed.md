# PayPal Payments — Detailed Userflow with Examples

Date: 2026-10-09 | Dir: ~/paypalHackathon | Sources: developer.paypal.com (REST, Orders v2, Payments v2, Vault v3, Payouts, Webhooks, JS SDK v6)

## 1. Mental model

PayPal is wallet + ledger, not card-charge. Buyer approves on PayPal pages. Merchant backend creates Order, frontend SDK gets approval, backend captures. Money lands in merchant PayPal balance, then bank. Merchant never sees PAN — only opaque IDs: ORDER-ID, AUTH-ID, CAPTURE-ID, vault token 8kk....

Actors: Ana (buyer, personal sandbox), ShoeCo (seller, business sandbox), PayPal (auth/risk/ledger/settle). Platform MarketCo optional for multiparty.

Envs: sandbox `api-m.sandbox.paypal.com` + `sandbox.paypal.com/web-sdk/v6/core`; live `api-m.paypal.com` + `paypal.com/web-sdk/v6/core`. Auth: `CLIENT_ID:SECRET -> POST /v1/oauth2/token -> Bearer` (~31668s). Fastlane browser token separate (~15min).

## 2. Main userflow — $10 shoes, CAPTURE

### Step 0. Setup (once)

Merchant Dashboard > Apps & Credentials > copy Client ID + Secret. Sandbox Accounts: note personal (buyer login) + business (seller dashboard). Subscribe webhook `PAYMENT.CAPTURE.COMPLETED` to `https://shoeco.com/webhooks/paypal` (≤10 URLs/app, `*` or specific, app-scoped).

### Step 1. Ana shops

Ana browses ShoeCo, adds shoes to cart, goes to checkout, sees PayPal button. Frontend already ran:

```js
const sdk = await window.paypal.createInstance({clientId:"CLIENT_ID", components:["paypal-payments"], pageType:"checkout"});
const m = await sdk.findEligibleMethods({currencyCode:"USD"});
if (m.isEligible("paypal")) showButton();
```

This gates on location/currency/device/account. No button if ineligible.

### Step 2. Create order (backend, server-side only)

Frontend calls backend, backend computes total from DB (never trust browser):

```bash
curl -X POST https://api-m.sandbox.paypal.com/v2/checkout/orders \
 -H "Authorization: Bearer ACCESS" -H "Content-Type: application/json" \
 -d '{"intent":"CAPTURE","purchase_units":[{"reference_id":"shoes-001","description":"Running shoes","amount":{"currency_code":"USD","value":"10.00","breakdown":{"item_total":{"currency_code":"USD","value":"10.00"}}},"items":[{"name":"Running shoes","quantity":"1","unit_amount":{"currency_code":"USD","value":"10.00"}}],"shipping":{"address":{"address_line_1":"123 Main","admin_area_2":"San Jose","admin_area_1":"CA","postal_code":"95131","country_code":"US"}}}]}'
```

Response:

```json
{"id":"5O190127TN364715T","status":"CREATED","intent":"CAPTURE","purchase_units":[{...}],"links":[{"rel":"approve","href":"https://www.sandbox.paypal.com/checkoutnow?token=5O190127TN364715T"},{"rel":"self","href":"https://api-m.sandbox.paypal.com/v2/checkout/orders/5O190127TN364715T"}]}
```

Backend returns `{orderId:"5O190127TN364715T"}` to frontend `createOrder()`. Note: v6 must return `{orderId}`, not raw string.

### Step 3. Approve (Ana on PayPal)

```js
const session = sdk.createPayPalOneTimePaymentSession({onApprove, onCancel, onError});
btn.addEventListener("click", ()=> session.start({presentationMode:"auto"}, createOrder()));
async function onApprove(data){ await fetch("/api/capture",{method:"POST",body:JSON.stringify({orderId:data.orderId})}); }
```

Popup/redirect to approveUrl. Ana logs in with sandbox personal, picks funding (balance → bank → card → Pay Later), reviews $10 to ShoeCo, clicks Pay Now. Order → `APPROVED`. If issuer/SCA needs OTP/3DS (EU/IN cards), she completes challenge here via `confirm-payment-source` / redirect. Cancel → `onCancel`, error → `onError`.

### Step 4. Capture (backend)

```bash
curl -X POST https://api-m.sandbox.paypal.com/v2/checkout/orders/5O190127TN364715T/capture \
 -H "Authorization: Bearer ACCESS" -H "Content-Type: application/json" \
 -H "PayPal-Request-Id: 123e4567-e89b-12d3-a456-426655440040" -d '{}'
```

Response (trimmed):

```json
{"id":"5O190127TN364715T","status":"COMPLETED","purchase_units":[{"payments":{"captures":[{"id":"9XR60255JN8591232","status":"COMPLETED","amount":{"currency_code":"USD","value":"10.00"},"seller_protection":{"status":"ELIGIBLE","dispute_categories":["ITEM_NOT_RECEIVED","UNAUTHORIZED_TRANSACTION"]},"seller_receivable_breakdown":{"gross_amount":{"currency_code":"USD","value":"10.00"},"paypal_fee":{"currency_code":"USD","value":"0.59"},"net_amount":{"currency_code":"USD","value":"9.41"}},"disbursement_mode":"INSTANT"}]}}]}
```

$10 in ShoeCo balance instantly (minus fee). Statement shows ShoeCo, not agent. `PayPal-Request-Id` prevents double-charge on retry — reuse same UUID within window, `GET` reconcile before retry.

Failures: `INSTRUMENT_DECLINED` → `actions.restart()` let Ana pick new funding; `422 Payer Action Required` → SCA challenge; `422 intent mismatch` → wrong verb for intent; `422 breakdown` → `value != sum(breakdown)` (recompute server-side).

### Step 5. Webhook + fulfill

PayPal POSTs to listener:

```json
{"id":"WH-123","event_type":"PAYMENT.CAPTURE.COMPLETED","resource":{"id":"9XR60255JN8591232","status":"COMPLETED","amount":{"currency_code":"USD","value":"10.00"},"supplementary_data":{}}}
```

Handler: respond 2xx fast (else 25 retries/3d → Failed, manual resend), verify (CRC32+sig locally OR `POST /v1/notifications/verify-webhook-signature`), dedupe by `event_id/resource.id`, `GET /v2/payments/captures/9XR...` reconcile, then fulfill.

ShoeCo ships, adds tracking (helps Seller Protection):

```bash
curl -X POST https://api-m.sandbox.paypal.com/v2/checkout/orders/5O190127TN364715T/track \
 -H "Authorization: Bearer ACCESS" -H "Content-Type: application/json" \
 -d '{"trackers":[{"transaction_id":"9XR60255JN8591232","tracking_number":"1Z123","carrier":"UPS"}]}'
```

Ana gets confirmation. ShoeCo withdraws balance to bank (standard/instant, timing varies).

```mermaid
flowchart TD
  A[Ana: cart + PayPal click] --> B[Backend: POST /v2/checkout/orders CAPTURE]
  B --> C[Frontend: session.start + createOrder->{orderId}]
  C --> D[Ana approves on PayPal + SCA if needed]
  D --> E[onApprove orderId -> backend POST .../capture]
  E --> F[COMPLETED + balance + webhook]
  F --> G[Verify webhook + ship + track + withdraw]
```

## 3. Variation A — AUTHORIZE hold then capture (ship-later)

Same steps 1-3 but `intent:AUTHORIZE`. After approval:

```bash
curl -X POST https://api-m.sandbox.paypal.com/v2/checkout/orders/ORDER-ID/authorize -H "Authorization: Bearer ACCESS" -H "Content-Type: application/json" -d '{}'
# -> {purchase_units:[{payments:{authorizations:[{id:"AUTH-ID", status:"CREATED", expiration_time:"...29d"}]}}]}
```

Hold 29d, capture best in 3d honor. Later:

```bash
curl -X POST https://api-m.sandbox.paypal.com/v2/payments/authorizations/AUTH-ID/capture \
 -H "Authorization: Bearer ACCESS" -H "Content-Type: application/json" \
 -d '{"amount":{"currency_code":"USD","value":"9.00"},"invoice_id":"INV-1","final_capture":true}'
```

Partial $9 + `final_capture:true` releases $1, closes order. Rules: open auths ≤115%/+$75, ≤10/order (99 via support). Past honor <29d → `POST .../reauthorize` (new AUTH-ID, capture new ID; day-27 reauth = 2d left). Past 29d → new order. Cancel → `POST .../void`.

Use when: stock check, custom build, variable final amount.

## 4. Variation B — Saved/agent repeat (Vault, no re-login)

First purchase Ana consents to save. Backend:

```bash
curl -X POST https://api-m.sandbox.paypal.com/v3/vault/setup-tokens -H "Authorization: Bearer ACCESS" -H "Content-Type: application/json" -d '{"payment_source":{"paypal":{"description":"Save for ShopBot","usage_pattern":"UNSCHEDULED_PREPAID","usage_type":"MERCHANT"}},"customer":{"id":"ana-123"}}'
# approve on PayPal token page
curl -X POST https://api-m.sandbox.paypal.com/v3/vault/payment-tokens -H "Authorization: Bearer ACCESS" -H "Content-Type: application/json" -d '{"payment_source":{"setup_token":"SETUP-ID"}}'
# -> {id:"8kk8458...", status:"ACTIVE"}
```

Next $20 ShopB purchase, agent checks budget/allowlist/expiry itself then:

```bash
curl -X POST https://api-m.sandbox.paypal.com/v2/checkout/orders -H "Authorization: Bearer ACCESS" -H "Content-Type: application/json" -H "PayPal-Request-Id: UUID" -d '{"intent":"CAPTURE","purchase_units":[{"amount":{"currency_code":"USD","value":"20.00"},"payee":{"email_address":"shopb@example.com"}}],"payment_source":{"paypal":{"vault_id":"8kk8458..."}}}'
```

No redirect if issuer approves. If `422 Payer Action Required` (ICICI/EU), show challenge — saving does not bypass OTP. Revoke via `DELETE /v3/vault/payment-tokens/8kk...` → `VAULT.PAYMENT-TOKEN.DELETED`. This is PayPal analog of Stripe `pm_xxx` reuse (Vault) vs `spt_xxx` per-purchase (nonce/order).

## 5. Variation C — Marketplace disburse (Payouts)

MarketCo collects $10 then pays ShoeCo + others without each approving order. Prefund balance + fees, needs business + Payouts access + identity/email/bank confirmed:

```bash
curl -X POST https://api-m.sandbox.paypal.com/v1/payments/payouts -H "Authorization: Bearer ACCESS" -H "Content-Type: application/json" -H "PayPal-Request-Id: REQ" -d '{"sender_batch_header":{"sender_batch_id":"batch-001","recipient_type":"EMAIL","email_subject":"You have money!"},"items":[{"amount":{"value":"9.00","currency":"USD"},"sender_item_id":"s1","recipient_wallet":"PAYPAL","receiver":"shoeco@example.com"}]}'
# -> {batch_header:{payout_batch_id:"Y4JB...", batch_status:"PENDING"}}
```

Track `GET .../payouts/Y4JB...` + `GET .../payouts-item/ITEM-ID` (`SUCCESS/PENDING/UNCLAIMED/RETURNED/FAILED`). Venmo: `recipient_wallet:VENMO` + US mobile + note, USD only. Claim 30d else RETURNED. Up to 15k/call, separate call per currency. Webhooks `PAYMENT.PAYOUTSBATCH.*` + `PAYOUTS-ITEM.*`.

## 6. Refunds, disputes, subscriptions

Refund full/partial:

```bash
curl -X POST https://api-m.sandbox.paypal.com/v2/payments/captures/9XR60255JN8591232/refund -H "Authorization: Bearer ACCESS" -H "Content-Type: application/json" -d '{"amount":{"currency_code":"USD","value":"5.00"},"note_to_payer":"out of stock"}'
```

Disputes via Disputes API + dashboard; tracking preserves Seller Protection. Subscriptions: Product → Plan (fixed/volume/tier, trial) → Subscription via button + API, cycles fire `PAYMENT.SALE.COMPLETED`, manage activate/suspend/cancel/revise. Invoicing/Links/Buttons for no-code.

## 7. Testing checklist

- Personal approve at sandbox popup, business see money minus fee.
- Card generator + rejection triggers, `PayPal-Mock-Response` negatives (`INSTRUMENT_DECLINED`, 422).
- Postman public workspace, Webhooks Simulator + Events dashboard, Subscriptions autopay pages.
- Never trust browser totals, validate server-side; verify every webhook; idempotent handlers (`event_id`, `PayPal-Request-Id`, `sender_batch_id`).

Key URLs: /api/rest, /docs/api/orders/v2, /docs/api/payments/v2, /docs/api/payment-tokens/v3, /sdk/js/set-up, /api/rest/webhooks (+/event-names), /docs/payouts/standard, /docs/subscriptions, /platforms/checkout/standard/integrate, Postman, github paypal + v6 sample, llms.txt.
