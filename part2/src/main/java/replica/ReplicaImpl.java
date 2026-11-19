package replica;

import common.*;
import java.rmi.server.UnicastRemoteObject;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.*;

/**
 * ★ CJJ 核心文件 ★
 *
 * 副本的核心实现，负责两大职责：
 *   1. 两阶段复制协议（propose / commitUpTo）：保证所有副本的操作顺序和状态一致
 *   2. 拍卖状态机：在本地维护用户、拍卖品、出价等业务数据
 *
 * The core replica implementation, responsible for two things:
 *   1. Two-phase replication protocol (propose / commitUpTo): ensures all replicas
 *      execute operations in the same order and reach the same state
 *   2. Auction state machine: maintains users, auction items, and bids in local memory
 *
 * 继承 UnicastRemoteObject 使其成为 RMI 远程对象，可被 FrontEnd 和其他副本通过网络调用。
 * Extends UnicastRemoteObject to become an RMI remote object callable over the network.
 *
 * 负责人 / Owner: CJJ
 */
public class ReplicaImpl extends UnicastRemoteObject implements ReplicatedAuction {

    // ==================== 日志与序号状态 / Log and sequence number state ====================

    /**
     * 是否是当前 Sequencer（Leader）。
     * 只有 isLeader=true 的副本才能处理写请求（handleClientOperation）。
     * Whether this replica is the current sequencer (leader).
     * Only the leader can handle write requests (handleClientOperation).
     */
    private boolean isLeader = false;

    /**
     * 已分配出去的最大序号，仅 Sequencer 使用。
     * 每次新操作到来时 ++lastSeqAssigned，保证全局单调递增、不重复。
     * The highest sequence number assigned so far; used only by the sequencer.
     * Incremented atomically (++lastSeqAssigned) for each new operation.
     */
    private long lastSeqAssigned = 0;

    /**
     * 已完成 commit 阶段的最大序号。
     * FrontEnd 选主时比较各副本的此值，选最大的成为新 Sequencer。
     * The highest sequence number that has completed the commit phase.
     * Used by the FrontEnd during leader election: the replica with the highest value is elected.
     */
    private long lastCommitted = 0;

    /**
     * 已实际执行到本地状态机的最大序号。
     * 正常情况下 lastApplied == lastCommitted（apply 和 commit 同步推进）。
     * The highest sequence number that has been applied to the local state machine.
     * Under normal operation lastApplied == lastCommitted.
     */
    private long lastApplied = 0;

    /**
     * 有序日志，key=seqNo，value=LogEntry。
     * 使用 TreeMap（红黑树）保证按 seqNo 从小到大有序，方便顺序遍历。
     * The ordered replication log; key=seqNo, value=LogEntry.
     * TreeMap keeps entries sorted by seqNo, enabling efficient in-order traversal.
     */
    private final TreeMap<Long, LogEntry> log = new TreeMap<>();

    // ==================== 拍卖状态机数据 / Auction state machine data ====================

    /** 用户ID自增计数器，从 1 开始 / Auto-incrementing user ID counter, starting at 1 */
    private int userCounter = 1;

    /** 拍卖品ID自增计数器，从 1 开始 / Auto-incrementing item ID counter, starting at 1 */
    private int itemCounter = 1;

    /** 用户注册表：userID → email / User registry: userID → email */
    private final Map<Integer, String> users = new HashMap<>();

    /** 当前进行中的拍卖：itemID → AuctionItem（拍卖关闭后从此 map 移除）/ Active auctions: itemID → AuctionItem (removed on close) */
    private final Map<Integer, AuctionItem> activeItems = new HashMap<>();

    /** 每件拍卖品的所有者：itemID → ownerUserID / Owner of each auction item: itemID → ownerUserID */
    private final Map<Integer, Integer> itemOwners = new HashMap<>();

    /** 每件拍卖品的当前最高出价人：itemID → bidderUserID / Current highest bidder per item: itemID → bidderUserID */
    private final Map<Integer, Integer> highestBidders = new HashMap<>();

    // ==================== 副本身份 / Replica identity ====================

    /** 副本编号，用于日志打印 / Replica ID used for logging */
    private final int id;

