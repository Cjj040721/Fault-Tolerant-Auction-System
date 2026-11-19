package replica;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;
import common.*;

/**
 * 所有副本对外暴露的 RMI 接口，FrontEnd 通过此接口与副本通信。
 * 接口分为四组：写操作入口、两阶段复制协议、状态同步、选主辅助。
 *
 * The RMI interface exposed by every replica to the FrontEnd (and to other replicas).
 * Methods are grouped into four categories: write entry point, two-phase replication,
 * state synchronisation, and leader election helpers.
 *
 * 负责人 / Owner: CJJ（接口定义，实现见 ReplicaImpl.java）
 */
public interface ReplicatedAuction extends Remote {

    /**
     * 写操作入口，仅 Sequencer 处理。
     * FrontEnd 将客户端的写请求封装成 Operation 后调用此方法。
     * Sequencer 内部执行两阶段提交协议并返回结果。
     *
     * Entry point for write operations; only the sequencer handles this.
     * The FrontEnd wraps the client's write request into an Operation and calls this.
     * The sequencer internally runs the two-phase commit protocol and returns the result.
     *
     * @param op         要执行的操作 / The operation to execute
     * @param memberList 当前所有副本的 RMI 名列表，由 FrontEnd 传入 / RMI names of all current replicas, passed by FrontEnd
     */
    OperationResult handleClientOperation(Operation op, List<String> memberList) throws RemoteException;

    /**
     * 两阶段提交 — 第一阶段：Sequencer 通知 Follower 记录操作到日志（但不执行）。
     * Follower 将条目写入本地日志（committed=false）并返回 ACK。
     *
     * Two-phase commit — Phase 1: the sequencer asks a follower to record the operation in its log (without executing it).
     * The follower writes the entry to its local log (committed=false) and returns an ACK.
     *
     * @param seqNo Sequencer 分配的全局序号 / Globally assigned sequence number
     * @param op    要记录的操作 / The operation to log
     */
    boolean propose(long seqNo, Operation op) throws RemoteException;

    /**
     * 两阶段提交 — 第二阶段：Sequencer 通知 Follower 按顺序执行到 seqNo 为止的所有已提交操作。
     * Follower 将日志中 seqNo 及之前的所有条目顺序应用到本地状态机。
     *
     * Two-phase commit — Phase 2: the sequencer tells a follower to apply all committed operations up to seqNo in order.
     * The follower applies every log entry up to and including seqNo to its local state machine.
     *
     * @param seqNo 需要执行到的最大序号 / The highest sequence number to apply
     */
    boolean commitUpTo(long seqNo) throws RemoteException;

    /**
     * 返回所有序号大于 fromSeq 且已提交的日志条目。
     * 用途：① 新副本启动时全量同步（fromSeq=-1）；② commitUpTo 中 gap 修复时补全缺失条目。
     *
     * Returns all committed log entries with seqNo > fromSeq.
     * Used for: (1) full sync when a new replica starts (fromSeq=-1);
     *           (2) gap repair inside commitUpTo to fetch missing entries.
     */
    List<LogEntry> getEntriesAfter(long fromSeq) throws RemoteException;

    /**
     * 返回该副本已提交（commit 阶段完成）的最大序号。
     * FrontEnd 选主时比较各副本的此值，选最大的那个当新 Sequencer。
     *
     * Returns the highest sequence number committed by this replica.
     * Used by the FrontEnd during leader election: the replica with the highest value is elected.
     */
    long getLastCommittedSeqNo() throws RemoteException;

    /**
     * 返回该副本当前是否认为自己是 Sequencer。
     * Returns whether this replica currently believes it is the sequencer.
     */
    boolean isSequencer() throws RemoteException;

    /**
     * 设置该副本的 Sequencer 角色。
     * FrontEnd 选主后调用此方法激活新 leader。
     * 当 isLeader=true 时，副本还会将 lastSeqAssigned 重置为 lastCommitted，
     * 避免序号从 0 重新开始与旧日志冲突。
     *
     * Sets this replica's sequencer role.
     * Called by the FrontEnd after electing a new leader.
     * When isLeader=true, the replica also resets lastSeqAssigned to lastCommitted
     * to avoid reusing sequence numbers already in the log.
     */
    void setSequencer(boolean isLeader) throws RemoteException;

    /**
     * 查询单个拍卖品详情（只读，不走复制协议）。
     * Returns details of a single auction item (read-only; bypasses replication).
     */
    AuctionItem getSpec(int itemID) throws RemoteException;

    /**
     * 列出所有进行中的拍卖（只读，不走复制协议）。
     * Lists all active auction items (read-only; bypasses replication).
     */
    AuctionItem[] listItems() throws RemoteException;
}
