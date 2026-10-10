# PayPal as x402 Payment Scheme / Facilitator — Discussion Doc

Date: 2026-10-08
Dir: ~/paypalHackathon
Thread: Can we make PayPal a payment scheme/facilitator for x402 with minimal config? + MPP comparison + scope

## 0. Goal

Make PayPal a first-class x402 payment option so an ordinary API provider adds PayPal-backed pay-per-call with only `payTo + price` config, no Braintree / OAuth / webhook code. Facilitator does `verify()` + `settle()` and routes to PayPal at the end for both PayPal-wallet (PayPalMe-style) and cards sources.

## 1. x402 primer (Coinbase open protocol)

- Flow: `Client GET -> Server 402 + PAYMENT-REQUIRED {scheme, network, asset, maxAmountRequired, payTo, resource} -> Client signs -> retry with PAYMENT-SIGNATURE -> Server POST /verify + /settle to facilitator -> 200 + resource`.
- Facilitator handles verify + settle so seller needs no chain/bank infra. CDP facilitator covers `scheme:exact` on Base/Polygon/Arbitrum/Solana with USDC. Protocol is open + permissionless — anyone can run a facilitator for a new scheme.
- APIs: `POST /v2/x402/verify`, `POST /v2/x402/settle` with `{x402Version, paymentPayload, paymentRequirements}`.
- Refs: x402 whitepaper (402 revival, no API keys/subs, ~1s stablecoin), docs.cdp.coinbase.com/x402, x402.org FAQ, payai.network reference.

## 2. Two PayPal-backed paths

### Option A — PYUSD-native (minimal, headless, recommended first)

PYUSD is ERC-20. Same DX as USDC, only asset changes:

```js
"GET /api/data": {price:"$0.01", network:"base", asset:"PYUSD-contract", payTo:"0xMerchant"}
```

Buyer pays PYUSD from PayPal balance/wallet, facilitator settles onchain. Provider sets only `payTo`. No PayPal OAuth, no PCI, no approval popup. PayPal-backed x402 in ~3 lines.

### Option B — PayPal-fiat scheme (custom scheme for wallet + cards off-chain)

Define:

```json
{
 "scheme": "paypal-exact",
 "network": "paypal-sandbox",
 "asset": "USD",
 "maxAmountRequired": "10000",
 "payTo": "merchant@example.com",
 "resource": "https://api.example.com/premium-data",
 "extra": {"approveUrl": "https://paypal.com/checkout/...", "orderId": "ORDER-ID"}
}
```

Facilitator implements:

- `/verify {paymentPayload:{orderId|vaultToken|nonce}, paymentRequirements}`: `GET /v2/checkout/orders/{id}`, check `status APPROVED/COMPLETED`, `amount == maxAmountRequired`, `payee == payTo`, not expired/reused. Return `{isValid, payer}`. No money moves.
- `/settle {...}`: `POST /v2/checkout/orders/{id}/capture` (wallet + cards via PayPal Checkout) OR Braintree `transaction.sale(nonce)` for ACP/UCP nonces OR charge `vault_id 8kk...` for saved methods. Idempotency via `PayPal-Request-Id` + `checkout_session_id/orderId` dedupe + `GET` reconcile. Return receipt `{captureId, status, net}`. Webhooks `PAYMENT.CAPTURE.COMPLETED`, refunds via `POST /v2/payments/captures/{id}/refund`.

## 3. Minimal config for ordinary API provider

```js
import { paymentMiddleware } from "paypal-x402-express";

app.use(paymentMiddleware({
  facilitatorUrl: "https://paypal-x402.yourdomain.com",
  payTo: "merchant@example.com",
  routes: {
    "GET /api/data": {price:"$0.01", currency:"USD", description:"premium data"},
    "POST /api/analyze": {price:"$0.10", currency:"USD"}
  }
}));
```

Provider never holds `CLIENT_ID/SECRET`. Only `payTo + price`. Facilitator holds keys, does `POST /v1/oauth2/token`, verify/capture, Vault, Payouts, refunds, webhook verify (CRC32 or verify-signature endpoint).

PCI note from x402 whitepaper: no PCI for provider unless facilitator takes cards directly — PayPal facilitator keeps provider out of scope.

## 4. Buyer-approval catch (why Vault/balance matters)

