# Shared Payment Tokens (SPTs) - Complete Discussion Notes

Location: `~/paypalHackathon`
Source: Stripe Agentic Commerce docs - Shared Payment Tokens (Seller / Agent / Connect variants)
API Version: `2026-09-30.preview`
Date: 2026-10-08

## 1. What are SPTs

Shared Payment Tokens grant sellers scoped access to a customer's payment method for agent-initiated purchases.

Three parties:
- Customer - owns card / wallet
- Agent - AI assistant that shops, collects PaymentMethod, issues SPT
- Seller / Connect Platform - receives SPT, creates PaymentIntent, gets paid

SPT ID format: `spt_xxx`

Available to agents, customers, sellers in US, CA, GB + select EU:
AT, BE, BG, CA, CH, CY, CZ, DE, DK, EE, ES, FI, FR, GB, GI, GR, HR, HU, IE, IT, LI, LT, LU, LV, MT, NL, NO, PL, PT, RO, SE, SI, SK, US.
Note: IN (India) not supported.

By using SPTs, parties agree to Stripe SSA service terms for agent/seller services preview.

## 2. High-level flow

```
[Customer] -- submits PaymentMethod (pm_xxx) --> [Agent]
[Agent] -- issues SharedPaymentToken (spt_xxx) scoped to seller profile --> [Stripe]
[Agent] -- sends spt_xxx --> [Seller]
[Seller] -- creates PaymentIntent with spt_xxx --> [Stripe]
[Stripe] -- processes transaction (auth/capture/settle) --> [Customer charged, Seller paid]
```

For Connect:
```
[Platform] -- provides network ID (profile_xxx) --> [Agent]
[Customer] -- submits PM --> [Agent]
[Agent] -- issues SPT for network ID --> [Stripe]
[Agent] -- sends SPT --> [Platform]
[Platform] -- creates PaymentIntent with SPT --> [Stripe]
[Platform] -- confirms checkout --> [Agent] --> [Customer]
```

Network ID = Stripe profile ID passed as `seller_details[network_business_profile]` at issuance.

## 3. Roles in detail

### 3.1 Agent - issues SPTs

Prereqs: Stripe account + Stripe profile in Dashboard.

Steps:
1. Collect seller Stripe profile (`profile_xxx`). Test seller: `profile_test_61TU90nIeGjU7NNVXA6TU90m7ISQWsBxpcx9lASWWXTk`.
2. Collect customer payment via Payment Element with `mode=payment`, `amount`, `currency`, `paymentMethodCreation=manual`, `sellerDetails.networkBusinessProfile`. This ensures Stripe shows only payment methods seller supports. Checkout URL must be `https://`.
3. Create PaymentMethod via `stripe.preparePaymentMethod({elements, params})` -> `pm_xxx`. Send to backend.
4. Backend creates IssuedToken:
```
POST /v1/shared_payment/issued_tokens
  payment_method=pm_xxx
  seller_details[network_business_profile]=profile_xxx
  usage_limits[currency]=usd
  usage_limits[max_amount]=1000
  usage_limits[expires_at]=1798761600
  return_url=https://example.com/agent-checkout/return
```
Returns `spt_xxx` with `status` (`active` or `requires_action`) and `next_action` if needed.
5. Send `spt.id` to seller via order API.
6. Handle next actions if `requires_action`: retrieve token, call `stripe.handleNextAction({hashedValue: next_action.use_stripe_sdk.value})` on client. After success, webhook `issued_token.active`.
7. Can revoke anytime: `POST /v1/shared_payment/issued_tokens/spt_xxx/revoke`.

Supported methods: Cards, Link, Apple Pay, Google Pay, Klarna, Affirm (limited, with marketing + UI navigation restrictions). For cards Stripe may provision Mastercard Agent Pay / Visa Intelligent Commerce network tokens.

SPT state machine (issued_token.status):
- `active`: ready for agent to pass or seller to use -> can go to `requires_action`, `deactivated`
- `requires_action`: needs customer auth (3DS, redirect). Has `next_action` -> can go to `active`, `deactivated`
- `deactivated`: terminal. `deactivated_reason` = consumed / expired / revoked.

Agent webhooks:
- `shared_payment.issued_token.requires_action`
- `shared_payment.issued_token.active`
- `shared_payment.issued_token.used` (seller used it)
- `shared_payment.issued_token.deactivated`

### 3.2 Seller - receives / uses SPTs

Prereqs: Stripe account + Stripe profile.

Test receiving without agent:
```
POST /v1/test_helpers/shared_payment/granted_tokens
  payment_method=pm_card_visa
  usage_limits[currency]=usd
  usage_limits[max_amount]=1000
  usage_limits[expires_at]=1798761600
```

