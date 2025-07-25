package us.kbase.test.sdk.scripts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.ConnectException;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.io.FileUtils;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;

import us.kbase.common.service.JsonClientException;
import us.kbase.common.service.JsonServerMethod;
import us.kbase.common.service.JsonServerServlet;
import us.kbase.common.service.ServerException;
import us.kbase.common.service.UObject;
import us.kbase.kidl.KbFuncdef;
import us.kbase.kidl.KbService;
import us.kbase.kidl.KidlParser;
import us.kbase.sdk.compiler.JavaData;
import us.kbase.sdk.compiler.JavaFunc;
import us.kbase.sdk.compiler.JavaModule;
import us.kbase.sdk.compiler.JavaTypeGenerator;
import us.kbase.sdk.compiler.PrevCodeParser;
import us.kbase.sdk.compiler.RunCompileCommand;
import us.kbase.sdk.util.DiskFileSaver;
import us.kbase.sdk.util.FileSaver;
import us.kbase.sdk.util.ProcessHelper;
import us.kbase.sdk.util.TextUtils;

/**
 * Main test class for JavaTypeGenerator. It contains 10 tests checking different cases 
 * including basic primitive, collection and structure types, authentication, #includes,
 * syslog, documentation and GWT-stubs. 
 * @author rsutormin
 */
public class TypeGeneratorTest {

	// TODO TEST seems like the TODO below is done, but not entirely sure
	//           the tests work correctly. Verify then remove
	//TODO TESTING test python client with dynamic services
	// probably best way is with a mock service wizard that returns a url to
	// the running service
	//TODO TESTING pep8 test? Not really sure about this.

	private static final String rootPackageName = "us.kbase";
	private static final String SERVICE_WIZARD = "ServiceWizard";
	
	// specified in the build.gradle file
	private static final Path BUILD_LIB_DIR = Paths.get("build/generated-code-libs")
			.toAbsolutePath();

	private static boolean debugClientTimes = false;

	public static void main(String[] args) throws Exception{
		int testNum = Integer.parseInt(args[0]);
		if (testNum == 5) {
			new TypeGeneratorTest().testSyslog();
		} else if (testNum == 6) {
			new TypeGeneratorTest().testAuth();
		} else if (testNum == 8) {
			new TypeGeneratorTest().testServerCodeStoring();
		} else if (testNum == 9 || testNum == 10) {
			startTest(testNum, false);
		} else {
			startTest(testNum);
		}
	}
	
    @BeforeAll
    public static void prepareTestConfigParams() throws Exception {
        // Next line loads test config-params to java System properties:
        TestConfigHelper.init();
        suppressJettyLogging();
	}

    public static void suppressJettyLogging() {
        Log.setLog(new Logger() {
            @Override
            public void warn(String arg0, Object arg1, Object arg2) {}
            @Override
            public void warn(String arg0, Throwable arg1) {}
            @Override
            public void warn(String arg0) {}
            @Override
            public void setDebugEnabled(boolean arg0) {}
            @Override
            public boolean isDebugEnabled() {
                return false;
            }
            @Override
            public void info(String arg0, Object arg1, Object arg2) {}
            @Override
            public void info(String arg0) {}
            @Override
            public String getName() {
                return null;
            }
            @Override
            public Logger getLogger(String arg0) {
                return this;
            }
            @Override
            public void debug(String arg0, Object arg1, Object arg2) {}
            @Override
            public void debug(String arg0, Throwable arg1) {}
            @Override
            public void debug(String arg0) {}
        });
    }
	
	@BeforeEach
	public void beforeCleanup() {
	    System.clearProperty("KB_JOB_CHECK_WAIT_TIME");
	}
	
	@Test
	public void testSimpleTypesAndStructures() throws Exception {
		startTest(1);
	}

	@Test
	public void testIncludsAndMultiModules() throws Exception {
		startTest(2);
	}

	@Test
	public void testTuples() throws Exception {
		startTest(3);
	}

	@Test
	public void testObject() throws Exception {
		startTest(4);
	}

	@Test
	public void testSyslog() throws Exception {
		int testNum = 5;
		File workDir = prepareWorkDir(testNum);
		System.out.println();
		System.out.println("Test " + testNum + " (testSyslog) is starting in directory: " + workDir.getName());
		String testPackage = rootPackageName + ".test" + testNum;
		File srcDir = new File(workDir, "src");
		File libDir = new File(workDir, "lib");
		File binDir = new File(workDir, "bin");
		JavaData parsingData = prepareJavaCode(testNum, workDir, testPackage, libDir, binDir, null, true);
		javaServerCorrectionForTestCallback(srcDir, testPackage, parsingData, testPackage + ".Test" + testNum);
		String classPath = prepareClassPath(libDir, new ArrayList<URL>());
    	runJavac(workDir, srcDir, classPath, binDir, "src/us/kbase/test5/syslogtest/SyslogTestServer.java");
        int portNum = findFreePort();
		runJavaServerTest(testNum, true, workDir, testPackage, libDir, binDir, parsingData, null, portNum);
	}
	
	@Test
	public void testAuth() throws Exception {
		final int testNum = 6;
		final File workDir = prepareWorkDir(testNum);
		System.out.println();
		System.out.println("Test " + testNum + " (testAuth) is starting in directory: " + workDir.getAbsolutePath());
		String testPackage = rootPackageName + ".test" + testNum;
		final File libDir = new File(workDir, "lib");
		final File binDir = new File(workDir, "bin");
		int portNum = findFreePort();
		// I have no idea why this line is run over and over with the same args
		JavaData parsingData = prepareJavaCode(testNum, workDir, testPackage, libDir, binDir, portNum, true);
		parsingData = prepareJavaCode(testNum, workDir, testPackage, libDir, binDir, portNum, true);
		final File serverOutDir = preparePyServerCode(testNum, workDir);
		runPythonServerTest(testNum, true, workDir, testPackage, libDir, binDir, parsingData, serverOutDir, portNum);
		portNum = findFreePort();
		parsingData = prepareJavaCode(testNum, workDir, testPackage, libDir, binDir, portNum, true);
		runJavaServerTest(testNum, true, workDir, testPackage, libDir, binDir, parsingData, serverOutDir, portNum);
	}

	@Test
	public void testEmptyArgsAndReturns() throws Exception {
		startTest(7);
	}
	
