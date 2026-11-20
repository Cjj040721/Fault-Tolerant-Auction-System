package replica;

import common.LogEntry;
import common.Operation;
import common.OperationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReplicaImplTest {
    @Test
    void leaderAssignsMonotonicIdsAndAppliesOperations() throws Exception {
        ReplicaImpl leader = new ReplicaImpl(1, "replica1");
        leader.setSequencer(true);

        assertEquals(1, leader.handleClientOperation(Operation.register("alice@example.com"), List.of("replica1")).userId);
        assertEquals(2, leader.handleClientOperation(Operation.register("bob@example.com"), List.of("replica1")).userId);
        assertEquals(1, leader.handleClientOperation(Operation.newAuction(1, "Laptop", "Gaming", 500), List.of("replica1")).itemId);
        assertTrue(leader.handleClientOperation(Operation.bid(2, 1, 500), List.of("replica1")).bidOk);
        assertFalse(leader.handleClientOperation(Operation.bid(2, 1, 400), List.of("replica1")).bidOk);

        OperationResult close = leader.handleClientOperation(Operation.close(1, 1), List.of("replica1"));
        assertTrue(close.ok);
        assertEquals(2, close.closeWinner);
        assertEquals(500, close.closePrice);
        assertEquals(6, leader.getLastCommittedSeqNo());
        assertEquals(0, leader.listItems().length);
    }

    @Test
    void minorityAcknowledgementRejectsWrite() throws Exception {
        ReplicaImpl leader = new ReplicaImpl(1, "replica1");
        leader.setSequencer(true);
        List<String> members = List.of("replica1", "replica2", "replica3");

        OperationResult result = leader.handleClientOperation(Operation.register("alice@example.com"), members);

        assertFalse(result.ok);
        assertEquals(0, leader.getLastCommittedSeqNo());
        assertTrue(leader.getEntriesAfter(-1).isEmpty());
    }

    @Test
    void followerDoesNotSkipMissingLogEntries() throws Exception {
        ReplicaImpl follower = new ReplicaImpl(2, "replica2");
        follower.propose(1, Operation.register("alice@example.com"));
        follower.propose(2, Operation.register("bob@example.com"));
        follower.commitUpTo(2);
        assertEquals(2, follower.getLastCommittedSeqNo());

        follower.propose(4, Operation.register("dave@example.com"));
        follower.commitUpTo(4);
        assertEquals(2, follower.getLastCommittedSeqNo());

        follower.applyLogEntry(new LogEntry(3, Operation.register("carol@example.com")));
        follower.commitUpTo(4);
        assertEquals(4, follower.getLastCommittedSeqNo());
        assertEquals(4, follower.getEntriesAfter(-1).size());
    }

    @Test
    void newLeaderContinuesFromCommittedSequence() throws Exception {
        ReplicaImpl replica = new ReplicaImpl(2, "replica2");
        replica.propose(1, Operation.register("alice@example.com"));
        replica.propose(2, Operation.register("bob@example.com"));
        replica.commitUpTo(2);
        replica.setSequencer(true);

        OperationResult result = replica.handleClientOperation(Operation.register("carol@example.com"), List.of("replica2"));

        assertTrue(result.ok);
        assertEquals(3, result.userId);
        assertEquals(3, replica.getLastCommittedSeqNo());
    }

    @Test
    void nonLeaderRejectsWrite() throws Exception {
        ReplicaImpl follower = new ReplicaImpl(2, "replica2");
        OperationResult result = follower.handleClientOperation(Operation.register("alice@example.com"), List.of("replica2"));

        assertFalse(result.ok);
        assertEquals(0, follower.getLastCommittedSeqNo());
    }
}
