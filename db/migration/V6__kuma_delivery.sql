CREATE TABLE integration_config (
  id uuid PRIMARY KEY, integration_type varchar(32) NOT NULL DEFAULT 'UPTIME_KUMA' CHECK(integration_type='UPTIME_KUMA'),
  target_id uuid NOT NULL UNIQUE REFERENCES service_target(id), base_url varchar(2048) NOT NULL,
  secret_ref varchar(120) NOT NULL CHECK(secret_ref ~ '^env:KUMA_PUSH_TOKEN_[A-Z0-9_]{1,80}$'),
  enabled boolean NOT NULL DEFAULT false, smoke_passed boolean NOT NULL DEFAULT false,
  version integer NOT NULL DEFAULT 1, lease_token uuid, lease_until timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(), last_polled_at timestamptz
);
CREATE TABLE integration_delivery (
  id uuid PRIMARY KEY, integration_id uuid NOT NULL REFERENCES integration_config(id),
  check_run_id uuid REFERENCES check_run(id), kind varchar(8) NOT NULL CHECK(kind IN ('CHECK','SMOKE')),
  outcome varchar(4) NOT NULL CHECK(outcome IN ('UP','DOWN')), latency_ms integer,
  state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','DELIVERED','DEAD','SUPERSEDED')),
  attempts integer NOT NULL DEFAULT 0, available_at timestamptz NOT NULL DEFAULT now(),
  last_http_status integer, last_error_code varchar(80), created_at timestamptz NOT NULL DEFAULT now(),
  CHECK((kind='CHECK')=(check_run_id IS NOT NULL)), UNIQUE(integration_id,check_run_id)
);
CREATE UNIQUE INDEX ix_one_pending_smoke ON integration_delivery(integration_id) WHERE kind='SMOKE' AND state='PENDING';
CREATE INDEX ix_kuma_pending ON integration_delivery(state,available_at);
CREATE TABLE integration_delivery_attempt (
  id uuid PRIMARY KEY, delivery_id uuid NOT NULL REFERENCES integration_delivery(id),
  attempt_no integer NOT NULL, http_status integer, error_code varchar(80),
  attempted_at timestamptz NOT NULL DEFAULT now(), UNIQUE(delivery_id,attempt_no)
);
