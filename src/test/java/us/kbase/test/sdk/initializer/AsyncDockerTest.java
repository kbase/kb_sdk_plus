package us.kbase.test.sdk.initializer;

import java.io.File;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.kbase.test.sdk.scripts.TestConfigHelper;
import us.kbase.test.sdk.scripts.TypeGeneratorTest;

public class AsyncDockerTest extends DockerClientServerTester {

    private static final String SIMPLE_MODULE_NAME = "TestAsync";
    
    private static int execEnginePort;
    private static Server execEngineJettyServer;
    private static CallbackServerMock cbsMock;
    
    @BeforeAll
    public static void beforeClass() throws Exception {
        execEnginePort = TypeGeneratorTest.findFreePort();
        execEngineJettyServer = new Server(execEnginePort);
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        execEngineJettyServer.setHandler(context);
        cbsMock = new CallbackServerMock().withKBaseEndpoint(TestConfigHelper.getKBaseEndpoint());
        context.addServlet(new ServletHolder(cbsMock), "/*");
        execEngineJettyServer.start();
    }
    
    @AfterAll
    public static void tearDownModule() throws Exception {
        cbsMock.waitAndCleanAllJobs();
        if (execEngineJettyServer != null)
            execEngineJettyServer.stop();
    }
    
    private static void testAsyncClients(File moduleDir, String serverType) throws Exception {
        try {
            String dockerImage = prepareDockerImage(moduleDir, token);
            cbsMock.withModule(moduleDir.getName(), dockerImage, moduleDir);
            String clientEndpointUrl = "http://localhost:" + execEnginePort;
            testClients(moduleDir, clientEndpointUrl, true, false, serverType);
            testStatus(moduleDir, clientEndpointUrl, true, false, serverType);
        } finally {
            cbsMock.waitAndCleanAllJobs();
        }
    }

    @Test
    public void testJavaAsyncService() throws Exception {
        System.out.println("Test [testJavaAsyncService]");
        File moduleDir = initJava(SIMPLE_MODULE_NAME + "Java");
        testAsyncClients(moduleDir, "Java");
    }

    @Test
    public void testPythonAsyncService() throws Exception {
        System.out.println("Test [testPythonAsyncService]");
        File moduleDir = initPython(SIMPLE_MODULE_NAME + "Python");
        testAsyncClients(moduleDir, "Python");
    }
}
