package org.distributed.consensus.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exclusive lock on a room, held by the database.
 * <p>
 * A new booking cannot lock a row with SELECT ... FOR UPDATE, as the conflicting booking
 * might not exist yet (two parallel requests both see "no overlapping booking").
 * Therefore a PostgreSQL transaction-level advisory lock with the room id as key is used:
 * <ul>
 *     <li>it lives in the database, so it works across all api instances</li>
 *     <li>it is released automatically at the end of the transaction (commit, rollback or
 *     lost connection), so it is never held across several http requests</li>
 * </ul>
 */
@Repository
public class RoomLockRepository {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Blocks until no other transaction holds the lock of this room.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockRoom(long roomId) {
        // pg_advisory_xact_lock returns "void", which Hibernate cannot map -> select a constant instead
        entityManager.createNativeQuery("select 1 from pg_advisory_xact_lock(:roomId)")
                .setParameter("roomId", roomId)
                .getSingleResult();
    }
}
