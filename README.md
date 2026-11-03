# Booking App

REST api for bookings of rooms, whose booking process (reserve → confirm or cancel) is synchronised with
optimistic and pessimistic locking, so that no double booking is possible, even with several api instances.
This readme shows how to get it up and running and describes where and why it is locked.

## Project structure

The system consists of a database (PostgreSQL), a REST api (Spring Boot) and a web ui (Angular).

| Folder | Content |
|---|---|
| [spring-boot-api](./spring-boot-api) | REST api with Spring Boot (Java 21, JPA) |
| [ui/msa-booking-app](./ui/msa-booking-app) | Angular web ui, served by nginx in docker |
| [docker-compose.yml](./docker-compose.yml) | shared part of the system: database and ui |
| [docker-compose.spring.yml](./docker-compose.spring.yml) | adds the api |
| [docker-compose.scaled.yml](./docker-compose.scaled.yml) | optional: second api instance behind a round robin load balancer |
| [scripts](./scripts) | script that proves with parallel requests that no double booking is possible |

## Prerequisites

* Docker with Docker Compose v2 (`docker compose ...`)

Only needed when running the applications outside of docker:

* api: JDK 21 and Maven
* ui: Node.js 20 or later and the Angular CLI (`npm install -g @angular/cli`)

## Running the whole system with docker compose

```console
# build the images and start database, api and ui
docker compose -f docker-compose.yml -f docker-compose.spring.yml up --build

# the same, but running in the background
docker compose -f docker-compose.yml -f docker-compose.spring.yml up --build -d

# follow the log output of the api
docker compose -f docker-compose.yml -f docker-compose.spring.yml logs -f app

# stop the system and remove the containers (deletes the data in the database)
docker compose -f docker-compose.yml -f docker-compose.spring.yml down
```

Once everything is up

* the ui is reachable under http://localhost:80/index.html
* the api is reachable under http://localhost:8080 (e.g. http://localhost:8080/optimistic/booking)
* the database is reachable under localhost:5432 (user and password `compose-postgres`)

> [!IMPORTANT]
> The database container has no volume, therefore `docker compose down` deletes all data.

## Sample data

The api creates some confirmed sample bookings on startup when the `bookings` table is empty,
so the ui shows some rooms right away. If there is already at least one booking, nothing is added.
The seeding is implemented in [Application.java](./spring-boot-api/src/main/java/org/distributed/consensus/Application.java)
(**seedBookings(...)** bean).

You can add further bookings with a POST request:

```console
curl -H 'Content-Type: application/json' \
      -d '{"name": "Blue Room", "roomId": 1, "start": "2026-11-12", "finish": "2026-11-15"}' \
      -X POST \
      http://localhost:8080/optimistic/booking
```

You can modify the fields name, roomId, start and finish in the body as you like.

## api endpoints

The optimistic and the pessimistic variant are served by the same api and are distinguished by the first path segment.

| Method | Path | Description |
|---|---|---|
| GET | `/optimistic/booking` | all bookings |
| GET | `/optimistic/booking/{id}` | a single booking |
| POST | `/optimistic/booking` | reserve a room (step 1) |
| DELETE | `/optimistic/booking/{id}` | cancel a reservation |
| GET | `/optimistic/bookingconfirmation` | all confirmations |
| GET | `/optimistic/bookingconfirmation/{id}` | a single confirmation |
| POST | `/optimistic/bookingconfirmation` | confirm a reservation (step 2), body `{"bookingId": 1}` |
| POST | `/pessimistic/booking` | reserve a room (step 1) |
| DELETE | `/pessimistic/booking/{id}` | cancel a reservation |
| POST | `/pessimistic/bookingconfirmation` | confirm a reservation (step 2), body `{"bookingId": 1}` |

Both variants use the same tables, so the GET endpoints show the bookings of both variants.

## Booking process and status codes

A booking passes these states (column `status` of the table `bookings`):

```
RESERVED --POST bookingconfirmation--> CONFIRMED
RESERVED --DELETE booking-----------> CANCELLED
RESERVED --5 minutes----------------> EXPIRED
```

`CONFIRMED`, `CANCELLED` and `EXPIRED` are final. A reservation blocks its period until it is confirmed,
cancelled or expired. The expiry needs no background job: a reservation whose `expiresAt` has passed simply no longer
counts as blocking, and it is set to `EXPIRED` when a new booking needs the period.
The time to live is configured by `booking.reservation-ttl` (default `PT5M`) in
[application.properties](./spring-boot-api/src/main/resources/application.properties).
Periods are half-open (`[start, finish)`), so a booking may start on the day another one ends.

