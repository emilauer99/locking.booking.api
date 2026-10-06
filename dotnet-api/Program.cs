using Microsoft.EntityFrameworkCore;
using Repository;
using Entities;

var builder = WebApplication.CreateBuilder(args);

// Add services to the container.
// Learn more about configuring OpenAPI at https://aka.ms/aspnet/openapi
builder.Services.AddOpenApi();

builder.Services.AddDbContext<BookingDbContext>(options =>
    options.UseNpgsql(
        builder.Configuration.GetConnectionString("DefaultConnection")));

// CORS is enabled for all origins, methods and headers so that the ui
// (served from another port) can call the api from the browser
builder.Services.AddCors(options =>
    options.AddDefaultPolicy(policy =>
        policy.AllowAnyOrigin()
            .AllowAnyMethod()
            .AllowAnyHeader()));

var app = builder.Build();

app.UseHttpsRedirection();

app.UseCors();

MapOptimisticLockingApi(app);

MapPessimisticLockingApi(app);

SeedBookings(app);

app.Run();

static void SeedBookings(WebApplication app)
{
    // the database container might still be starting up (docker compose does not
    // wait until postgres accepts connections), therefore retry a couple of times
    const int maxAttempts = 10;
    for (var attempt = 1; attempt <= maxAttempts; attempt++)
    {
        try
        {
            using var scope = app.Services.CreateScope();
            var db = scope.ServiceProvider.GetRequiredService<BookingDbContext>();

            // only seed an empty database, otherwise every restart (or every further
            // api instance) would add the same bookings again
            if (db.Booking.Any())
            {
                return;
            }

            db.Booking.AddRange(
                new Booking("Blue Room", 1, new DateTime(2026, 11, 2), new DateTime(2026, 11, 6)),
                new Booking("Blue Room", 1, new DateTime(2026, 11, 20), new DateTime(2026, 11, 23)),
                new Booking("Green Room", 2, new DateTime(2026, 11, 10), new DateTime(2026, 11, 14)),
                new Booking("Red Suite", 3, new DateTime(2026, 12, 22), new DateTime(2026, 12, 27)),
                new Booking("Garden View", 4, new DateTime(2027, 1, 5), new DateTime(2027, 1, 9)));
            db.SaveChanges();

            app.Logger.LogInformation("Seeded database with sample bookings.");
            return;
        }
        catch (Exception e) when (attempt < maxAttempts)
        {
            app.Logger.LogWarning("Seeding the database failed (attempt {Attempt}/{MaxAttempts}): {Message}", attempt, maxAttempts, e.Message);
            Thread.Sleep(TimeSpan.FromSeconds(2));
        }
        catch (Exception e)
        {
            // e.g. the tables were not created (see Repository/Scripts/CreateDatabase.txt);
            // the api still starts, only without sample data
            app.Logger.LogError("Seeding the database failed, starting without sample data: {Message}", e.Message);
        }
    }
}

static void MapOptimisticLockingApi(WebApplication app)
{
    const string optimisticUrlPath = "optimistic";

    MapBookingApi(app, optimisticUrlPath);

    app.MapPost(
    $"/{optimisticUrlPath}/booking",
    (Booking booking) =>
    {
        // unfortunately we cannot guarantee that all locations which might
        // evaluate to false here are not somehow offered to our customers
        // (e.g.: shared links in social medias, clients that are working
        // with old data ... )
        // Therefore this check is crucial
        if (!CanBook(booking))
        {
            return Results.Conflict("The room is not available for the selected time frame.");
        }

        using var scope = app.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<BookingDbContext>();
        db.Booking.Add(booking);
        db.SaveChanges();
        return Results.Created(
            $"/optimistic/booking/{booking.Id}",
            booking);
    })
    .WithName($"{optimisticUrlPath}CreateBooking");

    app.MapGet(
        "/optimistic/bookingconfirmation",
        () =>
        {
            using var scope = app.Services.CreateScope();
            var db = scope.ServiceProvider.GetRequiredService<BookingDbContext>();
            var confirmation = db.Confirmation;
            return !confirmation?.Any() ?? true ?
                Results.NoContent() :
                Results.Ok(confirmation.ToList());
        })
        .WithName("OptimisticGetAllBookingConfirmation");

    app.MapGet(
        "/optimistic/bookingconfirmation/{id}",
        (int id) =>
        {
            using var scope = app.Services.CreateScope();
            var db = scope.ServiceProvider.GetRequiredService<BookingDbContext>();
            var confirmation = db.Confirmation.Where(c => c.BookingId == id).FirstOrDefault();
            return confirmation == null ?
                Results.NotFound() :
                Results.Ok(new { Id = id, Status = "Confirmed" });
        })
        .WithName("OptimisticGetSpecificBookingConfirmation");

    app.MapPost(
        "/optimistic/bookingconfirmation",
        (BookingConfirmation bookingConfirmation) =>
        {
            // TODO: make sure that the same room cannot be booked more than once
            // intention: as it is expected that a collisions of bookings are rare
            // we believe that we will not run into this error here very often

            using var scope = app.Services.CreateScope();
            var db = scope.ServiceProvider.GetRequiredService<BookingDbContext>();
            db.Confirmation.Add(bookingConfirmation);
            db.SaveChanges();
            return Results.Created(
                $"/optimistic/bookingconfirmation/{bookingConfirmation.Id}",
                bookingConfirmation);
        })
        .WithName("OptimisticCreateBookingConfirmation");
}

