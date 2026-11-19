package frontend;

import io.grpc.stub.StreamObserver;
import common.*;
import replica.*;

import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.ArrayList;
import java.util.List;

/**
 * FrontEnd 核心实现，同时充当两个角色：
 *   1. gRPC 服务端（AuctionServiceImplBase）：接收客户端请求，转发给 Sequencer
 *   2. RMI 管理接口（FrontEndAdmin）：接受副本注册，维护成员列表和当前 Sequencer
 *
 * FrontEnd core implementation, serving two roles simultaneously:
 *   1. gRPC service (AuctionServiceImplBase): receives client requests and forwards them to the sequencer
 *   2. RMI admin interface (FrontEndAdmin): accepts replica registrations and tracks the member list and sequencer
 *
 * 负责人 / Owner: LX
 */
public class FrontEndImpl extends AuctionServiceGrpc.AuctionServiceImplBase implements FrontEndAdmin {

    /** 当前 Sequencer 的 RMI 注册名，null 表示尚未选出 / RMI name of current sequencer; null if none elected yet */
    private volatile String sequencerName = null;

    /** 所有已注册副本的 RMI 名列表 / RMI names of all registered replicas */
    private final List<String> memberList = new ArrayList<>();

    // ===== FrontEndAdmin (RMI) — 副本注册与管理 / Replica registration and management =====

    /** 返回当前 Sequencer 名称 / Returns the current sequencer name */
    @Override
    public synchronized String getCurrentSequencerName() throws RemoteException {
        return sequencerName;
    }

    /**
     * 副本向 FrontEnd 报到，加入成员列表。
     * 若此前无 Sequencer，则将该副本提升为第一个 Sequencer。
     *
     * A replica registers with the FrontEnd and joins the member list.
     * If no sequencer exists yet, this replica is promoted to the first sequencer.
     */
    @Override
    public synchronized void registerReplica(int id, String rmiName) throws RemoteException {
        if (!memberList.contains(rmiName)) {
            memberList.add(rmiName);
        }
        // 第一个注册的副本自动成为 Sequencer / First replica to register becomes the sequencer
        if (sequencerName == null) {
            try {
                lookup(rmiName).setSequencer(true);
                sequencerName = rmiName;
            } catch (Exception e) {
                System.err.println("Failed to set sequencer: " + e.getMessage());
            }
        }
        System.out.println("Registered replica " + rmiName + "; sequencer=" + sequencerName
                + "; members=" + memberList);
    }

    // ===== gRPC：只读请求，直接转发给 Sequencer / Read-only requests forwarded directly to sequencer =====