	@Test
	public void testServerCodeStoring() throws Exception {
	    ////////////////////////////////////// Java ///////////////////////////////////////
		int testNum = 8;
		File workDir = prepareWorkDir(testNum);
		System.out.println();
		System.out.println("Test " + testNum + " (testServerCodeStoring) is staring in directory: " + workDir.getName());
		String testFileName = "test" + testNum + ".spec";
		extractSpecFiles(testNum, workDir, testFileName);
		File srcDir = new File(workDir, "src");
		String testPackage = rootPackageName + ".test" + testNum;
    	String serverFilePath = "src/" + testPackage.replace('.', '/') + "/storing/StoringServer.java";
        File serverJavaFile = new File(workDir, serverFilePath);
        serverJavaFile.getParentFile().mkdirs();
        serverJavaFile.createNewFile();
		File libDir = new File(workDir, "lib");
        // Test for empty server file
		setUpTestJars(new DiskFileSaver(libDir));
		try {
			JavaTypeGenerator.processSpec(new File(workDir, testFileName),
					srcDir, testPackage, true, null);
		} catch (Exception ex) {
			boolean key = ex.getMessage().contains("Missing header in original file");
			if (!key)
				ex.printStackTrace();
			assertTrue(key);
		}
        String testJavaResource = "Test" + testNum + ".java.properties";
        InputStream testClassIS = TypeGeneratorTest.class.getResourceAsStream(testJavaResource);
        if (testClassIS == null) {
        	fail("Java test class resource was not found: " + testJavaResource);
        }
        TextUtils.copyStreams(testClassIS, new FileOutputStream(serverJavaFile));
        // Test for full server file
		JavaData parsingData = JavaTypeGenerator.processSpec(
				new File(workDir, testFileName), srcDir, testPackage, true, null
		);
		List<URL> cpUrls = new ArrayList<URL>();
		String classPath = prepareClassPath(libDir, cpUrls);
		File binDir = new File(workDir, "bin");
        cpUrls.add(binDir.toURI().toURL());
		compileModulesIntoBin(workDir, srcDir, testPackage, parsingData, classPath, binDir);
		for (JavaModule module : parsingData.getModules())
        	createServerServletInstance(module, libDir, binDir, testPackage);
		String text = TextUtils.readFileText(serverJavaFile);
		assertTrue(text.contains("* Header comment."));
		assertTrue(text.contains("private int myValue = -1;"));
		assertTrue(text.contains("myValue = 0;"));
		assertTrue(text.contains("myValue = 1;"));
		assertTrue(text.contains("myValue = 2;"));
        assertTrue(text.contains("myValue = 3;"));
        assertTrue(text.contains("myValue = 4;"));
		/////////////////////////////// Python ////////////////////////////////////
        {
            File serverOutDir = preparePyServerCode(testNum, workDir);
            String implName = parsingData.getModules().get(0).getOriginal().getModuleName() + "Impl";
            File pythonImplFile = new File(serverOutDir, implName + ".py");
            TextUtils.copyStreams(TypeGeneratorTest.class.getResourceAsStream(
                    "Test" + testNum + ".python.properties"), new FileOutputStream(pythonImplFile));
            preparePyServerCode(testNum, workDir);
            text = TextUtils.readFileText(pythonImplFile);
            assertTrue(text.contains("# Header comment."));
            assertTrue(text.contains("# Class header comment."));
            assertTrue(text.contains("myValue = -1"));
            assertTrue(text.contains("myValue = 1"));
            assertTrue(text.contains("myValue = 2"));
            assertTrue(text.contains("myValue = 3"));
            assertTrue(text.contains("myValue = 4"));
        }
        ///////////////////////////////// Windows EOL chars /////////////////////////////////
        String codeText = "" +
                "#BEGIN_HEADER\r\n" +
                "text1\r\n" +
                "#END_HEADER\r\n" +
                "\r\n" +
                "class Storing:\r\n" +
                "    #BEGIN_CLASS_HEADER\r\n" +
                "    text2\r\n" +
                "    #END_CLASS_HEADER\r\n" +
                "    \r\n" +
                "    def __init__(self, config):\r\n" +
                "        #BEGIN_CONSTRUCTOR\r\n" +
                "        text3\r\n" +
                "        #END_CONSTRUCTOR\r\n" +
                "    \r\n" +
                "    def m1(self, ctx):\r\n" +
                "        #BEGIN m1\r\n" +
                "        text4\r\n" +
                "        #END m1\r\n";
        File tempFile = new File(workDir, "test.py");
        FileUtils.write(tempFile, codeText);
        Map<String, String> prevCode = PrevCodeParser.parsePrevCode(tempFile, "#", 
                Arrays.asList("m1"), true);
        assertEquals("text1", prevCode.get(PrevCodeParser.HEADER).trim());
        assertEquals("text2", prevCode.get(PrevCodeParser.CLSHEADER).trim());
        assertEquals("text3", prevCode.get(PrevCodeParser.CONSTRUCTOR).trim());
        assertEquals("text4", prevCode.get(PrevCodeParser.METHOD + "m1").trim());
	}
	
	// Test9 was removed as obsolete
	
	@Test
	public void testComments() throws Exception {
		startTest(10, false);
	}

	@Test
	public void testIncludsAndMultiModules2() throws Exception {
		startTest(11);
	}

	@Test
	public void testAsyncMethods() throws Exception {
		// TODO PYTHONSERVER Revert the change that causes errors to be repr'd rather than
		//                   str'd. Causes extra quotes around the string, which made this
		//                   test fail. Fix Test12.java.properties when done.
		// TODO TESTREINSTATEMENT see the comments in test12.spec.properties
		int testNum = 12;
		File workDir = prepareWorkDir(testNum);
		System.out.println();
		System.out.println("Test " + testNum + " (testAsyncMethods) is starting in directory: " + workDir.getName());
		Server jettyServer = startCBSMock(workDir, workDir);
		int cbsMockPort = jettyServer.getConnectors()[0].getLocalPort();
		try {
			String testPackage = rootPackageName + ".test" + testNum;
			File libDir = new File(workDir, "lib");
			File binDir = new File(workDir, "bin");
			JavaData parsingData = prepareJavaCode(
					testNum, workDir, testPackage, libDir, binDir, null, true, false, true, null
			);
			String moduleName = parsingData.getModules().get(0).getModuleName();
			String modulePackage = parsingData.getModules().get(0).getModulePackage();
			StringBuilder cp = new StringBuilder(binDir.getAbsolutePath());
			for (File f : libDir.listFiles()) {
				cp.append(":").append(f.getAbsolutePath());
			}
			File serverOutDir = preparePyServerCode(testNum, workDir, false, true);
			List<String> lines = null;
			System.setProperty("KB_JOB_CHECK_WAIT_TIME", "100");
			File cfgFile = prepareDeployCfg(workDir, getModuleName(parsingData));
			//////////////////////////////////////// Python server ///////////////////////////////////////////
			lines = new ArrayList<String>(Arrays.asList("#!/bin/bash"));
			lines.addAll(Arrays.asList(
					"export KB_DEPLOYMENT_CONFIG=" + cfgFile.getCanonicalPath(),
					"cd \"" + serverOutDir.getAbsolutePath() + "\"",
					"python " + findPythonServerScript(serverOutDir).getName() + " $1 $2 $3 > py_cli.out 2> py_cli.err"
					));
			TextUtils.writeFileLines(lines, new File(workDir, "run_" + moduleName + "_async_job.sh"));
			runPythonServerTest(testNum, true, workDir, testPackage, libDir, binDir, 
					parsingData, serverOutDir, cbsMockPort, null);
			//////////////////////////////////////// Java server ///////////////////////////////////////////
			lines = new ArrayList<String>(Arrays.asList("#!/bin/bash"));
			lines.addAll(Arrays.asList(
					"export KB_DEPLOYMENT_CONFIG=" + cfgFile.getCanonicalPath(),
					"java -cp \"" + cp + "\" " + testPackage + "." + modulePackage + "." + moduleName + "Server $1 $2 $3"
					));
			TextUtils.writeFileLines(lines, new File(workDir, "run_" + moduleName + "_async_job.sh"));
			runJavaServerTest(testNum, true, workDir, testPackage, libDir, binDir, 
					parsingData, serverOutDir, cbsMockPort, null);
		} finally {
			jettyServer.stop();
		}
	}

