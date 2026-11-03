package org.distributed.consensus;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves with parallel requests that no double booking is possible, for both locking variants.
 * <p>
 * Two api instances are started against the same database and the requests are distributed
 * round robin between them (like a load balancer without sticky sessions), so the
 * synchronisation has to work across instance boundaries.
 */
class ConcurrentBookingTest extends ApiTestSupport {

    private static final int PARALLEL_REQUESTS = 30;

    private static ConfigurableApplicationContext instance1;
    private static ConfigurableApplicationContext instance2;
    private static JdbcTemplate db;

    @BeforeAll
    static void startInstances() {
        // started one after the other, both create/update the same schema on startup
        instance1 = startInstance();
        instance2 = startInstance();
        db = jdbc(instance1);
    }

    @AfterAll
    static void stopInstances() {
        instance1.close();
        instance2.close();
    }

    /**
     * Round robin: even requests go to instance 1, odd requests to instance 2.
     */
    private static String instance(int requestIndex) {
        return baseUrl(requestIndex % 2 == 0 ? instance1 : instance2);
    }

    @ParameterizedTest
    @MethodSource("variants")
    void parallelReservationsOfTheSamePeriod_onlyOneSucceeds(String variant) throws Exception {
        long roomId = newRoomId();

        List<Integer> statusCodes = inParallel(PARALLEL_REQUESTS,
                i -> reserve(instance(i), variant, roomId, "2027-02-01", "2027-02-05"));

        assertThat(count(statusCodes, 201)).as("successful reservations").isEqualTo(1);
        assertThat(count(statusCodes, 409)).as("rejected reservations").isEqualTo(PARALLEL_REQUESTS - 1);
        assertThat(bookingsOfRoom(roomId)).as("bookings in the database").isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("variants")
    void parallelReservationsOfOverlappingPeriods_onlyOneSucceeds(String variant) throws Exception {
        long roomId = newRoomId();
        LocalDate base = LocalDate.parse("2027-03-01");

        // periods shifted by 0, 1, 2 days with a length of 3 days -> all of them overlap
        List<Integer> statusCodes = inParallel(PARALLEL_REQUESTS, i -> {
            LocalDate start = base.plusDays(i % 3);
            return reserve(instance(i), variant, roomId, start.toString(), start.plusDays(3).toString());
        });

        assertThat(count(statusCodes, 201)).as("successful reservations").isEqualTo(1);
        assertThat(count(statusCodes, 409)).as("rejected reservations").isEqualTo(PARALLEL_REQUESTS - 1);
        assertThat(bookingsOfRoom(roomId)).as("bookings in the database").isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("variants")
    void parallelReservationsOfAdjacentPeriods_allSucceed(String variant) throws Exception {
        long roomId = newRoomId();
        LocalDate base = LocalDate.parse("2027-04-01");

        // [day i, day i+1) -> the periods touch but do not overlap, nothing must be rejected
        List<Integer> statusCodes = inParallel(10, i -> {
            LocalDate start = base.plusDays(i);
            return reserve(instance(i), variant, roomId, start.toString(), start.plusDays(1).toString());
        });

        assertThat(count(statusCodes, 201)).as("successful reservations").isEqualTo(10);
        assertThat(bookingsOfRoom(roomId)).as("bookings in the database").isEqualTo(10);
    }

    @ParameterizedTest
    @MethodSource("variants")
    void parallelConfirmationsOfOneReservation_onlyOneSucceeds(String variant) throws Exception {
        long bookingId = idOf(reserve(baseUrl(instance1), variant, newRoomId(), "2027-05-01", "2027-05-05"));

        List<Integer> statusCodes = inParallel(PARALLEL_REQUESTS, i -> confirm(instance(i), variant, bookingId));

        assertThat(count(statusCodes, 201)).as("successful confirmations").isEqualTo(1);
        assertThat(count(statusCodes, 409)).as("rejected confirmations").isEqualTo(PARALLEL_REQUESTS - 1);
        assertThat(confirmationsOf(bookingId)).as("confirmations in the database").isEqualTo(1);
        assertThat(statusOf(bookingId)).isEqualTo("CONFIRMED");
    }

    @ParameterizedTest
    @MethodSource("variants")
    void parallelConfirmAndDelete_endInExactlyOneFinalState(String variant) throws Exception {
        // repeated, as the winner of the race differs from run to run
        for (int run = 0; run < 5; run++) {
            long bookingId = idOf(reserve(baseUrl(instance1), variant, newRoomId(), "2027-06-01", "2027-06-05"));

            // even index: confirm, odd index: delete
            List<Integer> statusCodes = inParallel(20, i -> i % 2 == 0
                    ? confirm(instance(i), variant, bookingId)
                    : delete(instance(i), variant, bookingId));
            List<Integer> confirms = everySecond(statusCodes, 0);
            List<Integer> deletes = everySecond(statusCodes, 1);

            String status = statusOf(bookingId);
            if (status.equals("CONFIRMED")) {
                // confirm won: exactly one confirmation, a confirmed booking cannot be deleted
                assertThat(count(confirms, 201)).isEqualTo(1);
                assertThat(count(confirms, 409)).isEqualTo(confirms.size() - 1);
                assertThat(deletes).containsOnly(409);
                assertThat(confirmationsOf(bookingId)).isEqualTo(1);
            } else {
                // delete won: a cancelled booking cannot be confirmed
                assertThat(status).isEqualTo("CANCELLED");
                assertThat(confirms).containsOnly(409);
                assertThat(confirmationsOf(bookingId)).isZero();
                if (variant.equals("pessimistic")) {
                    // the deletes wait for each other and see the cancelled booking -> idempotent
                    assertThat(deletes).containsOnly(204);
                } else {
                    // optimistic: deletes that read the same version as the winning delete
                    // lose the race when writing (version conflict) -> 409, the client has to reload
                    assertThat(deletes).containsAnyOf(204).isSubsetOf(204, 409);
                }
            }
        }
    }

    private static List<Integer> everySecond(List<Integer> statusCodes, int offset) {
        return java.util.stream.IntStream.range(0, statusCodes.size())
                .filter(i -> i % 2 == offset)
                .mapToObj(statusCodes::get)
                .toList();
    }

    private static long bookingsOfRoom(long roomId) {
        return db.queryForObject("select count(*) from bookings where room_id = ?", Long.class, roomId);
    }

    private static long confirmationsOf(long bookingId) {
        return db.queryForObject("select count(*) from bookingsconfirmation where booking_id = ?", Long.class, bookingId);
    }

    private static String statusOf(long bookingId) {
        return db.queryForObject("select status from bookings where id = ?", String.class, bookingId);
    }
}