static void MapPessimisticLockingApi(WebApplication app)
{
    const string pessimisticUrlPath = "pessimistic";

    MapBookingApi(app, pessimisticUrlPath);

    app.MapPost(
    $"/{pessimisticUrlPath}/booking",
    (Booking booking) =>
    {
        // unfortunately we cannot guarantee that all locations which might
        // evaluate to false here are not somehow offered to our customers
        // (e.g.: shared links in social medias, clients that are working
        // with old data ... )
        // Therefore this check is crucial
        if (!CanBook(booking))
        {
            return Results.Conflict("The room is not available for the selected time frame.");
        }

        // TODO: assure that the same room cannot be booked twice
        // intention: it might happen for some locations that booking attempts to the same rooms are
        // made in parallel. Consequently it is better when we prevent this very early in the process

        using var scope = app.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<BookingDbContext>();
        db.Booking.Add(booking);
        db.SaveChanges();
        return Results.Created(
            $"/{pessimisticUrlPath}/booking/{booking.Id}",
            booking);
    })
    .WithName($"{pessimisticUrlPath}CreateBooking");

    app.MapGet(
        "/pessimistic/bookingconfirmation",
        () =>
        {
            using var scope = app.Services.CreateScope();
            var db = scope.ServiceProvider.GetRequiredService<BookingDbContext>();
            var confirmation = db.Confirmation;
            return !confirmation?.Any() ?? true ?
                Results.NoContent() :
                Results.Ok(confirmation.ToList());
        })
        .WithName("PessimisticGetAllBookingConfirmation");

    app.MapGet(
        "/pessimistic/bookingconfirmation/{id}",
        (int id) =>
        {
            using var scope = app.Services.CreateScope();
            var db = scope.ServiceProvider.GetRequiredService<BookingDbContext>();
            var confirmation = db.Confirmation.Where(c => c.BookingId == id).FirstOrDefault();
            return confirmation == null ?
                Results.NotFound() :
                Results.Ok(new { Id = id, Status = "Confirmed" });
        })
        .WithName("PessimisticGetSpecificBookingConfirmation");

    app.MapPost(
        "/pessimistic/bookingconfirmation",
        (BookingConfirmation bookingConfirmation) =>
        {
            // This can usually not fail as we are using pessimistic locking
            // When this crashes it means that some infrastructure problem occurred
            // When the confirmation is not written it is indicating that the transaction
            // flow was aborted unexpectedly
            using var scope = app.Services.CreateScope();
            var db = scope.ServiceProvider.GetRequiredService<BookingDbContext>();
            db.Confirmation.Add(bookingConfirmation);
            db.SaveChanges();
            return Results.Created(
                $"/pessimistic/bookingconfirmation/{bookingConfirmation.Id}",
                bookingConfirmation);
        })
        .WithName("PessimisticCreateBookingConfirmation");
}

static void MapBookingApi(WebApplication app, string apiSubpath)
{
    app.MapGet(
        $"/{apiSubpath}/booking",
        () =>
        {
            using var scope = app.Services.CreateScope();
            var db = scope.ServiceProvider.GetRequiredService<BookingDbContext>();
            var bookings = db.Booking;
            return !bookings?.Any() ?? true ?
                Results.NoContent() :
                Results.Ok(bookings.ToList());
        })
        .WithName($"{apiSubpath}GetAllBookings");

    app.MapGet(
        $"/{apiSubpath}/booking/{{id}}",
        (long id) =>
        {
            using var scope = app.Services.CreateScope();
            var db = scope.ServiceProvider.GetRequiredService<BookingDbContext>();
            var booking = db.Booking.Find(id);
            return booking == null ?
                Results.NotFound() :
                Results.Ok(booking);
        })
        .WithName($"{apiSubpath}GetSpecificBooking");

    app.MapDelete(
        $"/{apiSubpath}/booking/{{id}}",
        (long id) =>
        {
            using var scope = app.Services.CreateScope();
            var db = scope.ServiceProvider.GetRequiredService<BookingDbContext>();
            var booking = db.Booking.Find(id);
            if (booking == null)
            {
                return Results.NotFound();
            }
            db.Booking.Remove(booking);
            db.SaveChanges();
            return Results.NoContent();
        })
        .WithName($"{apiSubpath}DeleteBooking");
}

static bool CanBook(Booking booking)
{
    // here are a couple of expensive checks  like:
        // checking if the location was already banned from the platform
        // (e.g.: as a consequence of bad feedback).
        // checking if authorities locked this location
        // (e.g.: risk of life and health)
        // checking if the location was already shut down
        // ...
    return true;
}