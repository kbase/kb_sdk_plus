package us.kbase.mobu.tester.test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import junit.framework.Assert;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import us.kbase.auth.AuthToken;
import us.kbase.mobu.ModuleBuilder;
import us.kbase.mobu.initializer.ModuleInitializer;
import us.kbase.mobu.tester.ModuleTester;
import us.kbase.scripts.test.TestConfigHelper;

public class ModuleTesterTest {
	
	// TODO TEST move the modules into a single test directory for easy deletion
	// TODO TEST fix tests leaving root owned files on disk

	private static final String SIMPLE_MODULE_NAME = "ASimpleModule_for_unit_testing";
	private static final boolean CLEANUP_AFTER_TESTS = true;
	
	private static final List<String> CREATED_MODULE_NAMES = new ArrayList<String>();
	private static AuthToken token;
	
    @BeforeClass
    public static void beforeClass() throws Exception {
        token = TestConfigHelper.getToken();
    }

	@AfterClass
	public static void tearDownModule() throws Exception {
		if (CLEANUP_AFTER_TESTS)
			for (String moduleName : CREATED_MODULE_NAMES)
				try {
					System.out.println("Deleting " + moduleName);
					deleteDir(moduleName);
				} catch (Exception ex) {
					System.err.println("Error cleaning up module [" + 
							moduleName + "]: " + ex.getMessage());
				}
	}
	
	@After
	public void afterTest() {
	    System.out.println();
	}
	
	private static void deleteDir(String moduleName) throws Exception {
		File module = new File(moduleName);
		if (module.exists() && module.isDirectory())
			FileUtils.deleteDirectory(module);
	}
	
    private void init(String lang, String moduleName) throws Exception {
        deleteDir(moduleName);
        CREATED_MODULE_NAMES.add(moduleName);
        ModuleInitializer initer = new ModuleInitializer(moduleName, token.getUserName(), 
                lang, false);
        initer.initialize(true);
    }

	private int runTestsInDocker(String moduleName) throws Exception {
		File moduleDir = new File(moduleName);
		return runTestsInDocker(moduleDir, token);
	}

	public static int runTestsInDocker(File moduleDir, AuthToken token) throws Exception {
		return runTestsInDocker(moduleDir, token, false);
	}

	public static int runTestsInDocker(
			final File moduleDir,
			final AuthToken token, 
			final boolean skipValidation)
					throws Exception {
		// TODO TESTCLEANUP remove the line below once the other test modules that call this method
		//                  are passing. 
		//DockerClientServerTester.correctDockerfile(moduleDir);
		File testCfgFile = new File(moduleDir, "test_local/test.cfg");
		String testCfgText = ""+
				"test_token=" + token.getToken() + "\n" +
				"kbase_endpoint=" + TestConfigHelper.getKBaseEndpoint() + "\n" +
				"auth_service_url=" + TestConfigHelper.getAuthServiceUrl() + "\n" +
				"auth_service_url_allow_insecure=" + 
				TestConfigHelper.getAuthServiceUrlInsecure() + "\n";
		FileUtils.writeStringToFile(testCfgFile, testCfgText);
		int exitCode = new ModuleTester(moduleDir).runTests(ModuleBuilder.DEFAULT_METHOD_STORE_URL,
				skipValidation, false);
		System.out.println("Exit code: " + exitCode);
		return exitCode;
	}

	@Test
	public void testPythonModuleExample() throws Exception {
		System.out.println("Test [testPythonModuleExample]");
		String lang = "python";
		String moduleName = SIMPLE_MODULE_NAME + "Python";
		init(lang, moduleName);
		// TODO TESTHACK remove this when there's a base image that deploys the authclient correctly
		FileUtils.copyFile(
				new File("./src/java/us/kbase/templates/authclient.py"),
				new File(moduleName + "/lib/" + moduleName + "/authclient.py")
				);
		int exitCode = runTestsInDocker(moduleName);
		Assert.assertEquals(0, exitCode);
	}

