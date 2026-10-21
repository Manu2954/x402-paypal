CREATE TABLE principal (
    id VARCHAR(40) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    api_key_hash VARCHAR(255) NOT NULL,
    budget_cents BIGINT NOT NULL,
    spent_cents BIGINT NOT NULL DEFAULT 0,
    period_start DATE NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE enrollment (
    id VARCHAR(40) PRIMARY KEY,
    principal_id VARCHAR(40) NOT NULL,
    paypal_customer_id VARCHAR(22) NOT NULL,
    source_type VARCHAR(20) NOT NULL,
    vault_token_enc VARCHAR(2048),
    brand VARCHAR(40),
    last4 VARCHAR(4),
    status VARCHAR(20) NOT NULL,
    consent_max_cents BIGINT,
    created_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP,
    CONSTRAINT fk_enrollment_principal FOREIGN KEY (principal_id) REFERENCES principal (id)
);

CREATE INDEX idx_enrollment_principal ON enrollment (principal_id);

CREATE TABLE challenge (
    id VARCHAR(40) PRIMARY KEY,
    principal_id VARCHAR(40) NOT NULL,
    resource VARCHAR(512) NOT NULL,
    amount_cents BIGINT NOT NULL,
    nonce VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    consumed_at TIMESTAMP,
    CONSTRAINT uk_challenge_nonce UNIQUE (nonce),
    CONSTRAINT fk_challenge_principal FOREIGN KEY (principal_id) REFERENCES principal (id)
);

CREATE TABLE payment_attempt (
    id VARCHAR(40) PRIMARY KEY,
    challenge_id VARCHAR(40),
    principal_id VARCHAR(40) NOT NULL,
    enrollment_id VARCHAR(40) NOT NULL,
    amount_cents BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL,
    order_id VARCHAR(64),
    capture_id VARCHAR(64),
    response_json CLOB,
    error_code VARCHAR(64),
    payer_action_url VARCHAR(2048),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_attempt_principal FOREIGN KEY (principal_id) REFERENCES principal (id),
    CONSTRAINT fk_attempt_enrollment FOREIGN KEY (enrollment_id) REFERENCES enrollment (id)
);

CREATE INDEX idx_attempt_principal ON payment_attempt (principal_id);
CREATE INDEX idx_attempt_order ON payment_attempt (order_id);
CREATE INDEX idx_attempt_capture ON payment_attempt (capture_id);

CREATE TABLE webhook_event (
    event_id VARCHAR(128) PRIMARY KEY,
    type VARCHAR(120) NOT NULL,
    payload CLOB NOT NULL,
    received_at TIMESTAMP NOT NULL,
    processed_at TIMESTAMP
);
