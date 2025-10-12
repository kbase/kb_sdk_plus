package us.kbase.sdk.tester;

import java.io.File;
import java.io.PrintWriter;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import us.kbase.auth.AuthToken;
import us.kbase.auth.client.AuthClient;

public class ConfigLoader {
    private final String authUrl;
    private final String authAllowInsecure;
    private final AuthToken token;
    private final String endPoint;
    private final String jobSrvUrl;
    private final String wsUrl;
    private final String shockUrl;
    private final String handleUrl;
    private final String srvWizUrl;
    private final String njswUrl;
    private final String catalogUrl;
    private final Map<String, String> secureCfgParams;

    public ConfigLoader(Properties props, boolean testMode, String configPathInfo
            ) throws Exception {
        if (configPathInfo == null) {
            throw new IllegalArgumentException("configPathInfo is required");
        }
        authUrl = props.getProperty("auth_service_url");
        if (authUrl == null)
            throw new IllegalStateException("Error: 'auth_service_url' parameter is not set in " +
                    configPathInfo);
        String testPrefix = testMode ? "test_" : "";
        String tokenString = props.getProperty(testPrefix + "token");
        if (tokenString != null && tokenString.trim().isEmpty()) {
            tokenString = null;
        }
        if (tokenString == null) {
            throw new IllegalStateException("Error: KBase account credentials are not set in " +
                    configPathInfo);
        }
        authAllowInsecure = props.getProperty("auth_service_url_allow_insecure");
        final String authUrlTrimmed = authUrl.split("api/")[0];
        final AuthClient auth;
        try {
            auth = AuthClient.from(new URI(authUrlTrimmed));
        } catch (UnknownHostException e) {
            // user gets a crummy default error with an unknown host exception
            throw new IllegalStateException(
                    String.format("Could not contact the KBase auth server at %s", authUrlTrimmed),
                    e
            );
        }
        token = auth.validateToken(tokenString);
        endPoint = props.getProperty("kbase_endpoint");
        if (endPoint == null) {
            throw new IllegalStateException("Error: KBase services end-point is not set in " +
                    configPathInfo);
        }
        jobSrvUrl = getConfigUrl(props, "job_service_url", endPoint, "userandjobstate");
        wsUrl = getConfigUrl(props, "workspace_url", endPoint, "ws");
        shockUrl = getConfigUrl(props, "shock_url", endPoint, "shock-api");
        handleUrl = getConfigUrl(props, "handle_url", endPoint, "handle_service");
        srvWizUrl = getConfigUrl(props, "srv_wiz_url", endPoint, "service_wizard");
        njswUrl = getConfigUrl(props, "njsw_url", endPoint, "njs_wrapper");
        catalogUrl = getConfigUrl(props, "catalog_url", endPoint, "catalog");
        secureCfgParams = new TreeMap<String, String>();
        for (Object propObj : props.keySet()) {
            String propName = propObj.toString();
            if (propName.startsWith("secure.")) {
                String paramName = propName.substring(7);
                secureCfgParams.put(paramName, props.getProperty(propName));
            }
        }
    }
    
    public String getAuthUrl() {
        return authUrl;
    }
    
    public String getAuthAllowInsecure() {
        return authAllowInsecure;
    }
    
    public AuthToken getToken() {
        return token;
    }
    
    public String getEndPoint() {
        return endPoint;
    }
    
    public String getCatalogUrl() {
        return catalogUrl;
    }
    
    public String getHandleUrl() {
        return handleUrl;
    }
    
    public String getJobSrvUrl() {
        return jobSrvUrl;
    }
    
    public String getNjswUrl() {
        return njswUrl;
    }
    
    public String getShockUrl() {
        return shockUrl;
    }
    
    public String getSrvWizUrl() {
        return srvWizUrl;
    }
    
    public String getWsUrl() {
        return wsUrl;
    }
    
    public void generateConfigProperties(File configPropsFile) throws Exception {
        PrintWriter pw = new PrintWriter(configPropsFile);
        try {
            pw.println("[global]");
            pw.println("kbase_endpoint = " + endPoint);
            pw.println("job_service_url = " + jobSrvUrl);
            pw.println("workspace_url = " + wsUrl);
            pw.println("shock_url = " + shockUrl);
            pw.println("handle_url = " + handleUrl);
            pw.println("srv_wiz_url = " + srvWizUrl);
            pw.println("njsw_url = " + njswUrl);
            pw.println("auth_service_url = " + authUrl);
            pw.println("auth_service_url_allow_insecure = " + 
                    (authAllowInsecure == null ? "false" : authAllowInsecure));
            for (String param : secureCfgParams.keySet()) {
                pw.println(param + " = " + secureCfgParams.get(param));
            }
        } finally {
            pw.close();
        }
    }
    
    private static String getConfigUrl(Properties props, String key, String endPoint, 
            String defaultUrlSuffix) {
        String ret = props.getProperty(key);
        return ret == null ? (endPoint + "/" + defaultUrlSuffix) : ret;
    }
}
