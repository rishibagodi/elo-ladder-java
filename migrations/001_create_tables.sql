CREATE TABLE IF NOT EXISTS players (
    id           SERIAL PRIMARY KEY,
    name         TEXT UNIQUE NOT NULL,
    rating       INT NOT NULL DEFAULT 1200,
    games_played INT NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS matches (
    id       SERIAL PRIMARY KEY,
    player_a INT NOT NULL REFERENCES players(id),
    player_b INT NOT NULL REFERENCES players(id),
    score_a  NUMERIC(2,1) NOT NULL CHECK (score_a IN (0, 0.5, 1)),
    delta_a  INT NOT NULL,
    delta_b  INT NOT NULL,
    played_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS players_rating ON players (rating DESC);
