CREATE TABLE runbook (
 id uuid PRIMARY KEY, target_id uuid NOT NULL REFERENCES service_target(id),
 title varchar(160) NOT NULL, draft text NOT NULL CHECK(length(draft) BETWEEN 1 AND 24000),
 revision bigint NOT NULL DEFAULT 1, published_revision bigint,
 latest_version integer NOT NULL DEFAULT 0, enabled boolean NOT NULL DEFAULT true,
 created_by uuid NOT NULL REFERENCES app_user(id), created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_runbook_target ON runbook(target_id,created_at,id);
CREATE TABLE runbook_version (
 runbook_id uuid NOT NULL REFERENCES runbook(id), version integer NOT NULL,
 title varchar(160) NOT NULL, markdown text NOT NULL,
 published_by uuid NOT NULL REFERENCES app_user(id), published_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(runbook_id,version)
);
CREATE TABLE runbook_section (
 id uuid PRIMARY KEY, runbook_id uuid NOT NULL, version integer NOT NULL,
 ordinal integer NOT NULL CHECK(ordinal BETWEEN 0 AND 4), text text NOT NULL CHECK(length(text) BETWEEN 1 AND 6000),
 content_hash char(64) NOT NULL,
 FOREIGN KEY(runbook_id,version) REFERENCES runbook_version(runbook_id,version), UNIQUE(runbook_id,version,ordinal)
);
CREATE TABLE agent_run (
 id uuid PRIMARY KEY, incident_id uuid NOT NULL REFERENCES incident(id), target_id uuid NOT NULL REFERENCES service_target(id),
 requested_by uuid NOT NULL REFERENCES app_user(id), request_id varchar(80) NOT NULL,
 status varchar(24) NOT NULL DEFAULT 'QUEUED' CHECK(status IN ('QUEUED','RUNNING','COMPLETED','FAILED','CANCELLED')),
 graph_version varchar(80) NOT NULL, model_name varchar(80) NOT NULL DEFAULT 'mock',
 input jsonb NOT NULL, result jsonb, error_code varchar(80),
 created_at timestamptz NOT NULL DEFAULT now(), started_at timestamptz, completed_at timestamptz,
 UNIQUE(incident_id,request_id), CHECK(result IS NULL OR status='COMPLETED')
);
CREATE INDEX ix_agent_incident ON agent_run(incident_id,created_at DESC,id);
CREATE TABLE agent_step (
 run_id uuid NOT NULL REFERENCES agent_run(id), ordinal integer NOT NULL,
 node varchar(40) NOT NULL, status varchar(16) NOT NULL CHECK(status IN ('PENDING','COMPLETED','FAILED')),
 completed_at timestamptz, PRIMARY KEY(run_id,ordinal), UNIQUE(run_id,node)
);
CREATE TABLE outbox_event (
 id uuid PRIMARY KEY, run_id uuid NOT NULL UNIQUE REFERENCES agent_run(id),
 type varchar(40) NOT NULL CHECK(type='AGENT_RUN_REQUESTED'),
 state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','LEASED','DONE','DEAD')),
 attempts integer NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 5),
 available_at timestamptz NOT NULL DEFAULT now(), lease_token uuid, lease_until timestamptz,
 last_error varchar(80), created_at timestamptz NOT NULL DEFAULT now(),
 CHECK((state='LEASED')=(lease_token IS NOT NULL AND lease_until IS NOT NULL))
);
CREATE INDEX ix_outbox_due ON outbox_event(available_at,created_at) WHERE state IN ('PENDING','LEASED');