Stablecoin x402 is stateless-signed. PayPal fiat is not:

- Interactive: first `402` returns `approveUrl`. Human/agent browser approves on PayPal (login + SCA/OTP — e.g. ICICI every time). Retry with `orderId`. Works for humans, breaks headless autonomy.
- Autonomous: pre-vault once (`POST /v3/vault/setup-tokens` -> `payment-tokens 8kk...` with consent + 3DS). Later x402 retries use vault token silently unless issuer step-up `422` -> fallback interactive. Zero-touch repeat without OTP only via pre-funded PayPal balance then capture/Payouts from balance (loaded-wallet pattern from SPT thread). Payouts `POST /v1/payments/payouts` fits agent-to-many-sellers.

Rule carried from prior docs: saving Vault/PM does not bypass OTP; biometric/Google/Apple cryptograms one-time, no replay.

## 5. Is MPP for PayPal almost same as this? Yes

MPP = Machine Payments Protocol (Stripe: `POST -> 402 challenge -> retry with credential -> receipt`, `mppx`, `stripe/charge`, `link-cli` SPT). PayPal x402 facilitator is same pattern, different rail:

| | Stripe MPP | PayPal x402 |
|---|---|---|
| Challenge | `paymentLink` / `stripe/charge {amount,currency,networkId}` | `PAYMENT-REQUIRED {paypal-exact, paypal, USD, maxAmount, payTo}` |
| Credential | `spt_xxx` scoped `profile/currency/max/expires` single-use | ACP `tokencc_...` / UCP `androidPayCards nonce` / Vault `8kk` / `ORDER-ID`, single-use except Vault |
| Facilitator | `spt.charge() + PI confirm granted_token`, clone `pm_xxx->pm_yyy`, Connect split | `GET orders` verify + `capture` / Braintree `sale`, Payouts split |
| Step-up | `requires_action` + `handleNextAction` | `422` + `confirm-payment-source` / `91565/915266/915267` |

PayPal ACP/UCP **is** PayPal's MPP already — delegated single-use nonce bound to `merchant_id/max_amount/currency/expires_at`. x402 wrapper just speaks same HTTP standard so any x402 client can pay a PayPal API.

## 6. Current limitation removed: Braintree -> just receiver

Today Agent Ready ACP/UCP requires Braintree MID + tokenization key + `transaction.sale` + UCP registration + Store Sync catalog/cart. Ordinary seller blocked.

With wrapper merchant = just PayPal receiver (business email/ID + price):

- Generic API monetization: no Braintree at all. Facilitator uses pure Orders v2/Vault/Payouts. Merchant only has PayPal business account.
- ChatGPT/Google surfaces: PayPal still mandates Braintree MID for nonce issuance, but facilitator owns that Braintree account, redeems nonce, forwards net via Payouts/multiparty `payee`. Merchant still just receiver; facilitator acts as platform.

## 7. Why generic API monetization is win-win

PayPal: TAM from large Braintree merchants to any API seller; routes x402 volume to PayPal rails (Orders/Vault/balance/PYUSD) vs losing to Coinbase facilitator; reuses Seller/Purchase Protection + fraud + disputes (crypto x402 has none).

Hackathon: novelty (PayPal as x402 scheme does not exist), 5-line middleware story, agent loop (Claude pays autonomously via Vault/balance after one approval), extensible to ACP/UCP later via same facilitator. Fastest demo: `curl -> 402 -> pay -> 200` with PayPal receipt.

## 8. Build scope (hackathon)

Main goal = facilitator `verify()` + `settle()` + route to PayPal for both PayPalMe/wallet and cards:

- `verify()`: amount/currency/payee/expiry/reuse checks, no money.
- `settle()`: capture order / charge vault / Braintree sale, idempotent, receipt + webhooks.
- Sources: wallet (`payment_source: paypal`, PayPalMe-style login/balance/bank) + cards (`payment_source: card` via Advanced Checkout/Braintree, Vault `setup -> payment-tokens` for repeat).
- Plus thin `paymentMiddleware(payTo, routes)` + docs + sandbox demo (personal approve + business receive + `link-cli`-style approval log).

Out of scope: full Store Sync catalog, subscription/digital/non-US, live Payouts approval, production fees/FX table.
