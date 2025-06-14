package us.kbase.test.sdk.installer;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import org.junit.Assert;

import org.apache.commons.io.FileUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.fasterxml.jackson.core.type.TypeReference;

import us.kbase.common.service.UObject;
import us.kbase.sdk.installer.ClientInstaller;
import us.kbase.sdk.installer.Dependency;
import us.kbase.sdk.util.DiskFileSaver;
import us.kbase.test.sdk.scripts.TestConfigHelper;

public class DepsTest {
    private static File tempDir = null;
    private static File depsFile = null;
    
    @BeforeClass
    public static void prepareClass() throws Exception {
        tempDir = Files.createTempDirectory(
                Paths.get(TestConfigHelper.getTempTestDir()), "test_deps_"
        ).toFile();
        depsFile = new File(tempDir, "dependencies.json");
    }
    
    @AfterClass
    public static void teardownClass() throws Exception {
        if (tempDir != null && tempDir.exists()) {
            FileUtils.deleteQuietly(tempDir);
        }
    }

    @Before
    public void prepareData() throws Exception {
        Dependency dep = new Dependency();
        dep.moduleName = "test";
        dep.type = "sdk";
        dep.versionTag = "dev";
        List<Dependency> deps = Arrays.asList(dep);
        UObject.getMapper().writeValue(depsFile, deps);
    }

    @Test
    public void testFromScratch() throws Exception {
        List<Dependency> deps = UObject.getMapper().readValue(depsFile, 
                new TypeReference<List<Dependency>>() {});
        Assert.assertEquals(1, deps.size());
        checkInitialDep(deps.get(0));
    }
    
    private void checkInitialDep(Dependency dep) throws Exception {
        Assert.assertEquals("test", dep.moduleName);
        Assert.assertEquals("sdk", dep.type);
        Assert.assertEquals("dev", dep.versionTag);
        Assert.assertNull(dep.filePath);
    }

    @Test
    public void testAdding() throws Exception {
        String newModule = "tes";
        String filePath = "http://some.where/else";
        ClientInstaller.addDependency(newModule, false, null, filePath, new DiskFileSaver(tempDir));
        List<Dependency> deps = UObject.getMapper().readValue(depsFile, 
                new TypeReference<List<Dependency>>() {});
        Assert.assertEquals(2, deps.size());
        checkInitialDep(deps.get(1));
        Dependency dep = deps.get(0);
        Assert.assertEquals(newModule, dep.moduleName);
        Assert.assertEquals("core", dep.type);
        Assert.assertNull(dep.versionTag);
        Assert.assertEquals(filePath, dep.filePath);
    }

    @Test
    public void testOverriding() throws Exception {
        String newTag = "release";
        ClientInstaller.addDependency("test", true, newTag, null, new DiskFileSaver(tempDir));
        List<Dependency> deps = UObject.getMapper().readValue(depsFile, 
                new TypeReference<List<Dependency>>() {});
        Assert.assertEquals(1, deps.size());
        Dependency dep = deps.get(0);
        Assert.assertEquals(newTag, dep.versionTag);
    }
}
