-- Roles for a single operator, alphabetical. Parameter 1: operator id.
SELECT role FROM operator_roles WHERE operator_id = ? ORDER BY role
