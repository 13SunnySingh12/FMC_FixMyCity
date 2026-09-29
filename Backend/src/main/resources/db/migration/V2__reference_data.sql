-- FMC.md lists the complaint categories; each gets the department that handles it.
-- Admins can rename, add or remove both through the admin dashboard.

INSERT INTO departments (name, description) VALUES
    ('Roads & Public Works', 'Road surfaces, potholes, footpaths and damaged public roads.'),
    ('Sanitation & Solid Waste', 'Garbage collection, waste accumulation and street cleaning.'),
    ('Street Lighting & Electrical', 'Streetlights and other public electrical fixtures.'),
    ('Drainage & Sewerage', 'Blocked drains, sewer overflow and waterlogging.'),
    ('Water Supply', 'Water supply interruptions, leaks, pipe bursts and water quality.'),
    ('General Administration', 'Civic issues that do not fit another department.');

INSERT INTO categories (name, description, department_id) VALUES
    ('Roads', 'Potholes, damaged roads and broken footpaths.',
        (SELECT id FROM departments WHERE name = 'Roads & Public Works')),
    ('Garbage', 'Uncollected garbage, overflowing bins and illegal dumping.',
        (SELECT id FROM departments WHERE name = 'Sanitation & Solid Waste')),
    ('Streetlights', 'Broken, flickering or missing streetlights.',
        (SELECT id FROM departments WHERE name = 'Street Lighting & Electrical')),
    ('Drainage', 'Blocked or overflowing drains and waterlogged streets.',
        (SELECT id FROM departments WHERE name = 'Drainage & Sewerage')),
    ('Water Supply', 'No water, low pressure, leaking pipes or dirty water.',
        (SELECT id FROM departments WHERE name = 'Water Supply')),
    ('Other', 'Any other civic issue.',
        (SELECT id FROM departments WHERE name = 'General Administration'));
