package us.kbase.sdk.runner;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.zafarkhaja.semver.Version;

import us.kbase.catalog.CatalogClient;
import us.kbase.catalog.ModuleVersion;
import us.kbase.catalog.SelectModuleVersion;
import us.kbase.common.service.UObject;
import us.kbase.sdk.ModuleBuilder;
import us.kbase.sdk.callback.CallbackProvenance;
import us.kbase.sdk.callback.CallbackServerManager;
import us.kbase.sdk.tester.ConfigLoader;
import us.kbase.sdk.util.DirUtils;
import us.kbase.sdk.util.ProcessHelper;

public class ModuleRunner {
    private final URL catalogUrl;
    private final File runDir;
    private final ConfigLoader cfgLoader;
    
    public ModuleRunner(String sdkHome) throws Exception {
        if (sdkHome == null) {
            sdkHome = System.getenv(ModuleBuilder.GLOBAL_SDK_HOME_ENV_VAR);
            if (sdkHome == null)
                throw new IllegalStateException("Path to kb-sdk home folder should be set either" +
                		" in command line (-h) or in " + ModuleBuilder.GLOBAL_SDK_HOME_ENV_VAR + 
                		" system environment variable");
        }
        File sdkHomeDir = new File(sdkHome);
        if (!sdkHomeDir.exists())
            sdkHomeDir.mkdirs();
        File sdkCfgFile = new File(sdkHomeDir, "sdk.cfg");
        String sdkCfgPath = sdkCfgFile.getCanonicalPath();
        if (!sdkCfgFile.exists()) {
            System.out.println("Warning: file " + sdkCfgFile.getAbsolutePath() + " will be "
                    + "initialized (with 'kbase_endpoint'/'catalog_url'/'auth_service_url' "
                    + "pointing to the AppDev environment). You will need to add your KBase "
                    + "developer token to the file before running a method");
            // TODO CODE client installer also writes the SDK config, plus several places in tests
            FileUtils.writeLines(sdkCfgFile, Arrays.asList(
                    "kbase_endpoint=https://appdev.kbase.us/services",
                    "catalog_url=https://appdev.kbase.us/services/catalog",
                    "auth_service_url=https://appdev.kbase.us/services/auth/api/legacy/"
                    + "KBase/Sessions/Login",
                    "token="
            ));
        }
        Properties sdkConfig = new Properties();
        try (InputStream is = new FileInputStream(sdkCfgFile)) {
            sdkConfig.load(is);
        }
        cfgLoader = new ConfigLoader(sdkConfig, false, sdkCfgPath);
        catalogUrl = new URL(cfgLoader.getCatalogUrl());
        runDir = new File(sdkHomeDir, "run_local");
    }
    
    public ModuleRunner(URL catalogUrl, File runDir, String[] callbackNetworks,
            ConfigLoader cfgLoader) {
        this.catalogUrl = catalogUrl;
        this.runDir = runDir;
        this.cfgLoader = cfgLoader;
    }
    
