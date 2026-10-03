-- +migrate Up
CREATE TABLE players (id INTEGER PRIMARY KEY, name TEXT NOT NULL, joined TEXT, profile TEXT);
CREATE TABLE cards (id INTEGER PRIMARY KEY, player_id INTEGER REFERENCES players(id), label TEXT);
-- +migrate Down
DROP TABLE cards;
DROP TABLE players;
