# Demo video script — PayPal Agent Pay

Target length: **2:50** (hard limit 3:00)
Format: 1920×1080, browser at 100% zoom, cursor visible
Platform: YouTube, public or unlisted, no password

---

## Before you hit record

- [ ] Open the judge link and let the session pill turn green (**Live sandbox ready**)
- [ ] Click **Start a new judge session** so the budget is a clean **$10.00**
- [ ] Close every other tab, mute notifications, hide the bookmarks bar
- [ ] Do **not** show `.env`, a terminal with tokens, or the PayPal dashboard
- [ ] Do one practice run, then reset with **Start a new judge session**
- [ ] If the host sleeps, load the page once and wait for it to wake before recording

---

## 0:00 – 0:18 · The problem

**On screen:** the hero, slowly scrolling from the headline to the buttons.

**Narration:**
> AI agents can call almost any API — but they can't pay for one. Every payment rail assumes a human at a checkout screen. So the moment an agent hits a paid endpoint, a person has to step in.

---

## 0:18 – 0:38 · What this is

**On screen:** point at the hero proof line — *Real PayPal sandbox capture · Card already enrolled · No sign-in required*.

**Narration:**
> This is PayPal Agent Pay. A pay-per-request paywall for AI agents, settled with a saved PayPal Vault card. One link, no sign-in, and every payment you're about to see is a real PayPal sandbox capture.

---

## 0:38 – 1:25 · The live run (the money shot)

**On screen:**
1. Click **Run a live $1 payment**
2. Let the three panels animate: request → 402 challenge → signed retry → capture → 200
3. Land on the receipt: **COMPLETED**, Charged **$1.00**, Order and Capture IDs, **REAL SANDBOX TRANSACTION**
4. Point at the budget dropping **$10.00 → $9.00**

**Narration:**
> The agent calls a paid endpoint with its API key. Instead of data, it gets a 402 with a signed, single-use challenge — resource, amount, nonce, expiry. The agent HMAC-signs those fields and retries the same request. The server verifies the signature, reserves budget, creates and captures a PayPal order against the vault token, and returns the content plus a receipt.
>
> That's a real capture ID from the PayPal sandbox, and the budget just dropped from ten dollars to nine.

---

## 1:25 – 1:50 · Replay protection

**On screen:** click **Send identical signature**. Show `X-X402-Replay: true`, the cached result, and the budget still at **$9.00**.

**Narration:**
> Now the important part. I'll replay the exact same signature. The server returns the cached result with `X-X402-Replay: true` — no second charge, and the budget doesn't move. Retries are safe by construction, which is what makes this usable by an autonomous agent.

---

## 1:50 – 2:12 · Funding source

**On screen:** scroll to **Funding source**. Hold on **Card already enrolled**, the `ACTIVE` badge, and `VISA •••• 1111`. Open **Set up a different card** for two seconds, then close it.

**Narration:**
> The agent never holds a card number. A card is approved once through PayPal's hosted CardFields, and we store only an encrypted vault token. The demo ships with a card already enrolled, so a judge can run this in ten seconds — and there's an optional flow to swap in a different card.

---

## 2:12 – 2:32 · Under the hood

**On screen:** open **Under the hood of this run**, click **402 challenge**, then **Signed retry**. Let the raw JSON sit on screen.

**Narration:**
> Everything here is the actual bytes the browser sent and received. The challenge is bound to the resource, amount and nonce, and the signature is HMAC-SHA256 over a canonical payload. Change one field and the server rejects it.

---

## 2:32 – 2:50 · Scope and close

**On screen:** scroll to the footer, then back to the hero.

**Narration:**
> To be clear about scope: the premium data is canned and the card is a sandbox test card — but the x402 flow, the vault tokens, the captures and the receipts are real. Next, we're taking this to fleets of agents sharing one funding source. Thanks for watching.

---

## Production notes

- **Narrate live or dub after.** Dubbing is safer: record the screen first, then read the script to the timeline.
- **Keep the cursor deliberate.** Move, pause, click. No hunting.
- **Zoom, don't scroll wildly.** A slow scroll plus one zoom on the capture ID reads better than fast panning.
- **No dead air.** If a payment takes a few seconds, keep talking — that's the suspense, not a gap.
- **If a live payment fails,** don't fake it. Reset the session and run it again; a retry is more credible than a cut.
- **Captions.** YouTube auto-captions are fine, but check that `x402`, `HMAC-SHA256` and `PayPal Vault` are spelled correctly.

---

## YouTube metadata

**Title**
`PayPal Agent Pay — AI agents that pay per request (x402 + PayPal Vault)`

**Description**
```
AI agents can call APIs, but they can't pay for them. PayPal Agent Pay is a
pay-per-request paywall for autonomous agents, settled with a saved PayPal Vault
card — real x402 challenge, real sandbox capture, hard budget, no card numbers on
the server.

Demo: <your link>
Code: https://github.com/Manu2954/x402-paypal

Chapters
0:00 The problem
0:18 What it is
0:38 A real $1 payment
1:25 Replay protection
1:50 Funding source
2:12 Under the hood
2:32 Scope and what's next

Built with PayPal Vault, PayPal Orders v2, PayPal CardFields and x402.
Premium data is canned and the card is a PayPal sandbox test card; the payment
flow and captures are real.
```

**Visibility:** Unlisted (no password) is fine and avoids a public comment feed.

---

## If you need a 60-second cut

Keep 0:00–0:18, 0:38–1:25, and 1:25–1:50. Drop the funding-source and under-the-hood beats. That lands at ~55 seconds and still shows a real capture plus replay protection.
