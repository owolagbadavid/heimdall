CREATE TABLE IF NOT EXISTS rules (
    id              VARCHAR(255) PRIMARY KEY,
    name            VARCHAR(255),
    api             VARCHAR(255) NOT NULL,
    op              VARCHAR(255) NOT NULL,
    time_in_seconds BIGINT       NOT NULL DEFAULT 0,
    rate_limit      BIGINT       NOT NULL DEFAULT 0,
    version         BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (api, op)
);
