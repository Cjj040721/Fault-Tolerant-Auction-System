package replica;
import java.io.Serializable;

/**
 * 表示一件正在进行拍卖的商品，存储在副本的内存状态机中。
 * 实现 Serializable 以便通过 RMI 在副本之间传输。
 *
 * Represents an active auction item held in each replica's in-memory state machine.
 * Implements Serializable so it can be transmitted between replicas over RMI.
 *
 * 负责人 / Owner: CJJ（被 ReplicaImpl 状态机使用 / used by ReplicaImpl state machine）
 */
public class AuctionItem implements Serializable {

    /** 拍卖品唯一ID，由副本的 itemCounter 自增分配 / Unique item ID, assigned by the replica's itemCounter */
    public final int itemID;

    /** 拍卖品名称 / Item name */
    public final String name;

    /** 拍卖品描述 / Item description */
    public final String description;

    /** 起拍价，出价必须不低于此值才被接受 / Reserve price; bids below this are rejected */
    public final int reservePrice;

    /** 当前最高出价，初始为 0，每次有效出价后更新 / Current highest bid; starts at 0, updated on each valid bid */
    public int highestBid;

    public AuctionItem(int id, String n, String d, int r) {
        this.itemID = id; this.name = n; this.description = d;
        this.reservePrice = r; this.highestBid = 0;
    }
}
