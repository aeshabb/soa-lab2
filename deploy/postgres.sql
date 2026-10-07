CREATE TABLE IF NOT EXISTS soa_lab2_venues (
    id integer PRIMARY KEY CHECK (id > 0),
    name text NOT NULL CHECK (length(name) > 0),
    capacity integer NOT NULL CHECK (capacity > 0),
    type text CHECK (type IN ('BAR', 'CINEMA', 'MALL'))
);

CREATE TABLE IF NOT EXISTS soa_lab2_tickets (
    id integer PRIMARY KEY CHECK (id > 0),
    name text NOT NULL CHECK (length(name) > 0),
    coordinate_x integer NOT NULL,
    coordinate_y double precision NOT NULL
        CHECK (coordinate_y > '-Infinity'::double precision AND coordinate_y < 'Infinity'::double precision),
    creation_date timestamp without time zone NOT NULL,
    price real NOT NULL CHECK (price > 0 AND price < 'Infinity'::real),
    comment text,
    type text NOT NULL CHECK (type IN ('VIP', 'USUAL', 'BUDGETARY', 'CHEAP')),
    venue_id integer NOT NULL UNIQUE REFERENCES soa_lab2_venues(id),
    person_id integer CHECK (person_id > 0)
);

CREATE TABLE IF NOT EXISTS soa_lab2_state (
    id integer PRIMARY KEY CHECK (id = 1),
    next_ticket_id bigint NOT NULL CHECK (next_ticket_id > 0),
    next_venue_id bigint NOT NULL CHECK (next_venue_id > 0)
);
