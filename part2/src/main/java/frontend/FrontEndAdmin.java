package frontend;
import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * FrontEnd 暴露给副本的 RMI 管理接口。
 * 副本启动时通过此接口向 FrontEnd 注册自己，并查询当前 Sequencer 的名称（用于同步）。
 *
 * The RMI management interface exposed by the FrontEnd to replicas.
 * Replicas use this interface on startup to register themselves with the FrontEnd
 * and to query the current sequencer name (needed for log synchronisation).
 *
 * 负责人 / Owner: LX
 */
public interface FrontEndAdmin extends Remote {

    /**
     * 返回当前 Sequencer 的 RMI 注册名，若尚无 Sequencer 则返回 null。
     * 副本启动时调用此方法判断自己是否需要向 leader 同步历史日志。
     *
     * Returns the RMI name of the current sequencer, or null if none has been elected yet.
     * Called by a replica on startup to determine whether it needs to sync from the leader.
     */
    String getCurrentSequencerName() throws RemoteException;

    /**
     * 副本启动完成并同步完状态后，调用此方法向 FrontEnd 报到，加入成员列表。
     * 第一个注册的副本会被自动提升为 Sequencer。
     *
     * Called by a replica after it has finished syncing state, to join the member list.
     * The first replica to register is automatically promoted to sequencer.
     */
    void registerReplica(int id, String rmiName) throws RemoteException;
}
