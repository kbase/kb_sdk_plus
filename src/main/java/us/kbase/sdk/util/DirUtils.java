package us.kbase.sdk.util;

import java.io.File;
import java.io.IOException;

import us.kbase.sdk.common.KBaseYmlConfig;

public class DirUtils {

    public static boolean isModuleDir(File dir) {
        return  new File(dir, "Dockerfile").exists() &&
                new File(dir, "Makefile").exists() &&
                new File(dir, KBaseYmlConfig.KBASE_YAML).exists() &&
                new File(dir, "lib").exists() &&
                new File(dir, "scripts").exists() &&
                new File(dir, "test").exists() &&
                new File(dir, "ui").exists();
    }

    public static File findModuleDir() throws IOException {
        return findModuleDir(new File("."));
    }
    
    public static File findModuleDir(File dir) throws IOException {
        dir = dir.getCanonicalFile();
        while (!isModuleDir(dir)) {
            dir = dir.getParentFile();
            if (dir == null)
                throw new IllegalStateException("You're currently not in module folder");
        }
        return dir;
    }
    
    public static String getFilePath(File f) throws Exception {
        boolean isWindows = System.getProperty("os.name").startsWith("Windows");
        String ret = f.getCanonicalPath();
        if (isWindows)
            ret = getWinShortPath(ret);
        return ret;
    }
    
    private static String getWinShortPath(String path) throws IOException, InterruptedException {
        // no idea what this is doing or if it works
        // TODO WINDOWS SUPPORT test on windows system or decide we're not supporting windows

        Process process = Runtime.getRuntime().exec(
                "cmd /c for %I in (\"" + path + "\") do @echo %~fsI"
        );
        process.waitFor();

        byte[] data = new byte[65536];
        int size = process.getInputStream().read(data);

        if (size <= 0) {
            return null;
        }
        
        return new String(data, 0, size).replaceAll("\\r\\n", "");
    }
}
