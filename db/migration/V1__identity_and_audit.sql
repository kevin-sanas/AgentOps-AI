CREATE TABLE app_user (
  id uuid PRIMARY KEY, username varchar(80) UNIQUE NOT NULL,
  password_hash text NOT NULL, display_name varchar(120) NOT NULL,
  active boolean NOT NULL DEFAULT true, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE app_role (
  id uuid PRIMARY KEY, code varchar(40) UNIQUE NOT NULL
    CHECK (code IN ('OBSERVER','ON_CALL','OPS_ADMIN','AUDITOR'))
);
INSERT INTO app_role (id,code) VALUES
 ('00000000-0000-0000-0000-000000000001','OBSERVER'),
 ('00000000-0000-0000-0000-000000000002','ON_CALL'),
 ('00000000-0000-0000-0000-000000000003','OPS_ADMIN'),
 ('00000000-0000-0000-0000-000000000004','AUDITOR');
CREATE TABLE user_role (
  user_id uuid NOT NULL REFERENCES app_user(id), role_id uuid NOT NULL REFERENCES app_role(id),
  PRIMARY KEY (user_id,role_id)
);
CREATE TABLE user_session (
  id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES app_user(id),
  token_hash char(64) UNIQUE NOT NULL, expires_at timestamptz NOT NULL,
  revoked_at timestamptz, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_session_user ON user_session(user_id);
CREATE INDEX ix_session_expiry ON user_session(expires_at);
CREATE TABLE audit_event (
  id uuid PRIMARY KEY, actor_id uuid REFERENCES app_user(id), action varchar(100) NOT NULL,
  subject_type varchar(80) NOT NULL, subject_id uuid, request_id varchar(100) NOT NULL,
  metadata jsonb NOT NULL DEFAULT '{}', created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_subject_time ON audit_event(subject_type,subject_id,created_at DESC);
