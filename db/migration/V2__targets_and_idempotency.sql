CREATE TABLE service_target (
  id uuid PRIMARY KEY, code varchar(80) UNIQUE NOT NULL,
  name varchar(160) NOT NULL, environment varchar(40) NOT NULL,
  base_url text NOT NULL, owner_team varchar(120), active boolean NOT NULL DEFAULT true,
  network_policy varchar(24) NOT NULL DEFAULT 'PUBLIC_ONLY'
    CHECK (network_policy IN ('PUBLIC_ONLY','APPROVED_PRIVATE_CIDR')),
  created_by uuid NOT NULL REFERENCES app_user(id), created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE target_access (
  target_id uuid NOT NULL REFERENCES service_target(id), user_id uuid NOT NULL REFERENCES app_user(id),
  can_read boolean NOT NULL DEFAULT true, can_operate boolean NOT NULL DEFAULT false,
  PRIMARY KEY (target_id,user_id), CHECK (NOT can_operate OR can_read)
);
CREATE INDEX ix_target_access_user ON target_access(user_id,target_id);
CREATE TABLE api_idempotency (
  id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES app_user(id),
  operation varchar(100) NOT NULL, idempotency_key varchar(128) NOT NULL,
  request_hash char(64) NOT NULL, state varchar(16) NOT NULL CHECK (state IN ('IN_PROGRESS','COMPLETED')),
  response_status smallint, response_resource_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(), expires_at timestamptz NOT NULL,
  UNIQUE (user_id,operation,idempotency_key),
  CHECK ((state='IN_PROGRESS' AND response_status IS NULL) OR
         (state='COMPLETED' AND response_status BETWEEN 200 AND 299))
);
CREATE INDEX ix_idempotency_expiry ON api_idempotency(expires_at);
