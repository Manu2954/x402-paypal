ALTER TABLE enrollment ADD COLUMN setup_token_id VARCHAR(64);
ALTER TABLE enrollment ADD COLUMN approve_url VARCHAR(2048);

ALTER TABLE challenge ADD COLUMN method VARCHAR(10);
ALTER TABLE challenge ADD COLUMN asset VARCHAR(10);

ALTER TABLE payment_attempt ADD COLUMN content_json CLOB;
ALTER TABLE payment_attempt ADD COLUMN content_type VARCHAR(120);

CREATE UNIQUE INDEX uk_principal_api_key_hash ON principal (api_key_hash);
