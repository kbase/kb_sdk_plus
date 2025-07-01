package us.kbase.test.sdk.common;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.collect.ImmutableMap;

import us.kbase.sdk.common.TestLocalManager;
import us.kbase.sdk.common.TestLocalManager.TestLocalInfo;

public class TestLocalManagerTest {
	
	private static final String README_FILE = "readme.txt";
	private static final String TEST_CFG_FILE = "test.cfg";
	private static final String RUN_DOCKER_FILE = "run_docker.sh";
	private static final String RUN_BASH_FILE = "run_bash.sh";
	private static final String RUN_TESTS_FILE = "run_tests.sh";
	private static final List<String> ALL_FILES = Arrays.asList(
			README_FILE, TEST_CFG_FILE, RUN_DOCKER_FILE, RUN_BASH_FILE, RUN_TESTS_FILE
	);

	private static final String README = """
			This directory contains temporary scripts and files needed to run tests
			locally in Docker installed on developers' machines. These files are not
			supposed to be committed into version control or to be copied inside the
			Docker image.
			
			To run tests:
			- add a valid test_token to test.cfg file
			- run "kb-sdk test"
			""";
	
	private static final String TEST_CFG = """
			# PLEASE DO NOT COMMIT THIS FILE INTO VERSION CONTROL!
			# Add a test_token from  your KBase developer account.
			# Tokens may be generated at https://narrative.kbase.us/account/dev-tokens.
			# If you don't have access to the developer tokens tab, please contact us:
			# https://www.kbase.us/support/
			
			test_token=
			kbase_endpoint=https://appdev.kbase.us/services
			
			# Next set of URLs correspond to core services. By default they
			# are defined automatically based on 'kbase_endpoint':
			#job_service_url=
			#workspace_url=
			#shock_url=
			#handle_url=
			#srv_wiz_url=
			#njsw_url=
			#catalog_url=
			
			#auth2-service URL:
			auth_service_url=https://appdev.kbase.us/services/auth/api/legacy/KBase/Sessions/Login
			auth_service_url_allow_insecure=false
			
			#callback_networks=docker0,vboxnet0,vboxnet1,eth0,en0,en1,en2,en3
			""";
	
	private static final String RUN_DOCKER = """
			#!/bin/bash
			docker $@
			""";
	
	private static final String RUN_BASH = """
			#!/bin/bash
			script_dir="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
			$script_dir/run_docker.sh run -i -t -v $script_dir/workdir:/kb/module/work test/%s:latest bash
			""";
	
	private static final String RUN_TESTS = """
			#!/bin/bash
			script_dir="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
			cd $script_dir/..
			$script_dir/run_docker.sh run -v $script_dir/workdir:/kb/module/work -e "SDK_CALLBACK_URL=$1" test/%s:latest test
			""";
	
	private static final String RUN_TESTS_WITH_REFDATA = """
			#!/bin/bash
			script_dir="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
			cd $script_dir/..
			if [ -f "$script_dir/refdata/__READY__" ]; then
			    echo "Reference data initialization is skipped because it was already prepared"
			else
			    echo "Reference data initialization"
			    if [ -d "$script_dir/refdata" ]; then
			        rm -r $script_dir/refdata/*
			    else
			        mkdir $script_dir/refdata
			    fi
			    $script_dir/run_docker.sh run -v $script_dir/workdir:/kb/module/work -v $script_dir/refdata:/data -v $script_dir/../data:/kb/module/data -e "SDK_CALLBACK_URL=$1" test/%s:latest init
			fi
			if [ -f "$script_dir/refdata/__READY__" ]; then
			    $script_dir/run_docker.sh run -v $script_dir/workdir:/kb/module/work -v $script_dir/refdata:/data:ro -e "SDK_CALLBACK_URL=$1" test/%s:latest test
			else
			    echo "ERROR: __READY__ file is not detected. Reference data initialization wasn't done correctly."
			    exit 1
			fi
			""";

	@TempDir
	private Path tempModuleDir;

	private Path testLocalDir;
	private Path refdataDir;

	@BeforeEach
	void setup() {
		testLocalDir = tempModuleDir.resolve("test_local");
		refdataDir = testLocalDir.resolve("refdata");
	}
	
