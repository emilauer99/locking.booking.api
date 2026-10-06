# Booking App

This application is a training example for those who want to tackle particular challenges of microservices,
in particular the synchronisation of a booking process with optimistic and pessimistic locking.
This readme shows how you can get it up and running.

## Project structure

The system consists of a database (PostgreSQL), a REST api and a web ui (Angular).
The api is available in two implementations; pick the one you prefer:

| Folder | Content |
|---|---|
| [spring-boot-api](./spring-boot-api) | REST api with Spring Boot (Java 21, JPA) |
| [dotnet-api](./dotnet-api) | REST api with ASP.NET Core (.NET 10, Entity Framework Core) |
| [ui/msa-booking-app](./ui/msa-booking-app) | Angular web ui, served by nginx in docker |
| [docker-compose.yml](./docker-compose.yml) | shared part of the system: database and ui |
| [docker-compose.spring.yml](./docker-compose.spring.yml) | adds the spring-boot api |
| [docker-compose.dotnet.yml](./docker-compose.dotnet.yml) | adds the .NET api |

## Prerequisites

* Docker with Docker Compose v2 (`docker compose ...`)

Only needed when running the applications outside of docker:

* spring-boot api: JDK 21 and Maven
* .NET api: .NET 10 SDK
* ui: Node.js 20 or later and the Angular CLI (`npm install -g @angular/cli`)

## Running the whole system with docker compose

The compose setup is split into a shared file (database and ui) and one file per api.
Always combine the shared file with exactly one api file and use the same `-f` options for every command.

### with the spring-boot api

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

The spring-boot variant is also the default configured in [.env](./.env), so the short forms
`docker compose up --build` and `docker compose down` do the same.

### with the .NET api

```console
# build the images and start database, api and ui
docker compose -f docker-compose.yml -f docker-compose.dotnet.yml up --build

# the same, but running in the background
docker compose -f docker-compose.yml -f docker-compose.dotnet.yml up --build -d

# follow the log output of the api
docker compose -f docker-compose.yml -f docker-compose.dotnet.yml logs -f app

# stop the system and remove the containers (deletes the data in the database)
docker compose -f docker-compose.yml -f docker-compose.dotnet.yml down
```

### reaching the applications

Both variants publish the api on port 8080, so the ui works with either of them.
Once everything is up

* the ui is reachable under http://localhost:80/index.html
* the api is reachable under http://localhost:8080 (e.g. http://localhost:8080/optimistic/booking)
* the database is reachable under localhost:5432 (user and password `compose-postgres`)

> [!IMPORTANT]
> The database container has no volume, therefore `docker compose down` deletes all data.
> Always run `docker compose down` before switching between the spring-boot and the .NET api.
> Both apis use different table layouts and the tables for the .NET api are only created when the database starts empty.

## Sample data

Both apis create the same sample bookings on startup when the `bookings` table is empty,
so the ui shows some rooms right away. If there is already at least one booking, nothing is added.
The seeding is implemented in [Application.java](./spring-boot-api/src/main/java/org/distributed/consensus/Application.java)
(**seedBookings(...)** bean) and in [Program.cs](./dotnet-api/Program.cs) (**SeedBookings(...)**).

> [!NOTE]
> The .NET api retries the seeding a couple of times, as the database might still be starting up.
> If the tables do not exist (see [.NET api](#net-api)), it logs an error and starts without sample data.

You can add further bookings with a POST request:

```console
curl -H 'Content-Type: application/json' \
      -d '{"name": "Blue Room", "roomId": 1, "start": "2026-11-12", "finish": "2026-11-15"}' \
      -X POST \
      http://localhost:8080/optimistic/booking
```

You can modify the fields name, roomId, start and finish in the body as you like.\
For a successful start the app looks like that
![](./images/ui.png)

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

> [!NOTE]
> The .NET api additionally offers the GET endpoints for the pessimistic variant.

The places where the synchronisation has to be implemented are marked with `TODO` in the controllers.

## Running the applications locally

### database

Start only the database container:

```console
docker compose up db
```

### spring-boot api

```console
cd spring-boot-api
mvn spring-boot:run
```

The api listens on port 8080 and needs a reachable database, otherwise it fails to start.
The connection is configured in [application.properties](./spring-boot-api/src/main/resources/application.properties);
the tables are created by Hibernate on startup (`spring.jpa.hibernate.ddl-auto=update`).\
CORS is enabled for all paths by the **corsConfigurer(...)** bean in [Application.java](./spring-boot-api/src/main/java/org/distributed/consensus/Application.java).\
The image is built by this [Dockerfile](./spring-boot-api/Dockerfile).

### .NET api

```console
cd dotnet-api
dotnet run
```

The api listens on port 5242 (see [launchSettings.json](./dotnet-api/Properties/launchSettings.json)).
The connection string is configured in [appsettings.json](./dotnet-api/appsettings.json) and the
Entity Framework context is [BookingDbContext.cs](./dotnet-api/Repository/BookingDbContext.cs).\
The tables are not created automatically. When running locally, create them once with the script
[CreateDatabase.txt](./dotnet-api/Repository/Scripts/CreateDatabase.txt), e.g.:

```console
docker exec -i db psql -U compose-postgres < dotnet-api/Repository/Scripts/CreateDatabase.txt
```

The image is built by this [Dockerfile](./dotnet-api/Dockerfile); inside the container the api listens on port 80.

### ui

```console
cd ui/msa-booking-app
npm install
ng serve
```

The ui is served on port 4500 and expects the api under **localhost:8080/optimistic/booking**.
When running the .NET api locally (port 5242) or when you need another address, change the hard-coded value in
[room.service.ts](./ui/msa-booking-app/src/app/service/room.service.ts).\
In docker the ui is served by nginx ([Dockerfile](./ui/msa-booking-app/Dockerfile)).

## Troubleshooting

**Tables are missing or have unexpected column names**\
The database was initialized by the other api. Run `docker compose down` and start again (see above).
