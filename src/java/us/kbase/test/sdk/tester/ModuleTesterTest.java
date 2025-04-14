package us.kbase.test.sdk.tester;

import static org.junit.Assert.assertThat;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedList;
import java.util.List;

import org.junit.Assert;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import us.kbase.auth.AuthToken;
import us.kbase.mobu.ModuleBuilder;
import us.kbase.mobu.initializer.ModuleInitializer;
import us.kbase.mobu.tester.ModuleTester;
import us.kbase.test.sdk.TestUtils;
import us.kbase.test.sdk.scripts.TestConfigHelper;

public class ModuleTesterTest {
	
	// TODO TEST fix tests leaving images and containers lying around

	private static final String SIMPLE_MODULE_NAME = "ASimpleModule_for_unit_testing";
	private static final boolean DELETE_TEST_MODULES = true;

	private static final List<Path> CREATED_MODULES = new LinkedList<>();
	private static AuthToken token;

	@BeforeClass
	public static void beforeClass() throws Exception {
		token = TestConfigHelper.getToken();
	}

	@AfterClass
	public static void tearDownModule() throws Exception {
		for (final Path mod: CREATED_MODULES) {
			TestUtils.deleteTestModule(mod, true, DELETE_TEST_MODULES);
		}
	}

	@After
	public void afterTest() {
		System.out.println();
	}

	private Path init(final String lang, final String moduleName) throws Exception {
		final Path workDir = Paths.get(TestConfigHelper.getTempTestDir(), moduleName);
		TestUtils.deleteTestModule(workDir, true, true);
		CREATED_MODULES.add(workDir);
		TestUtils.createSdkCfgFile(Paths.get(TestConfigHelper.getTempTestDir()), moduleName);
		new ModuleInitializer(
				moduleName,
				token.getUserName(),
				lang,
				false,
				new File(TestConfigHelper.getTempTestDir()),
				true
		).initialize(true);
		return workDir;
	}

	private int runTestsInDocker(final File moduleDir) throws Exception {
		return runTestsInDocker(moduleDir, token);
	}

	public static int runTestsInDocker(final File moduleDir, AuthToken token) throws Exception {
		return runTestsInDocker(moduleDir, token, false);
	}

	public static int runTestsInDocker(
			final File moduleDir,
			final AuthToken token, 
			final boolean skipValidation)
					throws Exception {
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
		final Path moduleDir = init(lang, moduleName);
		// TODO TESTHACK remove this when there's a base image that deploys the authclient correctly
		FileUtils.copyFile(
				new File("./src/java/us/kbase/templates/authclient.py"),
				moduleDir.resolve(Paths.get("lib", moduleName, "/authclient.py")).toFile()
				);
		// TODO TESTHACK PYTEST upgrade to pytest and remove this stuff assuming that works
		// nose is identifying the class as a test case
		final Path implFile = moduleDir.resolve(
				Paths.get("lib", moduleName, moduleName + "Impl.py")
		);
		final String implText = FileUtils.readFileToString(implFile.toFile());
		final String newText = implText.replace("    #BEGIN_CLASS_HEADER", 
				"    #BEGIN_CLASS_HEADER\n" +
				"    __test__ = False\n"
		);
		assertThat(implText, is(not(newText)));
		FileUtils.writeStringToFile(implFile.toFile(), newText);
		
		int exitCode = runTestsInDocker(moduleDir.toFile());
		Assert.assertEquals(0, exitCode);
	}

	@Test
	public void testPythonModuleError() throws Exception {
		System.out.println("Test [testPythonModuleError]");
		String lang = "python";
		String moduleName = SIMPLE_MODULE_NAME + "PythonError";
		final Path moduleDir = init(lang, moduleName);
		final Path implFile = moduleDir.resolve(
				Paths.get("lib", moduleName, moduleName + "Impl.py")
		);
		final String method = "run_ASimpleModule_for_unit_testingPythonError";
		final String implText = FileUtils.readFileToString(implFile.toFile());
		final String newText = implText.replace("    #BEGIN " + method, 
				"        #BEGIN " + method + "\n" +
				"        raise ValueError('Special error')"
		);
		assertThat(implText, is(not(newText)));
		FileUtils.writeStringToFile(implFile.toFile(), newText);
		int exitCode = runTestsInDocker(moduleDir.toFile());
		Assert.assertEquals(2, exitCode);
	}

