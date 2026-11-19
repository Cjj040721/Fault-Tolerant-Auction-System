package frontend;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;

/**
 * FrontEnd 的启动入口。
 * 负责将 FrontEndImpl 同时注册为 RMI 远程对象（供副本调用管理接口）
 * 和 gRPC 服务（供客户端发起拍卖请求），然后阻塞等待直到进程被终止。
 *
 * Entry point that starts the FrontEnd node.
 * Registers FrontEndImpl both as an RMI remote object (so replicas can call the admin interface)
 * and as a gRPC service (so clients can make auction requests), then blocks until terminated.
 *
 * 负责人 / Owner: LX
 */
public class FrontEndServer {
    public static void main(String[] args) throws Exception {
        FrontEndImpl svc = new FrontEndImpl();
        Registry reg = LocateRegistry.getRegistry();

        // 将 FrontEndImpl 导出为 RMI 远程对象并绑定名称 "FrontEnd"
        // Export FrontEndImpl as an RMI remote object and bind it under the name "FrontEnd"
        FrontEndAdmin stub = (FrontEndAdmin) UnicastRemoteObject.exportObject(svc, 0);
        reg.rebind("FrontEnd", stub);
        System.out.println("FrontEndAdmin bound as 'FrontEnd'");

        // 启动 gRPC 服务，监听 50055 端口
        // Start the gRPC server on port 50055
        Server s = ServerBuilder.forPort(50055).addService(svc).build();
        s.start();
        System.out.println("FrontEnd started (RMI: FrontEnd, gRPC:50055)");

        // 阻塞主线程，保持进程存活
        // Block the main thread to keep the process alive
        s.awaitTermination();
    }
}
