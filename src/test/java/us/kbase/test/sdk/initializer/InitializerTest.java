package us.kbase.test.sdk.initializer;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.kbase.sdk.initializer.ModuleInitializer;
import us.kbase.test.sdk.TestUtils;
import us.kbase.test.sdk.scripts.TestConfigHelper;

public class InitializerTest {
	private static File tempDir = null;

	private static final String SIMPLE_MODULE_NAME = "a_simple_module_for_unit_testing";
	private static final String EXAMPLE_METHOD_NAME = "run_" + SIMPLE_MODULE_NAME;
	private static final String USER_NAME = "kbasedev";
	private static final String[] EXPECTED_PATHS = {
			"data",
			"scripts",
			"test", 
			"ui", 
			"lib",
			"ui/narrative",
			"ui/narrative/methods/",
			"lib/README.md",
			"test/README.md",
			"data/README.md",
			"scripts/entrypoint.sh",
			"LICENSE",
			"README.md",
			".travis.yml",
			"Dockerfile",
			"Makefile"
	};
	private static final String[] EXPECTED_DEFAULT_PATHS = {
	};
	private static List<String> allExpectedDefaultPaths;
	private static List<String> allExpectedExamplePaths;
	private static List<String> pythonPaths;
	private static List<String> javaPaths;

	@AfterEach
	public void tearDownModule() throws IOException {
		File module = Paths.get(tempDir.getAbsolutePath(), SIMPLE_MODULE_NAME).toFile();
		if (module.exists() && module.isDirectory()) {
			FileUtils.deleteDirectory(module);
		}
	}

	@AfterAll
	public static void cleanupClass() throws Exception {
		FileUtils.deleteQuietly(tempDir);
	}

	public boolean checkPaths(List<String> pathList, String moduleName) {
		for (String p : pathList) {
			File f = Paths.get(tempDir.getAbsolutePath(), moduleName, p).toFile();
			System.out.println("testing " + p);
			if (!f.exists()) {
				System.out.println("Unable to find path: " + p);
				return false;
			}
		}
		return true;
	}

	/**
	 * Checks that all directories and files are present as expected.
	 * @param moduleName
	 * @return
	 */
	public boolean examineModule(String moduleName, boolean useExample, String language) {
		List<String> expectedPaths = allExpectedDefaultPaths;
		if (useExample)
			expectedPaths = allExpectedExamplePaths;

		if (!checkPaths(expectedPaths, moduleName))
			return false;

		if (useExample) {
			List<String> langPaths;
			switch(language) {
				case "python":
					langPaths = pythonPaths;
					break;
				case "java":
					langPaths = javaPaths;
					break;
				default:
					langPaths = pythonPaths;
					break;
			}
			if (!checkPaths(langPaths, moduleName))
				return false;
		}
		return true;
	}

	@BeforeAll
	public static void prepPathsToCheck() throws Exception {
		allExpectedDefaultPaths = new ArrayList<String>(Arrays.asList(EXPECTED_PATHS));
		allExpectedDefaultPaths.addAll(Arrays.asList(EXPECTED_DEFAULT_PATHS));
		allExpectedDefaultPaths.add(SIMPLE_MODULE_NAME + ".spec");

		allExpectedExamplePaths = new ArrayList<String>(Arrays.asList(EXPECTED_PATHS));
		allExpectedExamplePaths.add(SIMPLE_MODULE_NAME + ".spec");
		allExpectedExamplePaths.add("scripts/entrypoint.sh");
		allExpectedExamplePaths.add("scripts/run_async.sh");

		pythonPaths = new ArrayList<String>();
		pythonPaths.add("lib/" + SIMPLE_MODULE_NAME + "/" + SIMPLE_MODULE_NAME + "Impl.py");
		pythonPaths.add("ui/narrative/methods/" + EXAMPLE_METHOD_NAME);
		pythonPaths.add("ui/narrative/methods/" + EXAMPLE_METHOD_NAME + "/img");
		pythonPaths.add("ui/narrative/methods/" + EXAMPLE_METHOD_NAME + "/spec.json");
		pythonPaths.add("ui/narrative/methods/" + EXAMPLE_METHOD_NAME + "/display.yaml");

		javaPaths = new ArrayList<String>();
		javaPaths.add("lib/src/asimplemoduleforunittesting/ASimpleModuleForUnitTestingServer.java");
		javaPaths.add("ui/narrative/methods/" + EXAMPLE_METHOD_NAME);
		javaPaths.add("ui/narrative/methods/" + EXAMPLE_METHOD_NAME + "/img");
		javaPaths.add("ui/narrative/methods/" + EXAMPLE_METHOD_NAME + "/spec.json");
		javaPaths.add("ui/narrative/methods/" + EXAMPLE_METHOD_NAME + "/display.yaml");

		final Path rootTemp = Paths.get(TestConfigHelper.getTempTestDir());
		Files.createDirectories(rootTemp);
		tempDir = Files.createTempDirectory(rootTemp, "init_test_").toFile();
	}