	@Test
	public void testJavaModuleExample() throws Exception {
		System.out.println("Test [testJavaModuleExample]");
		String lang = "java";
		String moduleName = SIMPLE_MODULE_NAME + "Java";
		final Path moduleDir = init(lang, moduleName);
		int exitCode = runTestsInDocker(moduleDir.toFile());
		Assert.assertEquals(0, exitCode);
	}

	@Test
	public void testJavaModuleError() throws Exception {
		System.out.println("Test [testJavaModuleError]");
		String lang = "java";
		String moduleName = SIMPLE_MODULE_NAME + "JavaError";
		final Path moduleDir = init(lang, moduleName);
		final Path implFile = moduleDir.resolve(Paths.get(
				"lib/src/asimplemoduleforunittestingjavaerror/"
				+ "ASimpleModuleForUnitTestingJavaErrorServer.java"
		));
		final String method = "run_ASimpleModule_for_unit_testingJavaError";
		final String implText = FileUtils.readFileToString(implFile.toFile());
		final String newText = implText.replace("        //BEGIN " + method, 
				"        //BEGIN " + method + "\n" +
				"        if (true) throw new IllegalStateException(\"Special error\");"
		);
		assertThat(implText, is(not(newText)));
		FileUtils.writeStringToFile(implFile.toFile(), newText);
		int exitCode = runTestsInDocker(moduleDir.toFile());
		Assert.assertEquals(2, exitCode);
	}

	@Test
	public void testSelfCalls() throws Exception {
		System.out.println("Test [testSelfCalls]");
		String lang = "python";
		String moduleName = SIMPLE_MODULE_NAME + "Self";
		final Path workDir = Paths.get(TestConfigHelper.getTempTestDir(), moduleName);
		TestUtils.deleteTestModule(workDir, true, true);
		CREATED_MODULES.add(workDir);
		String implInit = "" +
				"#BEGIN_HEADER\n" +
				"import os\n"+
				"from " + moduleName + "." + moduleName + "Client import " + moduleName + " as local_client\n" +
				"#END_HEADER\n" +
				"\n" +
				"    #BEGIN_CLASS_HEADER\n" +
				// TODO TESTHACK PYTEST upgrade to pytest and remove this line assuming that works
				"    __test__ = False\n" + // nose is identifying the class as a test case
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
		File moduleDir = workDir.toFile();
		File implFile = new File(moduleDir, "lib/" + moduleName + "/" + moduleName + "Impl.py");
		TestUtils.createSdkCfgFile(Paths.get(TestConfigHelper.getTempTestDir()), moduleName);
		new ModuleInitializer(
				moduleName,
				token.getUserName(),
				lang,
				false,
				new File(TestConfigHelper.getTempTestDir()),
				true
		).initialize(false);
		File specFile = new File(moduleDir, moduleName + ".spec");
		String specText = FileUtils.readFileToString(specFile).replace("};", 
				"funcdef run_local(int input) returns (int) authentication required;\n" +
				"funcdef calc_square(int input) returns (int) authentication required;\n" +
				"};");
		File testFile = new File(moduleDir, "test/" + moduleName + "_server_test.py");
		final String testCode = FileUtils.readFileToString(testFile);
		final int index = testCode.indexOf("    def test_your_method(self)");
		final String newCode = testCode.substring(0, index)
				+ "    def test_your_method(self):\n"
				+ "        self.assertEqual(25, self.serviceImpl.run_local(self.ctx, 5)[0])\n";
		assertThat(testCode, is(not(newCode)));
		FileUtils.writeStringToFile(specFile, specText);
		FileUtils.writeStringToFile(implFile, implInit);
		FileUtils.writeStringToFile(testFile, newCode);
		int exitCode = runTestsInDocker(moduleDir, token, true);
		Assert.assertEquals(0, exitCode);
	}
}