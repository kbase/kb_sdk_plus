package us.kbase.common.utils;

import java.io.IOException;
import java.net.ServerSocket;

public class NetUtils {

    public static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {}
        throw new IllegalStateException("Can not find available port in the system");
    }
}