	@Test
	public void testErrors() throws Exception {
		startTest(13, true, true);
	}

    @Test
    public void testRpcContext() throws Exception {
        // TODO CRUFT remove call stack and all the other cruft from RpcContext in java_common
        //           and delete this test and remove serverManualCorrections from prepareJavaCode
        int testNum = 14;
        File workDir = prepareWorkDir(testNum);
        System.out.println();
        System.out.println("Test " + testNum + " (testRpcContext) is starting in directory: " + workDir.getName());
        String testPackage = rootPackageName + ".test" + testNum;
        File libDir = new File(workDir, "lib");
        File binDir = new File(workDir, "bin");
        int portNum = findFreePort();
        Map<String, String> serverManualCorrections = new HashMap<String, String>();
        serverManualCorrections.put("send_context", "returnVal = arg1; " +
                "returnVal.getMethods().add(jsonRpcContext.getCallStack().get(0).getMethod()); " +
                "returnVal.getMethods().add(jsonRpcContext.getCallStack().get(1).getMethod())");
        JavaData parsingData = prepareJavaCode(testNum, workDir, testPackage, libDir, binDir,
                portNum, true, false, false, serverManualCorrections
        );
        runJavaServerTest(testNum, true, workDir, testPackage, libDir, binDir, parsingData, null, portNum);
    }

    @Test
    public void testServerAuth() throws Exception {
        startTest(15);
    }
    
    @Test
    public void testMissingMethods() throws Exception {
        int testNum = 16;
        File workDir = prepareWorkDir(testNum);
        System.out.println();
        System.out.println("Test " + testNum + " (testMissingMethods) is starting in directory: " + workDir.getName());
        String testPackage = rootPackageName + ".test" + testNum;
        File libDir = new File(workDir, "lib");
        File binDir = new File(workDir, "bin");
        JavaData parsingData = prepareJavaCode(testNum, workDir, testPackage, libDir, binDir, null, true);
        File serverOutDir = preparePyServerCode(testNum, workDir);
        runPythonServerTest(testNum, true, workDir, testPackage, libDir, binDir, parsingData, 
                serverOutDir, findFreePort());
        runJavaServerTest(testNum, true, workDir, testPackage, libDir,
                binDir, parsingData, serverOutDir, findFreePort());
    }

    @Test
    public void testEmptyPackageParent() throws Exception {
        int testNum = 17;
        File workDir = prepareWorkDir(testNum);
        System.out.println();
        System.out.println("Test " + testNum + " (testEmptyPackageParent) is starting in directory: " + workDir.getName());
        String testPackage = ".";
        File libDir = new File(workDir, "lib");
        File binDir = new File(workDir, "bin");
        JavaData parsingData = prepareJavaCode(testNum, workDir, testPackage, libDir, binDir, null, true);
        runJavaServerTest(testNum, true, workDir, testPackage, libDir,
                binDir, parsingData, null, findFreePort());
    }

    // Test 18 was removed as obsolete

    @Test
    public void testStatus() throws Exception {
        int testNum = 19;
        File workDir = prepareWorkDir(testNum);
        System.out.println();
        System.out.println("Test " + testNum + " (testStatus) is starting in directory: " + workDir.getName());
        String testPackage = rootPackageName + ".test" + testNum;
        File libDir = new File(workDir, "lib");
        File binDir = new File(workDir, "bin");
        JavaData parsingData = prepareJavaCode(testNum, workDir, testPackage, libDir, binDir, null, true);
        File serverOutDir = preparePyServerCode(testNum, workDir);
        JavaModule module = parsingData.getModules().get(0);
        File javaServerImpl = new File(workDir, "src/" + testPackage.replace('.', '/') + "/" + 
                module.getModulePackage() +"/" + getServerClassName(module) + ".java");
        checkFileForKeyword(javaServerImpl, "BEGIN_STATUS", true);
        File pythonServerImpl = new File(serverOutDir, module.getOriginal().getModuleName() + "Impl.py");
        checkFileForKeyword(pythonServerImpl, "BEGIN_STATUS", true);
        runPythonServerTest(testNum, true, workDir, testPackage, libDir, binDir, parsingData, 
                serverOutDir, findFreePort());
        runJavaServerTest(testNum, true, workDir, testPackage, libDir,
                binDir, parsingData, serverOutDir, findFreePort());
    }

    private static void checkFileForKeyword(File f, String keyword, boolean occure) throws Exception {
        String text = FileUtils.readFileToString(f);
        assertEquals(occure, text.contains(keyword));
    }
    
    @Test
    public void testNoStatus() throws Exception {
        int testNum = 20;
        File workDir = prepareWorkDir(testNum);
        System.out.println();
        System.out.println("Test " + testNum + " (testNoStatus) is starting in directory: " + workDir.getName());
        String testPackage = rootPackageName + ".test" + testNum;
        File libDir = new File(workDir, "lib");
        File binDir = new File(workDir, "bin");
        JavaData parsingData = prepareJavaCode(testNum, workDir, testPackage, libDir, binDir, null, true);
        File serverOutDir = preparePyServerCode(testNum, workDir);
        JavaModule module = parsingData.getModules().get(0);
        File javaServerImpl = new File(workDir, "src/" + testPackage.replace('.', '/') + "/" + 
                module.getModulePackage() +"/" + getServerClassName(module) + ".java");
        checkFileForKeyword(javaServerImpl, "BEGIN_STATUS", false);
        File pythonServerImpl = new File(serverOutDir, module.getOriginal().getModuleName() + "Impl.py");
        checkFileForKeyword(pythonServerImpl, "BEGIN_STATUS", false);
        runPythonServerTest(testNum, true, workDir, testPackage, libDir, binDir, parsingData, 
                serverOutDir, findFreePort());
        runJavaServerTest(testNum, true, workDir, testPackage, libDir,
                binDir, parsingData, serverOutDir, findFreePort());
    }

