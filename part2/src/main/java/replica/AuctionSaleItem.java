package replica;
import java.io.Serializable;

/**
 * 客户端创建新拍卖时提交的商品信息，封装在 Operation.NEW_AUCTION 中传递给副本。
 * 实现 Serializable 以便通过 RMI 在副本之间传输。
 *
 * The item details submitted by the client when creating a new auction.
 * Carried inside an Operation.NEW_AUCTION and passed to replicas over RMI.
 * Implements Serializable for RMI transmission.
 *
 * 负责人 / Owner: CJJ（被 ReplicaImpl.newAuction() 使用 / used by ReplicaImpl.newAuction()）
 */
public class AuctionSaleItem implements Serializable {

    /** 拍卖品名称 / Item name */
    public final String name;

    /** 拍卖品描述 / Item description */
    public final String description;

    /** 起拍价，出价必须不低于此值才被接受 / Reserve price; bids below this threshold are rejected */
    public final int reservePrice;

    public AuctionSaleItem(String n, String d, int r) {
        this.name = n; this.description = d; this.reservePrice = r;
    }
}
