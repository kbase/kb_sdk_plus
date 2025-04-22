package us.kbase.test.sdk.initializer;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;

import us.kbase.auth.AuthToken;
import us.kbase.common.service.ServerException;
import us.kbase.common.service.UObject;
import us.kbase.mobu.initializer.ModuleInitializer;
import us.kbase.mobu.installer.ClientInstaller;
import us.kbase.mobu.tester.ModuleTester;
import us.kbase.mobu.util.ProcessHelper;
import us.kbase.mobu.util.TextUtils;
import us.kbase.test.sdk.TestUtils;
import us.kbase.test.sdk.scripts.TestConfigHelper;
import us.kbase.test.sdk.scripts.TypeGeneratorTest;

public class DockerClientServerTester {

    protected static final boolean cleanupAfterTests = true;
    
    private static final String INSTALLED_CLIENTS = "installed_clients";

    protected static List<Path> CREATED_MODULES = new ArrayList<Path>();
    protected static AuthToken token;
    
    @BeforeClass
    public static void beforeTesterClass() throws Exception {
        token = TestConfigHelper.getToken();
        TypeGeneratorTest.suppressJettyLogging();
    }
    
    // TODO TEST CODE pretty sure a lot of this stuff is duplicated in multiple places elsewhere
    //                also needs to be updated to more modern code
    @AfterClass
    public static void afterTesterClass() throws Exception {
        if (cleanupAfterTests)
            for (final Path moduleName: CREATED_MODULES)
                try {
                    deleteDir(moduleName.toFile());
                } catch (Exception ex) {
                    System.err.println("Error cleaning up module [" + 
                            moduleName + "]: " + ex.getMessage());
                }
    }
    
    @After
    public void afterText() {
        System.out.println();
    }
    
    private static void deleteDir(File module) throws Exception {
        if (module.exists() && module.isDirectory())
            FileUtils.deleteDirectory(module);
    }
    
    private File init(
            final String lang,
            final String moduleName,
            final String implFileRelative,
            final String implInitText
        ) throws Exception {
        final Path moduleDir = Paths.get(TestConfigHelper.getTempTestDir(), moduleName);
        deleteDir(moduleDir.toFile());
        CREATED_MODULES.add(moduleDir);
        TestUtils.createSdkCfgFile(Paths.get(TestConfigHelper.getTempTestDir()), moduleName);
        new ModuleInitializer(
                moduleName,
                token.getUserName(),
                lang,
                false,
                new File(TestConfigHelper.getTempTestDir()),
                true
        ).initialize(false);
        File specFile = new File(moduleDir.toFile(), moduleName + ".spec");
        String specText = FileUtils.readFileToString(specFile).replace("};", 
                "funcdef run_test(string input) returns (string) authentication required;\n" +
                "funcdef throw_error(string input) returns () authentication optional;\n};");
        FileUtils.writeStringToFile(specFile, specText);
        if (implFileRelative != null && implInitText != null) {
            final File implFile = moduleDir.resolve(implFileRelative).toFile();
            FileUtils.writeStringToFile(implFile, implInitText);
        }
        // Running make for new repo with adding <kb_sdk>/bin/kb-sdk into PATH
        File shellFile = new File(moduleDir.toFile(), "run_make.sh");
        String pathToSdk = new File(".").getCanonicalPath();
        List<String> lines = new ArrayList<String>(Arrays.asList("#!/bin/bash"));
        lines.add("export PATH=" + pathToSdk + "/build:$PATH");
        lines.add("which kb-sdk");
        lines.add("make");
        TextUtils.writeFileLines(lines, shellFile);
        if(ProcessHelper.cmd("bash", shellFile.getCanonicalPath()).exec(
                moduleDir.toFile()).getExitCode() != 0)
            throw new IllegalStateException("Error making " + moduleName + " repo");
        //ProcessHelper.cmd("make").exec(moduleDir);
        FileUtils.writeStringToFile(new File(moduleDir.toFile(), "sdk.cfg"), 
                "catalog_url=http://kbase.us");
        return moduleDir.toFile();
    }

    protected File initJava(String moduleName) throws Exception {
        String implInit = "" +
                "//BEGIN_HEADER\n" +
                "//END_HEADER\n" +
                "\n" +
                "    //BEGIN_CLASS_HEADER\n" +
                "    //END_CLASS_HEADER\n" +
                "\n" +
                "        //BEGIN_CONSTRUCTOR\n" +
                "        //END_CONSTRUCTOR\n" +
                "\n" +
                "        //BEGIN run_test\n" +
                "        returnVal = input;\n" +
                "        //END run_test\n" +
                "\n" +
                "        //BEGIN throw_error\n" +
                "        throw new Exception(input);\n" +
                "        //END throw_error\n";
        final String implFile = "lib/src/" + moduleName.toLowerCase() + "/" + 
                moduleName + "Server.java";
        return init("java", moduleName, implFile, implInit);
    }
    
