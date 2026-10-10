# PayPal Autonomous vs MPP + Stripe (card) — Comparison

> Deep-researcher task ses_ee46e4a54ffe1EP9muF6WILVsq, 2026-10-08. Full output in task history.

## MPP meaning

- Canonical: MPP = Machine Payments Protocol (Stripe HTTP handshake: POST → 402 challenge → retry with credential → receipt). Spec mpp.dev/protocol, lib mppx, docs docs.stripe.com/payments/machine/mpp + monetize-mcp.
- MCP = tool/discovery layer. Production stack = MCP + MPP + Stripe together.
- Multi-party = pattern (Connect split vs PayPal multiparty + Payouts), not a protocol.

## Summary

- PayPal autonomous = wallet + ledger delegation, platform-gated. ACP → ChatGPT via delegated Braintree nonce (tokencc_..., allowance merchant_id/max_amount/currency/expires_at, 91565/915266/915267), UCP → Google AI via Google Pay nonce (androidPayCards 3h single-use). Store Sync catalog + 3 cart endpoints, physical/US/USD only. Vault 8kk reusable + Payouts 15k. Toolkit/MCP does not move buyer→seller money by itself.
- Stripe + MCP + MPP = card-network delegation, developer-composable. Saved pm_xxx → per-purchase spt_xxx scoped profile/currency/max/expires. Seller PI with granted_token, clone pm_xxx→pm_yyy. MPP 402 flow via mppx + link-cli. Network tokens Visa IC / Mastercard Agent Pay behind SPT.
- Rule: PayPal for wallet conversion + protection + ChatGPT/Google surfaces (accept US/USD/physical + form). Stripe for scoped card delegation + Connect split + stablecoin/BNPL + full policy control (own 3DS/budget/webhooks).

## Key tables (condensed)

Delegation: PayPal ACP/UCP single-use nonce (OpenAI/Google mints, merchant redeems via transaction.sale) vs Stripe spt_xxx dual-view issued/granted (agent mints, seller PI redeems, no money in SPT itself, clone semantics).

Scoping: ACP allowance merchant_id/max_amount/currency/expires_at vs SPT usage_limits profile/currency/max/expires. No global budget either side — enforce in agent code.

Auth: Both need human on OTP/3DS every time for ICICI/IN/EU. PayPal 422 / INSTRUMENT_DECLINED vs Stripe requires_action + handleNextAction. Saving Vault/PM does not bypass. Only pre-funded balance (PayPal balance/Payouts, Stripe balance/stablecoin) removes per-charge OTP.

Seller: PayPal needs Braintree MID + UCP registration + Store Sync cart API (catalog provided). Stripe needs profile_xxx + PaymentIntent granted_token (catalog/OMS you build).

Marketplace: PayPal Partner Referrals + multiparty + Payouts EMAIL/PHONE/ID 30d claim vs Stripe Connect direct/destination/separate + networkId + Stripe-Account header + applicationFee.

Protection/settlement: PayPal Seller/Purchase Protection + seller_receivable_breakdown + INSTANT to balance vs Stripe network signals + PI settle + Connect fees.

Dev surface: PayPal @paypal/mcp local/remote + Toolkit vs Stripe mcp.stripe.com + OAuth/rk_... + link-cli + mppx. Both need least-privilege + human-confirm writes + webhook verify.

Regions: PayPal Store Sync physical/US/USD, Payouts 96/24, Venmo US/USD vs Stripe SPT US/CA/GB+EU list, IN unsupported both.

Autonomy: PayPal supervised+automatic modes; Stripe mode is agent-built. Both need budget+allowlist+expiry + observability (facilitator_details vs issued/used/deactivated + withReceipt).

Failures: MID/profile mismatch, amount/currency/expiry, reuse, SCA, double-charge (PayPal-Request-Id/sender_batch_id vs Idempotency-Key/MPP challenge binding), webhook loss (PayPal 25x/3d app-scoped).

Recommendation + fastest demos + full URLs in task output. Local files verified: spt-discussion.md:1-251, paypal-payments-complete.md:1-251, paypal-payments-deep-research.md:1-467, paypal-autonomous-payments.md:1-24.
