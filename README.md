# x402 PayPal

Pay-per-request premium API for autonomous agents, settled with a PayPal Vault token.
The agent pays once for access. The funding source is a saved PayPal wallet or a saved card.
No card data reaches the API server.

## What is implemented

- Agent bootstrap with API key and derived HMAC secret.
- x402 v2 `402 Payment Required` with a base64 `PAYMENT-REQUIRED` header.
- HMAC-signed payment payloads with challenge, nonce, expiry, amount, asset, and resource checks.
- Budget reservation, replay detection, and cached content replay.
- Wallet enrollment with a PayPal approve URL.
- Card enrollment with PayPal CardFields hosted on `/enroll/{token}`.
- Per-request PayPal order create and capture using the saved vault token.
- SCA / payer-action mapping and a resume endpoint.
- PayPal webhook verification, dedupe, capture reconcile, and vault-token disable.
- Demo agent CLI.

Prices: `/v1/premium/quote` = `$1.00`, `/v1/premium/report` = `$2.00`. Both are configurable.

## Requirements

- Java 21.
- A PayPal sandbox business account with Accept Payments, Vault, and Advanced Credit/Debit Card Payments enabled.
- Sandbox client ID and secret.

`PAYPAL_MERCHANT_ID` is not required for a standalone seller integration.

## Configure

```bash
cp .env.example .env
```

Fill these values:

```dotenv
PAYPAL_ENV=sandbox
PAYPAL_CLIENT_ID=...
PAYPAL_CLIENT_SECRET=...
PAYPAL_WEBHOOK_ID=
APP_PUBLIC_BASE_URL=http://localhost:8080
X402_MASTER_KEY=...
X402_VAULT_KEY=...
```

Generate the two x402 keys with:

```bash
openssl rand -base64 32
```

Run each command twice and use a different value for each key. `.env` is gitignored.

## Run

```bash
./gradlew bootRun
```

Health check:

```bash
curl http://localhost:8080/actuator/health
```

PayPal diagnostics:

```bash
curl http://localhost:8080/v1/diagnostics/paypal
```

The app uses a local H2 file database at `./data/x402`. Flyway creates the schema.

## Create an agent

```bash
curl -sS -X POST http://localhost:8080/v1/agents \
  -H 'Content-Type: application/json' \
  -d '{"name":"demo-agent","budgetCents":1000}'
```

The response contains `agentId`, `apiKey`, and `hmacSecret`.
The API key is shown once; the server stores only its hash.
The HMAC secret is derived from `X402_MASTER_KEY`, so the server can recompute it.

## Enroll a payment method

Wallet:

```bash
curl -sS -X POST http://localhost:8080/v1/enrollments \
  -H "Authorization: Bearer $API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"sourceType":"PAYPAL_WALLET"}'
```

Open `approveUrl` and approve with a sandbox buyer. The return URL exchanges the approved setup token automatically.
If the return URL cannot reach `localhost`, call exchange after approval:

```bash
curl -sS -X POST http://localhost:8080/v1/enrollments/$ENROLLMENT_ID/exchange \
  -H "Authorization: Bearer $API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{}'
```

Then check the enrollment:

```bash
curl -sS http://localhost:8080/v1/enrollments \
  -H "Authorization: Bearer $API_KEY"
```

Card:

```bash
curl -sS -X POST http://localhost:8080/v1/enrollments \
  -H "Authorization: Bearer $API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"sourceType":"CARD"}'
```

Open `enrollmentUrl` in a browser and pay with a PayPal sandbox test card.
CardFields creates the vault setup token and posts it to the server.
No PAN reaches this service.

## Make a paid request

Without a payment header, the API returns `402` and a `PAYMENT-REQUIRED` header:

```bash
curl -i http://localhost:8080/v1/premium/quote \
  -H "Authorization: Bearer $API_KEY"
```

The demo agent does the full flow: reads the header, signs the challenge, retries, and prints the receipt.

```bash
./gradlew demoAgent --args="--base-url http://localhost:8080 \
  --api-key $API_KEY --hmac-secret $HMAC_SECRET --agent-id $AGENT_ID"
```

Use `--path /v1/premium/report` for the `$2.00` endpoint.
Use `--wait` to pause after `PAYER_ACTION_REQUIRED`, approve in the browser, then resume.

Replay the same `PAYMENT-SIGNATURE` to get the same content with `X-X402-Replay: true` and no second charge.

## API

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `POST` | `/v1/agents` | none | Demo bootstrap |
| `GET` | `/v1/agents/me` | API key | Agent budget and status |
| `POST` | `/v1/enrollments` | API key | Create wallet or card enrollment |
| `GET` | `/v1/enrollments` | API key | List enrollments |
| `GET` | `/v1/enrollments/{id}` | API key | Enrollment status |
| `POST` | `/v1/enrollments/{id}/exchange` | API key | Exchange an approved setup token |
| `DELETE` | `/v1/enrollments/{id}` | API key | Revoke and delete the vault token |
| `GET` | `/enroll/{token}` | signed link | Hosted card page |
| `GET` | `/enroll/{token}/return` | signed link | Wallet or 3DS return |
| `GET` | `/v1/premium/health` | API key | Free health check |
| `GET` | `/v1/premium/quote` | x402 | `$1.00` quote |
| `GET` | `/v1/premium/report` | x402 | `$2.00` report |
| `GET` | `/v1/payments/attempts/{id}` | API key | Payment attempt status |
| `POST` | `/v1/payments/attempts/{id}/resume` | API key | Resume after payer action |
| `POST` | `/webhooks/paypal` | PayPal signature | Capture and vault events |

## Error codes

`402` responses use `PAYMENT_SIGNATURE_REQUIRED`, `INVALID_SIGNATURE`, `PAYMENT_MISMATCH`,
`CHALLENGE_EXPIRED`, `REPLAY_DETECTED`, `ENROLLMENT_REQUIRED`, `BUDGET_EXCEEDED`,
`PAYER_ACTION_REQUIRED`, `INSTRUMENT_DECLINED`, or `PAYMENT_FAILED`.
A settled replay returns `200` plus `X-X402-Replay: true`.

## Tests

```bash
./gradlew test
```

## Local-only test helper

Run with the `dev` profile to activate an enrollment from a vault token you created directly in PayPal:

```bash
./gradlew bootRun --args='--spring.profiles.active=dev'
```

```bash
curl -sS -X POST http://localhost:8080/v1/dev/enrollments/$ENROLLMENT_ID/activate \
  -H "Authorization: Bearer $API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"vaultToken":"...","brand":"VISA","last4":"1111"}'
```

This endpoint exists only under the `dev` profile. It skips the normal PayPal exchange, so do not enable it in production.

## Security model

- The agent authenticates with an API key and signs the x402 challenge with its derived HMAC secret.
- The vault token never leaves the server and is encrypted at rest with AES-GCM.
- The vault token is a bearer charge credential. It is not placed in the x402 header.
- Card data goes from the browser to PayPal. The server sees only the setup token and the vault token.
- Set `PAYPAL_WEBHOOK_ID` to verify webhook signatures. If it is empty, local webhook verification is skipped.
- API keys are high-entropy random values. The server stores a SHA-256 hash, not the key.

## Docs

- `docs/x402-paypal-java-build-plan.md`: architecture, contract, schema, phases, status.
- `docs/paypal-vault-agent-story-detailed.md`: PayPal Vault and Orders reference story.
