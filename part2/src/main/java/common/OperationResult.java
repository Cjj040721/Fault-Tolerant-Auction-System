package common;
import java.io.Serializable;

/**
 * 封装操作执行后的结果，通过 Sequencer 返回给 FrontEnd，再由 FrontEnd 转成 gRPC 响应。
 * 使用静态工厂方法按操作类型构造，无关字段保持 null。
 *
 * Encapsulates the result of executing an operation on the state machine.
 * Returned from the Sequencer to the FrontEnd, which then translates it
 * into a gRPC response for the client.
 * Constructed via typed static factory methods; irrelevant fields remain null.
 *
 * 负责人 / Owner: CZR
 */
public class OperationResult implements Serializable {

    /** 操作是否成功 / Whether the operation succeeded */
    public final boolean ok;

    /** 失败时的错误信息，成功时为 null / Error message on failure; null on success */
    public final String error;

    /** REGISTER 结果：分配的用户ID / REGISTER result: the assigned user ID */
    public final Integer userId;

    /** NEW_AUCTION 结果：分配的拍卖品ID / NEW_AUCTION result: the assigned item ID */
    public final Integer itemId;

    /** BID 结果：出价是否被接受 / BID result: whether the bid was accepted */
    public final Boolean bidOk;

    /** CLOSE 结果：被关闭的拍卖品ID / CLOSE result: the closed item's ID */
    public final Integer closeItem;

    /** CLOSE 结果：获胜出价人的用户ID，无人出价则为 0 / CLOSE result: winning user ID; 0 if no bids */
    public final Integer closeWinner;

    /** CLOSE 结果：成交价格 / CLOSE result: final sale price */
    public final Integer closePrice;

    private OperationResult(boolean ok, String error, Integer userId, Integer itemId, Boolean bidOk,
                            Integer closeItem, Integer closeWinner, Integer closePrice) {
        this.ok=ok; this.error=error;
        this.userId=userId; this.itemId=itemId; this.bidOk=bidOk;
        this.closeItem=closeItem; this.closeWinner=closeWinner; this.closePrice=closePrice;
    }

    /** 构造 REGISTER 成功结果 / Build a successful REGISTER result */
    public static OperationResult reg(int uid) {
        return new OperationResult(true,null,uid,null,null,null,null,null);
    }

    /** 构造 NEW_AUCTION 成功结果 / Build a successful NEW_AUCTION result */
    public static OperationResult newA(int item) {
        return new OperationResult(true,null,null,item,null,null,null,null);
    }

    /** 构造 BID 结果（出价可能被接受也可能被拒绝）/ Build a BID result (may be accepted or rejected) */
    public static OperationResult bid(boolean ok) {
        return new OperationResult(true,null,null,null,ok,null,null,null);
    }

    /** 构造 CLOSE 成功结果 / Build a successful CLOSE result */
    public static OperationResult close(int item, int winner, int price) {
        return new OperationResult(true,null,null,null,null,item,winner,price);
    }

    /** 构造失败结果，ok=false，其他字段全为 null / Build a failure result; ok=false, all other fields null */
    public static OperationResult fail(String msg) {
        return new OperationResult(false,msg,null,null,null,null,null,null);
    }
}
