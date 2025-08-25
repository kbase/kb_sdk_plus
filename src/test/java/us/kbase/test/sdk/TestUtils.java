package us.kbase.test.sdk;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.commons.io.FileUtils;

import us.kbase.test.sdk.scripts.TestConfigHelper;

/** Yes I know utility classes are bad etc etc */

public class TestUtils {
	
	/** Delete an SDK module. Does nothing if the module path doesn't exist or isn't a directory.
	 * @param module the path to the module root.
	 * @param deleteModule if false, the module won't be deleted.
	 * @throws Exception if an error occurs
	 */
	public static void deleteTestModule(
			final Path module,
			boolean deleteModule
			) throws Exception {
		if (Files.exists(module) && Files.isDirectory(module)) {
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
	
	/** Creates the kb-sdk config file, sdk.cfg, in the target directory, initialized with a
	 * catalog URL determined by the test configuration file.
	 * 
	 * This needs to be run *prior* to initializing a module to direct the automatic
	 * module installs to use a different version of the catalog.
	 * 
	 * @param rootModuleDir the root directory for the module.
	 * @param moduleName the module name; the config file will be created under this directory.
	 * @throws Exception if an error occurs.
	 */
	public static void createSdkCfgFile(final Path rootModuleDir, final String moduleName)
			throws Exception {
		final Path target = rootModuleDir.resolve(moduleName);
		Files.createDirectories(target);
		// may want to make sdk.cfg a global constant, it's used all over the place
		final Path filePath = target.resolve("sdk.cfg");
		final String catURLCfg = "catalog_url=" + TestConfigHelper.getKBaseEndpoint() + "/catalog";
		Files.write(filePath, catURLCfg.getBytes(StandardCharsets.UTF_8));
	}

}
