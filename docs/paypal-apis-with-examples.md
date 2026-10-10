# PayPal APIs with Examples — Deep Research

> Task ses_ee0d8dedaffermOv9iyc6KL9wD, verified 2026-10-09. Full output in task history.

This file condenses the deep-researcher output. See task history for full curl/Node/JSON per API.

## Coverage

- OAuth2: `-u CLIENT_ID:SECRET grant_type=client_credentials` → Bearer `expires_in:31668`; Fastlane `clientToken` via `domains[]+response_type=client_token` (~15min). Hosts sandbox `api-m.sandbox.paypal.com` vs live `api-m.paypal.com`.
- Orders v2: 7 endpoints (create/show/patch/confirm/authorize/capture/track) + tracker PATCH. `CAPTURE` vs `AUTHORIZE`, 29d validity, `PayPal-Request-Id` for single-step with payment_source. Capture response has `seller_protection` + `seller_receivable_breakdown{gross,fee,net}`. Errors 422 intent-mismatch / payer-action-required, INSTRUMENT_DECLINED→restart.
- Payments v2: auth get/capture/reauthorize(void)/void, captures get/refund, refunds get, find-eligible-methods. 29d hold + 3d honor, ≤10 auths, ≤115%/+$75, reauth=new ID, final_capture closes.
- Vault v3: `POST setup-tokens` (verification_method, description, usage_pattern/type, permit_multiple) → approve → `POST payment-tokens` → `8kk...` + self/delete links. Reuse via `payment_source.vault_id` + budget guard. `DELETE` revokes. 422 still on SCA.
- Subscriptions: product→plan→subscription + activate/suspend/cancel/revise/capture, webhooks SALE.COMPLETED + BILLING.*.
- Payouts: `POST /v1/payments/payouts {sender_batch_header:{sender_batch_id}, items[]}` → batch PENDING, track batch/item, Venmo US/USD+note+mobile, 15k Std vs Large Batch SFTP, 30d claim else RETURNED. Needs business + access + identity/email/bank + prefund.
- Multiparty: Partner Referrals onboard, order with `payee{merchant_id}` + `platform_fees`, headers `PayPal-Partner-Attribution-Id: BN_CODE` + `PayPal-Auth-Assertion: JWT{iss,payer_id}`.
- Webhooks: ≤10 URLs/app app-scoped, 2xx else 25x/3d + resend, verify CRC32 or verify-signature endpoint, events CHECKOUT/PAYMENT/SALE/PAYOUTS/VAULT/BILLING.
- JS SDK v6: `web-sdk/v6/core`, `createInstance({clientId|clientToken, components, pageType, locale})`, `findEligibleMethods`, `createPayPalOneTimePaymentSession({onApprove,onCancel,onError})`, `start({presentationMode:auto}, createOrder())`, `createOrder()->{orderId}`.
- ACP: `requestCheckout({payment_provider:{braintree,MID,[card,applepay,googlepay]}, ready_for_payment, currency, totals[cents], live/test})` → MCP `complete_checkout(tokencc_...)` → `transaction.sale(nonce)`. Allowance 91565/915266/915267, facilitator_details=ChatGPT.
- UCP: handler `tokenizationSpecification{gateway:braintree,...MID/clientKey}` → `/checkout-session/{id}/complete` → parse `androidPayCards[0].nonce` → sale. Single-use 3h, no charge+vault same nonce.
- Store Sync: merchant `POST /merchant-cart`, `PUT /{id}` full-replace, `POST /{id}/checkout`; Orders pattern vs Braintree pattern; JWT via paypal.ai jwks; VALID/INVALID/REQUIRES_INFO + validation_issues; physical/US/USD only.
- Toolkit/MCP: `@paypal/agent-toolkit` + `@paypal/mcp --tools=all`, Bedrock/CrewAI/LangChain/MCP/OpenAI/Vercel, tools orders/invoices/products/plans/shipment/disputes/reporting + remote search/create/checkout_cart.

## x402 mapping

verify()=GET order (status APPROVED/COMPLETED + amount==max + payee==payTo + !expired/reused); settle()=capture/sale/vault idempotent + receipt + CAPTURE webhook. Provider collapses to payTo+price.

Full URLs, curl/Node/response snippets, Mermaid linked flow in task output. Re-verify authorize limits + Payouts approval at build.