    protected File initPython(String moduleName) throws Exception {
        String implInit = "" +
                "#BEGIN_HEADER\n" +
                "#END_HEADER\n" +
                "\n" +
                "    #BEGIN_CLASS_HEADER\n" +
                "    #END_CLASS_HEADER\n" +
                "\n" +
                "        #BEGIN_CONSTRUCTOR\n" +
                "        #END_CONSTRUCTOR\n" +
                "\n" +
                "        #BEGIN run_test\n" +
                "        returnVal = input\n" +
                "        #END run_test\n" +
                "\n" +
                "        #BEGIN throw_error\n" +
                "        raise ValueError(input)\n" +
                "        #END throw_error\n";
        final String implFile = "lib/" + moduleName + "/" + moduleName + "Impl.py";
        return init("python", moduleName, implFile, implInit);
    }
    
    protected static String prepareDockerImage(File moduleDir, 
            AuthToken token) throws Exception {
        String moduleName = moduleDir.getName();
        File testCfgFile = new File(moduleDir, "test_local/test.cfg");
        String testCfgText = ""+
                "test_token=" + token.getToken() + "\n" +
                "kbase_endpoint=" + TestConfigHelper.getKBaseEndpoint() + "\n" +
                "auth_service_url=" + TestConfigHelper.getAuthServiceUrlLegacy() + "\n" +
                "auth_service_url_allow_insecure=" + 
                TestConfigHelper.getAuthServiceUrlInsecure() + "\n";
        FileUtils.writeStringToFile(testCfgFile, testCfgText);
        File tlDir = new File(moduleDir, "test_local");
        File workDir = new File(tlDir, "workdir");
        workDir.mkdir();
        File tokenFile = new File(workDir, "token");
        FileWriter fw = new FileWriter(tokenFile);
        try {
            fw.write(token.getToken());
        } finally {
            fw.close();
        }
        File runDockerSh = new File(tlDir, "run_docker.sh");
        ProcessHelper.cmd("chmod", "+x", runDockerSh.getCanonicalPath()).exec(tlDir);
        String imageName = "test/" + moduleName.toLowerCase() + ":latest";
        if (!ModuleTester.buildNewDockerImageWithCleanup(moduleDir, tlDir, runDockerSh, 
                imageName))
            throw new IllegalStateException("Error building docker image");
        return imageName;
    }
	
