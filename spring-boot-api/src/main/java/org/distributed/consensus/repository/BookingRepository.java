package org.distributed.consensus.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.distributed.consensus.model.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    /**
     * Finds all bookings of a room that overlap with the period [start, finish) and still block it,
     * i.e. confirmed bookings and reservations that are not expired yet.
     */
    @Query("""
            select b from Booking b
            where b.roomId = :roomId
              and b.start < :finish
              and b.finish > :start
              and (b.status = CONFIRMED
                   or (b.status = RESERVED and b.expiresAt > :now))
            """)
    List<Booking> findActiveOverlapping(@Param("roomId") long roomId,
                                        @Param("start") LocalDate start,
                                        @Param("finish") LocalDate finish,
                                        @Param("now") Instant now);

    /**
     * Loads a booking and locks its row (SELECT ... FOR UPDATE) until the surrounding transaction ends.
     * Parallel confirm/delete requests for the same booking - on any api instance - wait here
     * and see the state written by the first one afterwards.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> findByIdForUpdate(@Param("id") long id);

    /**
     * Inserts a reservation, unless it would violate the exclusion constraint bookings_no_overlap.
     * <p>
     * A plain INSERT that violates an exclusion constraint can deadlock with a parallel INSERT of an
     * overlapping booking: both insert their row first, then each waits for the other one while checking
     * the constraint, until PostgreSQL aborts one of them after deadlock_timeout (1 s).
     * With ON CONFLICT DO NOTHING PostgreSQL uses speculative insertion, which checks for conflicts before
     * the row is finally inserted and avoids these deadlocks. The conflict is still detected by the
     * database when writing, nothing is locked up front.
     *
     * @return the id of the new booking, or an empty list if the period is already booked
     */
    @Transactional
    @Query(value = """
            insert into bookings (name, room_id, start, finish, status, created_at, expires_at, version)
            values (:name, :roomId, :start, :finish, 'RESERVED', :createdAt, :expiresAt, 0)
            on conflict do nothing
            returning id
            """, nativeQuery = true)
    List<Long> insertReservationIfFree(@Param("name") String name,
                                       @Param("roomId") long roomId,
                                       @Param("start") LocalDate start,
                                       @Param("finish") LocalDate finish,
                                       @Param("createdAt") Instant createdAt,
                                       @Param("expiresAt") Instant expiresAt);

    default boolean existsActiveOverlapping(long roomId, LocalDate start, LocalDate finish, Instant now) {
        return !findActiveOverlapping(roomId, start, finish, now).isEmpty();
    }

    /**
     * Marks the expired reservations of a room that overlap with [start, finish) as EXPIRED, so the
     * exclusion constraint in the database no longer treats them as blocking.
     * The version is incremented as well: a confirmation that read the reservation before
     * it was expired fails with an optimistic locking conflict instead of confirming it.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Booking b
            set b.status = EXPIRED, b.version = b.version + 1
            where b.roomId = :roomId
              and b.start < :finish
              and b.finish > :start
              and b.status = RESERVED
              and b.expiresAt <= :now
            """)
    int expireOverlappingReservations(@Param("roomId") long roomId,
                                      @Param("start") LocalDate start,
                                      @Param("finish") LocalDate finish,
                                      @Param("now") Instant now);
}