	@Test
	void testStaticMethods() {
		assertThat(TestLocalManager.getReadmeRelative(), is(Paths.get("test_local/readme.txt")));
		assertThat(TestLocalManager.getRunBashRelative(), is(Paths.get("test_local/run_bash.sh")));
		assertThat(TestLocalManager.getRunDockerRelative(),
				is(Paths.get("test_local/run_docker.sh"))
		);
		assertThat(TestLocalManager.getRunTestsRelative(),
				is(Paths.get("test_local/run_tests.sh"))
		);
		assertThat(TestLocalManager.getTestCfgRelative(), is(Paths.get("test_local/test.cfg")));
		assertThat(TestLocalManager.getTestLocalRelative(), is(Paths.get("test_local")));
	}
	
	@Test
	void testCreateAllFilesThenOverwriteDataVersion() throws Exception {
		TestLocalInfo tlm = TestLocalManager.ensureTestLocal(
				tempModuleDir, "foobar", Optional.empty()
		);
		assertOnStandardFileContents("foobar", false);
		assertThat("refdata directory should not exist", Files.exists(refdataDir), is(false));
		assertTLMCorrect(tlm, true);
		
		tlm = TestLocalManager.ensureTestLocal(
				tempModuleDir, "foobar", Optional.of("0.1")
		);
		assertOnStandardFileContents("foobar", true);
		assertThat("refdata directory should exist", Files.exists(refdataDir), is(true));
		assertTLMCorrect(tlm, false);
	}
	
	@Test
	void testNoopThenOverwriteWithDataVersion() throws Exception {
		// test the case where test_local is all set up
		Files.createDirectories(testLocalDir);
		for (final String file: ALL_FILES) {
			Files.writeString(testLocalDir.resolve(file), "don't overwrite");
		}
		TestLocalInfo tlm = TestLocalManager.ensureTestLocal(
				tempModuleDir, "mymod", Optional.empty()
		);
		for (final String file: ALL_FILES) {
			final String contents = Files.readString(testLocalDir.resolve(file));
			assertThat(String.format("File contents incorrect for %s", file),
					contents, is("don't overwrite"));
		}
		assertThat("refdata directory should not exist", Files.exists(refdataDir), is(false));
		assertTLMCorrect(tlm, false);
		
		// now test overwrite for just run tests
		tlm = TestLocalManager.ensureTestLocal(
				tempModuleDir, "mymod", Optional.of("0.1")
		);
		final List<String> sub = new ArrayList<>(ALL_FILES);
		sub.remove(RUN_TESTS_FILE);
		for (final String file: sub) {
			final String contents = Files.readString(testLocalDir.resolve(file));
			assertThat(String.format("File contents incorrect for %s", file),
					contents, is("don't overwrite"));
		}
		final String contents = Files.readString(testLocalDir.resolve(RUN_TESTS_FILE));
		assertThat("incorrect run test contents",
				contents, is(String.format(RUN_TESTS_WITH_REFDATA, "mymod", "mymod"))
		);
		assertThat("refdata directory should exist", Files.exists(refdataDir), is(true));
		assertTLMCorrect(tlm, false);
	}
	
	@Test
	public void testNoOverwriteWhenRefdataExists() throws Exception {
		Files.createDirectories(refdataDir);
		for (final String file: ALL_FILES) {
			Files.writeString(testLocalDir.resolve(file), "don't overwrite");
		}
		TestLocalInfo tlm = TestLocalManager.ensureTestLocal(
				tempModuleDir, "mymod", Optional.of("0.3")
		);
		for (final String file: ALL_FILES) {
			final String contents = Files.readString(testLocalDir.resolve(file));
			assertThat(String.format("File contents incorrect for %s", file),
					contents, is("don't overwrite"));
		}
		assertThat("refdata directory should exist", Files.exists(refdataDir), is(true));
		assertTLMCorrect(tlm, false);
	}
	
	@Test
	public void testRestoreReadme() throws Exception {
		testRestoreFile(README_FILE, README, false);
	}
	
	@Test
	public void testTestCfg() throws Exception {
		testRestoreFile(TEST_CFG_FILE, TEST_CFG, true);
	}
	
	@Test
	public void testRestoreRunDocker() throws Exception {
		testRestoreFile(RUN_DOCKER_FILE, RUN_DOCKER, false);
	}
	
