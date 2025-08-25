package us.kbase.test.sdk.callback;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.io.FileUtils;
import org.ini4j.InvalidFileFormatException;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.DateTimeFormatterBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.zafarkhaja.semver.Version;
import com.google.common.collect.ImmutableMap;

import us.kbase.auth.AuthToken;
import us.kbase.catalog.CatalogClient;
import us.kbase.catalog.ModuleInfo;
import us.kbase.catalog.ModuleVersionInfo;
import us.kbase.catalog.SelectOneModuleParams;
import us.kbase.common.service.JacksonTupleModule;
import us.kbase.common.service.JsonClientException;
import us.kbase.common.service.ServerException;
import us.kbase.common.service.UObject;
import us.kbase.sdk.callback.CallbackProvenance;
import us.kbase.sdk.callback.CallbackServerManager;
import us.kbase.test.sdk.scripts.TestConfigHelper;
import us.kbase.workspace.ProvenanceAction;
import us.kbase.workspace.SubAction;

public class CallbackServerTest {
    
    private static final Path TEST_DIR;
    static {
        try {
            TEST_DIR = Paths.get(
                    TestConfigHelper.getTempTestDir(), CallbackServerTest.class.getSimpleName()
            );
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }
   
    private static AuthToken token;
    private static CatalogClient CAT_CLI;
    
    public static ModuleVersionInfo getMVI(ModuleInfo mi, String release) {
        if (release.equals("dev")) {
            return mi.getDev();
        } else if (release.equals("beta")) {
            return mi.getBeta();
        } else {
            return mi.getRelease();
        }
    }
    
    private final static DateTimeFormatter DATE_PARSER =
            new DateTimeFormatterBuilder()
                .append(DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss"))
                .appendOptional(DateTimeFormat.forPattern(".SSS").getParser())
                .append(DateTimeFormat.forPattern("Z"))
                .toFormatter();
    
    private final static DateTimeFormatter DATE_FORMATTER =
            DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ssZ").withZoneUTC();
    
    @BeforeAll
    public static void beforeClass() throws Exception {
        FileUtils.deleteDirectory(TEST_DIR.toFile());
        Files.deleteIfExists(TEST_DIR);
        Files.createDirectories(TEST_DIR);
        
        token = TestConfigHelper.getToken();
        CAT_CLI = new CatalogClient(new URL(TestConfigHelper.getKBaseEndpoint() + 
                "/catalog"), token);
    }

    private static CallbackStuff startCallBackServer() throws Exception {
        final CallbackProvenance cp = CallbackProvenance.getBuilder(
                "foo.bar", Version.of(0, 0, 5)
        ).build();
        return startCallBackServer(cp, false);
    }
    
    private static CallbackStuff startCallBackServer(
            final CallbackProvenance prov,
            final boolean withTrailingSlash)
            throws Exception {
        String kBaseEndpoint = TestConfigHelper.getKBaseEndpoint().strip().replaceAll("/+$", "");
        // test the CSM handles urls with and without trailing slashes
        if (withTrailingSlash) {
            kBaseEndpoint = kBaseEndpoint + "/";
        }
        final CallbackServerManager csm = new CallbackServerManager(
                Files.createTempDirectory(TEST_DIR, "cbt"),
                new URL(kBaseEndpoint),
                token,
                prov,
                true
        );
        // not really a lot that can be tested re getting the urls
        final String port = csm.getCallbackPort() + "";
        assertThat(csm.getInContainerCallbackUrl().toString().endsWith(port), is(true));
        assertThat(csm.getLocalhostCallbackUrl(), is(new URL("http://localhost:" + port)));
        return new CallbackStuff(csm);
    }
    
    private static class CallbackStuff {
        final public CallbackServerManager manager; 
        final public Path tempdir;
        
        final private static ObjectMapper mapper = new ObjectMapper().registerModule(
                new JacksonTupleModule());

        private CallbackStuff(final CallbackServerManager manager) {
            this.manager = manager;
            this.tempdir = manager.getWorkDirRoot();
        }
        
        public List<ProvenanceAction> getProvenance() throws Exception {
            final TypeReference<List<ProvenanceAction>> retType =
                    new TypeReference<List<ProvenanceAction>>() {};
            final List<Object> arg = new ArrayList<Object>();
            final String method = "CallbackServer.get_provenance";
            
            return callServer(method, arg, null, retType);
        }
        
        public List<ProvenanceAction> setProvenance(final ProvenanceAction pa)
                throws Exception {
            final TypeReference<List<ProvenanceAction>> retType =
                    new TypeReference<List<ProvenanceAction>>() {};
            final List<Object> arg = Arrays.asList(pa);
            final String method = "CallbackServer.set_provenance";
            
            return callServer(method, arg, null, retType);
        }
        
        public Map<String, Object> callMethod(
                final String method,
                final Map<String, Object> params,
                final String serviceVer)
                throws Exception {
            return callServer(method, Arrays.asList(params), serviceVer,
                    new TypeReference<Map<String,Object>>() {});
        }
        
        public UUID callAsync(
                final String method,
                final Map<String, Object> params,
                final String serviceVer)
                throws Exception {
            final String[] modMeth = method.split("\\.");
            return callServer(modMeth[0] + "._" + modMeth[1] + "_submit",
                    Arrays.asList(params),
                    serviceVer, new TypeReference<UUID>() {});
        }
        
        public Map<String, Object> checkAsync(final UUID jobId)
                throws Exception {
            return callServer("foo._check_job", Arrays.asList(jobId), "fake",
                    new TypeReference<Map<String,Object>>() {});
        }
        
        public Map<String, Object> checkAsync(final List<?> params)
                throws Exception {
            return callServer("foo._check_job", params, "fake",
                    new TypeReference<Map<String,Object>>() {});
        }

        private <RET> RET callServer(
                final String method,
                final List<?> args,
                final String serviceVer,
                final TypeReference<RET> retType)
                throws Exception {
            final HttpURLConnection conn =
                    (HttpURLConnection) manager.getLocalhostCallbackUrl().openConnection();
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            try (final OutputStream os = conn.getOutputStream()) {
                final Map<String, Object> req = new HashMap<String, Object>();
                final String id = ("" + Math.random()).replace(".", "");
                req.put("params", args);
                req.put("method", method);
                req.put("version", "1.1");
                req.put("id", id);
                if (serviceVer != null) {
                    req.put("context", ImmutableMap.<String, String>builder()
                                .put("service_ver", serviceVer).build());
                }
                mapper.writeValue(os, req);
                os.flush();
            }
            final int code = conn.getResponseCode();
            if (code == 500) {
                @SuppressWarnings("unchecked")
                final Map<String, Object> msg = mapper.readValue(
                        conn.getErrorStream(), Map.class);
                @SuppressWarnings("unchecked")
                final Map<String, Object> err =
                        (Map<String, Object>) msg.get("error"); 
                final String data = (String) (err.get("data") == null ?
                        err.get("error") : err.get("data"));
                System.out.println("got traceback from server in test:");
                System.out.println(data);
                throw new ServerException((String) err.get("message"),
                        (Integer) err.get("code"), (String) err.get("name"),
                        data);
            } else {
                @SuppressWarnings("unchecked")
                final Map<String, Object> msg = mapper.readValue(
                        conn.getInputStream(), Map.class);
                @SuppressWarnings("unchecked")
                final List<List<Object>> ret =
                        (List<List<Object>>) msg.get("result");
                final RET res = UObject.transformObjectToObject(
                        ret.get(0), retType);
                return res;
            }
        }
    }
    
    private void checkResults(Map<String, Object> got,
            Map<String, Object> params, String name) {
        assertThat("incorrect name", (String) got.get("name"), is(name));
        if (params.containsKey("wait")) {
            assertThat("incorrect wait time", (Integer) got.get("wait"),
                    is(params.get("wait")));
        }
        assertThat("incorrect id", (String) got.get("id"),
                is(params.get("id")));
        assertNotNull((String) got.get("hash"), "missing hash");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parjobs =
                (List<Map<String, Object>>) params.get("jobs");
        if (params.containsKey("jobs")) {
            @SuppressWarnings("unchecked")
            List<List<Map<String,Object>>> gotjobs =
                    (List<List<Map<String, Object>>>) got.get("jobs");
            assertNotNull(gotjobs, "missing jobs");
            assertThat("not same number of jobs", gotjobs.size(),
                    is(parjobs.size()));
            Iterator<List<Map<String, Object>>> gotiter = gotjobs.iterator();
            Iterator<Map<String, Object>> pariter = parjobs.iterator();
            while (gotiter.hasNext()) {
                Map<String, Object> p = pariter.next();
                String modmeth = (String) p.get("method");
                String module = modmeth.split("\\.")[0];
                //params are always wrapped in a list
                @SuppressWarnings("unchecked")
                final Map<String, Object> innerparams =
                    ((List<Map<String, Object>>) p.get("params")).get(0);
                //as are results
                checkResults(gotiter.next().get(0), innerparams,
                        (String) module);
            }
        }
    }
    
    @Test
    public void initFail() throws Exception {
        final Path p = Paths.get("foo");
        final URL u = new URL("http://foo.com");
        final AuthToken t = new AuthToken("token", "user");
        final CallbackProvenance cp = CallbackProvenance.getBuilder("m.m", Version.of(1)).build();
        failInit(null, u, t, cp, NullPointerException.class, "workDirRoot");
        failInit(p, null, t, cp, NullPointerException.class, "kbaseBaseUrl");
        failInit(p, u, null, cp, NullPointerException.class, "token");
        failInit(p, u, t, null, NullPointerException.class, "prov");
    }
    
    private <T extends Exception> void failInit(
            final Path workdir,
            final URL baseUrl,
            final AuthToken token,
            final CallbackProvenance prov,
            final Class<T> exclass,
            final String exmsg
            ) throws Exception {
        final Exception e = assertThrows(exclass,
                () -> new CallbackServerManager(workdir, baseUrl, token, prov, true)
        );
        assertThat(e.getMessage(), is(exmsg));
    }
    
    @Test
    public void maxJobs() throws Exception {
        final CallbackStuff res = startCallBackServer();
        try {
            System.out.println("Running maxJobs in dir " + res.tempdir);
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("id", "outer");
            params.put("wait", 1);
            LinkedList<Map<String, Object>> jobs =
                    new LinkedList<Map<String, Object>>();
            params.put("jobs", jobs);
            params.put("run_jobs_async", true);
            for (int i = 0; i < 4; i++) {
                Map<String, Object> inner2 = new HashMap<String, Object>();
                inner2.put("wait", 3);
                inner2.put("id", "inner2-" + i);
                Map<String, Object> injob = new HashMap<String, Object>();
                injob.put("method", "njs_sdk_test_1.run");
                injob.put("ver", "dev");
                injob.put("params", Arrays.asList(inner2));
                if (i % 2 == 0) {
                    injob.put("cli_async", true);
                }
                Map<String, Object> innerparams = new HashMap<String, Object>();
                innerparams.put("wait", 2);
                innerparams.put("id", "inner-" + i);
                innerparams.put("jobs", Arrays.asList(injob));
                
                Map<String, Object> outerjob = new HashMap<String, Object>();
                outerjob.put("method", "njs_sdk_test_1.run");
                outerjob.put("ver", "dev");
                outerjob.put("params", Arrays.asList(innerparams));
                if (i % 2 == 0) {
                    outerjob.put("cli_async", true);
                };
                jobs.add(outerjob);
            }
            final ImmutableMap<String, Object> singlejob =
                    ImmutableMap.<String, Object>builder()
                        .put("method", "njs_sdk_test_1.run")
                        .put("ver", "dev")
                        .put("params", Arrays.asList(
                            ImmutableMap.<String, Object>builder()
                                .put("id", "singlejob")
                                .put("wait", 2)
                                .build()))
                        .build();
            jobs.add(singlejob);
            
            // should run
            Map<String, Object> r = res.callMethod(
                    "njs_sdk_test_1.run", params, "dev");
            checkResults(r, params, "njs_sdk_test_1");
            
            //throw an error during a sync job to check the job counter is
            // decremented
            Map<String, Object> errparam = new HashMap<String, Object>();
            errparam.put("id", "errjob");
            errparam.put("except", "planned exception");
            try {
                res.callMethod("njs_sdk_test_1.run", errparam, "dev");
                fail("expected exception");
            } catch (ServerException se) {
                assertThat("incorrect error message", se.getLocalizedMessage(),
                        is("'planned exception errjob'"));  // stupid sdk error quoting, arrrg
            }
            
            // run again to ensure the job counter is back to 0
            r = res.callMethod("njs_sdk_test_1.run", params, "dev");
            checkResults(r, params, "njs_sdk_test_1");
            
            // run with 11 jobs to force an exception
            jobs.add(ImmutableMap.<String, Object>builder()
                    .put("method", "njs_sdk_test_1.run")
                    .put("ver", "dev")
                    .put("params", Arrays.asList(
                            ImmutableMap.<String, Object>builder()
                                .put("id", "singlejob2")
                                .put("wait", 2)
                                .build()))
                    .build());
            try {
                res.callMethod("njs_sdk_test_1.run", params, "dev");
                fail("expected exception");
            } catch (ServerException se) {
                assertThat("incorrect error message", se.getLocalizedMessage(),
                        // you've got to be shitting me right now with this quoting shit wtf
                        is("\"'No more than 10 concurrently running methods are allowed'\""));
            }
        } finally {
            res.manager.close();
        }
    }

    @Test
    public void async() throws Exception {
        final CallbackStuff res = startCallBackServer();
        try {
            System.out.println("Running async in dir " + res.tempdir);
            final Map<String, Object> simplejob =
                    ImmutableMap.<String, Object>builder()
                        .put("id", "simplejob")
                        .put("wait", 10)
                        .build();
            UUID jobId = res.callAsync("njs_sdk_test_1.run", simplejob, "dev");
            int attempts = 1;
            List<Map<String, Object>> got;
            while (true) {
                if (attempts > 20) {
                    fail("timed out waiting for async results");
                }
                Map<String, Object> status = res.checkAsync(jobId);
                if (((Integer) status.get("finished")) == 1) {
                    @SuppressWarnings("unchecked")
                    final List<Map<String, Object>> tempgot =
                            (List<Map<String, Object>>) status.get("result");
                    got = tempgot;
                    break;
                }
                Thread.sleep(1000);
                attempts++;
            }
            checkResults(got.get(0), simplejob, "njs_sdk_test_1");
            
            // now the result should be in the cache, so check again
            Map<String, Object> status = res.checkAsync(jobId);
            assertThat("job not done", (Integer) status.get("finished"), is(1));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tempgot =
                    (List<Map<String, Object>>) status.get("result");
            checkResults(tempgot.get(0), simplejob, "njs_sdk_test_1");
            
            final UUID randomUUID = UUID.randomUUID();
            try {
                res.checkAsync(randomUUID);
                fail("expected exception");
            } catch (ServerException ise) {
                assertThat("wrong exception message", ise.getLocalizedMessage(),
                       is(String.format("No such job ID: %s", randomUUID))
                );
            }
        } finally {
            res.manager.close();
        }
    }
    
    @Test
    public void checkWithBadArgs() throws Exception {
        final CallbackStuff res = startCallBackServer();
        try {
            System.out.println("Running checkwithBadArgs in dir " + res.tempdir);
            String badUUID = UUID.randomUUID().toString();
            badUUID = badUUID.substring(0, badUUID.length() - 1) + "g";
            
            try {
                res.checkAsync(Arrays.asList(badUUID));
                fail("expected exception");
            } catch (ServerException ise) {
                assertThat("wrong exception message", ise.getLocalizedMessage(),
                       is("Invalid job ID: " + badUUID));
            }
            try {
                res.checkAsync(Arrays.asList(new HashMap<>()));
                fail("expected exception");
            } catch (ServerException ise) {
                assertThat("wrong exception message", ise.getLocalizedMessage(),
                       is("method params must be a list containing exactly one job ID string"));
            }
            try {
                res.checkAsync(Arrays.asList(1, 2));
                fail("expected exception");
            } catch (ServerException ise) {
                assertThat("wrong exception message", ise.getLocalizedMessage(),
                       is("method params must be a list containing exactly one job ID string"));
            }
        } finally {
            res.manager.close();
        }
    }
    
    @Test
    public void badRelease() throws Exception {
        final CallbackStuff res = startCallBackServer();
        try {
            System.out.println("Running badRelease in dir " + res.tempdir);
            // note that dev and beta releases can only have one version each,
            // version tracking only happens for prod
            
            // Re stupid quotes: https://github.com/kbase/catalog/issues/139
            failJob(res, "njs_sdk_test_1foo.run", "beta",
                    "Error looking up module njs_sdk_test_1foo with version " +
                    "beta: 'Module cannot be found based on module_name or " +
                    "git_url parameters.'");
            failJob(res, "njs_sdk_test_1.run", "beta",
                    "Error looking up module njs_sdk_test_1 with version " +
                    "beta: 'No module version found that matches your criteria!'");
            failJob(res, "njs_sdk_test_1.run", "release",
                    "Error looking up module njs_sdk_test_1 with version " +
                    "release: 'No module version found that matches your criteria!'");
            failJob(res, "njs_sdk_test_1.run", null,
                    "Error looking up module njs_sdk_test_1 with version " +
                     "release: 'No module version found that matches your criteria!'");
    
            // this git commit was registered to dev but 
            // then the previous git commit was registered to dev
            // Note 7 years later - I don't understand what the above comment means.
            // Dev versions aren't deleted if you register a new dev version AFAICT
            // That being said, the test passes.
            // Maybe the catalog used to replace dev versions?
            String git = "b0d487271c22f793b381da29e266faa9bb0b2d1b";
            failJob(res, "njs_sdk_test_1.run", git,
                    "Error looking up module njs_sdk_test_1 with version " +
                    git + ": 'No module version found that matches your criteria!'");
            failJob(res, "njs_sdk_test_1.run", "foo",
                    "Error looking up module njs_sdk_test_1 with version foo: " +
                    "'No module version found that matches your criteria!'");
        } finally {
            res.manager.close();
        }
    }
    
    @Test
    public void badMethod() throws Exception {
    	final CallbackStuff res = startCallBackServer();
        try {
            System.out.println("Running badMethod in dir " + res.tempdir);
            failJob(res, "njs_sdk_test_1run", "foo", "Illegal method name: njs_sdk_test_1run");
            failJob(res, "njs_sdk_test_1.r.un", "foo", "Illegal method name: njs_sdk_test_1.r.un");
        } finally {
            res.manager.close();
        }
    }
    
    private void failJob(CallbackStuff cbs, String moduleMeth, String release,
            String exp)
            throws Exception{
        try {
            cbs.callMethod(moduleMeth, new HashMap<String, Object>(), release);
            fail("Ran bad job");
        } catch (ServerException se) {
            assertThat("correct exception", se.getLocalizedMessage(), is(exp));
        }
    }
    
    @Test
    public void setProvenance() throws Exception {
        final CallbackProvenance cp = CallbackProvenance.getBuilder(
                "whooptywhoop.run", Version.of(1000, 1, 0))
                .withCommit("aaaaaaa")
                .withCodeUrl(new URL("https://github.com/kbasetest/whooptywhoop"))
                .withServiceVer("beta")
                .build();
        final CallbackStuff res = startCallBackServer(cp, true);
        try {
            System.out.println("Running setProvenance in dir " + res.tempdir);
            
            List<String> wsobjs = Arrays.asList("foo1", "bar1", "baz1");
            final List<String> param1 = Arrays.asList("foo1", "bar1");
            final Map<String, String> param2 = ImmutableMap.of("foo1", "bar1");
            ProvenanceAction pa = new ProvenanceAction()
                .withMethod("amethod")
                .withService("aservice")
                .withServiceVer("0.0.2-dev")
                .withTime(DATE_FORMATTER.print(new DateTime()))
                .withMethodParams(Arrays.asList(new UObject(param1), new UObject(param2)))
                .withInputWsObjects(wsobjs);
            
            res.setProvenance(pa);
            String moduleName = "njs_sdk_test_2";
            String methodName = "run";
            String release = "dev";
            String ver = "0.0.10";
            Map<String, Object> methparams = new HashMap<String, Object>();
            methparams.put("id", "myid");
            Map<String, Object> results = res.callMethod(
                    moduleName + '.' + methodName, methparams, "dev");
            
            List<SubActionSpec> expsas = new LinkedList<SubActionSpec>();
            expsas.add(new SubActionSpec()
                .withMod(moduleName)
                .withVer(ver)
                .withRel(release)
            );
            
            List<ProvenanceAction> p = res.getProvenance();
            System.out.println(p);
            checkProvenance("aservice", "amethod", "dev", "0.0.2", Arrays.asList(param1, param2),
                    expsas, wsobjs, p);
            checkResults(results, methparams, moduleName);
            
            try {
                res.setProvenance(null);
                fail("expected exception");
            } catch (ServerException se) {
                assertThat("incorrect excep msg", se.getLocalizedMessage(), is(
                        "method params must be a list containing exactly one provenance action"
                ));
            }
        } finally {
            res.manager.close();
        }
    }
    
    @Test
    public void multiCallProvenance() throws Exception {
        String moduleName = "njs_sdk_test_1";
        String methodName = "run";
        String release = "dev";
        String ver = "0.0.3";
        String nst1latest = "dbea6819e06d37a7f7f08f49673555edaf7f96a6";
        String nst1dev = "366eb8cead445aa3e842cbc619082a075b0da322";
        List<String> wsobjs = Arrays.asList("foo", "bar", "baz");
        List<Object> params = new ArrayList<Object>();
        params.add(Arrays.asList("foo", "bar"));
        params.add(ImmutableMap.of("foo", "bar"));
        final CallbackProvenance cp = CallbackProvenance.getBuilder(
                moduleName + "." + methodName, Version.parse(ver))
                .withParams(params)
                .withWorkspaceRefs(wsobjs)
                .withServiceVer(release)
                .withCodeUrl(new URL("https://github.com/kbasetest/njs_sdk_test_1"))
                .withCommit(nst1dev)
                .build();
        final CallbackStuff res = startCallBackServer(cp, false);
        try {
            System.out.println("Running multiCallProvenance in dir " + res.tempdir);
            String moduleName2 = "njs_sdk_test_2";
            String commit2 = "9d6b868bc0bfdb61c79cf2569ff7b9abffd4c67f";
            @SuppressWarnings("unchecked")
            Map<String, Object> methparams = UObject.transformStringToObject(
                    String.format(
                "{\"jobs\": [{\"method\": \"%s\"," +
                             "\"params\": [{\"id\": \"id1\", \"wait\": 3}]," +
                             "\"ver\": \"%s\"" +
                             "}," +
                            "{\"method\": \"%s\"," +
                             "\"params\": [{\"id\": \"id2\", \"wait\": 3}]," +
                             "\"ver\": \"%s\"" +
                             "}," +
                            "{\"method\": \"%s\"," +
                             "\"params\": [{\"id\": \"id3\", \"wait\": 3}]," +
                             "\"ver\": \"%s\"" +
                             "}" +
                            "]," +
                 "\"id\": \"myid\"" + 
                 "}",
                 moduleName2 + "." + methodName,
                 // beta is on this commit
                 commit2,
                 moduleName + "." + methodName,
                 // this is the latest commit, but a prior commit is registered to dev
                 nst1latest,
                 moduleName2 + "." + methodName,
                 // this should get ignored when pulling images since this module has already been
                 // run and is cached
                 "dev"), Map.class);
            List<SubActionSpec> expsas = new LinkedList<SubActionSpec>();
            expsas.add(new SubActionSpec()
                .withMod(moduleName)
                .withVer("0.0.3")
                .withRel("dev")
            );
            expsas.add(new SubActionSpec()
                .withMod(moduleName2)
                .withVer("0.0.9")
                .withCommit(commit2)
            );
            Map<String, Object> results = res.callMethod(
                    moduleName + '.' + methodName, methparams, "dev");
            List<ProvenanceAction> p = res.getProvenance();
            checkProvenance(moduleName, methodName, release, ver, params,
                    expsas, wsobjs, p);
            checkResults(results, methparams, moduleName);
        } finally {
            res.manager.close();
        }
    }
    
    private static class SubActionSpec {
        public String module;
        public String release;
        public String ver;
        public String commit;
        
        public SubActionSpec (){}
        public SubActionSpec withMod(String mod) {
            this.module = mod;
            return this;
        }
        
        public SubActionSpec withRel(String rel) {
            this.release = rel;
            return this;
        }
        
        public SubActionSpec withVer(String ver) {
            this.ver = ver;
            return this;
        }
        
        public SubActionSpec withCommit(String commit) {
            this.commit = commit;
            return this;
        }
        public String getVerRel() {
            if (release == null) {
                return ver + "-" + commit;
            }
            return ver + "-" + release;
        }
    }

    private void checkProvenance(
            String moduleName,
            String methodName,
            String release,
            String ver,
            List<Object> methparams,
            List<SubActionSpec> subs,
            List<String> wsobjs,
            List<ProvenanceAction> prov)
            throws Exception, IOException, InvalidFileFormatException,
            JsonClientException {
        if (release != null) {
            ver = ver + "-" + release;
        }

        assertThat("number of provenance actions",
                prov.size(), is(1));
        ProvenanceAction pa = prov.get(0);
        long got = DATE_PARSER.parseDateTime(pa.getTime()).getMillis();
        long now = new Date().getTime();
        assertTrue(got < now, "got prov time < now ");
        assertTrue(got > now - (5 * 60 * 1000), "got prov time > now - 5m");
        assertThat("correct service", pa.getService(), is(moduleName));
        assertThat("correct service version", pa.getServiceVer(),
                is(ver));
        assertThat("correct method", pa.getMethod(), is(methodName));
        assertThat("number of params", pa.getMethodParams().size(),
                is(methparams.size()));
        for (int i = 1; i < methparams.size(); i++) {
            assertThat("params not equal",
                    pa.getMethodParams().get(i).asClassInstance(Object.class),
                    is(methparams.get(i))
            );
        }
        assertThat("correct incoming ws objs",
                new HashSet<String>(pa.getInputWsObjects()),
                is(new HashSet<String>(wsobjs)));
        checkSubActions(pa.getSubactions(), subs);
    }
    
    private void checkSubActions(List<SubAction> gotsas,
            List<SubActionSpec> expsas) throws Exception {
        assertThat("correct # of subactions",
                gotsas.size(), is(expsas.size()));
        for (SubActionSpec sa: expsas) {
            if (sa.commit == null) {
                sa.commit = getMVI(CAT_CLI.getModuleInfo(
                        new SelectOneModuleParams().withModuleName(sa.module)),
                        sa.release).getGitCommitHash();
            }
        }
        Iterator<SubAction> giter = gotsas.iterator();
        Iterator<SubActionSpec> eiter = expsas.iterator();
        while (giter.hasNext()) {
            SubAction got = giter.next();
            SubActionSpec sa = eiter.next();
            assertThat("correct code url", got.getCodeUrl(),
                    is("https://github.com/kbasetest/" + sa.module));
            assertThat("correct commit", got.getCommit(), is(sa.commit));
            assertThat("correct name", got.getName(), is(sa.module));
            assertThat("correct version", got.getVer(), is(sa.getVerRel()));
        }
    }
}