    /**
     * RMI 注册名（"replica<id>"）。
     * 在 propose/commit 循环中跳过自己时使用。
     * RMI registration name ("replica<id>").
     * Used to skip self when iterating the member list during propose/commit.
     */
    private final String myName;

    /**
     * 构造器必须声明 throws RemoteException（UnicastRemoteObject 要求）。
     * Constructor must declare throws RemoteException (required by UnicastRemoteObject).
     */
    public ReplicaImpl(int id, String rmiName) throws RemoteException {
        this.id = id;
        this.myName = rmiName;
    }

    // ==================== 只读方法（不走复制协议）/ Read-only methods (bypass replication) ====================

    /**
     * 查询单个拍卖品详情，直接读取本地状态机，无需复制。
     * 由于所有写操作都经过 Sequencer 并 apply 后才返回，Sequencer 的本地状态始终最新。
     *
     * Returns details of a single item, read directly from local state — no replication needed.
     * Since all writes are applied locally before returning, the sequencer's state is always current.
     */
    @Override
    public synchronized AuctionItem getSpec(int itemID) {
        return activeItems.get(itemID);
    }

    /**
     * 列出所有进行中的拍卖品，直接读取本地状态机，无需复制。
     * Lists all active auction items from local state — no replication needed.
     */
    @Override
    public synchronized AuctionItem[] listItems() {
        return activeItems.values().toArray(new AuctionItem[0]);
    }

    // ==================== 本地状态机操作（私有，仅由 apply() 调用）/ Local state machine (private, called only by apply()) ====================

    /**
     * 注册新用户，分配自增ID。
     * Registers a new user and assigns an auto-incremented ID.
     */
    private int register(String email) {
        int uid = userCounter++; // 先取当前值作为新ID，再自增 / take current value as new ID, then increment
        users.put(uid, email);
        return uid;
    }

    /**
     * 创建新拍卖品；若用户不存在返回 -1。
     * Creates a new auction item; returns -1 if the user does not exist.
     */
    private int newAuction(int userID, AuctionSaleItem item) {
        if (!users.containsKey(userID)) return -1; // 用户合法性校验 / validate user exists
        int iid = itemCounter++;
        activeItems.put(iid, new AuctionItem(iid, item.name, item.description, item.reservePrice));
        itemOwners.put(iid, userID); // 记录所有者 / record owner
        return iid;
    }

    /**
     * 出价逻辑：出价必须同时满足 ① 高于当前最高价 ② 不低于起拍价，否则返回 false。
     * Bid logic: the bid must be strictly above the current highest bid AND at least the reserve price.
     */
    private boolean bid(int userID, int itemID, int price) {
        if (!users.containsKey(userID)) return false; // 用户不存在 / user not found
        AuctionItem item = activeItems.get(itemID);
        if (item == null) return false; // 拍卖不存在或已关闭 / item not found or already closed
        if (price > item.highestBid && price >= item.reservePrice) {
            item.highestBid = price;
            highestBidders.put(itemID, userID); // 更新最高出价人 / update highest bidder
            return true;
        }
        return false;
    }

    /**
     * 关闭拍卖：仅所有者可以关闭，关闭后从 activeItems 移除（之后的出价请求会返回 false）。
     * 无人出价时 winner=0，price=0（流拍）。
     *
     * Closes an auction: only the owner may close it; the item is removed from activeItems on close
     * (subsequent bids will return false). winner=0, price=0 if no bids were placed.
     */
    private AuctionResult closeAuction(int userID, int itemID) {
        AuctionItem item = activeItems.get(itemID);
        if (item == null) return null; // 不存在或已关闭 / not found or already closed
        Integer owner = itemOwners.get(itemID);
        if (owner == null || owner != userID) return null; // 非所有者无权关闭 / non-owner cannot close
        activeItems.remove(itemID); // 从活跃列表移除，拍卖结束 / remove from active set — auction over
        int winner = highestBidders.getOrDefault(itemID, 0); // 无出价者则 winner=0 / 0 if no bidder
        return new AuctionResult(itemID, winner, item.highestBid);
    }

    // ==================== ★ 两阶段复制协议核心 / Two-phase replication protocol core ★ ====================

