# PayPal Autonomous / Agentic Payments — Deep Research

> Verified live: 2026-10-08 via developer.paypal.com + docs.paypal.ai + github.com/paypal/agent-toolkit
> Prior work: spt-discussion.md, paypal-payments-complete.md, paypal-payments-deep-research.md

## 1) Summary

- PayPal autonomous payments = Agent Ready (pay) + Store Sync (discover/order) + Agent Toolkit/MCP (back-office tools).
- Agent Ready is Braintree-based and protocol-split: ACP → ChatGPT/OpenAI via delegated single-use nonces vs UCP → Google AI Mode/Gemini via Google Pay payment-handler nonce.
- Store Sync is catalog + 3 cart endpoints (POST /merchant-cart, PUT /merchant-cart/{id}, POST /merchant-cart/{id}/checkout) with Orders v2 or Braintree pattern, physical-goods / US / USD only.
- Delegation is single-use merchant-bound amount/currency/expiry-validated (91565 unknown/expired, 915266 amount exceeds max, 915267 currency mismatch; UCP 3h expiry). No reusable scoped grant like spt_xxx; reusable charging is Vault 8kk... + app-enforced budget, with 422 as requires_action equivalent.
- Toolkit/MCP does not move buyer→seller money by itself. @paypal/agent-toolkit exposes create_order/get_order/pay_order/create_refund, invoices, catalog, subscriptions, shipment, disputes, reporting + remote-only search_product/create_cart/checkout_cart (gift-cards only).

See task ses_ee472752bffeiafNfQFC9W43L2 for full tables, diagrams, code snippets, URLs, cross-verification and edge cases. Key URLs:

- https://developer.paypal.com/agentic-commerce-services/about
- https://developer.paypal.com/agent-ready/overview
- https://developer.paypal.com/agent-ready/agentic-commerce-protocol
- https://developer.paypal.com/agent-ready/universal-commerce-protocol
- https://developer.paypal.com/store-sync/overview + /store-sync/integrate + /store-sync/create-catalog
- https://developer.paypal.com/ai-tools/toolkit + /ai-tools/agent-tools + /ai-tools/mcp-server
- https://github.com/paypal/agent-toolkit

Full task output retained in history. Use paypal-payments-deep-research.md for classic flow diagrams and spt-discussion.md for Stripe contrast.
