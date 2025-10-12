package us.kbase.sdk.compiler;

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
import us.kbase.sdk.templates.TemplateFormatter;
import us.kbase.sdk.util.FileSaver;

public class TemplateBasedGenerator {

    public static boolean genPythonServer(boolean genPythonServer,
            String pythonServerName, String pythonImplName) {
        return genPythonServer || pythonServerName != null || pythonImplName != null;
    }

    // TDDO CODE now that everything except python is removed, this could probably use
    //           refactoring to make it simpler and less error prone
    @SuppressWarnings("unchecked")
    public static void generate(List<KbService> srvs, String defaultUrl, 
            boolean genPython, String pythonClientName, boolean genPythonServer,
            String pythonServerName, String pythonImplName, IncludeProvider ip, FileSaver output,
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
        genPythonServer = genPythonServer(genPythonServer, pythonServerName, pythonImplName);
        if (genPythonServer) {
            if (pythonServerName == null)
                pythonServerName = service.getName() + "Server";
        }
        if (genPython && pythonClientName == null)
            pythonClientName = service.getName() + "Client";
        final Map<String, Object> context = generateTemplateData(service, pythonImplName);
        if (defaultUrl != null)
            context.put("default_service_url", defaultUrl);
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
        if (pythonClientName != null) {
            String pythonClientPath = fixPath(pythonClientName, ".") + ".py";
            initPythonPackages(pythonClientPath, output, true);
            Writer pythonClient = output.openWriter(pythonClientPath);
            TemplateFormatter.formatTemplate("python_client", context, pythonClient);
            pythonClient.close();
        }
        //////////////////////////////////////// Servers /////////////////////////////////////////
        // AFAICT at this point either
        //     * the python server name is null and getPythonServer is false
        //     * the python server name is supplied and getPythonServer is true
        // so one implies the other
        if (pythonServerName != null) {
            String pythonServerPath = fixPath(pythonServerName, ".") + ".py";
            initPythonPackages(pythonServerPath, output, false);
            Writer pythonServer = output.openWriter(pythonServerPath);
            TemplateFormatter.formatTemplate("python_server", context, pythonServer);
            pythonServer.close();
        }
        if (genPythonServer) {
            List<Map<String, Object>> modules = (List<Map<String, Object>>)context.get("modules");
            for (int modulePos = 0; modulePos < modules.size(); modulePos++) {
                Map<String, Object> module = new LinkedHashMap<String, Object>(modules.get(modulePos));
                module.put("semantic_version", semanticVersion);
                module.put("git_url", gitUrl);
                module.put("git_commit_hash", gitCommitHash);
                List<Map<String, Object>> methods = (List<Map<String, Object>>)module.get("methods");
                List<String> methodNames = new ArrayList<String>();
                for (Map<String, Object> method : methods)
                    methodNames.add(method.get("name").toString());
                String pythonImplPath = null;
                if (genPythonServer) {
                    String pythonModuleImplName = (String) module.get("pymodule");
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
                Map<String, Object> moduleContext = new LinkedHashMap<String, Object>();
                moduleContext.put("module", module);
                moduleContext.put("empty_escaper", "");  // ${empty_escaper}
                moduleContext.put("display", new StringUtils());
                if (genPythonServer) {
                    Writer pythonImpl = output.openWriter(pythonImplPath);
                    TemplateFormatter.formatTemplate("python_impl", moduleContext, pythonImpl);
                    pythonImpl.close();
                }
            }
        }
    }

	private static Map<String, Object> generateTemplateData(
			final KbService service,
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

        if (client) {
            copyResourceFile(relativePyPath, output, "baseclient.py");
        } else {
            copyResourceFile("biokbase/log.py", output, "log.py");
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
        try (
            final InputStream input = TemplateFormatter.getResource(file);
            final Writer w = output.openWriter(filepath.toString())
        ) {
            IOUtils.copy(input, w);
        }
    }

    public static String fixPath(String path, String div) {
        return path.replace(div, "/");
    }
}