    /**
     * ★ 写操作入口，仅 Sequencer 调用 ★
     *
     * 实现两阶段提交协议：
     *   Phase 1（propose）：为操作分配全局序号，广播给所有 Follower 让其记录到日志
     *   多数派检查：至少 majority(n) 个副本确认（含自身）才继续
     *   Phase 2（commit）：本地执行操作，再通知所有 Follower 执行
     *
     * Write entry point; only the sequencer executes this method.
     *
     * Implements the two-phase commit protocol:
     *   Phase 1 (propose): assign a global sequence number, broadcast to all followers to log the operation
     *   Majority check: proceed only if at least majority(n) replicas acknowledged (including self)
     *   Phase 2 (commit): apply the operation locally, then notify all followers to apply it too
     *
     * @param op         要执行的操作 / The operation to execute
     * @param memberList FrontEnd 传入的当前成员列表 / Current member list supplied by the FrontEnd
     */
    @Override
    public synchronized OperationResult handleClientOperation(Operation op, List<String> memberList)
            throws RemoteException {

        // 安全检查：非 leader 直接拒绝（防御性编程，正常情况 FrontEnd 不会误发）
        // Safety check: reject if not leader (FrontEnd normally only calls this on the sequencer)
        if (!isLeader) {
            return OperationResult.fail("Not leader");
        }

        // ── Phase 1：分配序号，广播 propose ──────────────────────────────────────────
        // ── Phase 1: assign sequence number, broadcast propose ───────────────────────

        // 分配全局唯一、单调递增的序号（synchronized 保证原子性）
        // Assign a globally unique, monotonically increasing sequence number (atomic under synchronized)
        long seqNo = ++lastSeqAssigned;
        // 写入本地日志，committed=false（尚未确认多数派）
        // Write to local log; committed=false until majority is confirmed
        log.put(seqNo, new LogEntry(seqNo, op));

        // 向所有其他副本广播 propose，统计 ACK 数量（自身算 1 个）
        // Broadcast propose to all other replicas; count ACKs (self counts as 1)
        int acks = 1;
        for (String memberName : memberList) {
            if (memberName.equals(myName)) continue; // 跳过自己 / skip self
            try {
                ReplicatedAuction member = lookup(memberName);
                member.propose(seqNo, op); // Follower 记录到草稿日志 / follower writes to its draft log
                acks++;
            } catch (Exception e) {
                // 副本不可达 → 跳过，不中断流程（容错设计）
                // Replica unreachable — skip; don't abort (fault-tolerant design)
            }
        }

        // ── 多数派检查 / Majority check ─────────────────────────────────────────────
        // ACK 不足则操作不生效，保证不会在少数副本上单独执行
        // Abort if quorum not reached; prevents the operation from being applied on only a minority
        if (acks < majority(memberList.size())) {
            return OperationResult.fail("Quorum not achieved (" + acks + "/" + memberList.size() + ")");
        }

        // ── Phase 2：本地执行，再广播 commit ─────────────────────────────────────────
        // ── Phase 2: apply locally, then broadcast commit ────────────────────────────

        // Sequencer 先在本地执行，获取结果
        // Sequencer applies the operation locally first to obtain the result
        LogEntry le = log.get(seqNo);
        le.committed = true;                  // 标记为已提交 / mark as committed
        OperationResult result = apply(op);   // 执行到本地状态机 / apply to local state machine
        lastCommitted = seqNo;
        lastApplied = seqNo;

        // 通知所有 Follower 执行到 seqNo（Phase 2 广播）
        // Notify all followers to apply up to seqNo (Phase 2 broadcast)
        for (String memberName : memberList) {
            if (memberName.equals(myName)) continue;
            try {
                ReplicatedAuction member = lookup(memberName);
                member.commitUpTo(seqNo); // Follower 将草稿变为正式执行 / follower commits its draft
            } catch (Exception e) {
                // 副本不可达 → 跳过（Follower 可通过 gap 修复机制在之后追上）
                // Replica unreachable — skip (follower can catch up later via gap repair)
            }
        }

        return result; // 将执行结果返回给 FrontEnd / return result to FrontEnd
    }

    /**
     * 计算多数派门槛：n/2 + 1（整除）。
     * 3副本→2，4副本→3，5副本→3。
     * 任意两个多数派集合必然有交集，保证强一致性。
     *
     * Computes the majority threshold: n/2 + 1 (integer division).
     * 3 replicas→2, 4→3, 5→3.
     * Any two majority sets must overlap, guaranteeing strong consistency.
     */
    private int majority(int n) {
        return (n / 2) + 1;
    }

