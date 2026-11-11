(() => {
  "use strict";

  const PATHS = {
    quote: "/v1/premium/quote",
    report: "/v1/premium/report"
  };

  const state = {
    session: null,
    demoInfo: null,
    sessionReady: false,
    running: false,
    scenario: "quote",
    budgetCents: 1000,
    spentCents: 0,
    pricing: { quote: 100, report: 200 },
    lastSignatureHeader: null,
    lastPayload: null,
    lastCanonical: null,
    lastChallenge: null,
    lastReceipt: null,
    lastContent: null,
    lastStatus: null,
    lastPath: null,
    rawTab: "challenge",
    pendingHeroRun: false,
    creatingSession: false,
    enrollment: null,
    enrolling: false,
    enrollChecking: false,
    enrollPollTimer: null,
    enrollPolls: 0,
    sessionCreatedAt: null
  };

  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));

  function money(cents) {
    return `$${(Number(cents || 0) / 100).toFixed(2)}`;
  }

  function usd(value, fallback = "—") {
    if (value === null || value === undefined || value === "") return fallback;
    const number = Number(value);
    return Number.isFinite(number) ? `$${number.toFixed(2)}` : fallback;
  }

  function pause(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }

  function escapeHtml(value) {
    return String(value)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#039;");
  }

  function decodeBase64Utf8(value) {
    const binary = atob(value);
    const bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0));
    return new TextDecoder().decode(bytes);
  }

  function encodeBase64Utf8(value) {
    const bytes = new TextEncoder().encode(value);
    let binary = "";
    for (const byte of bytes) binary += String.fromCharCode(byte);
    return btoa(binary);
  }

  async function hmacBase64Url(secret, canonical) {
    const key = await crypto.subtle.importKey(
      "raw",
      new TextEncoder().encode(secret),
      { name: "HMAC", hash: "SHA-256" },
      false,
      ["sign"]
    );
    const signature = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(canonical));
    const bytes = new Uint8Array(signature);
    let binary = "";
    for (const byte of bytes) binary += String.fromCharCode(byte);
    return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  }

  function authHeaders(extra = {}) {
    return {
      Authorization: `Bearer ${state.session.apiKey}`,
      ...extra
    };
  }

  function setSessionPill(kind, label) {
    const pill = $("#session-pill");
    pill.classList.remove("ready", "running", "error", "starting");
    pill.classList.add(kind);
    $("#session-label").textContent = label;
  }

  function setRunning(running) {
    state.running = running;
    const button = $("#run-agent");
    button.classList.toggle("is-running", running);
    button.disabled = running || !state.sessionReady;
    $("#run-label").textContent = running ? "Agent is working…" : runLabel();
    $("#hero-run").disabled = running;
  }

  function runLabel() {
    const price = state.pricing[state.scenario] ?? (state.scenario === "quote" ? 100 : 200);
    return `Run agent · ${money(price)}`;
  }

  function updateBudget() {
    const remaining = Math.max(0, state.budgetCents - state.spentCents);
    $("#budget").textContent = money(remaining);
    $("#spent-label").textContent = `${money(state.spentCents)} spent`;
    $("#budget-total").textContent = `of ${money(state.budgetCents)}`;
    const percent = state.budgetCents > 0 ? (remaining / state.budgetCents) * 100 : 100;
    $("#budget-fill").style.width = `${Math.max(0, Math.min(100, percent))}%`;
  }

  function setCard(brand, last4) {
    const cardBrand = brand || "VISA";
    const cardLast4 = last4 || "1111";
    $("#card-brand").textContent = cardBrand;
    $("#card-last4").textContent = cardLast4;
    $("#funding-card-label").textContent = `${cardBrand} •••• ${cardLast4}`;
    $("#enroll-existing-label").textContent = `${cardBrand} •••• ${cardLast4}`;
  }

  function setPricing(pricing) {
    if (!pricing) return;
    if (pricing.quote) state.pricing.quote = Number(pricing.quote);
    if (pricing.report) state.pricing.report = Number(pricing.report);
    $("#quote-price").textContent = money(state.pricing.quote);
    $("#report-price").textContent = money(state.pricing.report);
    $("#run-label").textContent = runLabel();
  }

  function feedback(kind, html) {
    const element = $("#feedback");
    if (!html) {
      element.classList.add("hidden");
      element.className = "console-feedback hidden";
      element.innerHTML = "";
      return;
    }
    const icons = { success: "✓", error: "!", info: "i" };
    element.className = `console-feedback ${kind}`;
    element.innerHTML = `<span class="feedback-icon">${icons[kind] || "i"}</span><div class="feedback-copy">${html}</div>`;
  }

  function toast(message) {
    const element = $("#toast");
    element.textContent = message;
    element.classList.add("show");
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => element.classList.remove("show"), 1900);
  }

  function stepElement(name) {
    return $(`[data-step="${name}"]`);
  }

  function setStep(name, status, chip, output) {
    const element = stepElement(name);
    if (!element) return;
    element.classList.remove("is-active", "is-done", "is-error");
    if (status) element.classList.add(`is-${status}`);
    const chipElement = $(`[data-chip="${name}"]`);
    if (chipElement) chipElement.textContent = chip || "";
    const outputElement = $(`[data-output="${name}"]`, element);
    if (outputElement) {
      if (output) {
        outputElement.textContent = output;
        outputElement.classList.remove("hidden");
      } else {
        outputElement.textContent = "";
        outputElement.classList.add("hidden");
      }
    }
  }

  function resetSteps() {
    $$(".step").forEach((step) => {
      step.classList.remove("is-active", "is-done", "is-error");
      const chip = $(".step-chip", step);
      if (chip) chip.textContent = "";
      const output = $(".step-output", step);
      if (output) {
        output.textContent = "";
        output.classList.add("hidden");
      }
    });
  }

  function setFlowNote(text, isError = false) {
    const element = $("#flow-footnote");
    element.textContent = text;
    element.classList.toggle("error-text", isError);
  }

  function resetTransactionUI() {
    resetSteps();
    state.lastSignatureHeader = null;
    state.lastPayload = null;
    state.lastCanonical = null;
    state.lastChallenge = null;
    state.lastReceipt = null;
    state.lastContent = null;
    state.lastStatus = null;
    state.lastPath = null;
    state.rawTab = "challenge";
    $("#premium-output").classList.add("hidden");
    $("#premium-json").textContent = "";
    $("#receipt-body").classList.add("hidden");
    $("#empty-receipt").classList.remove("hidden");
    $("#replay-card").classList.add("hidden");
    $("#replay-result").className = "replay-result";
    $("#replay-result").textContent = "";
    $("#replay-button").disabled = false;
    $("#replay-button").textContent = "Send identical signature";
    feedback("", "");
    setFlowNote("Ready when you are. The first step is one click.");
    setRawTab("challenge");
    renderRaw();
  }

  function setRawTab(tab) {
    state.rawTab = tab;
    $$(".tab").forEach((button) => {
      const active = button.dataset.tab === tab;
      button.classList.toggle("active", active);
      button.setAttribute("aria-selected", active ? "true" : "false");
    });
    renderRaw();
  }

  function pretty(value) {
    return JSON.stringify(value, null, 2);
  }

  function highlightedJson(value) {
    const escaped = escapeHtml(pretty(value));
    return escaped
      .replace(/&quot;([^&]+?)&quot;:/g, '<span class="raw-key">&quot;$1&quot;:</span>')
      .replace(/: &quot;([^]*?)&quot;/g, ': <span class="raw-string">&quot;$1&quot;</span>');
  }

  function renderRaw() {
    const element = $("#raw-output");
    let value;
    if (state.rawTab === "challenge") {
      value = state.lastChallenge?.required;
    } else if (state.rawTab === "signature") {
      value = state.lastSignatureHeader
        ? {
            header: state.lastSignatureHeader,
            decoded: state.lastPayload,
            canonical: state.lastCanonical
          }
        : null;
    } else if (state.rawTab === "receipt") {
      value = state.lastReceipt;
    } else {
      value = state.lastContent;
    }

    if (!value) {
      element.textContent = "Run the agent above, then inspect the real protocol exchange here.";
      return;
    }
    element.innerHTML = highlightedJson(value);
  }

  function renderReceipt(receipt) {
    const extra = receipt.extra || {};
    const gross = extra.gross ?? (state.pricing[state.scenario] / 100).toFixed(2);
    $("#receipt-status-text").textContent = extra.status || "COMPLETED";
    $("#receipt-time").textContent = new Date().toLocaleTimeString([], {
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit"
    });
    $("#receipt-gross").textContent = usd(gross);
    $("#receipt-fee").textContent = usd(extra.fee);
    $("#receipt-net").textContent = usd(extra.net);
    $("#receipt-order").textContent = extra.orderId || "—";
    $("#receipt-capture").textContent = extra.captureId || receipt.transaction || "—";
    $("#empty-receipt").classList.add("hidden");
    $("#receipt-body").classList.remove("hidden");
    $("#replay-card").classList.remove("hidden");
  }

  function renderContent(content, path) {
    $("#premium-path").textContent = `GET ${path}`;
    $("#premium-json").textContent = pretty(content);
    $("#premium-output").classList.remove("hidden");
  }

  function failAt(step, code, message, extraHtml = "") {
    setStep(step, "error", code || "ERROR");
    setSessionPill("error", "Needs attention");
    feedback(
      "error",
      `<strong>${escapeHtml(code || "PAYMENT_FAILED")}</strong> — ${escapeHtml(message || "The request did not settle.")}${extraHtml}`
    );
    setFlowNote("The agent stopped honestly. No success state is shown for a failed charge.", true);
  }

  async function readError(response) {
    try {
      const body = await response.json();
      return {
        code: body.error || body.code || `HTTP_${response.status}`,
        message: body.message || body.errorMessage || response.statusText || "Request failed",
        body
      };
    } catch (error) {
      return {
        code: `HTTP_${response.status}`,
        message: response.statusText || "Request failed",
        body: null
      };
    }
  }

  async function loadInfo() {
    try {
      const response = await fetch("/v1/demo/info", { headers: { Accept: "application/json" } });
      if (!response.ok) return;
      const info = await response.json();
      state.demoInfo = info;
      setPricing(info.pricing);
      setCard(info.cardBrand, info.cardLast4);
      if (typeof info.budgetCents === "number") state.budgetCents = info.budgetCents;
      updateBudget();
    } catch (error) {
      // The session endpoint is still worth trying; a transient info failure should not block the demo.
    }
  }

  async function createSession() {
    if (state.running || state.creatingSession) return;
    state.creatingSession = true;
    try {
      await createSessionInner();
    } finally {
      state.creatingSession = false;
    }
  }

  async function createSessionInner() {
    state.sessionReady = false;
    setRunning(false);
    setSessionPill("starting", "Starting session…");
    resetTransactionUI();
    resetEnrollmentUi();
    try {
      const response = await fetch("/v1/demo/session", {
        method: "POST",
        headers: { Accept: "application/json" }
      });
      if (!response.ok) {
        const error = await readError(response);
        state.session = null;
        state.sessionReady = false;
        setSessionPill("error", "Session unavailable");
        setRunning(false);
        const hint = response.status === 429
          ? "The shared demo is busy right now. Wait a minute, then try again."
          : response.status === 503
            ? "This deployment is missing the sandbox demo configuration."
            : "The demo service is temporarily unavailable. Try again in a moment.";
        feedback(
          "error",
          `<strong>${escapeHtml(error.code)}</strong> — ${escapeHtml(error.message)}<br>
           <span style="color:var(--muted)">${escapeHtml(hint)}</span><br>
           <button class="feedback-link" data-retry-session="true" type="button">Try again</button>`
        );
        setFlowNote("Live demo is not ready on this deployment.", true);
        return;
      }

      const session = await response.json();
      state.session = session;
      state.sessionReady = true;
      state.sessionCreatedAt = new Date();
      state.budgetCents = Number(session.budgetCents || 1000);
      state.spentCents = Number(session.spentCents || 0);
      setPricing(session.pricing);
      setCard(session.cardBrand || state.demoInfo?.cardBrand, session.cardLast4 || state.demoInfo?.cardLast4);
      $("#agent-id").textContent = session.agentId || "agt_…";
      $("#agent-id").title = session.agentId || "";
      updateBudget();
      setSessionPill("ready", "Live sandbox ready");
      setRunning(false);
      setFlowNote("Session ready. Press Run agent to send the first paid request.");
      feedback(
        "info",
        `<strong>Judge session ready.</strong> A fresh agent with a ${money(state.budgetCents)} sandbox budget and a saved PayPal test card. No sign-in, no copy-paste.`
      );

      if (state.pendingHeroRun) {
        state.pendingHeroRun = false;
        await runAgent();
      }
    } catch (error) {
      state.sessionReady = false;
      setSessionPill("error", "Connection error");
      setRunning(false);
      feedback("error", `<strong>Could not reach the demo service.</strong> ${escapeHtml(error.message || error)}`);
    }
  }

  async function refreshAgent() {
    if (!state.session) return;
    try {
      const response = await fetch("/v1/agents/me", { headers: authHeaders() });
      if (!response.ok) return;
      const agent = await response.json();
      state.budgetCents = Number(agent.budgetCents || state.budgetCents);
      state.spentCents = Number(agent.spentCents || 0);
      updateBudget();
      if (agent.name) $("#agent-name").textContent = agent.name;
    } catch (error) {
      // The receipt already contains the authoritative charge. Keep the last known budget.
    }
  }

  async function runAgent() {
    if (!state.sessionReady || state.running || !state.session) return;
    if (state.scenario !== "quote" && state.scenario !== "report") return;

    const path = PATHS[state.scenario];
    const priceCents = Number(state.pricing[state.scenario] || (state.scenario === "quote" ? 100 : 200));
    const remaining = state.budgetCents - state.spentCents;
    if (remaining < priceCents) {
      feedback("error", `<strong>BUDGET_EXCEEDED</strong> — This agent has ${money(remaining)} left. Start a new judge session for a fresh budget.`);
      return;
    }

    state.running = true;
    setRunning(true);
    setSessionPill("running", "Agent is working…");
    resetTransactionUI();
    setSessionPill("running", "Agent is working…");
    setFlowNote("The agent is sending the first request now…");
    feedback("info", `The agent is buying <strong>${escapeHtml(path)}</strong> for <strong>${money(priceCents)}</strong>.`);

    try {
      setStep("request", "active", "sending");
      const first = await fetch(path, { headers: authHeaders({ Accept: "application/json" }) });
      await pause(220);
      const requiredHeader = first.headers.get("PAYMENT-REQUIRED");
      setStep("request", "done", String(first.status), `${first.status} ${first.statusText} · GET ${path}`);

      if (first.status !== 402) {
        const error = await readError(first);
        failAt("request", error.code, `Expected a 402 challenge but received ${first.status}.`);
        return;
      }
      if (!requiredHeader) {
        failAt("challenge", "NO_CHALLENGE", "The 402 response did not include a PAYMENT-REQUIRED header.");
        return;
      }

      let required;
      try {
        required = JSON.parse(decodeBase64Utf8(requiredHeader));
      } catch (error) {
        failAt("challenge", "INVALID_CHALLENGE", "The payment challenge could not be decoded.");
        return;
      }

      state.lastChallenge = { required, header: requiredHeader };
      const accepts = Array.isArray(required.accepts) ? required.accepts : [];
      const accept = accepts[0];
      if (!accept) {
        failAt("challenge", "NO_ACCEPT", "The challenge did not offer a payment option.");
        return;
      }

      setStep("challenge", "active", "parsing");
      await pause(330);
      const extra = accept.extra || {};
      const challengeId = extra.challengeId;
      const nonce = extra.nonce;
      setStep(
        "challenge",
        "done",
        "402",
        `amount     ${accept.amount} ${accept.asset}\nchallenge  ${challengeId || "—"}\nnonce      ${nonce || "—"}\nresource   ${required.resource?.url || path}`
      );

      if (!extra.enrolled) {
        failAt("challenge", "ENROLLMENT_REQUIRED", "This agent has no active funding source. Start a new judge session.");
        return;
      }
      if (!challengeId || !nonce) {
        failAt("challenge", "INVALID_CHALLENGE", "The challenge is missing its single-use nonce or id.");
        return;
      }

      setStep("sign", "active", "signing");
      await pause(300);
      const timestamp = Math.floor(Date.now() / 1000);
      const resource = required.resource?.url || "";
      const amount = accept.amount;
      const asset = accept.asset;
      const canonical = [challengeId, "GET", resource, amount, asset, nonce, String(timestamp)].join("\n");
      const signature = await hmacBase64Url(state.session.hmacSecret, canonical);

      const payload = {
        x402Version: 2,
        accepted: accept,
        payload: {
          agentId: state.session.agentId,
          challengeId,
          nonce,
          timestamp,
          signature
        }
      };
      const signatureHeader = encodeBase64Utf8(JSON.stringify(payload));

      state.lastSignatureHeader = signatureHeader;
      state.lastPayload = payload;
      state.lastCanonical = canonical;
      state.lastPath = path;
      state.lastStatus = null;
      setStep(
        "sign",
        "done",
        "signed",
        `canonical  ${canonical.split("\n").length} fields · ${canonical.length} bytes\nsignature  ${signature.slice(0, 26)}…\nheader     ${signatureHeader.slice(0, 26)}…`
      );

      setStep("paypal", "active", "charging");
      setFlowNote("PayPal is creating the order and capturing the vault token…");
      await pause(260);
      const paid = await fetch(path, {
        headers: authHeaders({
          Accept: "application/json",
          "PAYMENT-SIGNATURE": signatureHeader
        })
      });
      const receiptHeader = paid.headers.get("PAYMENT-RESPONSE");
      const replayHeader = paid.headers.get("X-X402-Replay");

      if (paid.status === 200 && receiptHeader) {
        let receipt;
        try {
          receipt = JSON.parse(decodeBase64Utf8(receiptHeader));
        } catch (error) {
          failAt("receipt", "INVALID_RECEIPT", "PayPal returned a receipt that could not be decoded.");
          return;
        }

        let content = null;
        try {
          content = await paid.json();
        } catch (error) {
          content = { note: "Content returned a non-JSON body." };
        }

        state.lastReceipt = receipt;
        state.lastContent = content;
        state.lastStatus = paid.status;

        // The charge is settled. Reflect the spend immediately, then reconcile
        // with the authoritative agent ledger in the background.
        state.spentCents = Number(state.spentCents || 0) + priceCents;
        state.running = false;
        setRunning(false);
        updateBudget();

        setStep(
          "paypal",
          "done",
          replayHeader ? "cached" : "captured",
          `order      ${receipt.extra?.orderId || "—"}\ncapture    ${receipt.extra?.captureId || receipt.transaction || "—"}\nstatus     ${receipt.extra?.status || "COMPLETED"} · ${receipt.network || "paypal:sandbox"}`
        );
        await pause(240);
        setStep("unlock", "done", "200", `${paid.status} OK · ${path}\ncontent unlocked · ${replayHeader ? "no new charge" : "new sandbox capture"}`);
        renderReceipt(receipt);
        renderContent(content, path);
        setRawTab("receipt");
        setSessionPill("ready", "Settled · 200 OK");
        setFlowNote("The request settled. The receipt on the right came from PayPal, not from this page.");
        feedback(
          "success",
          `<strong>Paid and unlocked.</strong> ${escapeHtml(path)} returned 200 with a real PayPal sandbox capture <code>${escapeHtml(receipt.extra?.captureId || receipt.transaction || "—")}</code>.`
        );
        await refreshAgent();
        return;
      }

      if (paid.status === 402) {
        const error = await readError(paid);
        const body = error.body || {};
        if (error.code === "PAYER_ACTION_REQUIRED" && body.payerActionUrl) {
          const resume = body.attemptId
            ? ` <button class="feedback-link" data-resume="${escapeHtml(body.attemptId)}" type="button">I approved — resume</button>`
            : "";
          failAt(
            "paypal",
            "PAYER_ACTION_REQUIRED",
            "PayPal needs the payer to approve this charge in the browser.",
            `<br><a class="feedback-link" href="${escapeHtml(body.payerActionUrl)}" target="_blank" rel="noreferrer">Open PayPal approval</a>${resume}`
          );
        } else {
          failAt("paypal", error.code, error.message);
        }
        return;
      }

      const error = await readError(paid);
      failAt("paypal", error.code, error.message);
    } catch (error) {
      const stepName = state.lastSignatureHeader ? "paypal" : "request";
      failAt(stepName, "NETWORK_ERROR", error.message || String(error));
    } finally {
      state.running = false;
      setRunning(false);
      if (state.sessionReady && !$("#session-pill").classList.contains("error")) {
        setSessionPill("ready", state.lastReceipt ? "Settled · 200 OK" : "Live sandbox ready");
      }
    }
  }

  async function replay() {
    if (!state.lastSignatureHeader || !state.session || state.running) return;
    const button = $("#replay-button");
    button.disabled = true;
    button.textContent = "Sending identical signature…";
    $("#replay-result").className = "replay-result";
    $("#replay-result").textContent = "";

    try {
      const replayPath = state.lastPath || PATHS[state.scenario];
      const response = await fetch(replayPath, {
        headers: authHeaders({
          Accept: "application/json",
          "PAYMENT-SIGNATURE": state.lastSignatureHeader
        })
      });
      const replayFlag = response.headers.get("X-X402-Replay");
      if (response.status === 200 && replayFlag === "true") {
        let content = null;
        try {
          content = await response.json();
        } catch (error) {
          content = state.lastContent;
        }
        state.lastContent = content;
        setRawTab("content");
        $("#replay-result").className = "replay-result success";
        $("#replay-result").innerHTML = `✓ X-X402-Replay: true — served from cache. No second charge. Budget unchanged at ${money(state.budgetCents - state.spentCents)}.`;
        feedback("success", "<strong>Replay-safe.</strong> The exact same signature returned the cached result. The budget did not move and PayPal was not charged again.");
        setFlowNote("Same signature, same content, zero new charge. This is the idempotency guarantee.");
        await refreshAgent();
      } else {
        const error = await readError(response);
        $("#replay-result").className = "replay-result error";
        $("#replay-result").textContent = `Expected a cached replay, received ${response.status}${replayFlag ? ` · X-X402-Replay: ${replayFlag}` : ""}.`;
        feedback("error", `<strong>${escapeHtml(error.code)}</strong> — ${escapeHtml(error.message)}`);
      }
    } catch (error) {
      $("#replay-result").className = "replay-result error";
      $("#replay-result").textContent = error.message || String(error);
    } finally {
      button.disabled = false;
      button.textContent = "Send identical signature";
    }
  }

  async function resumeAttempt(attemptId) {
    if (!attemptId || !state.session) return;
    try {
      feedback("info", "Resuming the PayPal payment attempt…");
      const response = await fetch(`/v1/payments/attempts/${encodeURIComponent(attemptId)}/resume`, {
        method: "POST",
        headers: authHeaders()
      });
      const receiptHeader = response.headers.get("PAYMENT-RESPONSE");
      if (response.status === 200 && receiptHeader) {
        const receipt = JSON.parse(decodeBase64Utf8(receiptHeader));
        const content = await response.json();
        state.lastReceipt = receipt;
        state.lastContent = content;
        renderReceipt(receipt);
        renderContent(content, PATHS[state.scenario]);
        setStep("paypal", "done", "captured", `capture ${receipt.extra?.captureId || "—"}\nstatus ${receipt.extra?.status || "COMPLETED"}`);
        setStep("unlock", "done", "200", `200 OK · ${PATHS[state.scenario]}`);
        setSessionPill("ready", "Settled · 200 OK");
        feedback("success", "<strong>Payment resumed and settled.</strong> The content is unlocked.");
        setRawTab("receipt");
        await refreshAgent();
        return;
      }
      const error = await readError(response);
      feedback("error", `<strong>${escapeHtml(error.code)}</strong> — ${escapeHtml(error.message)}`);
    } catch (error) {
      feedback("error", `<strong>NETWORK_ERROR</strong> — ${escapeHtml(error.message || error)}`);
    }
  }

  function setEnrollOpen(open) {
    const panel = $("#enroll-panel");
    const toggle = $("#enroll-toggle");
    if (!panel || !toggle) return;
    panel.classList.toggle("hidden", !open);
    toggle.setAttribute("aria-expanded", open ? "true" : "false");
    const label = $("#enroll-toggle-label");
    if (label) label.textContent = open ? "Hide optional setup" : "Set up a different card";
    if (open) {
      setTimeout(() => panel.scrollIntoView({ behavior: "smooth", block: "nearest" }), 40);
    }
  }

  function resetEnrollSteps() {
    $$(".enroll-step").forEach((step) => step.classList.remove("is-active", "is-done", "is-error"));
  }

  function setEnrollStep(name, status) {
    const step = $(`[data-enroll-step="${name}"]`);
    if (!step) return;
    step.classList.remove("is-active", "is-done", "is-error");
    if (status) step.classList.add(`is-${status}`);
  }

  function setEnrollStatus(kind, html) {
    const element = $("#enroll-status");
    if (!element) return;
    element.className = `enroll-status${kind ? ` ${kind}` : ""}`;
    element.innerHTML = html || "";
  }

  function stopEnrollmentPolling() {
    if (state.enrollPollTimer) {
      clearInterval(state.enrollPollTimer);
      state.enrollPollTimer = null;
    }
    state.enrollPolls = 0;
  }

  function publicEnrollmentUrl(value) {
    if (!value) return "";
    try {
      const parsed = new URL(value, window.location.origin);
      return window.location.origin + parsed.pathname + parsed.search;
    } catch (error) {
      return value;
    }
  }

  function resetEnrollmentUi() {
    stopEnrollmentPolling();
    state.enrollment = null;
    state.enrolling = false;
    state.enrollChecking = false;
    setEnrollOpen(false);
    resetEnrollSteps();
    const button = $("#enroll-card");
    if (button) {
      button.disabled = false;
      button.textContent = "Start optional card setup";
    }
    $("#enroll-open-link").classList.add("hidden");
    $("#enroll-open-link").href = "#";
    $("#enroll-check").classList.add("hidden");
    $("#enroll-retry").classList.add("hidden");
    setEnrollStatus("", "No new card set up. Your current card is ready.");
  }

  function openEnrollmentTab() {
    if (!state.enrollment?.url) return;
    const tab = window.open(state.enrollment.url, "_blank");
    if (tab) tab.opener = null;
  }

  async function startEnrollment() {
    if (state.enrolling) return;
    if (!state.session) {
      setEnrollStatus("error", "The judge session is still connecting. Wait for the green session pill, then try again.");
      return;
    }
    if (state.enrollment && state.enrollment.status !== "ACTIVE") {
      openEnrollmentTab();
      return;
    }
    if (state.enrollment?.status === "ACTIVE") {
      resetEnrollmentUi();
    }

    state.enrolling = true;
    resetEnrollSteps();
    setEnrollStep("create", "active");
    setEnrollStatus("info", "Creating a secure card enrollment with PayPal…");
    const button = $("#enroll-card");
    button.disabled = true;
    button.textContent = "Creating enrollment…";
    $("#enroll-open-link").classList.add("hidden");
    $("#enroll-check").classList.add("hidden");
    $("#enroll-retry").classList.add("hidden");

    try {
      const response = await fetch("/v1/enrollments", {
        method: "POST",
        headers: authHeaders({
          Accept: "application/json",
          "Content-Type": "application/json"
        }),
        body: JSON.stringify({ sourceType: "CARD" })
      });
      if (!response.ok) {
        const error = await readError(response);
        throw new Error(`${error.code}: ${error.message}`);
      }
      const data = await response.json();
      state.enrollment = {
        id: data.enrollmentId,
        status: data.status,
        url: publicEnrollmentUrl(data.enrollmentUrl)
      };
      setEnrollStep("create", "done");
      setEnrollStep("card", "active");
      setEnrollStatus(
        "info",
        `Enrollment <code>${escapeHtml(data.enrollmentId)}</code> created. PayPal's secure card form is opening in a new tab. <strong>Your current card stays active.</strong>`
      );
      const link = $("#enroll-open-link");
      link.href = state.enrollment.url;
      link.classList.remove("hidden");
      button.textContent = "Enrollment pending";
      $("#enroll-check").classList.remove("hidden");

      const tab = window.open(state.enrollment.url, "_blank");
      if (tab) {
        tab.opener = null;
      } else {
        setEnrollStatus(
          "info",
          `Enrollment created. Your browser blocked the new tab — use <strong>Open PayPal card setup</strong> below. Your current card stays active.`
        );
      }
      startEnrollmentPolling();
    } catch (error) {
      setEnrollStep("create", "error");
      setEnrollStatus(
        "error",
        `<strong>Could not create the card enrollment.</strong> ${escapeHtml(error.message || error)}`
      );
      button.disabled = false;
      button.textContent = "Try optional card setup";
      $("#enroll-retry").classList.remove("hidden");
    } finally {
      state.enrolling = false;
    }
  }

  function startEnrollmentPolling() {
    stopEnrollmentPolling();
    state.enrollPollTimer = setInterval(() => checkEnrollmentStatus(false), 2500);
    setTimeout(() => checkEnrollmentStatus(false), 1200);
    $("#enroll-check").classList.remove("hidden");
  }

  async function checkEnrollmentStatus(manual) {
    if (!state.enrollment || state.enrollment.status === "ACTIVE" || state.enrollChecking || !state.session) return;
    state.enrollChecking = true;
    state.enrollPolls += 1;
    try {
      const response = await fetch("/v1/enrollments", { headers: authHeaders({ Accept: "application/json" }) });
      if (!response.ok) {
        const error = await readError(response);
        throw new Error(`${error.code}: ${error.message}`);
      }
      const list = await response.json();
      const current = Array.isArray(list)
        ? list.find((item) => item.enrollmentId === state.enrollment.id)
        : null;
      if (!current) throw new Error("The enrollment could not be found. Start a new optional setup.");
      if (current.status === "ACTIVE") {
        applyActiveEnrollment(current);
        return;
      }
      if (current.status === "REVOKED") {
        throw new Error("This enrollment was revoked. Start a new optional setup.");
      }
      if (manual) {
        setEnrollStatus(
          "info",
          "PayPal has not confirmed the card yet. Finish the secure form in the other tab. Your current card is still active."
        );
      }
      if (state.enrollPolls >= 72) {
        stopEnrollmentPolling();
        setEnrollStatus(
          "info",
          "Still waiting for PayPal. Your current card is still active. Finish the card form in the other tab, or use <strong>Check status now</strong>."
        );
        $("#enroll-check").classList.remove("hidden");
        $("#enroll-retry").classList.remove("hidden");
      }
    } catch (error) {
      if (manual || state.enrollPolls >= 72) {
        setEnrollStatus(
          "error",
          `<strong>Status check failed.</strong> ${escapeHtml(error.message || error)}`
        );
        $("#enroll-retry").classList.remove("hidden");
      }
    } finally {
      state.enrollChecking = false;
    }
  }

  function applyActiveEnrollment(enrollment) {
    stopEnrollmentPolling();
    const brand = enrollment.brand || "VISA";
    const last4 = enrollment.last4 || "1111";
    state.enrollment = { ...state.enrollment, status: "ACTIVE", brand, last4 };
    if (state.session) {
      state.session.cardBrand = brand;
      state.session.cardLast4 = last4;
    }
    setCard(brand, last4);
    setEnrollStep("card", "done");
    setEnrollStep("active", "done");
    setEnrollStatus(
      "success",
      `<strong>New card active.</strong> ${escapeHtml(brand)} •••• ${escapeHtml(last4)} is now used for future runs. Your previous card remains saved.`
    );
    const button = $("#enroll-card");
    button.disabled = false;
    button.textContent = "Set up another card";
    $("#enroll-open-link").classList.add("hidden");
    $("#enroll-check").classList.add("hidden");
    $("#enroll-retry").classList.add("hidden");
    feedback(
      "success",
      `<strong>Optional card setup complete.</strong> ${escapeHtml(brand)} •••• ${escapeHtml(last4)} is active. The next paid run will use the new PayPal vault token.`
    );
    setFlowNote("New card enrolled. The next paid run will use the new funding source.");
    toast("New card enrolled");
  }

  function retryEnrollment() {
    resetEnrollmentUi();
    setEnrollOpen(true);
    startEnrollment();
  }

  function selectScenario(value) {
    if (!PATHS[value]) return;
    state.scenario = value;
    $$(".scenario-card").forEach((card) => {
      const input = $("input", card);
      card.classList.toggle("active", input.value === value);
      input.checked = input.value === value;
    });
    $("#premium-path").textContent = `GET ${PATHS[value]}`;
    $("#run-label").textContent = runLabel();
    if (state.lastReceipt) {
      feedback("info", `Resource switched to <strong>${escapeHtml(PATHS[value])}</strong>. Run the agent for a fresh charge, or replay the previous result first.`);
    }
  }

  async function copyText(text) {
    try {
      await navigator.clipboard.writeText(text);
      toast("Copied to clipboard");
    } catch (error) {
      const textarea = document.createElement("textarea");
      textarea.value = text;
      document.body.appendChild(textarea);
      textarea.select();
      document.execCommand("copy");
      textarea.remove();
      toast("Copied to clipboard");
    }
  }

  function wireEvents() {
    $("#run-agent").addEventListener("click", runAgent);
    $("#new-session").addEventListener("click", createSession);
    $("#replay-button").addEventListener("click", replay);
    $("#copy-raw").addEventListener("click", () => copyText($("#raw-output").textContent));
    $("#hero-run").addEventListener("click", async () => {
      document.getElementById("demo").scrollIntoView({ behavior: "smooth", block: "start" });
      if (state.running) return;
      if (state.sessionReady) {
        await runAgent();
      } else {
        state.pendingHeroRun = true;
        if (!state.session) await createSession();
      }
    });

    $$(".scenario-card").forEach((card) => {
      card.addEventListener("change", () => selectScenario($("input", card).value));
      card.addEventListener("click", () => selectScenario($("input", card).value));
    });

    $("#enroll-toggle").addEventListener("click", () => {
      setEnrollOpen($("#enroll-panel").classList.contains("hidden"));
    });
    $("#enroll-close").addEventListener("click", () => setEnrollOpen(false));
    $("#enroll-card").addEventListener("click", startEnrollment);
    $("#enroll-check").addEventListener("click", () => checkEnrollmentStatus(true));
    $("#enroll-retry").addEventListener("click", retryEnrollment);
    document.addEventListener("keydown", (event) => {
      if (event.key === "Escape" && !$("#enroll-panel").classList.contains("hidden")) {
        setEnrollOpen(false);
      }
    });

    $$(".tab").forEach((tab) => {
      tab.addEventListener("click", () => setRawTab(tab.dataset.tab));
    });

    $("#feedback").addEventListener("click", (event) => {
      const retry = event.target.closest("[data-retry-session]");
      if (retry) {
        createSession();
        return;
      }
      const target = event.target.closest("[data-resume]");
      if (target) resumeAttempt(target.dataset.resume);
    });

    $$(".copyable").forEach((button) => {
      button.addEventListener("click", () => {
        const id = button.dataset.copyTarget;
        const element = document.getElementById(id);
        if (element) copyText(element.textContent);
      });
    });
  }

  async function init() {
    wireEvents();
    updateBudget();
    setSessionPill("starting", "Starting session…");
    await loadInfo();
    await createSession();
  }

  document.addEventListener("DOMContentLoaded", init);
})();
