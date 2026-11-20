package server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuctionImplTest {
    @Test
    void auctionRulesAndOwnerChecksAreConsistent() throws Exception {
        AuctionImpl auction = new AuctionImpl();
        int alice = auction.register("alice@example.com");
        int bob = auction.register("bob@example.com");
        int item = auction.newAuction(alice, new AuctionSaleItem("Laptop", "Gaming", 500));

        assertEquals(1, alice);
        assertEquals(2, bob);
        assertEquals(1, item);
        assertEquals(-1, auction.newAuction(99, new AuctionSaleItem("Invalid", "Unknown user", 10)));
        assertFalse(auction.bid(bob, item, 100));
        assertTrue(auction.bid(bob, item, 500));
        assertFalse(auction.bid(alice, item, 400));
        assertNull(auction.closeAuction(bob, item));

        AuctionResult result = auction.closeAuction(alice, item);
        assertNotNull(result);
        assertEquals(bob, result.winningUser);
        assertEquals(500, result.price);
        assertFalse(auction.bid(bob, item, 1000));
        assertEquals(0, auction.listItems().length);
    }
}