	@Test
	public void testDynamicClients() throws Exception {
		int testNum = 21;
		File workDir = prepareWorkDir(testNum);
		System.out.println();
		System.out.println("Test " + testNum + " (testDynamicClients) is starting in directory: " + workDir.getName());
		String testPackage = rootPackageName + ".test" + testNum;
		File libDir = new File(workDir, "lib");
		File binDir = new File(workDir, "bin");
		JavaData parsingData = prepareJavaCode(
				testNum, workDir, testPackage, libDir, binDir, null, true, true, false, null
		);
		File serverOutDir = preparePyServerCode(testNum, workDir, true, false);
		// TODO TESTSIMPLICITY not sure why serverPortHolder is needed
		final int[] serverPortHolder = new int[] {-1};  // Servers should startup on this port
		// Starting up service wizard
		final File cfgFile = prepareDeployCfg(workDir, SERVICE_WIZARD, "servwiz");
		// needed for the service wizard mock to start up correctly
		System.setProperty("KB_DEPLOYMENT_CONFIG", cfgFile.getCanonicalPath());
		Server jettyServer = startServiceWizard(serverPortHolder);
		try {
			int serviceWizardPort = jettyServer.getConnectors()[0].getLocalPort();  // Clients should use it for URL lookup
			serverPortHolder[0] = findFreePort();
			runPythonServerTest(testNum, true, workDir, testPackage, libDir, binDir, parsingData, 
					serverOutDir, serviceWizardPort, serverPortHolder[0]);
			serverPortHolder[0] = findFreePort();
			runJavaServerTest(testNum, true, workDir, testPackage, libDir,
					binDir, parsingData, serverOutDir, serviceWizardPort, serverPortHolder[0]);
		} finally {
			jettyServer.stop();
		}
	}

	private Server startCBSMock(File binDir, File tempDir) throws Exception {
		Server jettyServer = new Server(findFreePort());
		ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
		context.setContextPath("/");
		jettyServer.setHandler(context);
		context.addServlet(new ServletHolder(
				new CallbackServerMock().withBinDir(binDir).withTempDir(tempDir)),"/*"
		);
		jettyServer.start();
		return jettyServer;
	}

	private Server startServiceWizard(int[] serverPortHolder) throws Exception {
		// TODO TESTLOGGING this service wizard doesn't appear to log at all which makes debugging
		// really suck
		Server jettyServer = new Server(findFreePort());
		ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
		context.setContextPath("/");
		jettyServer.setHandler(context);
		context.addServlet(new ServletHolder(new ServiceWizardMock(serverPortHolder)), "/*");
		jettyServer.start();
		return jettyServer;
	}

	private static void startTest(int testNum) throws Exception {
		startTest(testNum, true);
	}

	private static String getCallingMethod() {
		StackTraceElement[] st = Thread.currentThread().getStackTrace();
        int pos = 3;
		String methodName = st[pos].getMethodName();
		while (methodName.equals("startTest")) {
		    pos++;
			methodName = st[pos].getMethodName();
		}
		return methodName;
	}
	
	private static void startTest(int testNum, boolean needClientServer) throws Exception {
	    startTest(testNum, needClientServer, needClientServer);
	}

	private static void startTest(
			final int testNum,
			final boolean needJavaServer,
			final boolean needPythonServer)
			throws Exception {
		File workDir = prepareWorkDir(testNum);
		System.out.println();
		System.out.println("Test " + testNum + " (" + getCallingMethod() +
		        ") is starting in directory: " + workDir.getAbsolutePath());
		String testPackage = rootPackageName + ".test" + testNum;
		File libDir = new File(workDir, "lib");
		File binDir = new File(workDir, "bin");
		boolean needClientServer = needJavaServer || needPythonServer;
		JavaData parsingData = prepareJavaCode(testNum, workDir, testPackage, libDir, binDir, 
		        null, needClientServer);
		if (needClientServer) {
            File serverOutDir = preparePyServerCode(testNum, workDir);
            if (needPythonServer) {
		        runPythonServerTest(testNum, needClientServer, workDir,
		                testPackage, libDir, binDir, parsingData, serverOutDir, 
		                findFreePort());
		    }
		    if (needJavaServer)
		        runJavaServerTest(testNum, needClientServer, workDir, testPackage, libDir,
		                binDir, parsingData, serverOutDir, findFreePort());
		} else {
			runClientTest(testNum, testPackage, parsingData, libDir, binDir, -1, 
			        needClientServer, null, "no");
		}
	}

	protected static void runPythonServerTest(int testNum,
	        boolean needClientServer, File workDir, String testPackage,
	        File libDir, File binDir, JavaData parsingData, File serverOutDir,
	        int portNum) throws IOException, Exception {
	    runPythonServerTest(testNum, needClientServer, workDir, testPackage, libDir, 
	            binDir, parsingData, serverOutDir, portNum, portNum);
	}

	protected static void runPythonServerTest(
			final int testNum,
			final boolean needClientServer,
			final File workDir,
			final String testPackage,
			final File libDir,
			final File binDir,
			final JavaData parsingData,
			final File serverOutDir,
			final int clientPortNum,
			final Integer serverPortNum
			) throws Exception {
		String serverType = "Python";
		File pidFile = new File(serverOutDir, "pid.txt");
		pythonServerCorrection(serverOutDir, parsingData);
		try {
			File cfgFile = prepareDeployCfg(workDir, getModuleName(parsingData));
			File serverFile = findPythonServerScript(serverOutDir);
			File uwsgiFile = new File(serverOutDir, "start_py_server.sh");
			List<String> lines = new ArrayList<String>(Arrays.asList("#!/bin/bash"));
			//JavaTypeGenerator.checkEnvVars(lines, "PYTHONPATH");
			lines.addAll(Arrays.asList(
					"export KB_DEPLOYMENT_CONFIG=" + cfgFile.getCanonicalPath(),
					"cd \"" + serverOutDir.getAbsolutePath() + "\"",
					"if [ ! -d biokbase ]; then",
					"  mkdir -p ./biokbase",
					// TODO TESTCODE this is bonkers, need a better way of reffing files
					"  cp -r ../../../../src/main/resources/us/kbase/sdk/templates/log.py "
					+ "./biokbase/",
					"fi"
					));
			if (serverPortNum != null) {
				lines.addAll(Arrays.asList(
						"which python",
						"python " + serverFile.getName() + " --host localhost --port " + serverPortNum + 
						" >py_server.out 2>py_server.err & pid=$!",
						"echo $pid > " + pidFile.getName()
						));
				TextUtils.writeFileLines(lines, uwsgiFile);
				ProcessHelper.cmd("bash", uwsgiFile.getCanonicalPath()).exec(serverOutDir);
				System.out.println(serverType + " server was started up");
			} else {
				TextUtils.writeFileLines(lines, uwsgiFile);
				ProcessHelper.cmd("bash", uwsgiFile.getCanonicalPath()).exec(serverOutDir);
			}
			runClientTest(testNum, testPackage, parsingData, libDir, binDir, clientPortNum, needClientServer, 
					serverOutDir, serverType);
		} finally {
			if (pidFile.exists()) {
				String pid = TextUtils.readFileLines(pidFile).get(0).trim();
				ProcessHelper.cmd("kill", pid).exec(workDir);
				System.out.println(serverType + " server was stopped");
			}
		}
	}

