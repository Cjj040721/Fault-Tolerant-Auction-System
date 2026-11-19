package replica;

import common.*;
import frontend.FrontEndAdmin;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.List;

/**
 * 单个副本的启动入口。负责按顺序完成以下步骤：
 *   1. 创建 ReplicaImpl 并注册到 RMI registry
 *   2. 联系 FrontEnd 查询当前 Sequencer
 *   3. 若已有 Sequencer，从其拉取全量历史日志并同步到本地状态机
 *   4. 向 FrontEnd 报到，正式加入成员列表
 *   5. 阻塞主线程，保持进程存活
 *
 * Entry point for a single replica. Performs the following steps in order:
 *   1. Create a ReplicaImpl and register it in the RMI registry
 *   2. Contact the FrontEnd to query the current sequencer
 *   3. If a sequencer exists, pull the full committed log from it and sync the local state machine
 *   4. Register with the FrontEnd to officially join the member list
 *   5. Block the main thread to keep the process alive
 *
 * 负责人 / Owner: CJJ（调用 ReplicaImpl.syncFrom() 完成启动时状态同步）
 */
public class ReplicaMain {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: ReplicaMain <id>");
            System.exit(1);
        }
        int id = Integer.parseInt(args[0]);
        String name = "replica" + id;

        // 步骤 1：创建副本对象并注册到 RMI registry
        // Step 1: create the replica object and register it in the RMI registry
        ReplicaImpl r = new ReplicaImpl(id, name);
        Registry reg = LocateRegistry.getRegistry();
        reg.rebind(name, r);
        System.out.println("Replica " + id + " bound as '" + name + "'");

        // 步骤 2：联系 FrontEnd，查询当前是否已有 Sequencer
        // Step 2: contact the FrontEnd to find out if a sequencer already exists
        FrontEndAdmin fe = (FrontEndAdmin) reg.lookup("FrontEnd");
        String leaderName = fe.getCurrentSequencerName();

        if (leaderName != null) {
            // 步骤 3：已有 Sequencer，从其获取所有已提交日志条目，追赶状态
            // Step 3: a sequencer exists — pull all committed log entries and catch up
            System.out.println("Replica " + id + " syncing from sequencer '" + leaderName + "'...");
            ReplicatedAuction leader = (ReplicatedAuction) reg.lookup(leaderName);
            // fromSeq=-1 表示获取所有条目 / fromSeq=-1 means fetch all entries
            List<LogEntry> entries = leader.getEntriesAfter(-1L);
            System.out.println("Replica " + id + " received " + entries.size() + " entries from leader");

            // 批量应用历史日志，重建本地状态机
            // Bulk-apply all historical log entries to rebuild the local state machine
            r.syncFrom(entries);
        } else {
            // 没有 Sequencer，说明这是第一个副本，之后 FrontEnd 会将其选为 Sequencer
            // No sequencer yet — this is the first replica; FrontEnd will elect it as sequencer
            System.out.println("Replica " + id + ": no sequencer yet, will become first member");
        }

        // 步骤 4：向 FrontEnd 报到，加入成员列表（FrontEnd 此时可能调用 setSequencer(true)）
        // Step 4: register with the FrontEnd to join the member list (FrontEnd may call setSequencer(true) here)
        fe.registerReplica(id, name);
        System.out.println("Replica " + id + " registered with front-end. Ready.");

        // 步骤 5：阻塞主线程，使副本进程持续运行
        // Step 5: block the main thread to keep the replica process alive
        Thread.currentThread().join();
    }
}
