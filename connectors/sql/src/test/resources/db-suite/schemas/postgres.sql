DROP TABLE IF EXISTS cards;
DROP TABLE IF EXISTS players;
CREATE TABLE players (id SERIAL PRIMARY KEY, name TEXT NOT NULL, joined DATE, profile JSONB);
-- a comment; with a semicolon
CREATE TABLE cards (id SERIAL PRIMARY KEY, player_id INT REFERENCES players(id), label TEXT);
CREATE OR REPLACE FUNCTION card_count() RETURNS BIGINT AS $$
  SELECT count(*) FROM cards;
$$ LANGUAGE SQL;
