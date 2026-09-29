-- Deterministic owner account. Authentication is out of scope for increment 1, so
-- tests and the application address this row through a fixed identifier.
INSERT INTO user_account (user_id, external_subject, display_name, email, status)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'seed-owner-local',
    'Seed Owner',
    'seed-owner@diagramador.local',
    'ACTIVE'
)
ON CONFLICT DO NOTHING;
