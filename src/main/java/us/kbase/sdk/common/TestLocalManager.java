package us.kbase.sdk.common;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import us.kbase.sdk.templates.TemplateFormatter;

/** Manages creation and restoration of the test_local folder in a SDK repo. */
public class TestLocalManager {
	
	private static final Path TEST_LOCAL = Paths.get("test_local");
	private static final String README = "readme.txt";
	private static final String TEST_CFG = "test.cfg";
	private static final String RUN_TESTS_SH = "run_tests.sh";
	private static final String RUN_BASH_SH = "run_bash.sh";
	private static final String RUN_DOCKER_SH = "run_docker.sh";
	
	/** Get the path of the test local directory relative to the module root.
	 * @return the path.
	 */
	public static Path getTestLocalRelative() {
		return TEST_LOCAL;
	}
	
	/** Get the path of the test local readme file relative to the module root.
	 * @return the path.
	 */
	public static Path getReadmeRelative() {
		return TEST_LOCAL.resolve(README);
	}
	
	/** Get the path of the test local run tests shell file relative to the module root.
	 * @return the path.
	 */
	public static Path getRunTestsRelative() {
		return TEST_LOCAL.resolve(RUN_TESTS_SH);
	}
	
	/** Get the path of the test local run bash shell file relative to the module root.
	 * @return the path.
	 */
	public static Path getRunBashRelative() {
		return TEST_LOCAL.resolve(RUN_BASH_SH);
	}
	
	/** Get the path of the test local run docker shell file relative to the module root.
	 * @return the path.
	 */
	public static Path getRunDockerRelative() {
		return TEST_LOCAL.resolve(RUN_DOCKER_SH);
	}
	
	/** Get the path of the test config file relative to the module root.
	 * @return the path.
	 */
	public static Path getTesCfgRelative() {
		return TEST_LOCAL.resolve(TEST_CFG);
	}
	
	/** The results of ensuring the test local directory. */
	public static class TestLocalInfo {
		private final Path testLocalDir;
		private final boolean createdTestCfgFile;
		
		private TestLocalInfo(final Path testLocalDir, final boolean createdTestCfgFile) {
			this.testLocalDir = testLocalDir;
			this.createdTestCfgFile = createdTestCfgFile;
		}

		/** Get the path to the test local directory.
		 * @return the path.
		 */
		public Path getTestLocalDir() {
			return testLocalDir;
		}

		/** Get the path to the test local readme file.
		 * @return the path.
		 */
		public Path getReadmeFile() {
			return testLocalDir.resolve(README);
		}

		/** Get the path to the test local run tests shell file..
		 * @return the path.
		 */
		public Path getRunTestsShFile() {
			return testLocalDir.resolve(RUN_TESTS_SH);
		}

		/** Get the path to the test local run bash shell file.
		 * @return the path.
		 */
		public Path getRunBashShFile() {
			return testLocalDir.resolve(RUN_BASH_SH);
		}

		/** Get the path to the test local run docker shell file.
		 * @return the path.
		 */
		public Path getRunDockerShFile() {
			return testLocalDir.resolve(RUN_DOCKER_SH);
		}

		/** Get the path to the test local test config file.
		 * @return the path.
		 */
		public Path getTestCfgFile() {
			return testLocalDir.resolve(TEST_CFG);
		}

		/** Get whether the test config file was created as part of ensuring the test local
		 * directory.
		 * @return true if the test configuration file needed to be created. This means the user
		 * will need to edit the file to add their credentials. 
		 */
		public boolean isCreatedTestCfgFile() {
			return createdTestCfgFile;
		}

	}
	
	/** Create and / or restore the test local directory for an SDK app.
	 * 
	 * Will only overwrite files if they don't already exist.
	 * 
	 * @param moduleDir the SDK module root directory.
	 * @param moduleName the name of the module.
	 * @param dataVersion the version of the module's reference data, if applicable.
	 * If provided, the "refdata" subfolder will be created if it doesn't exist. If is doesn't
	 * exist, the test runner shell script will be regenerated to ensure it handles reference
	 * data correctly.
	 * @return information about the test local directory.
	 * @throws IOException if a file or directory cannot be created.
	 */
	public static TestLocalInfo ensureTestLocal(
			final Path moduleDir,
			final String moduleName,
			final Optional<String> dataVersion)
			throws IOException {
		final Path tlDir = requireNonNull(moduleDir, "moduleDir").resolve(TEST_LOCAL)
				.toAbsolutePath();
		final Path readmeFile = tlDir.resolve(README);
		final Path testCfg = tlDir.resolve(TEST_CFG);
		final Path runTestsSh = tlDir.resolve(RUN_TESTS_SH);
		final Path runBashSh = tlDir.resolve(RUN_BASH_SH);
		final Path runDockerSh = tlDir.resolve(RUN_DOCKER_SH);
		final Map<String, Object> moduleContext = new HashMap<>();
		// TODO CODE add a string checker that throws on whitespace only strings
		moduleContext.put("module_name", requireNonNull(moduleName, "moduleName"));
		Files.createDirectories(tlDir);
		if (requireNonNull(dataVersion, "dataVersion").isPresent()) {
			// TODO CODE add a string checker that throws on whitespace only strings
			moduleContext.put("data_version", dataVersion.get());
			final Path refDataDir = tlDir.resolve("refdata");
			if (!Files.exists(refDataDir)) {
				// We'll assume here that some bunghole hasn't created a file in the dir's place
				// The missing refdata dir is treated as a signal that the user has added a
				// refdata version to their kbase.yml file. That means the test runner
				// needs to be regenerated to handle refdata.
				// This all seems very clunky and could use a rethink
				TemplateFormatter.formatTemplate(
						"module_run_tests", moduleContext, runTestsSh.toFile()
				);
				Files.createDirectories(refDataDir);
			}
		}
		boolean createdTestCfg = false;
		// could make a template -> Path map here and DRY things up for all the but the last
		// if. Meh
		if (!Files.exists(readmeFile)) {
			TemplateFormatter.formatTemplate(
					"module_readme_test_local", moduleContext, readmeFile.toFile()
			);
		}
		if (!Files.exists(runTestsSh)) {
			TemplateFormatter.formatTemplate(
					"module_run_tests", moduleContext, runTestsSh.toFile()
			);
		}
		if (!Files.exists(runBashSh)) {
			TemplateFormatter.formatTemplate(
					"module_run_bash", moduleContext, runBashSh.toFile()
			);
		}
		if (!Files.exists(runDockerSh)) {
			TemplateFormatter.formatTemplate(
					"module_run_docker", moduleContext, runDockerSh.toFile()
			);
		}
		if (!Files.exists(testCfg)) {
			TemplateFormatter.formatTemplate(
					"module_test_cfg", moduleContext, testCfg.toFile()
			);
			createdTestCfg = true;
		}
		return new TestLocalInfo(tlDir, createdTestCfg);
	}

}