    /**
     * ★ 两阶段提交 — 第一阶段（Follower 侧）★
     *
     * Follower 收到 Sequencer 的 propose 后，将操作写入本地日志（committed=false），
     * 但不执行，返回 true 作为 ACK。
     *
     * Follower 端的关键不变量：只记录，不执行。
     * 只有收到 commitUpTo 后才会真正执行操作。
     *
     * Two-phase commit — Phase 1 (follower side).
     *
     * On receiving propose from the sequencer, the follower writes the operation to its local log
     * (committed=false) but does NOT execute it; returns true as an ACK.
     *
     * Key invariant on the follower: log only, never apply here.
     * Execution happens only after commitUpTo is received.
     */
    @Override
    public synchronized boolean propose(long seqNo, Operation op) {
        LogEntry existing = log.get(seqNo);
        // 若该序号不存在，或存在但尚未 committed，则写入（幂等设计，重复 propose 安全）
        // Write if entry doesn't exist or hasn't been committed yet (idempotent; repeated proposes are safe)
        if (existing == null || !existing.committed) {
            log.put(seqNo, new LogEntry(seqNo, op));
        }
        // 若已 committed（极端情况），保留现有状态，不覆盖
        // If already committed (edge case), keep existing entry — do not overwrite
        return true; // 始终返回 ACK / always ACK
    }

    /**
     * ★ 两阶段提交 — 第二阶段（Follower 侧）★
     *
     * Follower 收到 Sequencer 的 commitUpTo(seqNo) 后，
     * 按顺序将日志中 lastApplied+1 到 seqNo 的所有条目应用到本地状态机。
     *
     * 若发现日志中存在 gap（某些序号的 propose 因网络问题未到达），
     * 先向当前 Sequencer 拉取缺失条目（best-effort），再顺序执行。
     *
     * 核心不变量：必须按 seqNo 顺序执行，不能跳过任何条目，
     * 否则各副本的状态机会产生分歧。
     *
     * Two-phase commit — Phase 2 (follower side).
     *
     * On receiving commitUpTo(seqNo) from the sequencer, the follower applies all log entries
     * from lastApplied+1 up to seqNo to its local state machine, in order.
     *
     * If there are gaps (entries whose propose was lost due to network issues),
     * the follower first tries to fetch the missing entries from the current sequencer
     * (best-effort), then applies in order.
     *
     * Core invariant: entries must be applied strictly in seqNo order with no skips;
     * otherwise replicas will diverge.
     */
    @Override
    public synchronized boolean commitUpTo(long seqNo) {

        // ── Gap 检测与修复 / Gap detection and repair ────────────────────────────────
        // 扫描 lastApplied+1 到 seqNo，找到第一个缺失的序号
        // Scan from lastApplied+1 to seqNo to find the first missing sequence number
        for (long i = lastApplied + 1; i <= seqNo; i++) {
            if (!log.containsKey(i)) {
                // 发现 gap：向 FrontEnd 询问当前 leader，拉取缺失条目
                // Gap found: ask the FrontEnd for the current leader, then fetch missing entries
                try {
                    String leaderName = findLeaderName();
                    if (leaderName != null && !leaderName.equals(myName)) {
                        ReplicatedAuction leader = lookup(leaderName);
                        // 拉取 lastApplied 之后所有已提交条目，补入本地日志
                        // Fetch all committed entries after lastApplied and insert them into the local log
                        List<LogEntry> missing = leader.getEntriesAfter(lastApplied);
                        for (LogEntry mle : missing) {
                            if (!log.containsKey(mle.seqNo)) {
                                log.put(mle.seqNo, mle);
                            }
                        }
                    }
                } catch (Exception e) {
                    // best-effort：修复失败则继续，执行阶段遇到 null 会自动停止
                    // Best-effort: if repair fails, continue; the apply loop will stop at the gap
                }
                break; // 只尝试一次 gap 修复 / attempt repair only once
            }
        }

        // ── 顺序执行 / In-order application ──────────────────────────────────────────
        // 从 lastApplied+1 到 seqNo 顺序应用，遇到缺失条目立即停止（保证顺序性）
        // Apply entries from lastApplied+1 to seqNo in order; stop immediately at any gap
        for (long i = lastApplied + 1; i <= seqNo; i++) {
            LogEntry le = log.get(i);
            if (le == null) break; // 仍有 gap，停止（不跳过）/ gap remains — stop, don't skip
            if (!le.committed) {
                le.committed = true; // 标记为已提交 / mark as committed
            }
            apply(le.op);       // 执行到本地状态机 / apply to local state machine
            lastApplied = i;    // 推进已执行序号 / advance applied pointer
            lastCommitted = i;  // 推进已提交序号 / advance committed pointer
        }
        return true;
    }

