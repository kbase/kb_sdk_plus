package us.kbase.sdk.tester;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.io.FileUtils;

import com.github.zafarkhaja.semver.Version;

import us.kbase.sdk.callback.CallbackProvenance;
import us.kbase.sdk.callback.CallbackServerManager;
import us.kbase.sdk.common.KBaseYmlConfig;
import us.kbase.sdk.common.TestLocalManager;
import us.kbase.sdk.common.TestLocalManager.TestLocalInfo;
import us.kbase.sdk.initializer.ModuleInitializer;
import us.kbase.sdk.util.DirUtils;
import us.kbase.sdk.util.ProcessHelper;
import us.kbase.sdk.util.TextUtils;
import us.kbase.sdk.validator.ModuleValidator;

public class ModuleTester {
    private File moduleDir;
    private KBaseYmlConfig kbaseYmlConfig;
    private Map<String, Object> moduleContext;

    public ModuleTester() throws Exception {
        this(null);
    }
    
    public ModuleTester(File dir) throws Exception {
        moduleDir = dir == null ? DirUtils.findModuleDir() : DirUtils.findModuleDir(dir);
        kbaseYmlConfig = new KBaseYmlConfig(moduleDir.toPath());
        moduleContext = new HashMap<String, Object>();
        moduleContext.put("module_name", kbaseYmlConfig.getModuleName());
        moduleContext.put("module_root_path", moduleDir.getAbsolutePath());
        if (kbaseYmlConfig.getDataVersion().isPresent()) {
            moduleContext.put("data_version", kbaseYmlConfig.getDataVersion().get());
        }
        // this next line will throw an exception if the language is unsupported
        ModuleInitializer.qualifyLanguage((String) kbaseYmlConfig.getServiceLanguage());
        moduleContext.put("os_name", System.getProperty("os.name"));
    }
    
    private static void checkIgnoreLine(File f, String line) throws IOException {
        List<String> lines = new ArrayList<String>();
        if (f.exists())
            lines.addAll(FileUtils.readLines(f));
        if (!new HashSet<String>(lines).contains(line)) {
            System.out.println("Warning: file \"" + f.getName() + "\" doesn't contain \"" + line +
                    "\" line, it will be added.");
            lines.add(line);
            FileUtils.writeLines(f, lines);
        }
    }
    
