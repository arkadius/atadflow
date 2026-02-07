CREATE TABLE flow (
    id          UUID PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE flow_node (
    id          UUID PRIMARY KEY,
    flow_id     UUID NOT NULL REFERENCES flow(id) ON DELETE CASCADE,
    node_key    VARCHAR(255) NOT NULL,
    label       VARCHAR(255) NOT NULL,
    node_type   VARCHAR(255) NOT NULL,
    position_x  DOUBLE PRECISION NOT NULL DEFAULT 0,
    position_y  DOUBLE PRECISION NOT NULL DEFAULT 0,
    config      JSONB,
    python_code TEXT
);

CREATE TABLE flow_edge (
    id            UUID PRIMARY KEY,
    flow_id       UUID NOT NULL REFERENCES flow(id) ON DELETE CASCADE,
    source_node   VARCHAR(255) NOT NULL,
    target_node   VARCHAR(255) NOT NULL,
    source_handle VARCHAR(255),
    target_handle VARCHAR(255)
);

CREATE TABLE job (
    id            UUID PRIMARY KEY,
    flow_id       UUID NOT NULL REFERENCES flow(id),
    status        VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    spark_app_id  VARCHAR(255),
    submitted_at  TIMESTAMP,
    started_at    TIMESTAMP,
    finished_at   TIMESTAMP,
    error_message TEXT
);
