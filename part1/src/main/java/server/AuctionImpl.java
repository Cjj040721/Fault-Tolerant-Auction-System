package server;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.HashMap;
import java.util.Map;

public class AuctionImpl extends UnicastRemoteObject implements Auction {

    private int userCounter = 1;
    private int itemCounter = 1;
    private final Map<Integer, String> users = new HashMap<>();
    private final Map<Integer, AuctionItem> activeItems = new HashMap<>();
    private final Map<Integer, Integer> itemOwners = new HashMap<>();
    private final Map<Integer, Integer> highestBidders = new HashMap<>();

    public AuctionImpl() throws RemoteException {
        super();
    }

    @Override
    public synchronized int register(String email) {
        int uid = userCounter++;
        users.put(uid, email);
        return uid;
    }

    @Override
    public synchronized int newAuction(int userID, AuctionSaleItem item) {
        if (!users.containsKey(userID)) return -1;
        int iid = itemCounter++;
        activeItems.put(iid, new AuctionItem(iid, item.name, item.description, item.reservePrice));
        itemOwners.put(iid, userID);
        return iid;
    }

    @Override
    public synchronized AuctionItem getSpec(int itemID) {
        return activeItems.get(itemID);
    }

    @Override
    public synchronized AuctionItem[] listItems() {
        return activeItems.values().toArray(new AuctionItem[0]);
    }

    @Override
    public synchronized boolean bid(int userID, int itemID, int price) {
        if (!users.containsKey(userID)) return false;
        AuctionItem item = activeItems.get(itemID);
        if (item == null) return false;
        if (price > item.highestBid && price >= item.reservePrice) {
            item.highestBid = price;
            highestBidders.put(itemID, userID);
            return true;
        }
        return false;
    }

    @Override
    public synchronized AuctionResult closeAuction(int userID, int itemID) {
        AuctionItem item = activeItems.get(itemID);
        if (item == null) return null;
        Integer owner = itemOwners.get(itemID);
        if (owner == null || owner != userID) return null;
        activeItems.remove(itemID);
        int winner = highestBidders.getOrDefault(itemID, 0);
        return new AuctionResult(itemID, winner, item.highestBid);
    }
}
