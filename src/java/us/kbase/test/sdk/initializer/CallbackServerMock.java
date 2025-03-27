package us.kbase.test.sdk.initializer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;

import us.kbase.auth.AuthToken;
import us.kbase.common.service.JacksonTupleModule;
import us.kbase.common.service.JsonServerServlet;
import us.kbase.common.service.JsonServerSyslog;

//BEGIN_HEADER


import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;

import us.kbase.common.service.UObject;
import us.kbase.mobu.util.DirUtils;
import us.kbase.mobu.util.ProcessHelper;
import us.kbase.test.sdk.scripts.TestConfigHelper;

//END_HEADER

/**
 * <p>Original spec-file module name: KBaseJobService</p>
 * <pre>
 * </pre>
 */
public class CallbackServerMock extends JsonServerServlet {
    private static final long serialVersionUID = 1L;

    //BEGIN_CLASS_HEADER
    private int lastJobId = 0;
    private String kbaseEndpoint = null;
    private Map<String, String> jobToModule = new LinkedHashMap<String, String>();
    private Map<String, File> jobToWorkDir = new LinkedHashMap<String, File>();
    private Map<String, Thread> jobToWorker = new LinkedHashMap<String, Thread>();
    private Map<String, Map<String, Object>> jobToResults = new LinkedHashMap<>();
    private Map<String, String> moduleToDockerImage = new LinkedHashMap<String, String>();
    private Map<String, File> moduleToRepoDir = new LinkedHashMap<String, File>();
    private static boolean debugCheckJobTimes = false;
    private Long lastCheckJobAccessTime = null;
    
    public CallbackServerMock withModule(String moduleName, String dockerImage, File repoDir) {
        moduleToDockerImage.put(moduleName, dockerImage);
        moduleToRepoDir.put(moduleName, repoDir);
        return this;
    }
    
    public CallbackServerMock withKBaseEndpoint(String endpoint) {
        this.kbaseEndpoint = endpoint;
        return this;
    }
    
    protected void processRpcCall(RpcCallData rpcCallData, String token, JsonServerSyslog.RpcInfo info, 
            String requestHeaderXForwardedFor, ResponseStatusSetter response, OutputStream output,
            boolean commandLine) {
        if (rpcCallData.getMethod().startsWith("NarrativeJobService.")) {
            super.processRpcCall(rpcCallData, token, info, requestHeaderXForwardedFor, response, output, commandLine);
        } else {
            String rpcName = rpcCallData.getMethod();
            List<UObject> paramsList = rpcCallData.getParams();
            List<Object> result = null;
            ObjectMapper mapper = new ObjectMapper().registerModule(new JacksonTupleModule());
            Exception exc = null;
            try {
                final AuthToken t = new AuthToken(token, "<unknown>");
                if (rpcName.endsWith("_submit")) {
                    String origRpcName = rpcName.substring(0, rpcName.lastIndexOf('_'));
                    String[] parts = origRpcName.split(Pattern.quote("."));
                    if (!parts[1].startsWith("_"))
                        throw new IllegalStateException("Unexpected method name: " + rpcName);
                    origRpcName = parts[0] + "." + parts[1].substring(1);
                    String serviceVer = rpcCallData.getContext() == null ? null : 
                        (String)rpcCallData.getContext().getAdditionalProperties().get("service_ver");
                    final Map<String, Object> runJobParams = new HashMap<>();
                    runJobParams.put("service_ver", serviceVer);
                    runJobParams.put("method", origRpcName);
                    runJobParams.put("params", paramsList);
                    result = new ArrayList<Object>(); 
                    result.add(runJob(runJobParams, t));
                } else if (rpcName.endsWith("._check_job") && paramsList.size() == 1) {
                    String jobId = paramsList.get(0).asClassInstance(String.class);
                    if (lastCheckJobAccessTime != null && debugCheckJobTimes) {
                        System.out.println("ExecEngineMock.checkJob: time = " + (System.currentTimeMillis() - lastCheckJobAccessTime));
                    }
                    lastCheckJobAccessTime = System.currentTimeMillis();
                    Map<String, Object> fjp = jobToResults.get(jobId);
                    if (fjp != null && fjp.get("error") != null) {
                        Map<String, Object> ret = new LinkedHashMap<String, Object>();
                        ret.put("version", "1.1");
                        ret.put("error", fjp.get("error"));
                        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                        mapper.writeValue(new UnclosableOutputStream(output), ret);
                        return;
                    }
                    final Map<String, Object> res = new HashMap<>();
                    res.put("finished", fjp == null ? 0 : 1);
                    res.put("result", fjp != null ? fjp.get("result"): null);
                    res.put("error", fjp != null ? fjp.get("error") : null);
                    result = Arrays.asList((Object) res);
                } else {
                    throw new IllegalArgumentException("Method [" + rpcName +
                            "] doesn't ends with \"_submit\" or \"_check_job\" suffix");
                }
                Map<String, Object> ret = new LinkedHashMap<String, Object>();
                ret.put("version", "1.1");
                ret.put("result", result);
                mapper.writeValue(new UnclosableOutputStream(output), ret);
                return;
            } catch (Exception ex) {
                exc = ex;
            }
            try {
                Map<String, Object> error = new LinkedHashMap<String, Object>();
                error.put("name", "JSONRPCError");
                error.put("code", -32601);
                error.put("message", exc.getLocalizedMessage());
                error.put("error", ExceptionUtils.getStackTrace(exc));
                Map<String, Object> ret = new LinkedHashMap<String, Object>();
                ret.put("version", "1.1");
                ret.put("error", error);
                mapper.writeValue(new UnclosableOutputStream(output), ret);
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            } catch (Exception ex) {
                new Exception("Error sending error: " +
                        exc.getLocalizedMessage(), ex).printStackTrace();
            }
        }
    }
    