	protected static void runJavaServerTest(
			final int testNum,
			final boolean needClientServer,
			final File workDir,
			final String testPackage,
			final File libDir,
			final File binDir,
			final JavaData parsingData,
			final File serverOutDir, 
			final int portNum)
			throws Exception {
		runJavaServerTest(testNum, needClientServer, workDir, testPackage, libDir, binDir, 
				parsingData, serverOutDir, portNum, portNum);
	}

	protected static void runJavaServerTest(
			final int testNum,
			final boolean needClientServer,
			final File workDir,
			final String testPackage,
			final File libDir,
			final File binDir,
			final JavaData parsingData,
			final File serverOutDir,
			final int clientPortNum,
			final Integer serverPortNum)
			throws Exception {
		Server javaServer = null;
		try {
			if (serverPortNum != null) {
				File cfgFile = prepareDeployCfg(workDir, getModuleName(parsingData));
				System.setProperty("KB_DEPLOYMENT_CONFIG", cfgFile.getCanonicalPath());
				JavaModule mainModule = parsingData.getModules().get(0);
				//long time = System.currentTimeMillis();
				javaServer = new Server(serverPortNum);
				ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
				context.setContextPath("/");
				javaServer.setHandler(context);
				Class<?> serverClass = createServerServletInstance(mainModule, libDir, binDir, testPackage);
				context.addServlet(new ServletHolder(serverClass), "/*");
				javaServer.start();
				System.out.println("Java server was started up");
			}
			runClientTest(testNum, testPackage, parsingData, libDir, binDir, clientPortNum, needClientServer, 
					serverOutDir, "Java");
		} finally {
			if (javaServer != null) {
				javaServer.stop();
				System.out.println("Java server was stopped");
			}
		}
	}

	public static int findFreePort() {
	    try (ServerSocket socket = new ServerSocket(0)) {
	        return socket.getLocalPort();
	    } catch (IOException e) {}
	    throw new IllegalStateException("Can not find available port in system");
	}

	private static File prepareDeployCfg(final File dir, final String moduleName
			) throws Exception {
		return prepareDeployCfg(dir, moduleName, "");
	}

	// TODO TEST CLEANUP move to a test utility file
	public static File prepareDeployCfg(
			final File dir,
			final String moduleName,
			final String suffix)
			throws Exception {
		File cfgFile = new File(dir, "deploy" + suffix + ".cfg");
		if (!cfgFile.exists()) {
			List<String> lines = new ArrayList<String>(Arrays.asList(
					"[" + moduleName + "]",
					"auth-service-url = " + TestConfigHelper.getAuthServiceUrlLegacy(),
					"auth-service-url-allow-insecure = "
							+ TestConfigHelper.getAuthServiceUrlInsecure()
			));
			TextUtils.writeFileLines(lines, cfgFile);
		}
		return cfgFile;
	}

	protected static File preparePyServerCode(int testNum, File workDir) throws Exception {
	    return preparePyServerCode(testNum, workDir, false, false);
	}
	
	protected static File preparePyServerCode(
			final int testNum,
			final File workDir, 
			final boolean isClientDynamic,
			final boolean isClientAsync
			) throws Exception {
		File testFile = new File(workDir, "test" + testNum + ".spec");
		File serverOutDir = new File(workDir, "out");
		if (!serverOutDir.exists())
			serverOutDir.mkdir();
		// Generate servers (old or new style)
		// this has to change
		RunCompileCommand.generate(
				testFile,                // specFile
				null,                    // url
				true,                    // pyClientSide
				null,                    // pyClientName
				true,                    // pyServerSide
				null,                    // pyServerName
				null,                    // pyImplName
				false,                   // javaClientSide
				false,                   // javaServerSide
				null,                    // javaPackageParent
				null,                    // javaSrcPath
				serverOutDir,            // outDir
				null,                    // jsonSchemaPath
				null,                    // clientAsyncVer
				null,                    // dynServVer
				false,                   // html
				null,                    // semanticVersion
				null,                    // gitUrl
				null                     // gitCommitHash
		);
		// Generate clients (always new style)
		RunCompileCommand.generate(
				testFile,                // specFile
				null,                    // url
				true,                    // pyClientSide
				null,                    // pyClientName
				false,                   // pyServerSide
				null,                    // pyServerName
				null,                    // pyImplName
				false,                   // javaClientSide
				false,                   // javaServerSide
				null,                    // javaPackageParent
				null,                    // javaSrcPath
				serverOutDir,            // outDir
				null,                     // jsonSchemaPath
				isClientAsync ? "dev" : null,  // clientAsyncVer
				isClientDynamic ? "dev" : null,     // dynServVer
				false,                   // html
				null,                    // semanticVersion
				null,                    // gitUrl
				null                     // gitCommitHash
		);
		return serverOutDir;
	}

	protected static JavaData prepareJavaCode(int testNum, File workDir,
			String testPackage, File libDir, File binDir, Integer defaultUrlPort,
			boolean needJavaServerCorrection) throws Exception,
	IOException, MalformedURLException, FileNotFoundException {
		return prepareJavaCode(testNum, workDir, testPackage, libDir, binDir, defaultUrlPort, 
				needJavaServerCorrection, false, false, null);
	}

	protected static JavaData prepareJavaCode(
			final int testNum,
			final File workDir,
			final String testPackage,
			final File libDir,
			final File binDir,
			final Integer defaultUrlPort,
			final boolean needJavaServerCorrection,
			final boolean isDynamic,
			final boolean isAsync,
			final Map<String, String> serverManualCorrections)
			throws Exception, IOException, MalformedURLException, FileNotFoundException {
		JavaData parsingData = null;
		String testFileName = "test" + testNum + ".spec";
		extractSpecFiles(testNum, workDir, testFileName);
		File srcDir = new File(workDir, "src");
		URL defaultUrl = defaultUrlPort == null ? null :
			new URL("http://localhost:" + defaultUrlPort);
		parsingData = processSpec(workDir, testPackage, libDir, testFileName,
				srcDir, defaultUrl, isDynamic, isAsync);
		if (needJavaServerCorrection) {
			javaServerCorrection(srcDir, testPackage, parsingData, serverManualCorrections);
		}
		parsingData = processSpec(workDir, testPackage, libDir, testFileName,
				srcDir, defaultUrl, isDynamic, isAsync);
		List<URL> cpUrls = new ArrayList<URL>();
		String classPath = prepareClassPath(libDir, cpUrls);
		cpUrls.add(binDir.toURI().toURL());
		compileModulesIntoBin(workDir, srcDir, testPackage, parsingData, classPath, binDir);
		String testJavaFileName = "Test" + testNum + ".java";
		String testFilePath = "src/" + testPackage.replace('.', '/') + "/" + testJavaFileName;
		File testJavaFile = new File(workDir, testFilePath);
		String testJavaResource = testJavaFileName + ".properties";
		InputStream testClassIS = TypeGeneratorTest.class.getResourceAsStream(testJavaResource);
		if (testClassIS == null) {
			fail("Java test class resource was not found: " + testJavaResource);
		}
		TextUtils.copyStreams(testClassIS, new FileOutputStream(testJavaFile));
		runJavac(workDir, srcDir, classPath, binDir, testFilePath);
		File docDir = new File(workDir, "doc");
		docDir.mkdir();
		List<String> docPackages = new ArrayList<String>(Arrays.asList(testPackage));
		for (JavaModule module : parsingData.getModules()) {
			docPackages.add(testPackage + "." + module.getModulePackage());
		}
		runJavaDoc(workDir, srcDir, classPath, docDir, docPackages.toArray(new String[docPackages.size()]));
		return parsingData;
	}