	protected static void testClients(
			final File moduleDir,
			final String clientEndpointUrl,
			final boolean async,
			final boolean dynamic,
			final String serverType)
					throws Exception {
		String moduleName = moduleDir.getName();
		File specFile = new File(moduleDir, moduleName + ".spec");
		ClientInstaller clInst = new ClientInstaller(moduleDir, true);
		String input = "Super-string";
		// Java client
		{
			System.out.print("Java client -> " + serverType + " server ");
			clInst.install("java", async, false, dynamic, "dev", false, 
					specFile.getCanonicalPath(), "lib2");
			File binDir = new File(moduleDir, "bin");
			if (!binDir.exists())
				binDir.mkdir();
			File srcDir = new File(moduleDir, "lib2/src/" + INSTALLED_CLIENTS);
			File clientJavaFile = new File(srcDir, moduleName.toLowerCase() + "/" +
					moduleName + "Client.java");
			String classPath = System.getProperty("java.class.path");
			ProcessHelper.cmd("javac", "-g:source,lines", "-d", binDir.getCanonicalPath(), 
					"-sourcepath", srcDir.getCanonicalPath(), "-cp", classPath, 
					"-Xlint:deprecation").add(clientJavaFile.getCanonicalPath())
			.exec(moduleDir);
			List<URL> cpUrls = new ArrayList<URL>();
			cpUrls.add(binDir.toURI().toURL());
			URLClassLoader urlcl = URLClassLoader.newInstance(cpUrls.toArray(
					new URL[cpUrls.size()]));
			String clientClassName = moduleName.toLowerCase() + "." + moduleName + "Client";
			Class<?> clientClass = urlcl.loadClass(clientClassName);
			Object client = clientClass.getConstructor(URL.class, AuthToken.class)
					.newInstance(new URL(clientEndpointUrl), token);
			clientClass.getMethod("setIsInsecureHttpConnectionAllowed", Boolean.TYPE).invoke(client, true);
			Method method = null;
			for (Method m : client.getClass().getMethods())
				if (m.getName().equals("runTest"))
					method = m;
			Object obj = null;
			Exception error = null;
			int javaAttempts = dynamic ? 10 : 1;
			long time = -1;
			long startTime = -1;
			for (int i = 0; i < javaAttempts; i++) {
				try {
					startTime = System.currentTimeMillis();
					obj = method.invoke(client, input, null);
					error = null;
					break;
				} catch (Exception ex) {
					error = ex;
				}
				Thread.sleep(100);
			}
			method = null;
			for (Method m : client.getClass().getMethods())
				if (m.getName().equals("throwError"))
					method = m;
			try {
				method.invoke(client, input, null);
				error = new Exception("Method throwError should fail");
			} catch (Exception ex) {
				if (ex instanceof InvocationTargetException) {
					ex = (Exception)ex.getCause();
				}
				if (ex instanceof ServerException) {
					final String data = ((ServerException) ex).getData();
					if (data == null) {
						error = new Exception("ServerException has no data field. Message: "
								+ ex.getMessage(), ex);
					}
					else if (!data.contains(input)) {
						error = new Exception("Server error doesn't include expected text: " + data);
					}
					// input string is found in server error data
				} else {
					error = new Exception("Unexpected error type: " + ex.getClass().getName());
				}
			}
			time = System.currentTimeMillis() - startTime;
			System.out.println("(" + time + " ms)");
			if (error != null)
				throw error;
			Assert.assertNotNull(obj);
			Assert.assertTrue(obj instanceof String);
			Assert.assertEquals(input, obj);
		}
		// Common non-java preparation
		// Note 24/12/13: No longer common, just python
		Map<String, Object> config = new LinkedHashMap<String, Object>();
		config.put("package", moduleName + "Client");
		config.put("class", moduleName);
		Map<String, Object> test1 = new LinkedHashMap<String, Object>();
		test1.put("method", "run_test");
		test1.put("auth", true);
		test1.put("params", Arrays.asList(input));
		test1.put("outcome", UObject.getMapper().readValue("{\"status\":\"pass\"}", Map.class));
		Map<String, Object> test2 = new LinkedHashMap<String, Object>();
		test2.put("method", "throw_error");
		test2.put("auth", true);
		test2.put("params", Arrays.asList(input));
		test2.put("outcome", UObject.getMapper().readValue("{\"status\":\"fail\", " +
				"\"error\": [\"" + input + "\"]}", Map.class));
		config.put("tests", Arrays.asList(test1, test2));
		File configFile = new File(moduleDir, "tests.json");
		UObject.getMapper().writeValue(configFile, config);
		File lib2 = new File(moduleDir, "lib2");
		// Python client
		System.out.print("Python client -> " + serverType + " server ");
		clInst.install("python", async, false, dynamic, "dev", false, 
				specFile.getCanonicalPath(), "lib2");
		File shellFile = new File(moduleDir, "test_python_client.sh");
		List<String> lines = new ArrayList<String>(Arrays.asList("#!/bin/bash"));
		lines.add("python " + new File("test_scripts/python/test_client.py").getAbsolutePath() + 
				" -t " + configFile.getAbsolutePath() + " -o " + token.getToken() +
				" -e " + clientEndpointUrl);
		TextUtils.writeFileLines(lines, shellFile);
		{
			long startTime = System.currentTimeMillis();
			ProcessHelper ph = ProcessHelper.cmd("bash", shellFile.getCanonicalPath()).exec(
					new File(lib2, INSTALLED_CLIENTS).getCanonicalFile(), null, true, true);
			System.out.println("(" + (System.currentTimeMillis() - startTime) + " ms)");
			int exitCode = ph.getExitCode();
			if (exitCode != 0) {
				String out = ph.getSavedOutput();
				if (!out.isEmpty())
					System.out.println("Python client output:\n" + out);
				String err = ph.getSavedErrors();
				if (!err.isEmpty())
					System.err.println("Python client errors:\n" + err);
			}
			Assert.assertEquals("Python client exit code should be 0", 0, exitCode);
		}
	}

