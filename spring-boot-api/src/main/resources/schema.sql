-- Runs after Hibernate created/updated the tables (spring.jpa.defer-datasource-initialization=true).
-- Statements are separated by '^;' (see spring.sql.init.separator), because the DO blocks
-- contain semicolons themselves. Every statement is idempotent, so it can run on every startup.

-- needed to combine "=" on room_id and "&&" on the date range in one gist index
CREATE EXTENSION IF NOT EXISTS btree_gist
^;

-- Optimistic locking for new bookings: the api does not lock anything when booking,
-- instead the database rejects a second overlapping booking for the same room when the
-- insert is executed (SQLSTATE 23P01), no matter which api instance handles the request.
-- Only RESERVED and CONFIRMED bookings block a period. Expired reservations are set to
-- EXPIRED by the api before inserting, so they do not block the period anymore.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'bookings_no_overlap') THEN
        ALTER TABLE bookings
            ADD CONSTRAINT bookings_no_overlap
            EXCLUDE USING gist (room_id WITH =, daterange(start, finish, '[)') WITH &&)
            WHERE (status IN ('RESERVED', 'CONFIRMED'));
    END IF;
END $$
^;

-- a booking can be confirmed only once (SQLSTATE 23505)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'bookingsconfirmation_booking_unique') THEN
        ALTER TABLE bookingsconfirmation
            ADD CONSTRAINT bookingsconfirmation_booking_unique UNIQUE (booking_id);
    END IF;
END $$
^;
