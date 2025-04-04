package us.kbase.mobu.compiler;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;

import us.kbase.jkidl.IncludeProvider;
import us.kbase.kidl.KbFuncdef;
import us.kbase.kidl.KbModule;
import us.kbase.kidl.KbModuleComp;
import us.kbase.kidl.KbService;
import us.kbase.mobu.util.FileSaver;
import us.kbase.templates.TemplateFormatter;

public class TemplateBasedGenerator {

    public static boolean genPerlServer(boolean genPerlServer, 
            String perlServerName, String perlImplName, String perlPsgiName) {
        return genPerlServer || perlServerName != null || perlImplName != null || perlPsgiName != null;
    }
    
    public static boolean genPythonServer(boolean genPythonServer,
            String pythonServerName, String pythonImplName) {
        return genPythonServer || pythonServerName != null || pythonImplName != null;
    }

    @SuppressWarnings("unchecked")
    public static void generate(List<KbService> srvs, String defaultUrl, 
            boolean genJs, String jsClientName,
            boolean genPerl, String perlClientName, boolean genPerlServer, 
            String perlServerName, String perlImplName, String perlPsgiName, 
            boolean genPython, String pythonClientName, boolean genPythonServer,
            String pythonServerName, String pythonImplName, boolean genR, 
            String rClientName, boolean genRServer, String rServerName, String rImplName, 
            boolean enableRetries, IncludeProvider ip, FileSaver output,
            String clientAsyncVer, String dynservVer, String semanticVersion,
            String gitUrl, String gitCommitHash)
            throws Exception {
        if (semanticVersion == null)
            semanticVersion = "";
        if (gitUrl == null)
            gitUrl = "";
        if (gitCommitHash == null)
            gitCommitHash = "";
        KbService service = srvs.get(0);
        genPerlServer = genPerlServer(genPerlServer, perlServerName, perlImplName, perlPsgiName);
        if (genPerlServer) {
            genPerl = true;
            if (perlServerName == null)
                perlServerName = service.getName() + "Server";
            if (perlPsgiName == null) {
            	perlPsgiName = service.getName() + ".psgi";
            }
        }
        genPythonServer = genPythonServer(genPythonServer, pythonServerName, pythonImplName);
        if (genPythonServer) {
            genPython = true;
            if (pythonServerName == null)
                pythonServerName = service.getName() + "Server";
        }
        if (genPython && pythonClientName == null)
            pythonClientName = service.getName() + "Client";
        genRServer = genPythonServer(genRServer, rServerName, rImplName);
        if (genRServer) {
            System.out.println(
                    "************************************************************************\n" +
                    "WARNING: R support is deprecated and will be removed in a future release\n" +
                    "************************************************************************");
            genR = true;
            if (rServerName == null) {
                rServerName = service.getName() + "Server";
            }
        }
        final Map<String, Object> context = generateTemplateData(
                service, perlImplName, pythonImplName
        );
        if (defaultUrl != null)
            context.put("default_service_url", defaultUrl);
        context.put("client_package_name", perlClientName);
        context.put("server_package_name", perlServerName);
        if (enableRetries)
            context.put("enable_client_retry", true);
        context.put("empty_escaper", "");  // ${empty_escaper}
        context.put("display", new StringUtils());
        context.put("async_version", clientAsyncVer);
        context.put("dynserv_ver", dynservVer);
        final String serviceVer;
        if (clientAsyncVer != null) {
            serviceVer = clientAsyncVer;
        } else if (dynservVer != null) {
            serviceVer = dynservVer;
        } else {
            serviceVer = null;
        }
        context.put("service_ver", serviceVer);
        if (jsClientName != null) {
            Writer jsClient = output.openWriter(jsClientName + ".js");
            TemplateFormatter.formatTemplate("javascript_client", context, jsClient);
            jsClient.close();
        }
        Map<String, Object> perlMakefileContext = new LinkedHashMap<String, Object>(context);
        if (perlClientName != null) {
            String perlClientPath = fixPath(perlClientName, "::") + ".pm";
            Writer perlClient = output.openWriter(perlClientPath);
            TemplateFormatter.formatTemplate("perl_client", context, perlClient);
            perlClient.close();
            perlMakefileContext.put("client_package_name", perlClientName);
            perlMakefileContext.put("client_file", perlClientPath);
        }
        if (pythonClientName != null) {
            String pythonClientPath = fixPath(pythonClientName, ".") + ".py";
            initPythonPackages(pythonClientPath, output, true);
            Writer pythonClient = output.openWriter(pythonClientPath);
            TemplateFormatter.formatTemplate("python_client", context, pythonClient);
            pythonClient.close();
        }
        if (rClientName != null) {
            String rClientPath = rClientName + ".r";
            Writer rClient = output.openWriter(rClientPath);
            TemplateFormatter.formatTemplate("r_client", context, rClient);
            rClient.close();
        }
        //////////////////////////////////////// Servers /////////////////////////////////////////
        if (perlServerName != null) {
            String perlServerPath = fixPath(perlServerName, "::") + ".pm";
            Writer perlServer = output.openWriter(perlServerPath);
            TemplateFormatter.formatTemplate("perl_server", context, perlServer);
            perlServer.close();
            perlMakefileContext.put("server_package_name", perlServerName);
            perlMakefileContext.put("server_file", perlServerPath);
        }
        if (pythonServerName != null) {
            String pythonServerPath = fixPath(pythonServerName, ".") + ".py";
            initPythonPackages(pythonServerPath, output, false);
            Writer pythonServer = output.openWriter(pythonServerPath);
            TemplateFormatter.formatTemplate("python_server", context, pythonServer);
            pythonServer.close();
        }
        if (rServerName != null) {
            String rServerPath = rServerName + ".r";
            Writer rServer = output.openWriter(rServerPath);
            TemplateFormatter.formatTemplate("r_server", context, rServer);
            rServer.close();
        }
        if (genPerlServer || genPythonServer || genRServer) {
            List<Map<String, Object>> modules = (List<Map<String, Object>>)context.get("modules");
            for (int modulePos = 0; modulePos < modules.size(); modulePos++) {
                Map<String, Object> module = new LinkedHashMap<String, Object>(modules.get(modulePos));
                module.put("semantic_version", semanticVersion);
                module.put("git_url", gitUrl);
                module.put("git_commit_hash", gitCommitHash);
                perlMakefileContext.put("module", module);
                List<Map<String, Object>> methods = (List<Map<String, Object>>)module.get("methods");
                List<String> methodNames = new ArrayList<String>();
                for (Map<String, Object> method : methods)
                    methodNames.add(method.get("name").toString());
                String perlImplPath = null;
                if (genPerlServer) {
                    String perlModuleImplName = (String)module.get("impl_package_name");
                    perlImplPath = fixPath(perlModuleImplName, "::") + ".pm";
                    Map<String, String> innerPrevCode = PrevCodeParser.parsePrevCode(
                            output.getAsFileOrNull(perlImplPath), "#", methodNames, false
                    );
                    module.put("module_header", innerPrevCode.get(PrevCodeParser.HEADER));
                    module.put("module_constructor", innerPrevCode.get(PrevCodeParser.CONSTRUCTOR));
                    module.put("module_status", innerPrevCode.get(PrevCodeParser.STATUS));
                    for (Map<String, Object> method : methods) {
                        String code = innerPrevCode.get(PrevCodeParser.METHOD + method.get("name"));
                        method.put("user_code", code == null ? "" : code);
                    }
                    perlMakefileContext.put("impl_package_name", perlModuleImplName);
                    perlMakefileContext.put("impl_file", perlImplPath);
                }
                String pythonImplPath = null;
                if (genPythonServer) {
                    String pythonModuleImplName = (String)module.get("pymodule");
                    pythonImplPath = fixPath(pythonModuleImplName, ".") + ".py";
                    Map<String, String> innerPrevCode = PrevCodeParser.parsePrevCode(
                            output.getAsFileOrNull(pythonImplPath), "#", methodNames, true
                    );
                    module.put("py_module_header", innerPrevCode.get(PrevCodeParser.HEADER));
                    module.put("py_module_class_header", innerPrevCode.get(PrevCodeParser.CLSHEADER));
                    module.put("py_module_constructor", innerPrevCode.get(PrevCodeParser.CONSTRUCTOR));
                    module.put("py_module_status", innerPrevCode.get(PrevCodeParser.STATUS));
                    for (Map<String, Object> method : methods) {
                        String code = innerPrevCode.get(PrevCodeParser.METHOD + method.get("name"));
                        method.put("py_user_code", code == null ? "" : code);
                    }
                }
                String rImplPath = null;
                if (genRServer) {
                    rImplPath = rImplName + ".r";
                    Map<String, String> innerPrevCode = PrevCodeParser.parsePrevCode(
                            output.getAsFileOrNull(rImplPath), "#", methodNames, false
                    );
                    module.put("r_module_header", innerPrevCode.get(PrevCodeParser.HEADER));
                    module.put("r_module_constructor", innerPrevCode.get(PrevCodeParser.CONSTRUCTOR));
                    for (Map<String, Object> method : methods) {
                        String code = innerPrevCode.get(PrevCodeParser.METHOD + method.get("name"));
                        method.put("r_user_code", code == null ? "" : code);
                    }
                }
                Map<String, Object> moduleContext = new LinkedHashMap<String, Object>();
                moduleContext.put("module", module);
                moduleContext.put("server_package_name", perlServerName);
                moduleContext.put("empty_escaper", "");  // ${empty_escaper}
                moduleContext.put("display", new StringUtils());
                if (genPerlServer) {
                    Writer perlImpl = output.openWriter(perlImplPath);
                    TemplateFormatter.formatTemplate("perl_impl", moduleContext, perlImpl);
                    perlImpl.close();
                }
                if (genPythonServer) {
                    Writer pythonImpl = output.openWriter(pythonImplPath);
                    TemplateFormatter.formatTemplate("python_impl", moduleContext, pythonImpl);
                    pythonImpl.close();
                }
                if (genRServer) {
                    Writer rImpl = output.openWriter(rImplPath);
                    TemplateFormatter.formatTemplate("r_impl", moduleContext, rImpl);
                    rImpl.close();
                }
            }
        }
        if (perlPsgiName != null) {
            Writer perlPsgi = output.openWriter(perlPsgiName);
            TemplateFormatter.formatTemplate("perl_psgi", context, perlPsgi);
            perlPsgi.close();
            perlMakefileContext.put("psgi_file", perlPsgiName);
        }
    }

