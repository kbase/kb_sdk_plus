package us.kbase.test.sdk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.commons.io.FileUtils;

import us.kbase.mobu.util.ProcessHelper;
import us.kbase.test.sdk.scripts.TestConfigHelper;

/** Yes I know utility classes are bad etc etc */

public class TestUtils {
	
	/** Set docker output owned by root to publicly readable, writable, and executable.
	 * 
	 * Some tests leave files that can only be written to by root on the drive.
	 * 
	 * @param moduleRoot the root of the module to modify.
	 * @param target the directory to recursively alter, relative to /kb/module/work in the
	 * container, where test_local/workdir is mounted.
	 * @param ignoreMissing do nothing if the target directory does not exist or is not a
	 * directory. Otherwise an error may be thrown.
	 * 
	 * @throws Exception if an error occurs.
	 */
	public static void makeDockerOutputWritable(
			final Path moduleRoot,
			final Path target,
			boolean ignoreMissing)
			throws Exception {
		final Path workdir = moduleRoot.resolve("test_local/workdir");
		final Path lclTarget = workdir.resolve(target).toRealPath();
		if (ignoreMissing && (!Files.exists(lclTarget) || !Files.isDirectory(lclTarget))) {
			return;
		}
		final Path tgt = Paths.get("/kb/module/work").resolve(target);
		final int exitCode = ProcessHelper.cmd(
				"docker", "run",
				"-v", workdir.toRealPath().toString() + ":/kb/module/work",
				"--entrypoint", "chmod",
				// use an image that'll be used for the tests anyway
				// should be updated if dockerfile template changes
				"kbase/sdkbase2:latest", 
				"-R", "o+rwx", tgt.toString()
		).exec(moduleRoot.toFile()).getExitCode();
		if (exitCode != 0) {
			throw new IllegalStateException(
					"Setting permissions on root owned files failed with exit code " + exitCode
			);
		}
	}
	
	/** Delete an SDK module. Does nothing if the module path doesn't exist or isn't a directory.
	 * @param module the path to the module root.
	 * @param makeWriteable true if the module is expected to have root owned data
	 * in test_local/workdir, which will be set to world writable depending on the
	 * test configuration file.
	 * @param deleteModule if false, the module won't be deleted.
	 * @throws Exception if an error occurs
	 */
	public static void deleteTestModule(
			final Path module,
			boolean makeWriteable,
			boolean deleteModule)
			throws Exception {
		if (Files.exists(module) && Files.isDirectory(module)) {
			if (TestConfigHelper.getMakeRootOwnedFilesWriteable() && makeWriteable) {
				TestUtils.makeDockerOutputWritable(module, Paths.get("."), true);
			}
			if (deleteModule) {
				try {
					System.out.println("Deleting " + module);
					FileUtils.deleteDirectory(module.toFile());
				} catch (Exception ex) {
					System.err.println(
							"Error cleaning up module [" + module + "]: " + ex.getMessage());
				}
			}
		}
	}

}
