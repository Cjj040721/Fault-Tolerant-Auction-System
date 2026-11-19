package client;

import frontend.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

/**
 * 拍卖系统客户端，通过 gRPC 连接 FrontEnd（50055端口）完成完整的拍卖流程测试。
 * 客户端对底层复制协议完全透明：无论后端是单机（Part1）还是三副本集群（Part2），
 * 客户端代码无需任何修改。
 *
 * Auction system client. Connects to the FrontEnd over gRPC (port 50055)
 * and runs a complete end-to-end auction scenario.
 * The client is fully unaware of the underlying replication: whether the backend
 * is a single server (Part1) or a replicated cluster (Part2), the client code is identical.
 *
 * 负责人 / Owner: LKH
 */
public class AuctionClient {
    public static void main(String[] args) {

        // 建立 gRPC 连接，连接到本机 FrontEnd
        // Establish a gRPC channel to the local FrontEnd
        ManagedChannel ch = ManagedChannelBuilder.forAddress("localhost", 50055)
                .usePlaintext().build();
        var stub = AuctionServiceGrpc.newBlockingStub(ch);

        // ── 注册用户 / Register users ────────────────────────────────────────────────
        System.out.println("=== Register users ===");
        int alice = stub.register(RegisterRequest.newBuilder().setEmail("alice@example.com").build()).getUserId();
        int bob   = stub.register(RegisterRequest.newBuilder().setEmail("bob@example.com").build()).getUserId();
        int carol = stub.register(RegisterRequest.newBuilder().setEmail("carol@example.com").build()).getUserId();
        int dave  = stub.register(RegisterRequest.newBuilder().setEmail("dave@example.com").build()).getUserId();
        //System.out.printf("alice=%d  bob=%d  carol=%d%n", alice, bob, carol);
        System.out.printf("alice=%d  bob=%d  carol=%d  dave=%d%n", alice, bob, carol, dave);

        // ── 创建拍卖 / Create auctions ───────────────────────────────────────────────
        // alice 创建 Laptop（起拍价 500），bob 创建 Phone（起拍价 200）
        // alice creates Laptop (reserve 500), bob creates Phone (reserve 200)
        System.out.println("\n=== Create auctions ===");
        int item1 = stub.newAuction(NewAuctionRequest.newBuilder()
                .setUserId(alice).setName("Laptop").setDescription("Gaming laptop").setReservePrice(500)
                .build()).getItemId();
        int item2 = stub.newAuction(NewAuctionRequest.newBuilder()
                .setUserId(bob).setName("Phone").setDescription("Smartphone").setReservePrice(200)
                .build()).getItemId();
        System.out.printf("item1(Laptop)=%d  item2(Phone)=%d%n", item1, item2);

        // ── 列出拍卖品 / List items ─────────────────────────────────────────────────
        System.out.println("\n=== List items ===");
        stub.listItems(Empty.newBuilder().build()).getItemsList().forEach(i ->
            System.out.printf("  id=%d name=%s reserve=%d bid=%d%n",
                i.getItemId(), i.getName(), i.getReservePrice(), i.getHighestBid()));

        // ── 出价测试 / Bidding tests ─────────────────────────────────────────────────
        System.out.println("\n=== Bidding ===");

        // 低于起拍价 → false / Below reserve price → false
        boolean b1 = stub.bid(BidRequest.newBuilder().setUserId(bob).setItemId(item1).setPrice(100).build()).getSuccess();
        System.out.println("Bob bids 100 on Laptop (reserve 500, expect false): " + b1);

        // 等于起拍价 → true（满足底价）/ Equal to reserve → true (meets reserve)
        boolean b2 = stub.bid(BidRequest.newBuilder().setUserId(bob).setItemId(item1).setPrice(500).build()).getSuccess();
        System.out.println("Bob bids 500 on Laptop (expect true): " + b2);

        // 高于当前最高价 → true / Above current highest → true
        boolean b3 = stub.bid(BidRequest.newBuilder().setUserId(carol).setItemId(item1).setPrice(750).build()).getSuccess();
        System.out.println("Carol bids 750 on Laptop (expect true): " + b3);

        // 低于当前最高价 → false / Below current highest → false
        boolean b4 = stub.bid(BidRequest.newBuilder().setUserId(bob).setItemId(item1).setPrice(600).build()).getSuccess();
        System.out.println("Bob bids 600 (< 750, expect false): " + b4);

        // ── 查询商品详情 / Get item spec ─────────────────────────────────────────────
        System.out.println("\n=== GetSpec ===");
        var spec = stub.getSpec(GetSpecRequest.newBuilder().setItemId(item1).build());
        System.out.printf("Laptop: reserve=%d highestBid=%d%n", spec.getReservePrice(), spec.getHighestBid());

        // ── 关闭拍卖 / Close auctions ────────────────────────────────────────────────
        System.out.println("\n=== Close auction ===");

        // 非所有者尝试关闭 → 返回全零（权限不足）/ Non-owner tries to close → zeroed result (not permitted)
        var r1 = stub.closeAuction(CloseRequest.newBuilder().setUserId(bob).setItemId(item1).build());
        System.out.println("Bob closes Alice's auction (expect empty): winner=" + r1.getWinningUser() + " price=" + r1.getPrice());

        // 所有者关闭 → 返回获胜者和成交价 / Owner closes → returns winner and final price
        var r2 = stub.closeAuction(CloseRequest.newBuilder().setUserId(alice).setItemId(item1).build());
        System.out.printf("Alice closes Laptop: winner=%d price=%d%n", r2.getWinningUser(), r2.getPrice());

        // 对已关闭商品出价 → false / Bid on closed item → false
        boolean b5 = stub.bid(BidRequest.newBuilder().setUserId(bob).setItemId(item1).setPrice(9999).build()).getSuccess();
        System.out.println("Bid on closed item (expect false): " + b5);

        // 关闭无人出价的拍卖 → winner=0, price=0（流拍）/ Close with no bids → winner=0, price=0 (no sale)
        var r3 = stub.closeAuction(CloseRequest.newBuilder().setUserId(bob).setItemId(item2).build());
        System.out.printf("Bob closes Phone (no bids): winner=%d price=%d%n", r3.getWinningUser(), r3.getPrice());

        // ── 关闭后列表应为空 / List after closes — should be empty ──────────────────
        System.out.println("\n=== List after closes (expect empty) ===");
        stub.listItems(Empty.newBuilder().build()).getItemsList().forEach(i ->
            System.out.printf("  id=%d name=%s%n", i.getItemId(), i.getName()));

        ch.shutdown();
        System.out.println("\nDone.");
    }
}