Live test: use `link-cli`:
```bash
npx @stripe/link-cli auth login
npx @stripe/link-cli payment-methods list
npx @stripe/link-cli spend-request create \
  --payment-method-id csmrpd_xxx \
  --context "Test purchase..." \
  --amount 100 \
  --credential-type shared_payment_token \
  --network-id profile_xxx \
  --request-approval
```

Use SPT:
```
POST /v1/payment_intents
  amount=1000 currency=usd
  payment_method_data[shared_payment_granted_token]=spt_123
  confirm=true
```
On confirm, Stripe sets `payment_method` to new cloned PaymentMethod. Refunds/reporting behave as if seller provided PM directly.

Retrieve grant:
```
GET /v1/shared_payment/granted_tokens/spt_123
```
Shows limited `payment_method_details.card.brand/last4` + `usage_limits`. No full PAN, no source PM ID.

Seller webhook:
- `shared_payment.granted_token.deactivated` (consumed/expired/revoked, can no longer use)

### 3.3 Connect platforms

SPTs provide credential + Stripe payment semantics only. They do NOT define discovery, catalog, inventory, carts, tax, shipping, order state, fulfillment. Platform must expose these to agent via its own endpoint/tool contract. Alternative managed options: Sell through agents as SaaS platform, Monetize MCP server.

Network ID choice by charge type:
- Direct charge: connected account profile
- Destination charge: platform profile (with `on_behalf_of`, either works, recommend connected account profile)
- Separate charges and transfers: platform profile (with `on_behalf_of`, either works, recommend connected)

Creating connected-account profiles requires Stripe enablement (via rep/support), then Dashboard > Connected accounts > select account > Profile > View all > Stripe profile > Create profile.

Use: standard PaymentIntent for charge type (direct/destination/separate) + `-d payment_method_data[shared_payment_granted_token]=spt_123`. Add `-H "Stripe-Account: {{CONNECTED_ACCOUNT_ID}}"` when SPT granted to connected account. Omit header when granted to platform.

Testing/webhooks same as Sellers tab.

## 4. How SPTs differ from traditional payments

Traditional:
1. Customer gives PM directly to seller.
2. Seller creates/confirms PaymentIntent with that PM.
3. Customer present on seller checkout. Seller handles 3DS, risk, PCI.

SPT:
1. Customer gives PM to agent once.
2. Agent creates scoped slip for one seller/amount/expiry.
3. Seller redeems slip, never sees real card.

Differences:
- 3 parties vs 2. Collection (agent) decoupled from processing (seller).
- No raw credential sharing. Seller sees brand/last4/limits only.
- Scoped + short-lived. Locked to one profile, currency, max_amount, expires_at. Single-use -> deactivated. Traditional saved PMs are long-lived reusable.
- Revocable by agent.
- Auth handled by agent via handleNextAction.
- Network agent programs (Visa Intelligent Commerce, Mastercard Agent Pay) may be used behind SPT.
- Two views same ID: issued_token (agent full control) vs granted_token (seller limited).

Analogy: traditional = give master key to each shop. SPT = assistant makes single-use hotel key card for one shop, one amount, one hour.

## 5. Example implementation (test mode)

### Agent frontend
- Stripe.js from `https://js.stripe.com/endive/stripe.js`
- `stripe.elements({mode:'payment', amount:1000, currency:'usd', paymentMethodCreation:'manual', sellerDetails:{networkBusinessProfile:'profile_test_...'}})`
- Mount `elements.create('payment')`
- On submit: `elements.submit()`, then `stripe.preparePaymentMethod()`, then `POST /create-spt {paymentMethodId}`.
- `handleServerResponse`: if `requires_action`, call `stripe.handleNextAction({hashedValue})`.

### Agent backend (Node/Express)
- `stripe.sharedPayment.issuedTokens.create({payment_method, seller_details:{network_business_profile}, usage_limits:{currency, max_amount, expires_at}, return_url})`
- Return `{sptId, status, next_action}`. Forward `sptId` to seller.

### Seller backend
- `sellerStripe.paymentIntents.create({amount:1000, currency:'usd', confirm:true, payment_method_data:{shared_payment_granted_token: sptId}})`
- Test without agent via `/test_helpers/shared_payment/granted_tokens` with `pm_card_visa`.

Full code samples were walked through in chat (Payment Element + /create-spt + /pay + webhooks).

## 6. Deep dive: how Stripe takes money with SPT

SPT itself moves no money. Money moves on seller's PaymentIntent.