| Request | Status | Meaning |
|---|---|---|
| POST booking | 201 | reservation created |
| | 409 | the period overlaps with a confirmed booking or a valid reservation of the room |
| | 400 | invalid body (missing fields, `start` not before `finish`, broken json) |
| POST bookingconfirmation | 201 | reservation confirmed |
| | 409 | already confirmed, cancelled, expired or changed by a parallel request |
| | 404 | unknown booking |
| DELETE booking | 204 | reservation cancelled (also when it was already cancelled or expired) |
| | 409 | booking is already confirmed or was changed by a parallel request (optimistic only) |
| | 404 | unknown booking |
| any | 503 / 500 | database not reachable / unexpected error |

Errors are answered with an [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem detail, e.g.

```json
{"title": "Booking conflict", "status": 409, "detail": "room 1 is already booked between 2026-11-03 and 2026-11-04", "instance": "/optimistic/booking"}
```

All errors are handled in [ApiExceptionHandler.java](./spring-boot-api/src/main/java/org/distributed/consensus/controller/ApiExceptionHandler.java)
and logged in three groups: conflicts and invalid requests as `WARN` (without stack trace), technical errors as `ERROR` with stack trace.
Constraint violations are only treated as conflicts (409) for the constraints of the booking process (exclusion constraint
`23P01`, unique constraint `23505`), every other violation is a technical error (500).

## Locking

Both variants keep the whole state in the database, nothing in the memory of an api instance.
Every request runs in exactly one short database transaction, so no lock is ever held across several http requests.
Between the requests of the booking process (reserve → client side logic → confirm) the period is blocked by the
reservation itself (status `RESERVED`, at most 5 minutes), not by a database lock.
Therefore the requests of one booking can be handled by different api instances.

### Optimistic: `/optimistic/...`

Implemented in [OptimisticBookingService.java](./spring-boot-api/src/main/java/org/distributed/consensus/service/OptimisticBookingService.java).
Nothing is locked up front. Every request assumes that there is no conflict and the database detects a conflict
when the change is written:

| Where | How | Why |
|---|---|---|
| POST booking | exclusion constraint `bookings_no_overlap` (see [schema.sql](./spring-boot-api/src/main/resources/schema.sql)): no two `RESERVED`/`CONFIRMED` bookings of the same room with overlapping periods, written with `INSERT ... ON CONFLICT DO NOTHING RETURNING id` | two parallel requests can both see "room is free", a check in the code is not enough. The database skips the conflicting insert, no id is returned → 409 |
| POST bookingconfirmation, DELETE booking | `@Version` column of the booking: Hibernate writes `UPDATE ... WHERE id = ? AND version = ?` | if a parallel request confirmed, cancelled or expired the booking in the meantime, no row matches → `ObjectOptimisticLockingFailureException` → 409 |
| POST bookingconfirmation | unique constraint on `bookingsconfirmation.booking_id` | a booking can never get two confirmations (SQLSTATE `23505`) → 409 |

`ON CONFLICT DO NOTHING` matters: with a plain `INSERT`, two parallel inserts of overlapping bookings both insert
their row first and then wait for each other while checking the constraint, until PostgreSQL detects the deadlock
after `deadlock_timeout` (1 s) and aborts one of them. With `ON CONFLICT` PostgreSQL uses speculative insertion,
which checks for conflicts before the row is finally inserted and avoids these deadlocks.

Before inserting, expired reservations of the period are set to `EXPIRED` (and their version is incremented), so they
no longer block the period in the constraint and a confirmation that still has the old version fails.
The losers of a race get a 409 and have to reload, this also applies to two parallel deletes of the same reservation.

### Pessimistic: `/pessimistic/...`

Implemented in [PessimisticBookingService.java](./spring-boot-api/src/main/java/org/distributed/consensus/service/PessimisticBookingService.java).
Every request first takes a lock in the database and only then checks and changes the state,
so parallel requests for the same room or booking are executed one after the other:

| Where | How | Why |
|---|---|---|
| POST booking, first statement | advisory lock on the room: `pg_advisory_xact_lock(roomId)` ([RoomLockRepository.java](./spring-boot-api/src/main/java/org/distributed/consensus/repository/RoomLockRepository.java)) | the booking that would conflict might not exist yet, so there is no row to lock with `FOR UPDATE`. The lock on the room id makes all parallel requests for this room - on all instances - wait, then each of them checks for overlaps and inserts while holding the lock |
| POST bookingconfirmation, DELETE booking | row lock on the booking: `@Lock(PESSIMISTIC_WRITE)` → `SELECT ... FOR UPDATE` | parallel confirm/delete requests of the same booking wait and then see the state written by the first one → 409 (or 204 for a repeated delete) |

Both locks are released automatically at the end of the transaction (commit, rollback or lost connection).
The exclusion constraint of the optimistic variant stays in place as a safety net, the parallel tests show that it
is never hit by the pessimistic variant.

## Proof: parallel requests

### automated tests

[ConcurrentBookingTest.java](./spring-boot-api/src/test/java/org/distributed/consensus/ConcurrentBookingTest.java) starts a
PostgreSQL container (Testcontainers) and **two api instances** against it. The requests are distributed round robin
between the instances and released at the same time by a start gate. For both variants it checks that

* 30 parallel reservations of the same period: exactly 1 × 201, 29 × 409 and one row in the database
* 30 parallel reservations of overlapping, shifted periods: exactly 1 × 201
* 10 parallel reservations of adjacent periods: all 201 (no false conflicts)
* 30 parallel confirmations of one reservation: exactly 1 × 201 and one confirmation in the database
* parallel confirms and deletes of one reservation: the booking ends either confirmed (one confirmation, deletes 409)
  or cancelled (no confirmation, confirms 409), never both

[BookingProcessTest.java](./spring-boot-api/src/test/java/org/distributed/consensus/BookingProcessTest.java) checks the
states and status codes, including the expiry (with a time to live of 2 seconds).

```console
cd spring-boot-api
mvn test
```

The tests need JDK 21 and a running docker.

### script

[parallel-requests.sh](./scripts/parallel-requests.sh) sends parallel requests against a running api (bash and curl, e.g. git bash on windows):

```console
# against the single instance
scripts/parallel-requests.sh http://localhost:8080

# against two instances behind the load balancer (see below), only the pessimistic variant, 50 parallel requests
scripts/parallel-requests.sh http://localhost:8090 pessimistic 50
```

### two instances behind a load balancer

[docker-compose.scaled.yml](./docker-compose.scaled.yml) adds a second api instance and an nginx
([loadbalancer/nginx.conf](./loadbalancer/nginx.conf)) that distributes the requests round robin without sticky sessions.
The header `X-Api-Instance` of a response shows which instance answered.

```console
docker compose -f docker-compose.yml -f docker-compose.spring.yml -f docker-compose.scaled.yml up --build
```

The load balancer is reachable under http://localhost:8090.

## Running the applications locally

### database

Start only the database container:

```console
docker compose -f docker-compose.yml up db
```

### api

```console
cd spring-boot-api
mvn spring-boot:run
```

The api listens on port 8080 and needs a reachable database, otherwise it fails to start.
The connection is configured in [application.properties](./spring-boot-api/src/main/resources/application.properties);
the tables are created by Hibernate on startup (`spring.jpa.hibernate.ddl-auto=update`), the constraints
Hibernate cannot create are added by [schema.sql](./spring-boot-api/src/main/resources/schema.sql).\
CORS is enabled for all paths by the **corsConfigurer(...)** bean in [Application.java](./spring-boot-api/src/main/java/org/distributed/consensus/Application.java).\
The image is built by this [Dockerfile](./spring-boot-api/Dockerfile).

### ui

```console
cd ui/msa-booking-app
npm install
ng serve
```

The ui is served on port 4500 and expects the api under **localhost:8080/optimistic/booking**.
When you need another address, change the hard-coded value in
[room.service.ts](./ui/msa-booking-app/src/app/service/room.service.ts).\
In docker the ui is served by nginx ([Dockerfile](./ui/msa-booking-app/Dockerfile)).

## Troubleshooting

**The api fails to start with an error about the table layout or a constraint, or every request answers with 500**\
The database still contains tables of an older version. `spring.jpa.hibernate.ddl-auto=update` only adds missing
tables and columns, it does not change existing ones (e.g. the `id` column of `bookings`, which is generated by the
database since the optimistic variant inserts with native sql). The api may start anyway, but every booking then fails
with an unexpected constraint violation (logged as `ERROR`, answered with 500).
Run `docker compose down` (this removes the database container including its data) and start again (see above).