	private static JavaData processSpec(
			final File workDir,
			final String testPackage,
			final File libDir,
			final String testFileName,
			final File srcDir,
			final URL defaultUrl,
			final boolean isDynamic,
			final boolean isAsync
			) throws Exception {
		setUpTestJars(new DiskFileSaver(libDir));
		File specFile = new File(workDir, testFileName);
		List<KbService> services = KidlParser.parseSpec(specFile, null);
		JavaData parsingData = JavaTypeGenerator.processSpec(services, new DiskFileSaver(srcDir), 
				testPackage, true, defaultUrl, 
				isAsync ? "dev": null, isDynamic ? "dev" : null, null, null, null);
		return parsingData;
	}
	
	private static void setUpTestJars(final FileSaver libOutDir) throws Exception {
		// TODO TEST CLEANUP remove this method and figure out some other way of handling test deps
		//                   maybe mark deps in gradle?
		checkLib(libOutDir, "jackson-annotations-2.2.3");
		checkLib(libOutDir, "jackson-core-2.2.3");
		checkLib(libOutDir, "jackson-databind-2.2.3");
		checkLib(libOutDir, "auth2_client_java-0.5.0");
		checkLib(libOutDir, "java_common-0.3.1");
		checkLib(libOutDir, "javax.annotation-api-1.3.2");
		checkLib(libOutDir, "servlet-api-2.5");
		checkLib(libOutDir, "jetty-all-7.0.0.v20091005");
		checkLib(libOutDir, "ini4j-0.5.2");
		checkLib(libOutDir, "syslog4j-0.9.46");
		checkLib(libOutDir, "jna-3.4.0");
		checkLib(libOutDir, "joda-time-2.2");
		checkLib(libOutDir, "logback-core-1.1.2");
		checkLib(libOutDir, "logback-classic-1.1.2");
		checkLib(libOutDir, "slf4j-api-1.7.7");
	}

	private static void checkLib(FileSaver libDir, String libName) throws Exception {
		// TODO TEST CLEANUP try to eliminate this method entirely
		final Path lib = BUILD_LIB_DIR.resolve(libName + ".jar");
		InputStream is = new FileInputStream(lib.toFile());
		OutputStream os = libDir.openStream(lib.getFileName().toString());
		TextUtils.copyStreams(is, os);
	}
	
	private static File findPythonServerScript(File dir) {
		for (File f : dir.listFiles()) {
			if (f.getName().endsWith("Server.py"))
				return f;
		}
		throw new IllegalStateException("Can not find python server script");
	}

	private static void compileModulesIntoBin(File workDir, File srcDir, String testPackage, 
			JavaData parsingData, String classPath, File binDir) throws IOException, MalformedURLException {
		if (!binDir.exists())
			binDir.mkdir();
        for (JavaModule module : parsingData.getModules()) {
        	String clientFilePath = "src/" + testPackage.replace('.', '/') + "/" + module.getModulePackage() + "/" + 
					getClientClassName(module) + ".java";
        	String serverFilePath = "src/" + testPackage.replace('.', '/') + "/" + module.getModulePackage() + "/" + 
					getServerClassName(module) + ".java";
        	runJavac(workDir, srcDir, classPath, binDir, clientFilePath, serverFilePath);
        }
	}

	private static String prepareClassPath(File libDir, List<URL> cpUrls)
			throws Exception {
		checkLib(new DiskFileSaver(libDir), "junit-4.12");
		checkLib(new DiskFileSaver(libDir), "hamcrest-core-1.3");
		StringBuilder classPathSB = new StringBuilder();
		for (File jarFile : libDir.listFiles()) {
			if (!jarFile.getName().endsWith(".jar"))
				continue;
			addLib(jarFile, libDir, classPathSB, cpUrls);
		}
		return classPathSB.toString();
	}

	private static Class<?> createServerServletInstance(JavaModule module,
			File libDir, File binDir, String testPackage) throws Exception,
			MalformedURLException, ClassNotFoundException {
		URLClassLoader urlcl = prepareUrlClassLoader(libDir, binDir);
        String serverClassName = pref(testPackage) + module.getModulePackage() + "." + getServerClassName(module);
        Class<?> serverClass = urlcl.loadClass(serverClassName);
		return serverClass;
	}

	private static URLClassLoader prepareUrlClassLoader(File libDir, File binDir)
			throws Exception, MalformedURLException {
		List<URL> cpUrls = new ArrayList<URL>();
        prepareClassPath(libDir, cpUrls);
        cpUrls.add(binDir.toURI().toURL());
        URLClassLoader urlcl = URLClassLoader.newInstance(cpUrls.toArray(new URL[cpUrls.size()]));
		return urlcl;
	}
	
	private static File prepareWorkDir(int testNum) throws Exception {
		final Path tempDir = Paths.get(
				TestConfigHelper.getTempTestDir(), TypeGeneratorTest.class.getSimpleName()
		);
		Files.createDirectories(tempDir);
		for (File dir : tempDir.toFile().listFiles()) {
			if (dir.isDirectory() && dir.getName().startsWith("test" + testNum + "_"))
				try {
					TextUtils.deleteRecursively(dir);
				} catch (Exception e) {
					System.out.println(
							"Can not delete directory [" + dir.getName() + "]: " + e.getMessage()
					);
				}
		}
		final File workDir = new File(
				tempDir.toFile(), "test" + testNum + "_" + System.currentTimeMillis()
		);
		if (!workDir.exists())
			workDir.mkdir();
		return workDir;
	}

	private static void runClientTest(
			final int testNum,
			final String testPackage,
			final JavaData parsingData, 
			final File libDir,
			final File binDir,
			final int portNum,
			final boolean needClientServer,
			final File outDir,
			final String serverType)
			throws Exception {
		System.out.println("- Java client -> " + serverType + " server");
		runJavaClientTest(testNum, testPackage, parsingData, libDir, binDir, portNum, needClientServer);
		if (outDir != null) {
			String resourceName = "Test" + testNum + ".config.properties";
			String clientConfigText = checkForClientConfig(resourceName);
			if (clientConfigText.isEmpty())
				return;
			System.out.println("- Python client -> " + serverType + " server");
			runPythonClientTest(
					testNum, parsingData, portNum, needClientServer, outDir
			);
		}
	}
	