1. Agent holds source PM `pm_xxx` on agent account.
2. Agent locks it: Stripe stores `spt_123 -> {source pm_xxx, allowed seller profile_xxx, limits}`. May provision network token. Status `active`. Creates issued view (agent) + granted view (seller).
3. Seller redeems: `POST /payment_intents` with `shared_payment_granted_token=spt_123`. Stripe validates: caller == grantee, not deactivated, currency match, amount <= max_amount, not expired.
4. Stripe clones: copies `pm_xxx` to seller account as new `pm_yyy`, attaches to PaymentIntent. This clone is used for refunds/disputes/reporting. Seller keeps `pm_yyy`, not customer card.
5. Normal processing: PaymentIntent -> auth to issuer via network -> capture -> settle to seller balance. Descriptor shows seller, not agent. Agent never holds funds. If issuer wants 3DS, SPT flips to `requires_action` with `next_action.use_stripe_sdk.value`, agent completes with customer, flips to `active`, payment continues.
6. Token dies: `deactivated: consumed|expired|revoked`. Seller gets `granted_token.deactivated`, agent gets `issued_token.used/deactivated`. Cloned `pm_yyy` remains for refund, but `spt_123` cannot charge again.

## 7. Q&A covered

### Does agent need new PaymentMethod every purchase?
No. PM reusable, SPT single-use.
- PM = card on file (create once, reuse `pm_xxx`).
- SPT = one slip per purchase (new `spt_xxx` per seller/amount/expiry, dies after use).
Repeat flow: reuse `pm_xxx` -> issue new `spt_xxx` -> seller charges. Re-collect PM only if wallet needs tap, 3DS needs fresh auth, card expired, or agent deleted it.

### Is creating PM like giving capped access?
No. PM has no cap. Cap lives on SPT `usage_limits`.
Stripe enforces no global cap across multiple SPTs from one PM. Agent could issue many SPTs. Total budget ("spend up to $100 without asking") must be built in agent logic. Human still needed when agent policy requires approval, bank asks 3DS, wallet asks biometric, or PM revoked.

### Does agent need my approval each time (Visa example)?
Depends on bank + agent build. Stripe does not force approval per SPT.
- No-approval case: saved PM, low-risk US $10, issuer approves no 3DS. Money moves, only receipt after.
- Approval case: issuer asks 3DS (common EU PSD2, high amount, new shop). SPT `requires_action`, agent shows bank popup, waits.
Agent is responsible for getting instruction ("buy shoes"). Good pattern: ask "Buy $10 Shop A? [Approve]", allow auto-approve under $20, always handle 3DS popup.

### ICICI card that always needs OTP for manual payment - does agent need intervention?
Yes, every time.
OTP is decided by ICICI at charge time, not save time. Saving PM does not remove OTP. Each seller charge is new charge -> new OTP -> SPT `requires_action` -> agent shows OTP popup. One SPT OTP does not cover next SPT. Plus IN not in SPT supported countries today.

### Can wallet at Stripe level bypass OTP by saving pm_xxx via wallet?
No. Issuer checks charge, not storage location. Saving same ICICI PM in wallet still = ICICI charge = still OTP.
Wallet helps only if it changes method:
- Pre-funded balance: load $100 once with OTP, later spends from balance (not ICICI). Then SPT not used.
- Network token + device auth: Apple Pay/Google Pay/Link biometric counts as 2FA, bank may accept instead of OTP. That is alternative 2FA, not bypass.
For India repeat without OTP, legal path is RBI e-mandate (first OTP + mandate registration, auto-debit up to limit). SPT is not e-mandate.

### Can we store biometric token and reuse it?
No. Biometric never leaves device. It unlocks secure chip locally. Chip creates one-time cryptogram bound to amount/seller/time. Replaying old cryptogram for new purchase fails at network/issuer. Attempted replay = decline + compliance violation.

### So only loaded wallet helps bypass human touch?
Yes, only decoupling helps.
Card per purchase = bank sees new charge = can ask OTP.
Loaded wallet = bank sees one load, later spends from balance = no new OTP.
Still needs human for: load (OTP once), wallet KYC/PPI rules (IN), and telling agent what to buy. Removing bank OTP does not remove need for user consent. SPT not used for balance spends.

## 8. Key takeaways for hackathon

- Use SPT for agent-to-seller card delegation with per-transaction scoping.
- PM once, SPT per purchase.
- Enforce budget + approval in agent code; Stripe only enforces per-SPT limits.
- Expect `requires_action` for IN / EU cards; build `handleNextAction` + webhooks.
- For zero-touch repeat (no OTP), need pre-funded wallet/balance model, not SPT replay. That changes product from "charge card via SPT" to "top-up wallet then spend balance".
- Do not attempt to cache/replay OTPs or cryptograms; will fail and violates scheme/RBI rules.

## 9. References

- Sellers: https://docs.stripe.com/agentic-commerce/concepts/shared-payment-tokens.md?agent-seller=seller
- Agents: https://docs.stripe.com/agentic-commerce/concepts/shared-payment-tokens.md?agent-seller=agent
- Connect: https://docs.stripe.com/agentic-commerce/concepts/shared-payment-tokens.md?agent-seller=connect
- API: SharedPayment Granted Token, Issued Token
- Test helpers: `/v1/test_helpers/shared_payment/granted_tokens`
- Link CLI: https://link.com/agents