    /** 查询单个拍卖品详情 / Get details of a single auction item */
    @Override
    public void getSpec(GetSpecRequest req, StreamObserver<Item> resp) {
        try {
            ReplicatedAuction leader = getLeaderWithElection();
            AuctionItem ai = leader.getSpec(req.getItemId());
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

    /** 列出所有进行中的拍卖 / List all active auction items */
    @Override
    public void listItems(Empty req, StreamObserver<ListReply> resp) {
        try {
            ReplicatedAuction leader = getLeaderWithElection();
            AuctionItem[] items = leader.listItems();
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

    // ===== gRPC：写操作，经两阶段复制协议执行 / Write operations executed via two-phase replication =====

    /** 注册新用户 / Register a new user */
    @Override
    public void register(RegisterRequest req, StreamObserver<RegisterReply> resp) {
        try {
            Operation op = Operation.register(req.getEmail());
            OperationResult res = executeWithRetry(op);
            if (res != null && res.ok) {
                resp.onNext(RegisterReply.newBuilder().setUserId(res.userId).build());
                resp.onCompleted();
            } else {
                resp.onError(new RuntimeException(res != null ? res.error : "register failed"));
            }
        } catch (Exception e) { resp.onError(e); }
    }

    /** 创建新拍卖 / Create a new auction item */
    @Override
    public void newAuction(NewAuctionRequest req, StreamObserver<NewAuctionReply> resp) {
        try {
            Operation op = Operation.newAuction(
                req.getUserId(), req.getName(), req.getDescription(), req.getReservePrice());
            OperationResult res = executeWithRetry(op);
            if (res != null && res.ok) {
                resp.onNext(NewAuctionReply.newBuilder().setItemId(res.itemId).build());
                resp.onCompleted();
            } else {
                resp.onError(new RuntimeException(res != null ? res.error : "newAuction failed"));
            }
        } catch (Exception e) { resp.onError(e); }
    }

    /** 提交出价 / Submit a bid */
    @Override
    public void bid(BidRequest req, StreamObserver<BidReply> resp) {
        try {
            Operation op = Operation.bid(req.getUserId(), req.getItemId(), req.getPrice());
            OperationResult res = executeWithRetry(op);
            if (res != null && res.ok) {
                resp.onNext(BidReply.newBuilder().setSuccess(res.bidOk).build());
                resp.onCompleted();
            } else {
                resp.onNext(BidReply.newBuilder().setSuccess(false).build());
                resp.onCompleted();
            }
        } catch (Exception e) { resp.onError(e); }
    }

    /** 关闭拍卖 / Close an auction */
    @Override
    public void closeAuction(CloseRequest req, StreamObserver<AuctionResult> resp) {
        try {
            Operation op = Operation.close(req.getUserId(), req.getItemId());
            OperationResult res = executeWithRetry(op);
            if (res != null && res.ok) {
                resp.onNext(AuctionResult.newBuilder()
                    .setItemId(res.closeItem)
                    .setWinningUser(res.closeWinner)
                    .setPrice(res.closePrice)
                    .build());
                resp.onCompleted();
            } else {
                // 关闭失败（权限不足等），返回全零结果 / Close not permitted; return zeroed result
                resp.onNext(AuctionResult.newBuilder().build());
                resp.onCompleted();
            }
        } catch (Exception e) { resp.onError(e); }
    }

    // ===== Leader Election — Sequencer 故障检测与选主 / Sequencer failure detection and election =====

    /**
     * 从成员列表中选出 lastCommittedSeqNo 最大的副本作为新 Sequencer。
     * 选主策略：日志最新的副本拥有最完整的状态，最适合接替 leader。
     *
     * Elects the replica with the highest lastCommittedSeqNo as the new sequencer.
     * Rationale: the replica with the most up-to-date log has the most complete state.
     */
    private synchronized void electNewLeader() {
        System.out.println("Electing new sequencer from members: " + memberList);
        long bestSeq = -1;
        String bestName = null;
        for (String name : memberList) {
            try {
                long seq = lookup(name).getLastCommittedSeqNo();
                if (seq > bestSeq) {
                    bestSeq = seq;
                    bestName = name;
                }
            } catch (Exception e) {
                // 副本不可达，跳过 / Replica unreachable — skip
            }
        }
        if (bestName != null) {
            try {
                lookup(bestName).setSequencer(true);
                sequencerName = bestName;
                System.out.println("Elected new sequencer: " + sequencerName);
            } catch (Exception e) {
                System.err.println("Failed to promote new sequencer: " + e.getMessage());
            }
        }
    }

    /**
     * 执行写操作，失败时触发一次选主后重试。
     * 若 Sequencer 在处理过程中宕机（RemoteException），先选出新 leader 再重试一次。
     *
     * Executes a write operation with one retry after leader election on failure.
     * If the sequencer crashes mid-operation (RemoteException), elect a new leader and retry once.
     */
    private OperationResult executeWithRetry(Operation op) throws Exception {
        List<String> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(memberList);
        }
        try {
            // 第一次尝试：直接发给当前 Sequencer / First attempt: send to current sequencer
            return lookupLeader().handleClientOperation(op, snapshot);
        } catch (RemoteException e) {
            // Sequencer 不可达 → 触发选主后重试 / Sequencer unreachable — elect new leader and retry
            System.out.println("Sequencer unreachable, electing new leader...");
            electNewLeader();
            synchronized (this) {
                snapshot = new ArrayList<>(memberList);
            }
            return lookupLeader().handleClientOperation(op, snapshot);
        }
    }

    /**
     * 获取当前 Sequencer 的 RMI 代理，若不可达则先选主再返回。
     * 供只读请求（getSpec / listItems）使用。
     *
     * Returns the RMI stub for the current sequencer, electing a new one first if unreachable.
     * Used by read-only operations (getSpec / listItems).
     */
    private ReplicatedAuction getLeaderWithElection() throws Exception {
        try {
            return lookupLeader();
        } catch (Exception e) {
            electNewLeader();
            return lookupLeader();
        }
    }

    // ===== 工具方法 / Helpers =====

    /** 通过 RMI 名称查找副本代理 / Look up a replica's RMI stub by name */
    private ReplicatedAuction lookup(String rmiName) throws Exception {
        Registry reg = LocateRegistry.getRegistry();
        return (ReplicatedAuction) reg.lookup(rmiName);
    }

    /** 获取当前 Sequencer 的 RMI 代理 / Get the RMI stub for the current sequencer */
    private ReplicatedAuction lookupLeader() throws Exception {
        String name;
        synchronized (this) {
            name = sequencerName;
        }
        if (name == null) throw new IllegalStateException("No sequencer registered yet");
        return lookup(name);
    }
}
