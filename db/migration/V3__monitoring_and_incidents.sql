ALTER TABLE service_target ADD COLUMN config_version integer NOT NULL DEFAULT 1;
ALTER TABLE service_target ADD COLUMN allow_http boolean NOT NULL DEFAULT false;
ALTER TABLE service_target ADD COLUMN last_probe_started_at timestamptz;
CREATE TABLE probe_host_lease (
  host varchar(253) PRIMARY KEY, lease_token uuid, lease_until timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE probe_slot (
  id smallint PRIMARY KEY CHECK(id BETWEEN 1 AND 4), lease_token uuid, lease_until timestamptz NOT NULL DEFAULT now()
);
INSERT INTO probe_slot(id) VALUES(1),(2),(3),(4);
CREATE TABLE probe_budget (
  id boolean PRIMARY KEY DEFAULT true CHECK(id), window_started timestamptz NOT NULL DEFAULT now(),
  claimed integer NOT NULL DEFAULT 0 CHECK(claimed BETWEEN 0 AND 4)
);
INSERT INTO probe_budget(id) VALUES(true);
CREATE TABLE target_network_policy (
  id uuid PRIMARY KEY, target_id uuid NOT NULL REFERENCES service_target(id),
  version integer NOT NULL, allowed_private_cidrs cidr[] NOT NULL DEFAULT '{}',
  reason varchar(500) NOT NULL, approved_by uuid NOT NULL REFERENCES app_user(id),
  approved_at timestamptz NOT NULL DEFAULT now(), active boolean NOT NULL DEFAULT true,
  UNIQUE(target_id,version)
);
CREATE UNIQUE INDEX ux_network_active ON target_network_policy(target_id) WHERE active;
CREATE TABLE check_definition (
  id uuid PRIMARY KEY, target_id uuid NOT NULL REFERENCES service_target(id),
  method varchar(8) NOT NULL CHECK(method IN ('GET','HEAD')),
  interval_seconds integer NOT NULL CHECK(interval_seconds>=60),
  timeout_ms integer NOT NULL CHECK(timeout_ms BETWEEN 100 AND 10000),
  expected_statuses integer[] NOT NULL CHECK(cardinality(expected_statuses) BETWEEN 1 AND 20),
  fail_threshold integer NOT NULL CHECK(fail_threshold BETWEEN 1 AND 20),
  recover_threshold integer NOT NULL CHECK(recover_threshold BETWEEN 1 AND 20),
  state varchar(16) NOT NULL DEFAULT 'DISABLED' CHECK(state IN ('ACTIVE','PAUSED','DISABLED')),
  config_version integer NOT NULL DEFAULT 1,
  failure_count integer NOT NULL DEFAULT 0, success_count integer NOT NULL DEFAULT 0,
  next_run_at timestamptz NOT NULL DEFAULT now(), last_started_at timestamptz,
  execution_id uuid, lease_token uuid, lease_until timestamptz, scheduled_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK ((lease_token IS NULL AND lease_until IS NULL) OR (lease_token IS NOT NULL AND lease_until IS NOT NULL))
);
CREATE INDEX ix_check_due ON check_definition(next_run_at) WHERE state='ACTIVE';
CREATE TABLE check_run (
  id uuid PRIMARY KEY, check_definition_id uuid NOT NULL REFERENCES check_definition(id),
  config_version integer NOT NULL, target_config_version integer NOT NULL,
  started_at timestamptz NOT NULL, completed_at timestamptz NOT NULL, scheduled_at timestamptz NOT NULL,
  outcome varchar(16) NOT NULL CHECK(outcome IN ('UP','DOWN','UNKNOWN')),
  status_code integer, latency_ms integer CHECK(latency_ms>=0), error_code varchar(80),
  resolved_ips text[] NOT NULL DEFAULT '{}', redirect_decision varchar(24) NOT NULL DEFAULT 'NOT_FOLLOWED',
  UNIQUE(check_definition_id,scheduled_at), CHECK(completed_at>=started_at)
);
CREATE INDEX ix_check_history ON check_run(check_definition_id,completed_at DESC,id);
CREATE TABLE incident (
  id uuid PRIMARY KEY, target_id uuid NOT NULL REFERENCES service_target(id),
  status varchar(24) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','ACKNOWLEDGED','INVESTIGATING','MONITORING','RESOLVED','CLOSED')),
  severity varchar(16) NOT NULL DEFAULT 'P2' CHECK(severity IN ('P1','P2','P3','P4')),
  opened_at timestamptz NOT NULL DEFAULT now(), last_failure_at timestamptz NOT NULL,
  acknowledged_by uuid REFERENCES app_user(id), recovered_at timestamptz,
  resolved_at timestamptz, closed_at timestamptz, summary varchar(2000) NOT NULL,
  version bigint NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_incident_active_target ON incident(target_id) WHERE status IN ('OPEN','ACKNOWLEDGED','INVESTIGATING','MONITORING');
CREATE INDEX ix_incident_recent ON incident(opened_at DESC,id);
CREATE TABLE incident_check_snapshot (
  incident_id uuid NOT NULL REFERENCES incident(id), check_definition_id uuid NOT NULL REFERENCES check_definition(id),
  config_version integer NOT NULL, recover_threshold integer NOT NULL,
  monitoring_successes integer NOT NULL DEFAULT 0,
  PRIMARY KEY(incident_id,check_definition_id)
);
CREATE TABLE incident_event (
  id uuid PRIMARY KEY, event_sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
  incident_id uuid NOT NULL REFERENCES incident(id), actor_id uuid REFERENCES app_user(id),
  event_type varchar(80) NOT NULL, body varchar(2000), metadata jsonb NOT NULL DEFAULT '{}',
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_incident_events ON incident_event(incident_id,event_sequence DESC);
