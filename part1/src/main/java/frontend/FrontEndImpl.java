package frontend;

import io.grpc.stub.StreamObserver;
import server.*;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

public class FrontEndImpl extends AuctionServiceGrpc.AuctionServiceImplBase {
    private final Auction auction;

    public FrontEndImpl() throws Exception {
        Registry reg = LocateRegistry.getRegistry();
        this.auction = (Auction) reg.lookup("AuctionServer");
    }

    @Override
    public void register(RegisterRequest req, StreamObserver<RegisterReply> resp) {
        try {
            int id = auction.register(req.getEmail());
            resp.onNext(RegisterReply.newBuilder().setUserId(id).build());
            resp.onCompleted();
        } catch (Exception e) { resp.onError(e); }
    }

    @Override
    public void newAuction(NewAuctionRequest req, StreamObserver<NewAuctionReply> resp) {
        try {
            AuctionSaleItem item = new AuctionSaleItem(
                req.getName(), req.getDescription(), req.getReservePrice());
            int iid = auction.newAuction(req.getUserId(), item);
            resp.onNext(NewAuctionReply.newBuilder().setItemId(iid).build());
            resp.onCompleted();
        } catch (Exception e) { resp.onError(e); }
    }

    @Override
    public void bid(BidRequest req, StreamObserver<BidReply> resp) {
        try {
            boolean ok = auction.bid(req.getUserId(), req.getItemId(), req.getPrice());
            resp.onNext(BidReply.newBuilder().setSuccess(ok).build());
            resp.onCompleted();
        } catch (Exception e) { resp.onError(e); }
    }

    @Override
    public void listItems(Empty req, StreamObserver<ListReply> resp) {
        try {
            AuctionItem[] items = auction.listItems();
            ListReply.Builder builder = ListReply.newBuilder();
            if (items != null) {
                for (AuctionItem ai : items) {
                    builder.addItems(Item.newBuilder()
                        .setItemId(ai.itemID)
                        .setName(ai.name)
                        .setDescription(ai.description)
                        .setReservePrice(ai.reservePrice)
                        .setHighestBid(ai.highestBid)
                        .build());
                }
            }
            resp.onNext(builder.build());
            resp.onCompleted();
        } catch (Exception e) { resp.onError(e); }
    }

    @Override
    public void getSpec(GetSpecRequest req, StreamObserver<Item> resp) {
        try {
            AuctionItem ai = auction.getSpec(req.getItemId());
            if (ai == null) {
                resp.onNext(Item.newBuilder().build());
            } else {
                resp.onNext(Item.newBuilder()
                    .setItemId(ai.itemID)
                    .setName(ai.name)
                    .setDescription(ai.description)
                    .setReservePrice(ai.reservePrice)
                    .setHighestBid(ai.highestBid)
                    .build());
            }
            resp.onCompleted();
        } catch (Exception e) { resp.onError(e); }
    }

    @Override
    public void closeAuction(CloseRequest req, StreamObserver<AuctionResult> resp) {
        try {
            server.AuctionResult ar = auction.closeAuction(req.getUserId(), req.getItemId());
            if (ar == null) {
                resp.onNext(AuctionResult.newBuilder().build());
            } else {
                resp.onNext(AuctionResult.newBuilder()
                    .setItemId(ar.itemID)
                    .setWinningUser(ar.winningUser)
                    .setPrice(ar.price)
                    .build());
            }
            resp.onCompleted();
        } catch (Exception e) { resp.onError(e); }
    }
}
