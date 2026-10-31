CREATE TABLE model_provider_config (
  id smallint PRIMARY KEY CHECK (id = 1),
  provider varchar(40) NOT NULL CHECK (provider = 'deepseek'),
  model varchar(80) NOT NULL CHECK (model = 'deepseek-flash'),
  api_key_ciphertext bytea NOT NULL CHECK (octet_length(api_key_ciphertext) >= 17),
  api_key_nonce bytea NOT NULL CHECK (octet_length(api_key_nonce) = 12),
  key_suffix varchar(4) NOT NULL CHECK (char_length(key_suffix) = 4),
  version bigint NOT NULL CHECK (version >= 1),
  updated_at timestamptz NOT NULL DEFAULT now(),
  updated_by uuid NOT NULL REFERENCES app_user(id),
  last_test_at timestamptz,
  last_test_outcome varchar(16) CHECK (last_test_outcome IN ('SUCCEEDED','FAILED')),
  last_test_error_code varchar(80),
  CHECK (
    (last_test_at IS NULL AND last_test_outcome IS NULL AND last_test_error_code IS NULL)
    OR (last_test_at IS NOT NULL AND last_test_outcome IS NOT NULL)
  )
);

CREATE TABLE model_provider_request (
  id uuid PRIMARY KEY,
  request_id varchar(100) NOT NULL,
  purpose varchar(24) NOT NULL CHECK (purpose IN ('DIAGNOSIS','CONNECTION_TEST','BALANCE_QUERY')),
  provider varchar(40) NOT NULL CHECK (provider = 'deepseek'),
  model varchar(80) NOT NULL CHECK (model = 'deepseek-flash'),
  agent_run_id uuid REFERENCES agent_run(id),
  incident_id uuid REFERENCES incident(id),
  actor_id uuid REFERENCES app_user(id),
  outcome varchar(16) NOT NULL DEFAULT 'STARTED' CHECK (outcome IN ('STARTED','SUCCEEDED','FAILED','UNKNOWN')),
  error_code varchar(80),
  http_status integer CHECK (http_status BETWEEN 100 AND 599),
  elapsed_ms integer CHECK (elapsed_ms >= 0),
  prompt_tokens integer CHECK (prompt_tokens >= 0),
  completion_tokens integer CHECK (completion_tokens >= 0),
  total_tokens integer CHECK (total_tokens >= 0),
  cache_hit_tokens integer CHECK (cache_hit_tokens >= 0),
  cache_miss_tokens integer CHECK (cache_miss_tokens >= 0),
  started_at timestamptz NOT NULL DEFAULT now(),
  completed_at timestamptz,
  UNIQUE (purpose, request_id),
  CHECK (
    (prompt_tokens IS NULL AND completion_tokens IS NULL AND total_tokens IS NULL
      AND cache_hit_tokens IS NULL AND cache_miss_tokens IS NULL)
    OR (prompt_tokens IS NOT NULL AND completion_tokens IS NOT NULL AND total_tokens IS NOT NULL
      AND total_tokens = prompt_tokens + completion_tokens
      AND (cache_hit_tokens IS NULL OR cache_hit_tokens <= prompt_tokens)
      AND (cache_miss_tokens IS NULL OR cache_miss_tokens <= prompt_tokens))
  ),
  CHECK (
    (outcome = 'STARTED' AND completed_at IS NULL)
    OR (outcome <> 'STARTED' AND completed_at IS NOT NULL)
  )
);

CREATE UNIQUE INDEX ux_model_request_diagnosis_run
  ON model_provider_request(agent_run_id)
  WHERE purpose = 'DIAGNOSIS' AND agent_run_id IS NOT NULL;
CREATE INDEX ix_model_request_started ON model_provider_request(started_at DESC,id);
CREATE INDEX ix_model_request_purpose_started ON model_provider_request(purpose,started_at DESC,id);
CREATE INDEX ix_model_request_incident ON model_provider_request(incident_id,started_at DESC)
  WHERE incident_id IS NOT NULL;