	private static String pref(String testPackage) {
	    if (testPackage.equals("."))
	        testPackage = "";
	    return testPackage.isEmpty() ? "" : (testPackage + ".");
	}
	
    private static void runJavaClientTest(int testNum, String testPackage, JavaData parsingData, 
            File libDir, File binDir, int portNum, boolean needClientServer) throws Exception {
		//System.out.println("Port: " + portNum);
        long time = System.currentTimeMillis();
        URLClassLoader urlcl = prepareUrlClassLoader(libDir, binDir);
		ConnectException error = null;
		for (int n = 0; n < 50; n++) {
			Thread.sleep(100);
			try {
				for (JavaModule module : parsingData.getModules()) {
					Class<?> testClass = urlcl.loadClass(pref(testPackage) + "Test" + testNum);
					if (needClientServer) {
						String clientClassName = getClientClassName(module);
						Class<?> clientClass = urlcl.loadClass(pref(testPackage) + module.getModulePackage() + "." + clientClassName);
						Object client = clientClass.getConstructor(URL.class).newInstance(new URL("http://localhost:" + portNum));
						try {
							testClass.getConstructor(clientClass).newInstance(client);
						} catch (NoSuchMethodException e) {
							testClass.getConstructor(clientClass, Integer.class).newInstance(client, portNum);
						}
					} else {
						try {
							testClass.getConstructor().newInstance();
						} catch (NoSuchMethodException e) {
							testClass.getConstructor(File.class).newInstance(binDir.getParentFile());
						}
					}
				}
				error = null;
				//System.out.println("Timeout before server response: " + (n * 100) + " ms.");
				break;
			} catch (InvocationTargetException ex) {
				Throwable t = ex.getCause();
				if (t != null && t instanceof Exception) {
					if (t instanceof ConnectException) {
						error = (ConnectException)t;
					} else {
						if (t instanceof ServerException) {
							t.printStackTrace();
							throw new IllegalStateException("ServerException: " + t.getMessage() + 
									" (" + ((ServerException)t).getData() + ")");
						}
						throw (Exception)t;
					}
				} else if (t != null && t instanceof Error) {
				    throw (Error)t;
				} else {
					throw ex;
				}
			}
		}
		if (error != null)
			throw error;
		if (debugClientTimes)
		    System.out.println("  (time=" + (System.currentTimeMillis() - time) + " ms)");
	}

    private static void prepareClientTestConfigFile(JavaData parsingData,
            String resourceName, File configFile) throws IOException,
            JsonParseException, JsonMappingException, JsonGenerationException {
        InputStream configIs = TypeGeneratorTest.class.getResourceAsStream(resourceName);
        Map<String, Object> config = UObject.getMapper().readValue(configIs, 
                new TypeReference<Map<String, Object>>() {});
        configIs.close();
        //TextUtils.writeFileLines(TextUtils.readStreamLines(), configFile);
        if (!config.containsKey("package")) {
            String serviceName = getModuleName(parsingData);
            config.put("package", serviceName + "Client");
        }
        if (!config.containsKey("class")) {
            String moduleName = parsingData.getModules().get(0).getOriginal().getModuleName();
            config.put("class", moduleName);
        }
        UObject.getMapper().writeValue(configFile, config);
    }

    private static String getModuleName(JavaData parsingData) {
        return parsingData.getModules().get(0).getOriginal().getServiceName();
    }

    private static void runPythonClientTest(
            final int testNum,
            final JavaData parsingData, 
            final int portNum,
            final boolean needClientServer,
            final File outDir) throws Exception {
        if (!needClientServer)
            return;
        long time = System.currentTimeMillis();
        String resourceName = "Test" + testNum + ".config.properties";
        File shellFile = null;
        File configFile = new File(outDir, "tests.json");
        prepareClientTestConfigFile(parsingData, resourceName, configFile);
        shellFile = new File(outDir, "test_python_client.sh");
        List<String> lines = new ArrayList<String>(Arrays.asList("#!/bin/bash"));
        String pyName = "Python";
        String pyCmd = pyName.toLowerCase();
        lines.addAll(Arrays.asList(
                // TODO TESTCODE need a better way of referencing the test code, this is dumb
                pyCmd + " ../../../../test_scripts/python/test_client.py -t " + configFile.getName() + 
                " -e http://localhost:" + portNum +
                " -o \"" + System.getProperty("test.token") + "\"" +
                (System.getProperty("KB_JOB_CHECK_WAIT_TIME") == null ? "" :
                    (" -a " + System.getProperty("KB_JOB_CHECK_WAIT_TIME")))
                ));
        TextUtils.writeFileLines(lines, shellFile);
        if (shellFile != null) {
            ProcessHelper ph = ProcessHelper.cmd("bash", shellFile.getCanonicalPath()).exec(outDir, null, true, true);
            int exitCode = ph.getExitCode();
            if (exitCode != 0) {
                String out = ph.getSavedOutput();
                if (!out.isEmpty())
                    System.out.println(pyName + " client output:\n" + out);
                String err = ph.getSavedErrors();
                if (!err.isEmpty())
                    System.err.println(pyName + " client errors:\n" + err);
            }
            assertEquals(0, exitCode, pyName + " client exit code should be 0");
        }
        if (debugClientTimes)
            System.out.println("  (time=" + (System.currentTimeMillis() - time) + " ms)");
    }

    private static String checkForClientConfig(String resourceName) throws Exception {
        InputStream configIs = TypeGeneratorTest.class.getResourceAsStream(resourceName);
        if (configIs == null)
            return null;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        TextUtils.copyStreams(configIs, baos);
        configIs.close();
        return new String(baos.toByteArray());
    }

	private static void extractSpecFiles(int testNum, File workDir,
			String testFileName) {
		try {
			TextUtils.writeFileLines(TextUtils.readStreamLines(TypeGeneratorTest.class.getResourceAsStream(
			        testFileName + ".properties")), new File(workDir, testFileName));
		} catch (Exception ex) {
			String zipFileName = "test" + testNum + ".zip";
			try {
				ZipInputStream zis = new ZipInputStream(TypeGeneratorTest.class.getResourceAsStream(zipFileName + ".properties"));
				while (true) {
					ZipEntry ze = zis.getNextEntry();
					if (ze == null)
						break;
					TextUtils.writeFileLines(TextUtils.readStreamLines(zis, false), new File(workDir, ze.getName()));
				}
				zis.close();
			} catch (Exception e2) {
				throw new IllegalStateException("Can not find neither " + testFileName + " resource nor " + zipFileName + 
						" in resources having .properties suffix", ex);
			}
		}
	}

