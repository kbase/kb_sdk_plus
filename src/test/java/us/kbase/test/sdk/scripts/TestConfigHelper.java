package us.kbase.test.sdk.scripts;

import java.io.File;
import java.net.URI;

import org.ini4j.Ini;

import us.kbase.auth.AuthToken;
import us.kbase.auth.client.AuthClient;

public class TestConfigHelper {
    public static final String TEST_CFG = "kb_sdk_test";

    private static boolean initialized = false;
    private static AuthToken token1 = null;
    private static AuthToken token2 = null;
    
    public static void init() throws Exception {
        final Ini testini = new Ini(new File("test.cfg"));
        for (Object key: testini.get(TEST_CFG).keySet()) {
            String prop = key.toString();
            String value = testini.get(TEST_CFG, key);
            System.setProperty(prop, value);
        }
        initialized = true;
    }
    
    private static String getTestConfigParam(String param, boolean required) throws Exception {
        if (!initialized) {
            init();
        }
        String ret = System.getProperty(param);
        if (required && ret == null) {
            throw new IllegalStateException("Parameter [" + param + "] is not set " +
                   "in test configuration. Please check test.cfg file");
        }
        return ret;
    }

    private static String getTestConfigParam(String param, String defaultValue) throws Exception {
        String ret = getTestConfigParam(param, false);
        return ret == null ? defaultValue : ret;
    }
    
    public static String getAuthServiceUrlLegacy() throws Exception {
        return getAuthServiceUrl().replaceAll("/+$", "") + "/api/legacy/KBase/Sessions/Login";
    }
    
    public static String getAuthServiceUrl() throws Exception {
        return getTestConfigParam("test.auth-service-url", true);
    }
    
    public static String getAuthServiceUrlInsecure() throws Exception {
        return getTestConfigParam("test.auth-service-url-allow-insecure", "false");
    }
    
    private static AuthClient getAuthService() throws Exception {
        return AuthClient.from(new URI(getAuthServiceUrl()));
    }
    
    public static AuthToken getToken() throws Exception {
        final AuthClient authService = getAuthService();
        if (token1 == null) {
            String tokenString = getTestConfigParam("test.token", true);
            token1 = authService.validateToken(tokenString);
        }
        return token1;
    }

    public static AuthToken getToken2() throws Exception {
        final AuthClient authService = getAuthService();
        if (token2 == null) {
            String tokenString = getTestConfigParam("test.token2", true);
            token2 = authService.validateToken(tokenString);
        }
        return token2;
    }
    
    public static String getKBaseEndpoint() throws Exception {
        return getTestConfigParam("test.kbase.endpoint", true);
    }
    
    public static String getTempTestDir() throws Exception {
        return getTestConfigParam("test.temp.dir", true);
    }
    
    public static boolean getMakeRootOwnedFilesWriteable() throws Exception {
        return getTestConfigParam("test.make-root-owned-files-writeable", "true").equals("true");
    }
}
