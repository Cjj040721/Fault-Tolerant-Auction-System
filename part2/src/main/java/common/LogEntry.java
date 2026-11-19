package common;
import java.io.Serializable;

/**
 * 复制日志中的一条记录，保存操作及其全局序号和提交状态。
 * 所有副本都维护一份相同顺序的 LogEntry 列表，这是状态机复制的核心数据结构。
 *
 * A single entry in the replication log, storing an operation together with
 * its globally assigned sequence number and commit status.
 * All replicas maintain an identical ordered list of LogEntry objects —
 * this is the core data structure of state machine replication.
 *
 * 负责人 / Owner: CZR（被 CJJ 的 ReplicaImpl 大量使用 / heavily used by CJJ's ReplicaImpl）
 */
public class LogEntry implements Serializable {

    /** 全局唯一、单调递增的序号，由 Sequencer 分配 / Globally unique, monotonically increasing sequence number assigned by the sequencer */
    public final long seqNo;

    /** 该条目对应的操作内容 / The operation this entry represents */
    public final Operation op;

    /**
     * 是否已完成提交（两阶段中的 commit 阶段）
     * propose 阶段写入时为 false，收到 commitUpTo 后变为 true
     *
     * Whether this entry has been committed (i.e., passed the commit phase of two-phase replication).
     * Set to false when written during the propose phase; set to true upon receiving commitUpTo.
     */
    public boolean committed;

    /** 构造时 committed 默认为 false / committed defaults to false on construction */
    public LogEntry(long s, Operation o) {
        this.seqNo=s;
        this.op=o;
        this.committed=false;
    }
}
