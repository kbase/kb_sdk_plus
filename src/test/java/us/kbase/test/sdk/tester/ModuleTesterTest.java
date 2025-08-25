package us.kbase.test.sdk.tester;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedList;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.kbase.auth.AuthToken;
import us.kbase.sdk.initializer.ModuleInitializer;
import us.kbase.sdk.tester.ModuleTester;
import us.kbase.test.sdk.TestUtils;
import us.kbase.test.sdk.scripts.TestConfigHelper;

public class ModuleTesterTest {
	
	// TODO TEST fix tests leaving images and containers lying around

	private static final String SIMPLE_MODULE_NAME = "ASimpleModule_for_unit_testing";
	private static final boolean DELETE_TEST_MODULES = true;

	private static final List<Path> CREATED_MODULES = new LinkedList<>();
	private static AuthToken token;

	@BeforeAll
	public static void beforeClass() throws Exception {
		token = TestConfigHelper.getToken();
	}

	@AfterAll
	public static void tearDownModule() throws Exception {
		for (final Path mod: CREATED_MODULES) {
			TestUtils.deleteTestModule(mod, DELETE_TEST_MODULES);
		}
	}

	@AfterEach
	public void afterTest() {
		System.out.println();
	}

	private Path init(final String lang, final String moduleName) throws Exception {
		final Path workDir = Paths.get(TestConfigHelper.getTempTestDir(), moduleName);
		TestUtils.deleteTestModule(workDir, true);
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
				"auth_service_url=" + TestConfigHelper.getAuthServiceUrlLegacy() + "\n" +
				"auth_service_url_allow_insecure=" + 
				TestConfigHelper.getAuthServiceUrlInsecure() + "\n";
		FileUtils.writeStringToFile(testCfgFile, testCfgText);
		int exitCode = new ModuleTester(moduleDir).runTests(
				skipValidation, TestConfigHelper.getMakeRootOwnedFilesWriteable()
		);
		System.out.println("Exit code: " + exitCode);
		return exitCode;
	}

	@Test
	public void testPythonModuleExample() throws Exception {
		System.out.println("Test [testPythonModuleExample]");
		String lang = "python";
		String moduleName = SIMPLE_MODULE_NAME + "Python";
		final Path moduleDir = init(lang, moduleName);
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
		assertEquals(0, exitCode);
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
		assertEquals(2, exitCode);
	}

	@Test
	public void testJavaModuleExample() throws Exception {
		System.out.println("Test [testJavaModuleExample]");
		String lang = "java";
		String moduleName = SIMPLE_MODULE_NAME + "Java";
		final Path moduleDir = init(lang, moduleName);
		int exitCode = runTestsInDocker(moduleDir.toFile());
		assertEquals(0, exitCode);
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
		assertEquals(2, exitCode);
	}

}