    /**
     * ★ 状态机分发层 ★
     *
     * 根据 op.type 将操作路由到对应的私有业务方法并返回结果。
     * Sequencer 和 Follower 都通过此方法执行操作，保证状态机逻辑只写一份。
     *
     * Routes the operation to the corresponding private business method based on op.type.
     * Both the sequencer and followers go through this method, keeping state machine logic in one place.
     *
     * 注意 / Note: BID 的出价金额存在 op.reservePrice 字段（Operation 字段复用）。
     *              The bid price is stored in op.reservePrice due to field reuse in Operation.
     */
    private OperationResult apply(Operation op) {
        switch (op.type) {
            case REGISTER -> {
                int uid = register(op.email);
                return OperationResult.reg(uid);
            }
            case NEW_AUCTION -> {
                int iid = newAuction(op.userId, new AuctionSaleItem(op.name, op.description, op.reservePrice));
                return (iid > 0) ? OperationResult.newA(iid) : OperationResult.fail("unknown user");
            }
            case BID -> {
                // op.reservePrice 在 BID 操作中存的是出价金额（字段复用）
                // op.reservePrice stores the bid amount for BID operations (field reuse)
                boolean ok = bid(op.userId, op.itemId, op.reservePrice);
                return OperationResult.bid(ok);
            }
            case CLOSE -> {
                AuctionResult ar = closeAuction(op.userId, op.itemId);
                if (ar == null) return OperationResult.fail("Auction close not permitted!");
                return OperationResult.close(ar.itemID, ar.winningUser, ar.price);
            }
            default -> { return OperationResult.fail("Unknown op"); }
        }
    }

    // ==================== 状态同步 / State synchronisation ====================

    /**
     * 返回所有序号大于 fromSeq 且已提交的日志条目。
     *
     * 两个使用场景：
     *   1. ReplicaMain 启动时传入 -1L，获取全部历史条目做全量同步
     *   2. commitUpTo 中 gap 修复时，传入 lastApplied，获取缺失条目
     *
     * Returns all committed log entries with seqNo > fromSeq.
     *
     * Two usage scenarios:
     *   1. ReplicaMain passes -1L on startup to fetch the full history for initial sync
     *   2. commitUpTo passes lastApplied during gap repair to fetch missing entries
     */
    @Override
    public synchronized List<LogEntry> getEntriesAfter(long fromSeq) {
        List<LogEntry> out = new ArrayList<>();
        // tailMap(fromSeq+1) 只遍历 seqNo > fromSeq 的部分（TreeMap 有序，O(log n)）
        // tailMap(fromSeq+1) efficiently iterates only entries with seqNo > fromSeq (TreeMap, O(log n))
        for (var e : log.tailMap(fromSeq + 1).entrySet()) {
            LogEntry le = e.getValue();
            if (le.committed) {
                // 创建副本返回，防止外部修改内部状态 / Return a copy to prevent external mutation
                out.add(new LogEntry(le.seqNo, le.op));
            }
        }
        return out;
    }

    /**
     * 单条目应用（供外部按条目逐一追赶使用，当前主要路径是 syncFrom）。
     * 只有当该条目恰好是 lastApplied+1 时才执行，保证顺序性。
     *
     * Applies a single log entry (for external incremental catch-up; main path currently is syncFrom).
     * Only executes if the entry is exactly lastApplied+1, enforcing in-order application.
     */
    public synchronized void applyLogEntry(LogEntry le) {
        if (!log.containsKey(le.seqNo)) {
            log.put(le.seqNo, le);
        }
        log.get(le.seqNo).committed = true;
        // 只执行紧接着 lastApplied 的下一条，保证顺序 / Only apply the immediately next entry
        if (le.seqNo == lastApplied + 1) {
            apply(le.op);
            lastApplied = le.seqNo;
            lastCommitted = le.seqNo;
        }
    }