    public int runTests(final boolean skipValidation, final boolean setFilesGloballyWriteable
        ) throws Exception {
        // TODO CODE some of this code looks similar to that in the module runner, DRY possible
        final TestLocalInfo tli = TestLocalManager.ensureTestLocal(
                moduleDir.toPath(),
                kbaseYmlConfig.getModuleName(),
                kbaseYmlConfig.getDataVersion()
        );
        final String testLocalRel = TestLocalManager.getTestLocalRelative().toString();
        final String testCfgRel = TestLocalManager.getTestCfgRelative().toString();
        if (skipValidation) {
            System.out.println("Validation step is skipped");
        } else {
            ModuleValidator mv = new ModuleValidator(moduleDir.getCanonicalPath(), false);
            int returnCode = mv.validate();
            if (returnCode != 0) {
                System.out.println("You can skip validation step using -s (or --skip_validation)" +
                		" flag");
                // TODO CODE should be throwing exceptions, not returning return codes all over the
                //           place
                return returnCode;
            }
        }
        checkIgnoreLine(new File(moduleDir, ".gitignore"), testLocalRel);
        checkIgnoreLine(new File(moduleDir, ".dockerignore"), testLocalRel);
        if (tli.isCreatedTestCfgFile()) {
            System.out.println(String.format(
                    "Set KBase account credentials in %s and then test again", testCfgRel
            ));
            return 1;
        }
        // TODO CODE update the code below to use Path
        final File testCfg = tli.getTestCfgFile().toFile();
        final File tlDir = tli.getTestLocalDir().toFile();
        final File runDockerSh = tli.getRunDockerShFile().toFile();
        final File runBashSh = tli.getRunBashShFile().toFile();
        final File runTestsSh = tli.getRunTestsShFile().toFile();
        Properties props = new Properties();
        InputStream is = new FileInputStream(testCfg);
        try {
            props.load(is);
        } finally {
            is.close();
        }
        
        final ConfigLoader cfgLoader = new ConfigLoader(props, true, testCfgRel);
        
        File workDir = new File(tlDir, "workdir");
        workDir.mkdir();
        File tokenFile = new File(workDir, "token");
        FileWriter fw = new FileWriter(tokenFile);
        try {
            fw.write(cfgLoader.getToken().getToken());
        } finally {
            fw.close();
        }
        File testCfgCopy = new File(workDir, "test.cfg");
        Files.copy(testCfg.toPath(), testCfgCopy.toPath(),
                StandardCopyOption.REPLACE_EXISTING);
        File configPropsFile = new File(workDir, "config.properties");
        cfgLoader.generateConfigProperties(configPropsFile);
        ProcessHelper.cmd("chmod", "+x", runBashSh.getCanonicalPath()).exec(tlDir);
        ProcessHelper.cmd("chmod", "+x", runDockerSh.getCanonicalPath()).exec(tlDir);
        String moduleName = kbaseYmlConfig.getModuleName();
        String imageName = "test/" + moduleName.toLowerCase() + ":latest";
        File subjobsDir = new File(tlDir, "subjobs");
        if (subjobsDir.exists())
            TextUtils.deleteRecursively(subjobsDir);
        File scratchDir = new File(workDir, "tmp");
        if (scratchDir.exists())
            TextUtils.deleteRecursively(scratchDir);
        scratchDir.mkdir();
        if (!buildNewDockerImageWithCleanup(moduleDir, tlDir, runDockerSh, imageName))
            return 1;
        ///////////////////////////////////////////////////////////////////////////////////////////
        final CallbackServerManager csm = new CallbackServerManager(
                tlDir.toPath(),
                new URL(cfgLoader.getEndPoint()),
                cfgLoader.getToken(),
                CallbackProvenance.getBuilder(moduleName + ".run_local_tests", Version.of(0, 1, 0))
                        .withCodeUrl(new URL("http://localhost"))
                        .build(),
                setFilesGloballyWriteable
        );
        System.out.println(String.format("Local callback port: %s", csm.getCallbackPort()));
        System.out.println(String.format(
                "In container callback url: %s", csm.getInContainerCallbackUrl())
        );
        try (csm) {
            ProcessHelper.cmd("chmod", "+x", runTestsSh.getCanonicalPath()).exec(tlDir);
            int exitCode = ProcessHelper.cmd(
                    "bash",
                    DirUtils.getFilePath(runTestsSh),
                    csm.getInContainerCallbackUrl().toExternalForm()
            ).exec(tlDir).getExitCode();
            return exitCode;
        }
    }

    public static boolean buildNewDockerImageWithCleanup(File moduleDir, File tlDir,
            File runDockerSh, String imageName) throws Exception {
        System.out.println();
        System.out.println("Delete old Docker containers");
        String runDockerPath = DirUtils.getFilePath(runDockerSh);
        List<String> lines = exec(tlDir, "bash", DirUtils.getFilePath(runDockerSh), "ps", "-a");
        for (String line : lines) {
            String[] parts = splitByWhiteSpaces(line);
            if (parts[1].equals(imageName)) {
                String cntId = parts[0];
                ProcessHelper.cmd("bash", runDockerPath, "rm", "-v", "-f", cntId).exec(tlDir);
            }
        }
        String oldImageId = findImageIdByName(tlDir, imageName, runDockerSh);    
        System.out.println();
        System.out.println("Build Docker image");
        boolean ok = buildImage(moduleDir, imageName, runDockerSh);
        if (!ok)
            return false;
        if (oldImageId != null) {
            String newImageId = findImageIdByName(tlDir, imageName, runDockerSh);
            if (!newImageId.equals(oldImageId)) {
                // It's not the same image (not all layers are cached)
                System.out.println("Delete old Docker image");
                ProcessHelper.cmd("bash", runDockerPath, "rmi", oldImageId).exec(tlDir);
            }
        }
        return true;
    }
    
