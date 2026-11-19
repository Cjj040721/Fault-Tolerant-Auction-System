package replica;
import java.io.Serializable;

/**
 * 关闭拍卖后的结果，由 closeAuction() 返回，经 apply() 包装成 OperationResult 传回给 FrontEnd。
 * 实现 Serializable 以便通过 RMI 传输。
 *
 * The result of closing an auction, returned by closeAuction() and wrapped into
 * an OperationResult by apply() before being sent back to the FrontEnd.
 * Implements Serializable for RMI transmission.
 *
 * 负责人 / Owner: CJJ（被 ReplicaImpl.closeAuction() 使用 / used by ReplicaImpl.closeAuction()）
 */
public class AuctionResult implements Serializable {

    /** 被关闭的拍卖品ID / The ID of the closed auction item */
    public final int itemID;

    /** 获胜出价人的用户ID；无人出价则为 0（流拍）/ Winning bidder's user ID; 0 if no bids were placed */
    public final int winningUser;

    /** 成交价格；无人出价则为 0 / Final sale price; 0 if no bids */
    public final int price;

    public AuctionResult(int itemID, int user, int price) {
        this.itemID = itemID; this.winningUser = user; this.price = price;
    }
}