    /**
     * 副本启动时批量同步历史状态（由 ReplicaMain 调用）。
     * 与 commitUpTo 的区别：
     *   - syncFrom：一次性传入完整列表，假设无 gap，不做 gap 修复
     *   - commitUpTo：运行时增量推进，需要处理 gap
     *
     * Bulk-applies historical log entries on replica startup (called by ReplicaMain).
     * Differs from commitUpTo:
     *   - syncFrom: receives a complete list at once, assumes no gaps, skips gap repair
     *   - commitUpTo: incremental during runtime, must handle gaps
     */
    public synchronized void syncFrom(List<LogEntry> entries) {
        // 先把所有条目写入本地日志，全部标记为 committed
        // Write all entries to the local log and mark them as committed
        for (LogEntry le : entries) {
            log.put(le.seqNo, le);
            log.get(le.seqNo).committed = true;
        }
        // 按序执行直到遇到 null（没有更多条目）
        // Apply in order until there are no more entries
        for (long i = lastApplied + 1; ; i++) {
            LogEntry le = log.get(i);
            if (le == null) break; // 没有更多条目 / no more entries
            apply(le.op);
            lastApplied = i;
            lastCommitted = i;
        }
    }

    // ==================== 工具方法 / Private helpers ====================

    /**
     * 向 FrontEnd 查询当前 Sequencer 的 RMI 名称。
     * 在 commitUpTo 的 gap 修复中使用，用于找到 leader 拉取缺失条目。
     *
     * Queries the FrontEnd for the current sequencer's RMI name.
     * Used in commitUpTo's gap repair to locate the leader for fetching missing entries.
     */
    private String findLeaderName() throws Exception {
        Registry reg = LocateRegistry.getRegistry();
        frontend.FrontEndAdmin fe = (frontend.FrontEndAdmin) reg.lookup("FrontEnd");
        return fe.getCurrentSequencerName();
    }

    /**
     * 通过 RMI 名称查找副本的远程代理。
     * 每次调用都重新 lookup，避免因副本重启导致的旧 stub 失效。
     *
     * Looks up a replica's RMI stub by name.
     * Always performs a fresh lookup to avoid stale stubs after replica restarts.
     */
    private ReplicatedAuction lookup(String rmiName) throws Exception {
        Registry reg = LocateRegistry.getRegistry();
        return (ReplicatedAuction) reg.lookup(rmiName);
    }

    // ==================== 选主接口 / Leader election interface ====================

    /**
     * 返回该副本已提交的最大序号。
     * FrontEnd 选主时调用此方法比较各副本，选最大的成为新 Sequencer。
     *
     * Returns the highest committed sequence number of this replica.
     * Called by the FrontEnd during leader election; the replica with the highest value is elected.
     */
    @Override
    public synchronized long getLastCommittedSeqNo() {
        return lastCommitted;
    }

    /**
     * 返回该副本是否认为自己是当前 Sequencer。
     * Returns whether this replica currently believes it is the sequencer.
     */
    @Override
    public synchronized boolean isSequencer() {
        return isLeader;
    }

    /**
     * 设置该副本的 Sequencer 角色，由 FrontEnd 在选主后调用。
     *
     * 当 isLeader=true 时，将 lastSeqAssigned 重置为 lastCommitted，
     * 保证新 leader 从正确的序号继续分配，不与旧日志冲突。
     * （若不重置，新 leader 会从 0 开始，导致与已有日志中的序号 1/2/3... 冲突）
     *
     * Sets this replica's sequencer role; called by the FrontEnd after leader election.
     *
     * When promoted (isLeader=true), resets lastSeqAssigned to lastCommitted so the new leader
     * continues assigning sequence numbers from where the old leader left off,
     * avoiding conflicts with sequence numbers already in the log.
     */
    @Override
    public synchronized void setSequencer(boolean isLeader) {
        this.isLeader = isLeader;
        if (isLeader) {
            // 从已提交的最大序号继续分配，防止序号重用
            // Continue from the highest committed seqNo to prevent sequence number reuse
            this.lastSeqAssigned = this.lastCommitted;
        }
    }
}
