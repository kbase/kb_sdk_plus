package us.kbase.test.sdk.initializer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.kbase.common.service.JsonClientException;
import us.kbase.common.service.JsonServerMethod;
import us.kbase.common.service.JsonServerServlet;
import us.kbase.sdk.util.DirUtils;
import us.kbase.sdk.util.ProcessHelper;
import us.kbase.test.sdk.scripts.TestConfigHelper;
import us.kbase.test.sdk.scripts.TypeGeneratorTest;

public class DynamicServiceTest extends DockerClientServerTester {

    private static final String SIMPLE_MODULE_NAME = "TestDynamic";
	public static final String SERVICE_WIZARD = "ServiceWizard";
    
    private static int serviceWizardPort;
    private static Server serviceWizardJettyServer;
    private static ServiceWizardMock serviceWizard;
    
    @BeforeAll
    public static void beforeClass() throws Exception {
        // TODO TEST CLEANUP delete this directory
        final Path workDir = Paths.get(
                TestConfigHelper.getTempTestDir(), SIMPLE_MODULE_NAME + "ServWizConfig"
        );
        Files.createDirectories(workDir);
        final File cfgFile = TypeGeneratorTest.prepareDeployCfg(
                workDir.toFile(), SERVICE_WIZARD, "servwiz");
        // needed for the service wizard mock to start up correctly
        System.setProperty("KB_DEPLOYMENT_CONFIG", cfgFile.getCanonicalPath());
        serviceWizardPort = TypeGeneratorTest.findFreePort();
        serviceWizardJettyServer = new Server(serviceWizardPort);
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        serviceWizardJettyServer.setHandler(context);
        serviceWizard = new ServiceWizardMock();
        context.addServlet(new ServletHolder(serviceWizard), "/*");
        serviceWizardJettyServer.start();
    }
    
    @AfterAll
    public static void tearDownModule() throws Exception {
        if (serviceWizardJettyServer != null)
            serviceWizardJettyServer.stop();
    }
    
    public static String runServerInDocker(File moduleDir, 
            int port) throws Exception {
        String imageName = prepareDockerImage(moduleDir, token);
        String moduleName = moduleDir.getName();
        File tlDir = new File(moduleDir, "test_local");
        File workDir = new File(tlDir, "workdir");
        File runDockerSh = new File(tlDir, "run_docker.sh");
        System.out.println();
        System.out.println("Starting up dynamic service...");
        String runDockerPath = DirUtils.getFilePath(runDockerSh);
        String workDirPath = DirUtils.getFilePath(workDir);
        String containerName = "test_" + moduleName.toLowerCase() + "_" + 
                System.currentTimeMillis();
        String endPoint = TestConfigHelper.getKBaseEndpoint();
        ProcessHelper.cmd("bash", runDockerPath, "run", "-d", "-p", port + ":5000",
                "--dns", "8.8.8.8", "-v", workDirPath + ":/kb/module/work", 
                "--name", containerName, "-e", "KBASE_ENDPOINT=" + endPoint, "-e", 
                "AUTH_SERVICE_URL=" + TestConfigHelper.getAuthServiceUrlLegacy(), "-e", 
                "AUTH_SERVICE_URL_ALLOW_INSECURE=" + TestConfigHelper.getAuthServiceUrlInsecure(),
                imageName).exec(tlDir);
        return containerName;
    }

    private static void testDynamicClients(
        final File moduleDir,
        final int port, 
        final String contName,
        final String serverType
        ) throws Exception {
        try {
            FileUtils.writeStringToFile(new File(moduleDir, "sdk.cfg"), 
                    "catalog_url=http://kbase.us");
            String dockerAddress = "localhost";
            String dockerHost = System.getenv("DOCKER_HOST");
            if (dockerHost != null && dockerHost.startsWith("tcp://")) {
                dockerAddress = dockerHost.substring(6).split(":")[0];
            }
            serviceWizard.fwdUrl = "http://" + dockerAddress + ":" + port;
            String clientEndpointUrl = "http://localhost:" + serviceWizardPort;
            testClients(moduleDir, clientEndpointUrl, false, true, serverType);
            testStatus(moduleDir, clientEndpointUrl, false, true, serverType);
        } finally {
            String runDockerPath = DirUtils.getFilePath(new File(
                    new File(moduleDir, "test_local"), "run_docker.sh"));
            ProcessHelper.cmd("bash", runDockerPath, "logs", contName).exec(moduleDir);
            ProcessHelper.cmd("bash", runDockerPath, "rm", "-v", "-f", contName).exec(moduleDir);
            System.out.println("Docker container " + contName + " was stopped and removed");
        }
    }

    @Test
    public void testJavaDynamicService() throws Exception {
        System.out.println("Test [testJavaDynamicService]");
        File moduleDir = initJava(SIMPLE_MODULE_NAME + "Java");
        int port = TypeGeneratorTest.findFreePort();
        String contName = runServerInDocker(moduleDir, port);
        testDynamicClients(moduleDir, port, contName, "Java");
    }

    @Test
    public void testPythonDynamicService() throws Exception {
        System.out.println("Test [testPythonDynamicService]");
        File moduleDir = initPython(SIMPLE_MODULE_NAME + "Python");
        int port = TypeGeneratorTest.findFreePort();
        String contName = runServerInDocker(moduleDir, port);
        testDynamicClients(moduleDir, port, contName, "Python");
    }

    public static class ServiceWizardMock extends JsonServerServlet {
        private static final long serialVersionUID = 1L;
        
        public String fwdUrl;

        public ServiceWizardMock() {
            super(SERVICE_WIZARD);
        }

        @JsonServerMethod(rpc = SERVICE_WIZARD + ".get_service_status")
        public Map<String, Object> getServiceStatus(Map<String, String> params) throws IOException, JsonClientException {
            Map<String, Object> ret = new LinkedHashMap<String, Object>();
            ret.put("url", fwdUrl);
            return ret;
        }
    }
}