	@Test
	public void testPythonModuleError() throws Exception {
        System.out.println("Test [testPythonModuleError]");
		String lang = "python";
        String moduleName = SIMPLE_MODULE_NAME + "PythonError";
        init(lang, moduleName);
        File implFile = new File(moduleName + "/lib/" + moduleName + "/" + moduleName + "Impl.py");
        String implText = FileUtils.readFileToString(implFile);
        implText = implText.replace("    #BEGIN filter_contigs", 
                "        #BEGIN filter_contigs\n" +
                "        raise ValueError('Special error')");
        FileUtils.writeStringToFile(implFile, implText);
        int exitCode = runTestsInDocker(moduleName);
        Assert.assertEquals(2, exitCode);
	}

	@Test
	public void testJavaModuleExample() throws Exception {
        System.out.println("Test [testJavaModuleExample]");
		String lang = "java";
        String moduleName = SIMPLE_MODULE_NAME + "Java";
        init(lang, moduleName);
        int exitCode = runTestsInDocker(moduleName);
        Assert.assertEquals(0, exitCode);
	}

	@Test
	public void testJavaModuleError() throws Exception {
	    System.out.println("Test [testJavaModuleError]");
	    String lang = "java";
	    String moduleName = SIMPLE_MODULE_NAME + "JavaError";
	    init(lang, moduleName);
        File implFile = new File(moduleName + "/lib/src/" +
        		"asimplemoduleforunittestingjavaerror/ASimpleModuleForUnitTestingJavaErrorServer.java");
        String implText = FileUtils.readFileToString(implFile);
        implText = implText.replace("        //BEGIN filter_contigs", 
                "        //BEGIN filter_contigs\n" +
                "        if (true) throw new IllegalStateException(\"Special error\");");
        FileUtils.writeStringToFile(implFile, implText);
	    int exitCode = runTestsInDocker(moduleName);
	    Assert.assertEquals(2, exitCode);
	}

    @Test
    public void testSelfCalls() throws Exception {
        System.out.println("Test [testSelfCalls]");
        String lang = "python";
        String moduleName = SIMPLE_MODULE_NAME + "Self";
        deleteDir(moduleName);
        CREATED_MODULE_NAMES.add(moduleName);
        String implInit = "" +
                "#BEGIN_HEADER\n" +
                "import os\n"+
                "from " + moduleName + "." + moduleName + "Client import " + moduleName + " as local_client\n" +
                "#END_HEADER\n" +
                "\n" +
                "    #BEGIN_CLASS_HEADER\n" +
                "    #END_CLASS_HEADER\n" +
                "\n" +
                "        #BEGIN_CONSTRUCTOR\n" +
                "        #END_CONSTRUCTOR\n" +
                "\n" +
                "        #BEGIN run_local\n" +
                "        returnVal = local_client(os.environ['SDK_CALLBACK_URL']).calc_square(input)\n" +
                "        #END run_local\n" +
                "\n" +
                "        #BEGIN calc_square\n" +
                "        returnVal = input * input\n" +
                "        #END calc_square\n";
        File moduleDir = new File(moduleName);
        File implFile = new File(moduleDir, "lib/" + moduleName + "/" + 
                moduleName + "Impl.py");
        ModuleInitializer initer = new ModuleInitializer(moduleName, token.getUserName(), lang, false);
        initer.initialize(false);
        File specFile = new File(moduleDir, moduleName + ".spec");
        String specText = FileUtils.readFileToString(specFile).replace("};", 
                "funcdef run_local(int input) returns (int) authentication required;\n" +
                "funcdef calc_square(int input) returns (int) authentication required;\n" +
                "};");
        File testFile = new File(moduleDir, "test/" + moduleName + "_server_test.py");
        String testCode = FileUtils.readFileToString(testFile).replace("    def test_your_method(self):", 
                "    def test_your_method(self):\n" +
                "        self.assertEqual(25, self.getImpl().run_local(self.getContext(), 5)[0])"
        );
        FileUtils.writeStringToFile(specFile, specText);
        FileUtils.writeStringToFile(implFile, implInit);
        FileUtils.writeStringToFile(testFile, testCode);
        int exitCode = runTestsInDocker(moduleDir, token, true);
        Assert.assertEquals(0, exitCode);
   }
}