    private static class UnclosableOutputStream extends OutputStream {
        OutputStream inner;
        boolean isClosed = false;
        
        public UnclosableOutputStream(OutputStream inner) {
            this.inner = inner;
        }
        
        @Override
        public void write(int b) throws IOException {
            if (isClosed)
                return;
            inner.write(b);
        }
        
        @Override
        public void close() throws IOException {
            isClosed = true;
        }
        
        @Override
        public void flush() throws IOException {
            inner.flush();
        }
        
        @Override
        public void write(byte[] b) throws IOException {
            if (isClosed)
                return;
            inner.write(b);
        }
        
        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (isClosed)
                return;
            inner.write(b, off, len);
        }
    }
    
    public void waitAndCleanAllJobs() throws Exception {
        for (String jobId : new ArrayList<String>(jobToModule.keySet())) {
            String moduleName = jobToModule.get(jobId);
            Thread t = jobToWorker.get(jobId);
            t.join();
            jobToModule.remove(jobId);
            jobToResults.remove(jobId);
            jobToWorkDir.remove(jobId);
            jobToWorker.remove(jobId);
            moduleToDockerImage.remove(moduleName);
            moduleToRepoDir.remove(moduleName);
        }
    }
    //END_CLASS_HEADER

    public CallbackServerMock() throws Exception {
        super("NarrativeJobService");
        //BEGIN_CONSTRUCTOR
        //END_CONSTRUCTOR
    }

    private String runJob(Map<String, Object> params, AuthToken authPart) throws Exception {
        lastJobId++;
        final String jobId = "" + lastJobId;
        final AuthToken token = authPart;
        String moduleName = ((String)params.get("method")).split(Pattern.quote("."))[0];
        jobToModule.put(jobId, moduleName);
        File moduleDir = moduleToRepoDir.get(moduleName);
        File testLocalDir = new File(moduleDir, "test_local");
        final File jobDir = new File(testLocalDir, "job_" + jobId);
        if (!jobDir.exists())
            jobDir.mkdirs();
        jobToWorkDir.put(jobId, jobDir);
        File jobFile = new File(jobDir, "job.json");
        UObject.getMapper().writeValue(jobFile, params);
        final String dockerImage = moduleToDockerImage.get(moduleName);
        Map<String, Object> rpc = new LinkedHashMap<String, Object>();
        rpc.put("version", "1.1");
        rpc.put("method", params.get("method"));
        rpc.put("params", params.get("params"));
        File tokenFile = new File(jobDir, "token");
        FileUtils.writeStringToFile(tokenFile, token.getToken());
        File inputFile = new File(jobDir, "input.json");
        UObject.getMapper().writeValue(inputFile, rpc);
        final File outputFile = new File(jobDir, "output.json");
        final String containerName = "test_" + moduleName.toLowerCase() + "_" + 
                System.currentTimeMillis();
        File runDockerSh = new File(testLocalDir, "run_docker.sh");
        final String runDockerPath = DirUtils.getFilePath(runDockerSh);
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ProcessHelper.cmd("bash", runDockerPath, "run", "-v", 
                            jobDir.getCanonicalPath() + ":/kb/module/work", 
                            "--name", containerName, "-e", "KBASE_ENDPOINT=" + kbaseEndpoint,
                            "-e", "AUTH_SERVICE_URL=" + TestConfigHelper.getAuthServiceUrl(),
                            "-e", "AUTH_SERVICE_URL_ALLOW_INSECURE=" + 
                                    TestConfigHelper.getAuthServiceUrlInsecure(),
                                    dockerImage, "async").exec(jobDir, 
                                            null, (PrintWriter)null, null);
                    if (!outputFile.exists()) {
                        ProcessHelper.cmd("bash", runDockerPath, "logs", containerName).exec(jobDir);
                        throw new IllegalStateException("Output file wasn't created");
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> result = UObject.getMapper().readValue(
                            outputFile, Map.class
                    );
                    jobToResults.put(jobId, result);
                } catch (Exception ex) {
                    ex.printStackTrace();
                    final Map<String, Object> result = ImmutableMap.of("error",
                            (Object) ImmutableMap.of(
                                    "code", -1L,
                                    "name", "JSONRPCError",
                                    "message", "Job service side error: " + ex.getMessage()
                            )
                    );
                    jobToResults.put(jobId, result);
                } finally {
                    try {
                        ProcessHelper.cmd("bash", runDockerPath, "rm", "-v", "-f", 
                                containerName).exec(jobDir, null, (PrintWriter)null, null);
                    } catch (Exception ex) {
                        System.out.println("Error deleting container [" + containerName + "]: " + ex.getMessage());
                    }
                }
            }
        });
        t.start();
        jobToWorker.put(jobId, t);
        return jobId;
    }
}
