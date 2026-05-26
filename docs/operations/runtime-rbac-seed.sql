-- Seed RUNTIME_* permissions for life-engine platform RBAC (run against platform DB).
-- Assign to roles via role_permissions after inserting permissions.

INSERT INTO permissions (code, description) VALUES
  ('RUNTIME_VIEWER', 'Read runtime runs, registries, and SSE streams'),
  ('RUNTIME_OPERATOR', 'Start and cancel workflow runs'),
  ('RUNTIME_ADMIN', 'Runtime actuator metrics and admin endpoints')
ON CONFLICT (code) DO NOTHING;

-- Example: grant viewer+operator to an existing operator role (adjust role_id).
-- INSERT INTO role_permissions (role_id, permission_id)
-- SELECT r.id, p.id FROM roles r, permissions p
-- WHERE r.code = 'OPERATOR' AND p.code IN ('RUNTIME_VIEWER', 'RUNTIME_OPERATOR');