    public int run(String methodName, File inputFile, boolean stdin, String inputJson, 
            File output, String tagVer, boolean verbose, boolean keepTempFiles,
            String provRefs, boolean setFilesGloballyWriteable
            ) throws Exception {
        ////////////////////////////////// Loading image name /////////////////////////////////////
        CatalogClient client = new CatalogClient(catalogUrl);
        String moduleName = methodName.split(Pattern.quote("."))[0];
        ModuleVersion mv = client.getModuleVersion(
                new SelectModuleVersion().withModuleName(moduleName).withVersion(tagVer)
                .withIncludeCompilationReport(1L));
        if (mv.getDataVersion() != null)
            throw new IllegalStateException("Reference data is required for module " + moduleName +
            		". This feature is not supported for local calls.");
        String dockerImage = mv.getDockerImgName();
        System.out.println("Docker image name received from Catalog: " + dockerImage);
        ////////////////////////////////// Standard files in run_local ////////////////////////////
        if (!runDir.exists())
            runDir.mkdir();
        File runLocalSh = new File(runDir, "run_local.sh");
        File runDockerSh = new File(runDir, "run_docker.sh");
        if (!runLocalSh.exists()) {
            FileUtils.writeLines(runLocalSh, Arrays.asList(
                    "#!/bin/bash",
                    "sdir=\"$(cd \"$(dirname \"$(readlink -f \"$0\")\")\" && pwd)\"",
                    "callback_url=$1",
                    "cnt_id=$2",
                    "docker_image=$3",
                    "$sdir/run_docker.sh run " +
                    "-v $sdir/workdir:/kb/module/work " +
                    "-e \"SDK_CALLBACK_URL=$callback_url\" --name $cnt_id $docker_image async"));
            ProcessHelper.cmd("chmod", "+x", runLocalSh.getCanonicalPath()).exec(runDir);
        }
        if (!runDockerSh.exists()) {
            FileUtils.writeLines(runDockerSh, Arrays.asList(
                    "#!/bin/bash",
                    "docker \"$@\""));
            ProcessHelper.cmd("chmod", "+x", runDockerSh.getCanonicalPath()).exec(runDir);
        }
        ////////////////////////////////// Temporary files ////////////////////////////////////////
        File workDir = new File(runDir, "workdir");
        workDir.mkdir();
        File subjobsDir = new File(runDir, "subjobs");
        if (subjobsDir.exists())
            FileUtils.deleteDirectory(subjobsDir);
        File tokenFile = new File(workDir, "token");
        try (FileWriter fw = new FileWriter(tokenFile)) {
            fw.write(cfgLoader.getToken().getToken());
        }
        File configPropsFile = new File(workDir, "config.properties");
        cfgLoader.generateConfigProperties(configPropsFile);
        File scratchDir = new File(workDir, "tmp");
        scratchDir.mkdir();
        ////////////////////////////////// Preparing input.json ///////////////////////////////////
        String jsonString;
        if (inputFile != null) {
            jsonString = FileUtils.readFileToString(inputFile);
        } else if (inputJson != null) {
            jsonString = inputJson;
        } else if (stdin) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            IOUtils.copy(System.in, baos);
            jsonString = new String(baos.toByteArray(), Charset.forName("utf-8"));
        } else {
            throw new IllegalStateException("No one input method is used");
        }
        jsonString = jsonString.trim();
        if (!jsonString.startsWith("["))
            jsonString = "[" + jsonString + "]";  // Wrapping one argument by array
        if (verbose)
            System.out.println("Input parameters: " + jsonString);
        Map<String, Object> rpc = new LinkedHashMap<String, Object>();
        rpc.put("version", "1.1");
        rpc.put("method", methodName);
        List<UObject> params = UObject.getMapper().readValue(jsonString, 
                new TypeReference<List<UObject>>() {});
        rpc.put("params", params);
        rpc.put("context", new LinkedHashMap<String, Object>());
        UObject.getMapper().writeValue(new File(workDir, "input.json"), rpc);
        ////////////////////////////////// Starting callback service //////////////////////////////
        final Set<String> releaseTags = new TreeSet<String>();
        if (mv.getReleaseTags() != null)
            releaseTags.addAll(mv.getReleaseTags());
        final String requestedRelease = releaseTags.contains("release") ? "release" :
            (releaseTags.contains("beta") ? "beta" : "dev");
        final List<String> inputWsObjects = new ArrayList<>();
        if (provRefs != null) {
            inputWsObjects.addAll(Arrays.asList(provRefs.split(Pattern.quote(","))));
        }
        final CallbackServerManager csm = new CallbackServerManager(
                runDir.toPath(),
                new URL(cfgLoader.getEndPoint()),
                cfgLoader.getToken(),
                CallbackProvenance.getBuilder(methodName, Version.parse(mv.getVersion()))
                        .withServiceVer(requestedRelease)
                        .withCodeUrl(new URL(mv.getGitUrl()))
                        .withCommit(mv.getGitCommitHash())
                        .withParams(params
                                .stream()
                                .map(o -> o.asInstance())
                                .collect(Collectors.toList())
                        )
                        .withWorkspaceRefs(inputWsObjects)
                        .build(),
                setFilesGloballyWriteable
        );
        System.out.println(String.format("Local callback port: %s", csm.getCallbackPort()));
        System.out.println(String.format(
                "In container callback url: %s", csm.getInContainerCallbackUrl())
        );
        ////////////////////////////////// Running Docker /////////////////////////////////////////
        final String containerName = "local_" + moduleName.toLowerCase() + "_" + 
                System.currentTimeMillis();
        try {
            System.out.println();
            final int exitCode;
            try (csm) {
                exitCode = ProcessHelper.cmd(
                        "bash", DirUtils.getFilePath(runLocalSh),
                        csm.getInContainerCallbackUrl().toExternalForm(),
                        containerName,
                        dockerImage
                ).exec(runDir).getExitCode();
            }
            File outputTmpFile = new File(workDir, "output.json");
            if (!outputTmpFile.exists())
                throw new IllegalStateException("Output JSON file was not found");
            // TODO CODE change this to an internal class or use a map or something.
            //           untested so need to write tests first
            FinishJobParams outObj = UObject.getMapper().readValue(outputTmpFile, 
                    FinishJobParams.class);
            if (outObj.getError() != null || outObj.getResult() == null) {
                System.out.println();
                if (outObj.getError() == null) {
                    System.err.println("Unknown error (no information)");
                } else {
                    System.err.println("Error: " + outObj.getError().getMessage());
                    if (verbose && outObj.getError().getError() != null) {
                        System.err.println("Error details: \n" + outObj.getError().getError());
                    }
                }
                System.out.println();
            } else {
                String outputJson = UObject.getMapper().writeValueAsString(outObj.getResult());
                if (output != null) {
                    FileUtils.writeStringToFile(output, outputJson);
                    System.out.println("Output is saved to file: " + output.getCanonicalPath());
                } else {
                    System.out.println();
                    System.out.println("Output returned by the method:");
                    System.out.println(outputJson);
                    System.out.println();
                }
            }
            return exitCode;
        } finally {
            try {
                System.out.println("Deleteing docker container...");
                ProcessHelper.cmd("bash", DirUtils.getFilePath(runDockerSh), "rm", "-v", "-f",
                        containerName).exec(runDir);
            } catch (Exception ex) {
                System.out.println("Error deleting container [" + containerName + "]: " + 
                        ex.getMessage());
            }
            if (!keepTempFiles) {
                System.out.println("Deleting temporary files...");
                FileUtils.deleteDirectory(subjobsDir);
            }
        }
    }
}