	@Test
	public void testSimpleModule() throws Exception {
		boolean useExample = false;
		TestUtils.createSdkCfgFile(tempDir.toPath(), SIMPLE_MODULE_NAME);
		ModuleInitializer initer = new ModuleInitializer(SIMPLE_MODULE_NAME, USER_NAME, null, 
				false, tempDir, true);
		initer.initialize(useExample);
		assertTrue(examineModule(SIMPLE_MODULE_NAME, useExample, 
				ModuleInitializer.DEFAULT_LANGUAGE));
	}

	@Test
	public void testModuleWithUser() throws Exception {
		boolean useExample = false;
		String language = "python";
		TestUtils.createSdkCfgFile(tempDir.toPath(), SIMPLE_MODULE_NAME);
		ModuleInitializer initer = new ModuleInitializer(SIMPLE_MODULE_NAME, USER_NAME, language, 
				false, tempDir, true);
		initer.initialize(false);
		assertTrue(examineModule(SIMPLE_MODULE_NAME, useExample, language));
	}

	@Test
	public void testModuleAlreadyExists() throws Exception {
		File f = Paths.get(tempDir.getAbsolutePath(), SIMPLE_MODULE_NAME).toFile();
		if (!f.exists())
			f.mkdir();
		// should throw error if dir exists, so no dirExists boolean arg
		ModuleInitializer initer = new ModuleInitializer(SIMPLE_MODULE_NAME, USER_NAME, null, 
				false, tempDir);
		assertThrows(IOException.class, () -> initer.initialize(false));
	}

	@Test
	public void testNoNameModule() throws Exception {
		// not modulename = fails immediately
		ModuleInitializer initer = new ModuleInitializer(null, null, null, false, tempDir);
		assertThrows(Exception.class, () -> initer.initialize(false));
	}

	@Test
	public void testPythonModuleExample() throws Exception {
		boolean useExample = true;
		String lang = "python";
		TestUtils.createSdkCfgFile(tempDir.toPath(), SIMPLE_MODULE_NAME);
		ModuleInitializer initer = new ModuleInitializer(SIMPLE_MODULE_NAME, USER_NAME, lang, 
				false, tempDir, true);
		initer.initialize(useExample);
		assertTrue(examineModule(SIMPLE_MODULE_NAME, useExample, lang));
	}

	@Test
	public void testJavaModuleExample() throws Exception {
		boolean useExample = true;
		String lang = "java";
		TestUtils.createSdkCfgFile(tempDir.toPath(), SIMPLE_MODULE_NAME);
		ModuleInitializer initer = new ModuleInitializer(SIMPLE_MODULE_NAME, USER_NAME, lang, 
				false, tempDir, true);
		initer.initialize(useExample);
		assertTrue(examineModule(SIMPLE_MODULE_NAME, useExample, lang));
	}

}