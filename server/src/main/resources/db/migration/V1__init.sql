CREATE TABLE projects (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    name        text        NOT NULL UNIQUE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    archived_at timestamptz
);

CREATE TABLE virtual_keys (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id   uuid        NOT NULL REFERENCES projects (id),
    name         text        NOT NULL,
    key_prefix   text        NOT NULL,
    key_hash     text        NOT NULL UNIQUE,
    created_at   timestamptz NOT NULL DEFAULT now(),
    revoked_at   timestamptz,
    last_used_at timestamptz
);

CREATE INDEX virtual_keys_project_id_idx ON virtual_keys (project_id);

CREATE TABLE model_prices (
    id                    uuid           PRIMARY KEY DEFAULT gen_random_uuid(),
    provider              text           NOT NULL,
    model                 text           NOT NULL,
    input_per_mtok        numeric(18, 8) NOT NULL,
    output_per_mtok       numeric(18, 8) NOT NULL,
    cached_input_per_mtok numeric(18, 8),
    currency              text           NOT NULL DEFAULT 'USD',
    effective_from        timestamptz    NOT NULL,
    created_at            timestamptz    NOT NULL DEFAULT now(),
    UNIQUE (provider, model, effective_from)
);

CREATE TABLE usage_records (
    id                  bigserial      PRIMARY KEY,
    request_id          uuid           NOT NULL UNIQUE,
    started_at          timestamptz    NOT NULL,
    key_id              uuid           NOT NULL REFERENCES virtual_keys (id),
    project_id          uuid           NOT NULL REFERENCES projects (id),
    tag                 text,
    protocol            text           NOT NULL CHECK (protocol IN ('openai', 'anthropic')),
    provider            text           NOT NULL,
    model_requested     text           NOT NULL,
    model_served        text,
    input_tokens        integer        NOT NULL DEFAULT 0,
    output_tokens       integer        NOT NULL DEFAULT 0,
    cached_input_tokens integer        NOT NULL DEFAULT 0,
    cost                numeric(18, 8),
    price_id            uuid           REFERENCES model_prices (id),
    usage_source        text           NOT NULL CHECK (usage_source IN ('response', 'stream', 'none')),
    ttft_ms             integer,
    duration_ms         integer        NOT NULL,
    http_status         integer        NOT NULL,
    streamed            boolean        NOT NULL,
    outcome             text           NOT NULL CHECK (outcome IN ('ok', 'upstream_error', 'client_abort', 'timeout', 'gateway_error')),
    error_type          text
);

CREATE INDEX usage_records_project_started_idx ON usage_records (project_id, started_at DESC);
CREATE INDEX usage_records_started_idx ON usage_records (started_at DESC);
