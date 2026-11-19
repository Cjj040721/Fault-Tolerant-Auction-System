package client;

import frontend.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

public class AuctionClient {
    public static void main(String[] args) {
        ManagedChannel ch = ManagedChannelBuilder.forAddress("localhost", 50055)
                .usePlaintext().build();
        var stub = AuctionServiceGrpc.newBlockingStub(ch);

        System.out.println("=== Register users ===");
        int alice = stub.register(RegisterRequest.newBuilder().setEmail("alice@example.com").build()).getUserId();
        int bob   = stub.register(RegisterRequest.newBuilder().setEmail("bob@example.com").build()).getUserId();
        int carol = stub.register(RegisterRequest.newBuilder().setEmail("carol@example.com").build()).getUserId();
        //int dave  = stub.register(RegisterRequest.newBuilder().setEmail("dave@example.com").build()).getUserId();
        System.out.printf("alice=%d  bob=%d  carol=%d%n", alice, bob, carol);
        //System.out.printf("alice=%d  bob=%d  carol=%d  dave=%d%n", alice, bob, carol, dave);

        System.out.println("\n=== Create auctions ===");
        int item1 = stub.newAuction(NewAuctionRequest.newBuilder()
                .setUserId(alice).setName("Laptop").setDescription("Gaming laptop").setReservePrice(500).build()).getItemId();
        int item2 = stub.newAuction(NewAuctionRequest.newBuilder()
                .setUserId(bob).setName("Phone").setDescription("Smartphone").setReservePrice(200).build()).getItemId();
        System.out.printf("item1(Laptop)=%d  item2(Phone)=%d%n", item1, item2);

        // Unregistered user tries to create auction
        int badItem = stub.newAuction(NewAuctionRequest.newBuilder()
                .setUserId(999).setName("Ghost").setDescription("n/a").setReservePrice(1).build()).getItemId();
        System.out.println("Unregistered user auction (expect -1): " + badItem);

        System.out.println("\n=== List items ===");
        var list = stub.listItems(Empty.newBuilder().build());
        list.getItemsList().forEach(i ->
            System.out.printf("  id=%d name=%s reserve=%d highestBid=%d%n",
                i.getItemId(), i.getName(), i.getReservePrice(), i.getHighestBid()));

        System.out.println("\n=== Bidding ===");
        // Below reserve — rejected
        boolean b1 = stub.bid(BidRequest.newBuilder().setUserId(bob).setItemId(item1).setPrice(100).build()).getSuccess();
        System.out.println("Bob bids 100 on Laptop (reserve 500, expect false): " + b1);

        // At reserve — accepted
        boolean b2 = stub.bid(BidRequest.newBuilder().setUserId(bob).setItemId(item1).setPrice(500).build()).getSuccess();
        System.out.println("Bob bids 500 on Laptop (expect true): " + b2);

        // Outbid — accepted
        boolean b3 = stub.bid(BidRequest.newBuilder().setUserId(carol).setItemId(item1).setPrice(750).build()).getSuccess();
        System.out.println("Carol bids 750 on Laptop (expect true): " + b3);

        // Lower than current highest — rejected
        boolean b4 = stub.bid(BidRequest.newBuilder().setUserId(bob).setItemId(item1).setPrice(600).build()).getSuccess();
        System.out.println("Bob bids 600 on Laptop (< 750, expect false): " + b4);

        // Bid on non-existent item
        boolean b5 = stub.bid(BidRequest.newBuilder().setUserId(alice).setItemId(999).setPrice(1000).build()).getSuccess();
        System.out.println("Bid on non-existent item (expect false): " + b5);

        System.out.println("\n=== GetSpec ===");
        var spec = stub.getSpec(GetSpecRequest.newBuilder().setItemId(item1).build());
        System.out.printf("Laptop spec: reserve=%d highestBid=%d%n", spec.getReservePrice(), spec.getHighestBid());

        System.out.println("\n=== Close auction ===");
        // Non-owner tries to close
        var r1 = stub.closeAuction(CloseRequest.newBuilder().setUserId(bob).setItemId(item1).build());
        System.out.println("Bob closes Alice's auction (expect 0,0,0): itemId=" + r1.getItemId()
                + " winner=" + r1.getWinningUser() + " price=" + r1.getPrice());

        // Owner closes
        var r2 = stub.closeAuction(CloseRequest.newBuilder().setUserId(alice).setItemId(item1).build());
        System.out.printf("Alice closes Laptop: winner=%d price=%d%n", r2.getWinningUser(), r2.getPrice());

        // Close again — already closed
        var r3 = stub.closeAuction(CloseRequest.newBuilder().setUserId(alice).setItemId(item1).build());
        System.out.println("Close again (expect 0,0,0): itemId=" + r3.getItemId());

        // Bid on closed item
        boolean b6 = stub.bid(BidRequest.newBuilder().setUserId(bob).setItemId(item1).setPrice(9999).build()).getSuccess();
        System.out.println("Bid on closed item (expect false): " + b6);

        // Close auction with no bids
        var r4 = stub.closeAuction(CloseRequest.newBuilder().setUserId(bob).setItemId(item2).build());
        System.out.printf("Bob closes Phone (no bids): winner=%d price=%d%n", r4.getWinningUser(), r4.getPrice());

        System.out.println("\n=== List after closes ===");
        stub.listItems(Empty.newBuilder().build()).getItemsList().forEach(i ->
            System.out.printf("  id=%d name=%s%n", i.getItemId(), i.getName()));

        ch.shutdown();
    }
}
