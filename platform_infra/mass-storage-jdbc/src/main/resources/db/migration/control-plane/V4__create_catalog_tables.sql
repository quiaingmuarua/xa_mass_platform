CREATE TABLE IF NOT EXISTS xa_catalog_event (
  event_code VARCHAR(128) PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  description TEXT,
  payload_types_json TEXT NOT NULL,
  task_modes_json TEXT NOT NULL,
  enabled BOOLEAN NOT NULL,
  default_routing_code VARCHAR(128),
  priority_class VARCHAR(64),
  response_mode VARCHAR(64),
  delivery_acknowledgement_mode VARCHAR(64),
  convergence_mode VARCHAR(64),
  target_scope VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS xa_catalog_project (
  project_code VARCHAR(128) PRIMARY KEY,
  tenant_id VARCHAR(128) NOT NULL,
  name VARCHAR(255) NOT NULL,
  description TEXT,
  enabled BOOLEAN NOT NULL,
  owner_principal_id VARCHAR(128)
);

CREATE TABLE IF NOT EXISTS xa_catalog_project_event (
  project_code VARCHAR(128) NOT NULL,
  event_code VARCHAR(128) NOT NULL,
  PRIMARY KEY(project_code, event_code),
  FOREIGN KEY(project_code) REFERENCES xa_catalog_project(project_code) ON DELETE CASCADE,
  FOREIGN KEY(event_code) REFERENCES xa_catalog_event(event_code) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_xa_catalog_project_event_event
  ON xa_catalog_project_event(event_code);
