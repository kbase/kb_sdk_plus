package us.kbase.test.sdk.installer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.kbase.sdk.initializer.ModuleInitializer;
import us.kbase.sdk.installer.ClientInstaller;
import us.kbase.test.sdk.TestUtils;
import us.kbase.test.sdk.scripts.TestConfigHelper;

public class ClientInstallerTest {
    private static File tempDir = null;
    
    @BeforeAll
    public static void prepareClass() throws Exception {
        final Path rootTemp = Paths.get(
                TestConfigHelper.getTempTestDir(), ClientInstallerTest.class.getSimpleName()
        );
        Files.createDirectories(rootTemp);
        tempDir = Files.createTempDirectory(rootTemp, "test_install_").toFile();
    }
    
    @AfterAll
    public static void teardownClass() throws Exception {
        if (tempDir != null && tempDir.exists()) {
            FileUtils.deleteQuietly(tempDir);
        }
    }
    
    @Test
    public void testJava() throws Exception {
        String moduleName = "JavaModule";
        TestUtils.createSdkCfgFile(tempDir.toPath(), moduleName);
        ModuleInitializer init = new ModuleInitializer(moduleName, "kbasetest", "java", false, 
                tempDir, true);
        init.initialize(false);
        File moduleDir = new File(tempDir, moduleName);
        File sdkCfgFile = new File(moduleDir, "sdk.cfg");
        FileUtils.writeLines(sdkCfgFile, Arrays.asList("catalog_url=" +
                TestConfigHelper.getKBaseEndpoint() + "/catalog"));
        ClientInstaller ci = new ClientInstaller(moduleDir, true);
        String module2 = "onerepotest";
        ci.install(null, false, false, false, "dev", true, module2, null, null);
        File dir = new File(moduleDir, "lib/src/installed_clients/" + module2);
        //ProcessHelper.cmd("ls", "-l", dir.getAbsolutePath()).exec(moduleDir);
        assertTrue(new File(dir, "OnerepotestClient.java").exists());
        assertTrue(new File(dir, "OnerepotestServiceClient.java").exists());
        checkDeps(moduleDir);
    }
    
    @Test
    public void testPython() throws Exception {
        String moduleName = "PythonModule";
        TestUtils.createSdkCfgFile(tempDir.toPath(), moduleName);
        ModuleInitializer init = new ModuleInitializer(moduleName, "kbasetest", "python", false,
                tempDir, true);
        init.initialize(false);
        File moduleDir = new File(tempDir, moduleName);
        File sdkCfgFile = new File(moduleDir, "sdk.cfg");
        FileUtils.writeLines(sdkCfgFile, Arrays.asList("catalog_url=" +
                TestConfigHelper.getKBaseEndpoint() + "/catalog"));
        ClientInstaller ci = new ClientInstaller(moduleDir, true);
        String module2 = "onerepotest";
        ci.install(null, false, false, false, "dev", true, module2, null, null);
        File dir = new File(moduleDir, "lib/installed_clients");
        assertTrue(new File(dir, "onerepotestClient.py").exists());
        assertTrue(new File(dir, "onerepotestServiceClient.py").exists());
        checkDeps(moduleDir);
    }

    private static void checkDeps(File moduleDir) throws Exception {
        File depsFile = new File(moduleDir, "dependencies.json");
        assertTrue(depsFile.exists());
        String expectedText = "" +
                "[ {\n" +
                "  \"module_name\" : \"KBaseReport\",\n" +
                "  \"type\" : \"sdk\",\n" +
                "  \"version_tag\" : \"release\"\n" +
                "}, {\n" +
                "  \"module_name\" : \"onerepotest\",\n" +
                "  \"type\" : \"sdk\",\n" +
                "  \"version_tag\" : \"dev\"\n" +
                "}, {\n" +
                "  \"module_name\" : \"Workspace\",\n" +
                "  \"type\" : \"core\",\n" +
                "  \"file_path\" : \"https://raw.githubusercontent.com/kbase/workspace_deluxe/master/workspace.spec\"\n" +
                "} ]";
        assertEquals(expectedText, FileUtils.readFileToString(depsFile));
    }
}
