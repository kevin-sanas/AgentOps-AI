ALTER TABLE agent_run DROP CONSTRAINT agent_run_status_check;
ALTER TABLE agent_run DROP CONSTRAINT agent_run_check;
ALTER TABLE agent_run ADD CONSTRAINT agent_run_status_check CHECK(status IN ('QUEUED','RUNNING','WAITING_HUMAN','APPROVED_FOR_MANUAL','REJECTED','COMPLETED','FAILED','CANCELLED'));
ALTER TABLE agent_run ADD CONSTRAINT agent_run_result_state CHECK(result IS NULL OR status IN ('WAITING_HUMAN','APPROVED_FOR_MANUAL','REJECTED','COMPLETED','FAILED','CANCELLED'));
ALTER TABLE agent_run ADD COLUMN result_hash char(64);
ALTER TABLE agent_step DROP CONSTRAINT agent_step_status_check;
ALTER TABLE agent_step ADD CONSTRAINT agent_step_status_check CHECK(status IN ('PENDING','COMPLETED','FAILED','SKIPPED'));
ALTER TABLE outbox_event ADD COLUMN phase varchar(16) NOT NULL DEFAULT 'INVOKE' CHECK(phase IN ('INVOKE','RESUME','CANCEL'));
ALTER TABLE outbox_event ADD COLUMN payload jsonb NOT NULL DEFAULT '{}';
CREATE TABLE approval_request (
 id uuid PRIMARY KEY, run_id uuid NOT NULL UNIQUE REFERENCES agent_run(id),
 version bigint NOT NULL DEFAULT 1, status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','APPROVED','REJECTED','EXPIRED')),
 output_hash char(64) NOT NULL, expires_at timestamptz NOT NULL DEFAULT now()+interval '24 hours',
 decided_by uuid REFERENCES app_user(id), reason varchar(2000), decision_hash char(64), decided_at timestamptz,
 CHECK((status IN ('APPROVED','REJECTED'))=(decided_by IS NOT NULL AND reason IS NOT NULL AND decision_hash IS NOT NULL))
);
CREATE INDEX ix_approval_expiry ON approval_request(expires_at) WHERE status='PENDING';
CREATE TABLE manual_action_report (
 run_id uuid PRIMARY KEY REFERENCES agent_run(id), outcome varchar(16) NOT NULL CHECK(outcome IN ('EXECUTED','NOT_EXECUTED','FAILED')),
 note varchar(2000) NOT NULL CHECK(length(trim(note)) BETWEEN 1 AND 2000), actor_id uuid NOT NULL REFERENCES app_user(id),
 reported_at timestamptz NOT NULL DEFAULT now()
);