	private static void javaServerCorrectionForTestCallback(File srcDir, String packageParent, JavaData parsingData, String testClassName) throws IOException {
		for (JavaModule module : parsingData.getModules()) {
            File moduleDir = new File(srcDir.getAbsolutePath() + "/" + packageParent.replace('.', '/') + "/" + module.getModulePackage());
            File serverImpl = new File(moduleDir, getServerClassName(module) + ".java");
            List<String> serverLines = TextUtils.readFileLines(serverImpl);
            for (int pos = 0; pos < serverLines.size(); pos++) {
            	String line = serverLines.get(pos);
            	if (line.startsWith("        //BEGIN ") || line.startsWith("        //BEGIN_CONSTRUCTOR")) {
            		pos++;
            		serverLines.add(pos, "        " + testClassName + ".serverMethod(this);");
            	}
            }
            TextUtils.writeFileLines(serverLines, serverImpl);
        }
	}

	private static void javaServerCorrection(File srcDir, String packageParent, JavaData parsingData, 
	        Map<String, String> serverManualCorrections) throws IOException {
		for (JavaModule module : parsingData.getModules()) {
            Map<String, JavaFunc> origNameToFunc = new HashMap<String, JavaFunc>();
            for (JavaFunc func : module.getFuncs()) {
            	origNameToFunc.put(func.getOriginal().getName(), func);
            }
            File moduleDir = new File(srcDir.getAbsolutePath() + "/" + packageParent.replace('.', '/') + "/" + module.getModulePackage());
            File serverImpl = new File(moduleDir, getServerClassName(module) + ".java");
            List<String> serverLines = TextUtils.readFileLines(serverImpl);
            for (int pos = 0; pos < serverLines.size(); pos++) {
            	String line = serverLines.get(pos);
            	if (line.startsWith("        //BEGIN ")) {
            		String origFuncName = line.substring(line.lastIndexOf(' ') + 1);
            		if (serverManualCorrections != null && serverManualCorrections.containsKey(origFuncName)) {
                        pos++;
                        serverLines.add(pos, "        " + serverManualCorrections.get(origFuncName) + ";");            		    
            		} else if (origNameToFunc.containsKey(origFuncName)) {
            			JavaFunc func = origNameToFunc.get(origFuncName);
            			if (origFuncName.equals("throw_error_on_server_side")) {
                            String message = func.getParams().size() > 0 ? ("\"\" + " + func.getParams().get(0).getJavaName()) : "";
                            pos++;
                            serverLines.add(pos, "        if (true) throw new Exception(" + message + ");");                     
            			} else {
            			    int paramCount = func.getParams().size();
            			    for (int paramPos = 0; paramPos < paramCount; paramPos++) {
            			        pos++;
            			        serverLines.add(pos, "        return" + (paramCount > 1 ? ("" + (paramPos + 1)) : "Val") + " = " + 
            			                func.getParams().get(paramPos).getJavaName() + ";");
            			    }
            			}
            		}
            	}
            }
            TextUtils.writeFileLines(serverLines, serverImpl);
        }
	}

	private static void pythonServerCorrection(File serverOutDir, JavaData parsingData
			) throws IOException {
		for (JavaModule module : parsingData.getModules()) {
            Map<String, JavaFunc> origNameToFunc = new HashMap<String, JavaFunc>();
            for (JavaFunc func : module.getFuncs()) {
            	origNameToFunc.put(func.getOriginal().getName(), func);
            }
            File pyServerImpl = new File(serverOutDir, module.getOriginal().getModuleName() + "Impl.py");
            List<String> pyServerLines = TextUtils.readFileLines(pyServerImpl);
            for (int pos = 0; pos < pyServerLines.size(); pos++) {
            	String line = pyServerLines.get(pos);
            	if (line.startsWith("        #BEGIN ")) {
            		String origFuncName = line.substring(line.lastIndexOf(' ') + 1);
                    if (origNameToFunc.containsKey(origFuncName)) {
            			KbFuncdef origFunc = origNameToFunc.get(origFuncName).getOriginal();
            			int paramCount = origFunc.getParameters().size();
                        if (origFuncName.equals("throw_error_on_server_side")) {
                            pos++;
                            pyServerLines.add(pos, "        raise Exception(" + (paramCount > 0 ? 
                                    getParamPyPlName(origFunc,0) : "''") + ")");
                        } else {
                            for (int paramPos = 0; paramPos < paramCount; paramPos++) {
                                pos++;
                                pyServerLines.add(pos, "        return" + (paramCount > 1 ? ("_" + (paramPos + 1)) : "Val") + " = " + 
                                        getParamPyPlName(origFunc,paramPos));
                            }
                            if (paramCount == 0) {
                                pos++;
                                pyServerLines.add(pos, "        pass");
                            }
                        }
            		}
            	}
            }
            TextUtils.writeFileLines(pyServerLines, pyServerImpl);
        }
	}
	
	private static String getParamPyPlName(KbFuncdef func, int pos) {
	    String ret = func.getParameters().get(pos).getOriginalName();
	    if (ret == null)
	        ret = "arg_" + (pos + 1);
	    return ret;
	}

	private static void runJavac(File workDir, File srcDir, String classPath, File binDir, 
			String... sourceFilePaths) throws IOException {
		ProcessHelper.cmd("javac", "-g:source,lines", "-d", binDir.getName(), "-sourcepath", srcDir.getName(), "-cp", 
				classPath, "-Xlint:deprecation").add(sourceFilePaths).exec(workDir);
	}

	private static void runJavaDoc(File workDir, File srcDir, String classPath, File docDir, String... packages) throws IOException {
		ProcessHelper.cmd("javadoc", "-d", docDir.getName(), "-sourcepath", srcDir.getName(), "-classpath", 
				classPath).add(packages).exec(workDir, (File)null, null);
	}

	private static String getClientClassName(JavaModule module) {
		return TextUtils.capitalize(module.getModuleName()) + "Client";
	}

	private static String getServerClassName(JavaModule module) {
		return TextUtils.capitalize(module.getModuleName()) + "Server";
	}

    private static void addLib(File libFile, File libDir, StringBuilder classPath, List<URL> libUrls) throws Exception {
        if (classPath.length() > 0)
            classPath.append(':');
        classPath.append("lib/").append(libFile.getName());
        libUrls.add(libFile.toURI().toURL());
    }
	
    // TODO TEST CLEANUP The service wizard in used in at least two places, move to test utils
    public static class ServiceWizardMock extends JsonServerServlet {
        private static final long serialVersionUID = 1L;
        
        private final int[] serverPortHolder;

        public ServiceWizardMock(int[] serverPortHolder) {
            super(SERVICE_WIZARD);
            this.serverPortHolder = serverPortHolder;
        }

        @JsonServerMethod(rpc = SERVICE_WIZARD + ".get_service_status")
        public Map<String, Object> getServiceStatus(Map<String, String> params) throws IOException, JsonClientException {
            Map<String, Object> ret = new LinkedHashMap<String, Object>();
            ret.put("url", "http://localhost:" + serverPortHolder[0]);
            return ret;
        }
    }
}
