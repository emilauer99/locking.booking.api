package org.distributed.consensus;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * States of the booking process and the http status codes, for both locking variants.
 * The reservation ttl is reduced to 2 seconds, so the expiry can be tested without waiting 5 minutes.
 */
class BookingProcessTest extends ApiTestSupport {

    private static final long TTL_MILLIS = 2000;

    private static ConfigurableApplicationContext instance;
    private static String api;

    @BeforeAll
    static void startApi() {
        instance = startInstance("--booking.reservation-ttl=PT2S");
        api = baseUrl(instance);
    }

    @AfterAll
    static void stopApi() {
        instance.close();
    }

    @ParameterizedTest
    @MethodSource("variants")
    void reserveAndConfirm(String variant) {
        HttpResponse<String> reservation = reserve(api, variant, newRoomId(), "2027-07-01", "2027-07-05");
        assertThat(reservation.statusCode()).isEqualTo(201);
        assertThat(reservation.body()).contains("\"status\":\"RESERVED\"");

        assertThat(confirm(api, variant, idOf(reservation)).statusCode()).isEqualTo(201);
    }

    @ParameterizedTest
    @MethodSource("variants")
    void expiredReservation_cannotBeConfirmed_andPeriodCanBeBookedAgain(String variant) throws Exception {
        long roomId = newRoomId();
        long expiredId = idOf(reserve(api, variant, roomId, "2027-07-01", "2027-07-05"));
        assertThat(reserve(api, variant, roomId, "2027-07-01", "2027-07-05").statusCode())
                .as("period is blocked while the reservation is valid").isEqualTo(409);

        Thread.sleep(TTL_MILLIS + 500);

        assertThat(confirm(api, variant, expiredId).statusCode()).as("confirm after expiry").isEqualTo(409);
        assertThat(reserve(api, variant, roomId, "2027-07-01", "2027-07-05").statusCode())
                .as("period is bookable again after expiry").isEqualTo(201);
    }

    @ParameterizedTest
    @MethodSource("variants")
    void deletedReservation_cannotBeConfirmed_andPeriodCanBeBookedAgain(String variant) {
        long roomId = newRoomId();
        long id = idOf(reserve(api, variant, roomId, "2027-08-01", "2027-08-05"));

        assertThat(delete(api, variant, id).statusCode()).isEqualTo(204);
        assertThat(delete(api, variant, id).statusCode()).as("delete is idempotent").isEqualTo(204);
        assertThat(confirm(api, variant, id).statusCode()).as("confirm after delete").isEqualTo(409);
        assertThat(reserve(api, variant, roomId, "2027-08-01", "2027-08-05").statusCode()).isEqualTo(201);
    }

    @ParameterizedTest
    @MethodSource("variants")
    void confirmedBooking_cannotBeDeletedOrConfirmedAgain(String variant) {
        long id = idOf(reserve(api, variant, newRoomId(), "2027-09-01", "2027-09-05"));
        assertThat(confirm(api, variant, id).statusCode()).isEqualTo(201);

        assertThat(confirm(api, variant, id).statusCode()).as("second confirm").isEqualTo(409);
        assertThat(delete(api, variant, id).statusCode()).as("delete after confirm").isEqualTo(409);
    }

    @ParameterizedTest
    @MethodSource("variants")
    void invalidRequests(String variant) {
        assertThat(reserve(api, variant, newRoomId(), "2027-10-05", "2027-10-01").statusCode())
                .as("start after finish").isEqualTo(400);
        assertThat(reserve(api, variant, -1, "2027-10-01", "2027-10-05").statusCode())
                .as("negative room id").isEqualTo(400);
        assertThat(confirm(api, variant, 999_999).statusCode()).as("unknown booking").isEqualTo(404);
        assertThat(delete(api, variant, 999_999).statusCode()).as("unknown booking").isEqualTo(404);
    }
}
