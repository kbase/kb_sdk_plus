package us.kbase.kbasejobservice;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

import us.kbase.auth.AuthToken;
import us.kbase.common.service.JacksonTupleModule;
import us.kbase.common.service.JsonServerServlet;
import us.kbase.common.service.JsonServerSyslog;

//BEGIN_HEADER

// There's a similar class in us.kbase.test.sdk.initializer.ExecEngineMock

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang.exception.ExceptionUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import us.kbase.common.service.UObject;
import us.kbase.mobu.util.ProcessHelper;

//END_HEADER

/**
 * <p>Original spec-file module name: KBaseJobService</p>
 * <pre>
 * </pre>
 */
public class KBaseJobServiceServer extends JsonServerServlet {
    private static final long serialVersionUID = 1L;

    //BEGIN_CLASS_HEADER
    private int lastJobId = 0;
    private final Map<String, FinishJobParams> results = new LinkedHashMap<>();
    private File binDir = null;
    private File tempDir = null;
    
    public KBaseJobServiceServer withBinDir(File binDir) {
        this.binDir = binDir;
        return this;
    }
    
    public KBaseJobServiceServer withTempDir(File tempDir) {
        this.tempDir = tempDir;
        return this;
    }
    
    private String getBinScript(String scriptName) {
        File ret = null;
        if (binDir == null) {
            ret = new File(".", scriptName);
            if (!ret.exists())
                return scriptName;
        } else {
            ret = new File(binDir, scriptName);
        }
        return ret.getAbsolutePath();
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
                    RunJobParams runJobParams = new RunJobParams();
                    String serviceVer = rpcCallData.getContext() == null ? null : 
                        (String)rpcCallData.getContext().getAdditionalProperties().get("service_ver");
                    runJobParams.setServiceVer(serviceVer);
                    runJobParams.setMethod(origRpcName);
                    runJobParams.setParams(paramsList);
                    result = new ArrayList<Object>(); 
                    result.add(runJob(runJobParams, t));
                } else if (rpcName.endsWith("._check_job") && paramsList.size() == 1) {
                    String jobId = paramsList.get(0).asClassInstance(String.class);
                    FinishJobParams fjp = results.get(jobId);
                    if (fjp != null && fjp.getError() != null) {
                        Map<String, Object> ret = new LinkedHashMap<String, Object>();
                        ret.put("version", "1.1");
                        ret.put("error", fjp.getError());
                        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                        mapper.writeValue(new UnclosableOutputStream(output), ret);
                        return;
                    }
                    final Map<String, Object> res = new HashMap<>();
                    res.put("finished", fjp == null ? 0 : 1);
                    res.put("result", fjp != null ? fjp.getResult(): null);
                    res.put("error", fjp != null ? fjp.getError() : null);
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
    //END_CLASS_HEADER

    public KBaseJobServiceServer() throws Exception {
        super("KBaseJobService");
        //BEGIN_CONSTRUCTOR
        //END_CONSTRUCTOR
    }

    private String runJob(RunJobParams params, AuthToken authPart) throws Exception {
        lastJobId++;
        final String jobId = "" + lastJobId;
        final AuthToken token = authPart;
        final File jobDir = new File(tempDir, "job_" + jobId);
        if (!jobDir.exists())
            jobDir.mkdirs();
        File jobFile = new File(jobDir, "job.json");
        UObject.getMapper().writeValue(jobFile, params);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final File jobDir = new File(tempDir, "job_" + jobId);
                    final File jobFile = new File(jobDir, "job.json");
                    final RunJobParams job = UObject.getMapper().readValue(jobFile, RunJobParams.class);
                    String serviceName = job.getMethod().split("\\.")[0];
                    if (!jobDir.exists()) {
                        jobDir.mkdirs();
                    }
                    Map<String, Object> rpc = new LinkedHashMap<String, Object>();
                    rpc.put("version", "1.1");
                    rpc.put("method", job.getMethod());
                    rpc.put("params", job.getParams());
                    File inputFile = new File(jobDir, "input.json");
                    UObject.getMapper().writeValue(inputFile, rpc);
                    String scriptFilePath = getBinScript("run_" + serviceName + "_async_job.sh");
                    File outputFile = new File(jobDir, "output.json");
                    ProcessHelper.cmd("bash", scriptFilePath, inputFile.getCanonicalPath(),
                            outputFile.getCanonicalPath(), token.getToken()).exec(jobDir);
                    FinishJobParams result = UObject.getMapper().readValue(outputFile, FinishJobParams.class);
                    results.put(jobId, result);
                } catch (Exception ex) {
                    FinishJobParams result = new FinishJobParams().withError(new JsonRpcError().withCode(-1L)
                            .withName("JSONRPCError").withMessage("Job service side error: " + ex.getMessage()));
                    results.put(jobId, result);
                }
            }
        }).start();
        return jobId;
    }
}