	@Test
	public void testRestoreRunBash() throws Exception {
		testRestoreFile(RUN_BASH_FILE, String.format(RUN_BASH, "neatmod"), false);
	}
	
	@Test
	public void testRestoreRunTests() throws Exception {
		testRestoreFile(RUN_TESTS_FILE, String.format(RUN_TESTS, "neatmod"), false);
	}

	private void testRestoreFile(
			final String filename,
			final String contents,
			final boolean createdTestConfig
			) throws Exception {
		Files.createDirectories(testLocalDir);
		final List<String> sub = new ArrayList<>(ALL_FILES);
		sub.remove(filename);
		for (final String file: sub) {
			Files.writeString(testLocalDir.resolve(file), "don't overwrite");
		}
		TestLocalInfo tlm = TestLocalManager.ensureTestLocal(
				tempModuleDir, "neatmod", Optional.empty()
		);
		for (final String file: sub) {
			final String con = Files.readString(testLocalDir.resolve(file));
			assertThat(String.format("File contents incorrect for %s", file),
					con, is("don't overwrite"));
		}
		final String gotcon = Files.readString(testLocalDir.resolve(filename));
		assertThat("incorrect file contents", gotcon, is(contents)
		);
		assertThat("refdata directory should not exist", Files.exists(refdataDir), is(false));
		assertTLMCorrect(tlm, createdTestConfig);
		
	}
	
	private void assertTLMCorrect(final TestLocalInfo tlm, final boolean createdTestCfg) {
		assertThat("incorrect created test cfg", tlm.isCreatedTestCfgFile(), is(createdTestCfg));
		assertThat("incorrect test_local path", tlm.getTestLocalDir(), is(testLocalDir));
		assertThat("incorrect readme path",
				tlm.getReadmeFile(), is(testLocalDir.resolve(README_FILE))
		);
		assertThat("incorrect test.cfg path",
				tlm.getTestCfgFile(), is(testLocalDir.resolve(TEST_CFG_FILE))
		);
		assertThat("incorrect run docker path",
				tlm.getRunDockerShFile(), is(testLocalDir.resolve(RUN_DOCKER_FILE))
		);
		assertThat("incorrect run bash path",
				tlm.getRunBashShFile(), is(testLocalDir.resolve(RUN_BASH_FILE))
		);
		assertThat("incorrect run tests path",
				tlm.getRunTestsShFile(), is(testLocalDir.resolve(RUN_TESTS_FILE))
		);
	}

	private void assertOnStandardFileContents(final String module, final boolean refdata
			) throws IOException {
		final String rt = refdata ? RUN_TESTS_WITH_REFDATA : RUN_TESTS;
		final Map<String, String> testcases = ImmutableMap.of(
				README_FILE, README,
				TEST_CFG_FILE, TEST_CFG,
				RUN_DOCKER_FILE, RUN_DOCKER,
				RUN_BASH_FILE, String.format(RUN_BASH, module),
				RUN_TESTS_FILE, String.format(rt, module, module)
		);
		for (final String file: testcases.keySet()) {
			final String contents = Files.readString(testLocalDir.resolve(file));
			assertThat(String.format("File contents incorrect for %s", file),
					contents, is(testcases.get(file)));
		}
	}
	
	
	@Test
	public void testBadArguments() throws Exception {
		Exception e = assertThrows(
				NullPointerException.class,
				() -> TestLocalManager.ensureTestLocal(null, "foo", Optional.empty())
		);
		assertThat("Incorrect exception", e.getMessage(), is("moduleDir"));
		
		// TODO TEST check for whitespace only strings when that's fixed
		e = assertThrows(
				NullPointerException.class,
				() -> TestLocalManager.ensureTestLocal(Paths.get("a"), null, Optional.empty())
		);
		assertThat("Incorrect exception", e.getMessage(), is("moduleName"));
		
		// TODO TEST check for whitespace only strings in the optional when that's fixed
		e = assertThrows(
				NullPointerException.class,
				() -> TestLocalManager.ensureTestLocal(Paths.get("a"), "a", null)
		);
		assertThat("Incorrect exception", e.getMessage(), is("dataVersion"));
	}

}
