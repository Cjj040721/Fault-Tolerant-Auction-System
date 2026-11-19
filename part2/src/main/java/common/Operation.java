package common;
import java.io.Serializable;

/**
 * 将每一个会修改系统状态的客户端请求封装成一个"操作对象"，
 * 用于在副本之间传输和记录到日志中。
 *
 * Wraps each state-mutating client request (register, new_auction, bid, close)
 * into an immutable operation object that can be transmitted between replicas
 * and persisted in the replication log.
 *
 * 负责人 / Owner: CZR
 */
public class Operation implements Serializable {

    /** 操作类型枚举 / Enum of all possible operation types */
    public enum Type { REGISTER, NEW_AUCTION, BID, CLOSE }

    public final Type type;

    /** 发起操作的用户ID / ID of the user who initiated the operation */
    public final int userId;

    /** 拍卖品名称，仅 NEW_AUCTION 使用 / Item name, used only by NEW_AUCTION */
    public final String name;

    /** 拍卖品描述，仅 NEW_AUCTION 使用 / Item description, used only by NEW_AUCTION */
    public final String description;

    /**
     * 字段复用：
     *   NEW_AUCTION 时 = 起拍价 (reserve price)
     *   BID 时        = 出价金额 (bid price)
     *
     * Field reuse:
     *   For NEW_AUCTION: reserve price
     *   For BID:         the bid amount
     */
    public final int reservePrice;

    /** 拍卖品ID，仅 BID / CLOSE 使用 / Item ID, used only by BID and CLOSE */
    public final int itemId;

    /** 用户邮箱，仅 REGISTER 使用 / User email, used only by REGISTER */
    public final String email;

    /** 私有构造器，通过静态工厂方法创建 / Private constructor; use static factory methods */
    private Operation(Type t, int userId, String email, String name, String desc, int reserve, int itemId) {
        this.type=t; this.userId=userId; this.email=email;
        this.name=name; this.description=desc; this.reservePrice=reserve; this.itemId=itemId;
    }

    /** 创建"注册用户"操作 / Create a REGISTER operation */
    public static Operation register(String email) {
        return new Operation(Type.REGISTER, 0, email, null, null, 0, 0);
    }

    /** 创建"新建拍卖"操作 / Create a NEW_AUCTION operation */
    public static Operation newAuction(int userId, String n, String d, int reserve) {
        return new Operation(Type.NEW_AUCTION, userId, null, n, d, reserve, 0);
    }

    /**
     * 创建"出价"操作，出价金额存入 reservePrice 字段
     * Create a BID operation; the bid price is stored in the reservePrice field
     */
    public static Operation bid(int userId, int itemId, int price) {
        return new Operation(Type.BID, userId, null, null, null, price, itemId);
    }

    /** 创建"关闭拍卖"操作 / Create a CLOSE operation */
    public static Operation close(int userId, int itemId) {
        return new Operation(Type.CLOSE, userId, null, null, null, 0, itemId);
    }
}
