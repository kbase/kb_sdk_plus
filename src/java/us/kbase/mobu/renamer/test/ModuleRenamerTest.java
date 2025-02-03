package us.kbase.mobu.renamer.test;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import junit.framework.Assert;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import us.kbase.auth.AuthToken;
import us.kbase.mobu.initializer.ModuleInitializer;
import us.kbase.mobu.renamer.ModuleRenamer;
import us.kbase.mobu.tester.test.ModuleTesterTest;
import us.kbase.scripts.test.TestConfigHelper;
import us.kbase.test.sdk.TestUtils;

public class ModuleRenamerTest {

    private static final String SIMPLE_MODULE_NAME = "a_SimpleModule_for_rename_unit_testing";
    private static final String TARGET_MODULE_NAME = "TargetModule_for_rename_unit_testing";
    private static final Map<Path, Boolean> CREATED_MODULES = new HashMap<>();
    private static final boolean DELETE_TEST_MODULES = true;

    private static AuthToken token = null;

    @BeforeClass
    public static void beforeClass() throws Exception {
        token = TestConfigHelper.getToken();
    }

    @AfterClass
    public static void tearDownModule() throws Exception {
        for (final Entry<Path, Boolean> dirAndCov : CREATED_MODULES.entrySet()) {
            TestUtils.deleteTestModule(
                    dirAndCov.getKey(), dirAndCov.getValue(), DELETE_TEST_MODULES
            );
        }
    }

    private static File initRepo(final String lang) throws Exception {
        return initRepo(lang, false);
    }
    
    private static File initRepo(final String lang, final boolean hasPyTestCov) throws Exception {
        final String moduleName = SIMPLE_MODULE_NAME + "_" + lang;
        final Path workDir = Paths.get(TestConfigHelper.getTempTestDir(), moduleName);
        final File ret = workDir.toFile();
        TestUtils.deleteTestModule(workDir, hasPyTestCov, true);
        CREATED_MODULES.put(workDir, hasPyTestCov);
        new ModuleInitializer(
                moduleName,
                token.getUserName(),
                lang,
                false,
                new File(TestConfigHelper.getTempTestDir())
        ).initialize(true);
        return ret;
    }

    @Test
    public void testJava() throws Exception {
        String newModuleName = TARGET_MODULE_NAME + "_java";
        File moduleDir = initRepo("java");
        new ModuleRenamer(moduleDir).rename(newModuleName);
        int exitCode = ModuleTesterTest.runTestsInDocker(moduleDir, token);
        Assert.assertEquals(0, exitCode);
    }

    @Test
    public void testPython() throws Exception {
        String newModuleName = TARGET_MODULE_NAME + "_python";
        File moduleDir = initRepo("python", true);
        new ModuleRenamer(moduleDir).rename(newModuleName);
        int exitCode = ModuleTesterTest.runTestsInDocker(moduleDir, token);
        Assert.assertEquals(0, exitCode);
    }
    
    @Test
    public void testWindowsEOFs() throws Exception {
        String oldModuleName = "a_SimpleModule_for_unit_testing";
        String newModuleName = "a_SimpleModule_for_UnitTesting";
        String text = "" +
                "module-name:\n" +
                "    " + oldModuleName + "\n" +
                "\n" +
                "module-description:\n" +
                "    A KBase module\n";
        String newText = ModuleRenamer.replace(text, "module-name:\\s*(" + oldModuleName + ")", 
                newModuleName, "module-name key is not found");
        Assert.assertTrue(newText.contains(newModuleName));
    }
}