	protected static void testStatus(
			final File moduleDir,
			final String clientEndpointUrl,
			final boolean async,
			final boolean dynamic,
			final String serverType)
			throws Exception {
		String moduleName = moduleDir.getName();
		File specFile = new File(moduleDir, moduleName + ".spec");
		// Java client
		System.out.println("Java client (status) -> " + serverType + " server");
		ClientInstaller clInst = new ClientInstaller(moduleDir, true);
		clInst.install("java", async, false, dynamic, "dev", false, 
				specFile.getCanonicalPath(), "lib2");
		File binDir = new File(moduleDir, "bin");
		if (!binDir.exists())
			binDir.mkdir();
		File srcDir = new File(moduleDir, "lib2/src/" + INSTALLED_CLIENTS);
		File clientJavaFile = new File(srcDir, moduleName.toLowerCase() + "/" +
				moduleName + "Client.java");
		String classPath = System.getProperty("java.class.path");
		ProcessHelper.cmd("javac", "-g:source,lines", "-d", binDir.getCanonicalPath(), 
				"-sourcepath", srcDir.getCanonicalPath(), "-cp", classPath, 
				"-Xlint:deprecation").add(clientJavaFile.getCanonicalPath())
		.exec(moduleDir);
		List<URL> cpUrls = new ArrayList<URL>();
		cpUrls.add(binDir.toURI().toURL());
		URLClassLoader urlcl = URLClassLoader.newInstance(cpUrls.toArray(
				new URL[cpUrls.size()]));
		String clientClassName = moduleName.toLowerCase() + "." + moduleName + "Client";
		Object client;
		if (async) {
			Class<?> clientClass = urlcl.loadClass(clientClassName);
			client = clientClass.getConstructor(URL.class, AuthToken.class)
					.newInstance(new URL(clientEndpointUrl), token);
			clientClass.getMethod("setIsInsecureHttpConnectionAllowed", Boolean.TYPE).invoke(client, true);

		} else {
			Class<?> clientClass = urlcl.loadClass(clientClassName);
			client = clientClass.getConstructor(URL.class)
					.newInstance(new URL(clientEndpointUrl));
		}
		Method method = null;
		for (Method m : client.getClass().getMethods())
			if (m.getName().equals("status"))
				method = m;
		Object obj = null;
		Exception error = null;
		int javaAttempts = dynamic ? 10 : 1;
		for (int i = 0; i < javaAttempts; i++) {
			try {
				obj = method.invoke(client, (Object)null);
				error = null;
				break;
			} catch (Exception ex) {
				error = ex;
			}
			Thread.sleep(100);
		}
		if (error != null)
			throw error;
		checkStatusResponse(obj);
		// Common non-java preparation
		// Note 24/12/13: No longer common, python only
		String pcg = moduleName + "Client";
		String cls = moduleName;
		String mtd = "status";
		File inputFile = new File(moduleDir, "status_input.json");
		FileUtils.writeStringToFile(inputFile, "[]");
		File outputFile = new File(moduleDir, "status_output.json");
		File errorFile = new File(moduleDir, "status_error.json");
		File lib2 = new File(moduleDir, "lib2");
		// Python
		System.out.println("Python client (status) -> " + serverType + " server");
		clInst.install("python", async, false, dynamic, "dev", false, 
				specFile.getCanonicalPath(), "lib2");
		{
			File shellFile = new File(moduleDir, "test_python_client.sh");
			List<String> lines = new ArrayList<String>(Arrays.asList("#!/bin/bash"));
			lines.add("python " + new File("test_scripts/python/run_client.py").getAbsolutePath() +
					" -e " + clientEndpointUrl + " -g " + pcg + " -c " + cls + " -m " + mtd + 
					" -i " + inputFile.getAbsolutePath() + " -o " + outputFile.getAbsolutePath() + 
					" -r " + errorFile.getAbsolutePath() + 
					(async ? (" -t " + token.getToken()) : ""));
			TextUtils.writeFileLines(lines, shellFile);
			ProcessHelper ph = ProcessHelper.cmd("bash", shellFile.getCanonicalPath()).exec(
					new File(lib2, INSTALLED_CLIENTS).getCanonicalFile(), null, true, true);
			int exitCode = ph.getExitCode();
			if (exitCode != 0) {
				String out = ph.getSavedOutput();
				if (!out.isEmpty())
					System.out.println("Python client runner output:\n" + out);
				String err = ph.getSavedErrors();
				if (!err.isEmpty())
					System.err.println("Python client runner errors:\n" + err);
				Assert.assertEquals("Python client runner exit code should be 0", 0, exitCode);
			} else {
				checkStatusResponse(outputFile, errorFile);
			}
		}
	}

    private static void checkStatusResponse(File output, File error) throws Exception {
        if (!output.exists()) {
            String msg = error.exists() ? FileUtils.readFileToString(error) :
                "Unknown error (error file wasn't created)";
            throw new IllegalStateException(msg);
        }
        checkStatusResponse(UObject.getMapper().readValue(output, Object.class));
    }

    private static void checkStatusResponse(Object obj) throws Exception {
        Assert.assertNotNull(obj);
        String errMsg = "Unexpected response: " + UObject.transformObjectToString(obj);
        if (obj instanceof List) {
            @SuppressWarnings("rawtypes")
            List<?> list = (List)obj;
            Assert.assertEquals(errMsg, 1, list.size());
            obj = list.get(0);
        }
        Assert.assertTrue(obj instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, String> map = (Map<String, String>)obj;
        Assert.assertEquals(errMsg, "OK", map.get("state"));
        Assert.assertTrue(errMsg, map.containsKey("version"));
        Assert.assertTrue(errMsg, map.containsKey("git_url"));
        Assert.assertTrue(errMsg, map.containsKey("git_commit_hash"));
    }
}