	private static Map<String, Object> generateTemplateData(
			final KbService service,
			final String perlImplName,
			final String pythonImplName) {
		Map<String, Object> ret = new LinkedHashMap<String, Object>();
		List<Map<String, Object>> modules = new ArrayList<Map<String, Object>>();
		boolean psbl = false;
		boolean only = true;
		int funcCount = 0;
		for (KbModule m : service.getModules()) {
			modules.add(m.accept(new TemplateVisitor()));
			for (KbModuleComp mc : m.getModuleComponents())
				if (mc instanceof KbFuncdef) {
					KbFuncdef func = (KbFuncdef)mc;
					funcCount++;
					boolean req = func.isAuthenticationRequired();
					boolean opt = func.isAuthenticationOptional();
					psbl |= req || opt;
					only &= req;
				}
		}
		only &= funcCount > 0;
		for (Map<String, Object> module : modules) {
			String moduleName = (String)module.get("module_name");
			module.put("impl_package_name",
					perlImplName == null ? (moduleName + "Impl") : perlImplName);
			final String pymod = pythonImplName == null ? (moduleName + "Impl") : pythonImplName;
			module.put("pymodule", pymod);
			final String pypkg;
			if (pymod.lastIndexOf(".") > 0) {
				pypkg = pymod.substring(0, pymod.lastIndexOf(".")) + ".";
			} else {
				pypkg = "";
			}
			module.put("pypackage", pypkg);
		}
		ret.put("modules", modules);
		if (psbl)
			ret.put("authenticated", true);
		if (only)
			ret.put("authenticated_only", true);
		ret.put("service_name", service.getName());
		return ret;
	}

    private static void initPythonPackages(String relativePyPath, FileSaver output, boolean client) throws Exception {
        String path = relativePyPath;
        while (true) {
            int pos = path.lastIndexOf("/");
            if (pos < 0)
                break;
            path = path.substring(0, pos);
            if (path.isEmpty())
                break;
            String initPath = path + "/__init__.py";
            File prevFile = output.getAsFileOrNull(initPath);
            if (prevFile == null || !prevFile.exists()) {
                output.openWriter(initPath).close();
            }
        }

        copyResourceFile(relativePyPath, output, "authclient.py");
        if (client) {
            copyResourceFile(relativePyPath, output, "baseclient.py");
        }
    }

    private static void copyResourceFile(
            final String relativePath,
            final FileSaver output,
            final String file)
            throws IOException {
        final Path filepath;
        if (Paths.get(relativePath).getParent() == null) {
            filepath = Paths.get(file);
        } else {
            filepath = Paths.get(relativePath).getParent()
                    .resolve(file);
        }
        try (final InputStream input =
                TemplateFormatter.getResource(file);
             final Writer w = output.openWriter(filepath.toString())) {
            IOUtils.copy(input, w);
        }
    }

    public static String fixPath(String path, String div) {
        return path.replace(div, "/");
    }
}
