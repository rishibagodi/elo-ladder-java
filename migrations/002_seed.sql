-- A spread of ratings and experience, so the K-factor asymmetry is
-- visible the moment you report your first match.
INSERT INTO players (name, rating, games_played) VALUES
    ('Anand',   2450, 310),   -- master: K = 10, barely moves
    ('Bhavna',  1875, 142),   -- established: K = 20
    ('Chetan',  1640,  88),
    ('Divya',   1510,  45),
    ('Eshan',   1200,   3),   -- provisional: K = 40, moves fast
    ('Farah',   1200,   0)    -- brand new
ON CONFLICT (name) DO NOTHING;