    public static String findImageIdByName(File tlDir, String imageName,
            File runDockerSh) throws Exception {
        List<String> lines;
        String ret = null;
        lines = exec(tlDir, "bash", DirUtils.getFilePath(runDockerSh), "images");
        for (String line : lines) {
            String[] parts = splitByWhiteSpaces(line);
            String name = parts[0] + ":" + parts[1];
            if (name.equals(imageName)) {
                ret = parts[2];
                break;
            }
        }
        if (ret == null) {
            System.out.println("Can't find image [" + imageName + "]. Here is 'docker images' output:");
            for (String line : lines) {
                System.out.println("\t" + line);
            }
            System.out.println();
        }
        return ret;
    }

    public static String[] splitByWhiteSpaces(String line) {
        String[] parts = line.split("\\s+");
        return parts;
    }
    
    private static List<String> exec(File workDir, String... cmd) throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        ProcessHelper.cmd(cmd).exec(workDir, null, pw, pw);
        pw.close();
        List<String> ret = new ArrayList<String>();
        BufferedReader br = new BufferedReader(new StringReader(sw.toString()));
        while (true) {
            String l = br.readLine();
            if (l == null)
                break;
            ret.add(l);
        }
        br.close();
        return ret;
    }
    
    public static boolean buildImage(File repoDir, String targetImageName, 
            File runDockerSh) throws Exception {
        String scriptPath = DirUtils.getFilePath(runDockerSh);
        String repoPath = DirUtils.getFilePath(repoDir);
        Process p = Runtime.getRuntime().exec(new String[] {"bash", 
                scriptPath, "build", "--rm", "--load", "-t",
                targetImageName, repoPath});
        List<Thread> workers = new ArrayList<Thread>();
        InputStream[] inputStreams = new InputStream[] {p.getInputStream(), p.getErrorStream()};
        final String[] cntIdToDelete = {null};
        final String[] imageIdToDelete = {null};
        for (int i = 0; i < inputStreams.length; i++) {
            final InputStream is = inputStreams[i];
            final boolean isError = i == 1;
            Thread ret = new Thread(new Runnable() {
                public void run() {
                    try {
                        BufferedReader br = new BufferedReader(new InputStreamReader(is));
                        while (true) {
                            String line = br.readLine();
                            if (line == null)
                                break;
                            if (isError) {
                                System.err.println(line);
                            } else {
                                System.out.println(line);
                                if (line.startsWith(" ---> Running in ")) {
                                    String[] parts = splitByWhiteSpaces(line.trim());
                                    if (parts.length > 3) {
                                        String cntId = parts[parts.length - 1];
                                        cntIdToDelete[0] = cntId;
                                    }
                                } else if (line.startsWith(" ---> ")) {
                                    String[] parts = splitByWhiteSpaces(line.trim());
                                    if (parts.length > 1) {
                                        String imageId = parts[parts.length - 1];
                                        imageIdToDelete[0] = imageId;
                                    }
                                }
                            }
                        }
                        br.close();
                    } catch (Exception e) {
                        e.printStackTrace();
                        throw new IllegalStateException("Error reading data from executed " +
                        		"container", e);
                    }
                }
            });
            ret.start();
            workers.add(ret);
        }
        for (Thread t : workers)
            t.join();
        p.waitFor();
        int exitCode = p.exitValue();
        if (exitCode != 0) {
            try {
                if (cntIdToDelete[0] != null) {
                    System.out.println("Cleaning up building container: " + cntIdToDelete[0]);
                    Thread.sleep(1000);
                    ProcessHelper.cmd("bash", scriptPath, 
                            "rm", "-v", "-f", cntIdToDelete[0]).exec(repoDir);
                }
            } catch (Exception ex) {
                System.err.println(ex.getMessage());
            }
        }
        return exitCode == 0;
    }
}
