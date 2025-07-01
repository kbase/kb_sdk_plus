package us.kbase.sdk.validator;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import us.kbase.kidl.KbFuncdef;
import us.kbase.kidl.KbList;
import us.kbase.kidl.KbModule;
import us.kbase.kidl.KbModuleComp;
import us.kbase.kidl.KbScalar;
import us.kbase.kidl.KbService;
import us.kbase.kidl.KbStruct;
import us.kbase.kidl.KbStructItem;
import us.kbase.kidl.KbTuple;
import us.kbase.kidl.KbType;
import us.kbase.kidl.KbTypedef;
import us.kbase.kidl.KidlParser;
import us.kbase.narrativemethodstore.NarrativeMethodStoreClient;
import us.kbase.narrativemethodstore.ValidateMethodParams;
import us.kbase.narrativemethodstore.ValidationResults;
import us.kbase.sdk.common.KBaseYmlConfig;
import us.kbase.sdk.common.TestLocalManager;
import us.kbase.sdk.common.TestLocalManager.TestLocalInfo;


public class ModuleValidator {
	
	private String modulePath;
	private boolean verbose;

	public ModuleValidator(final String modulePath, final boolean verbose) throws Exception {
		this.modulePath = modulePath;
		this.verbose = verbose;
	}

	private String getMethodStoreUrl(final Path mod, final KBaseYmlConfig kyc) throws IOException {
		// ensure test_local/test.cfg exists before trying to get a url from it
		final TestLocalInfo tlm = TestLocalManager.ensureTestLocal(
				mod,
				kyc.getModuleName(),
				kyc.getDataVersion()
		);
		final Path tltc = TestLocalManager.getTestCfgRelative();
		// everything except the happy path is currently tested manually
		final Properties props = new Properties();
		try (final InputStream is = new FileInputStream(tlm.getTestCfgFile().toFile());) {
			props.load(is);
		} catch (Exception e) {
			throw new IOException(String.format(
					"Could not read %s file: " + e.getMessage(), tltc), e
			);
		}
		final String endPoint = props.getProperty("kbase_endpoint");
		if (endPoint == null) {
			throw new IllegalStateException(String.format(
					"%s file is missing the kbase_endpoint property", tltc
			));
		}
		final String msurl = endPoint + "/narrative_method_store/rpc";
		
		System.out.println(String.format(
				"Using Narrative Method Store URL from %s for module validation:\n%s", tltc, msurl
		));
		return msurl;
	}

    private static boolean isModuleDir(File dir) {
        return  new File(dir, "Dockerfile").exists() &&
                new File(dir, "Makefile").exists() &&
                new File(dir, KBaseYmlConfig.KBASE_YAML).exists() &&
                new File(dir, "lib").exists() &&
                new File(dir, "scripts").exists() &&
                new File(dir, "test").exists() &&
                new File(dir, "ui").exists();
    }

	public int validate() {

		int errors = 0;

		// TODO CODE remove this crufty loop
		// TODO CODE this whole module needs a rewrite
		for(String modulePathString : Arrays.asList(modulePath)) {
			File module = new File(modulePathString);
			System.out.println("\nValidating module in ("+module+")");
			
			if(!module.exists()) {
				System.err.println("  **ERROR** - the module does not exist");
				errors++; continue;
			}
			if(!module.isDirectory()) {
				System.err.println("  **ERROR** - the module location is not a directory.");
				errors++; continue;
			}
			
			try {
				if(verbose) System.out.println("  - canonical path = "+module.getCanonicalPath()+"");
	            File dir = module.getCanonicalFile();
	            while (!isModuleDir(dir)) {
	                dir = dir.getParentFile();
	                if (dir == null)
	                    throw new IllegalStateException("  **ERROR** - cannot find folder with module structure");
	            }
	            module = dir;
			} catch (IOException e) {
				System.err.println("  **ERROR** - unable to extract module canonical path:");
				System.err.println("                " + e.getMessage());
			}

			// 1) Validate the configuration file
			final KBaseYmlConfig kyc;
			try {
				final Path kbaseYmlFile = module.toPath().toAbsolutePath()
						.resolve(KBaseYmlConfig.KBASE_YAML);
				if (verbose) {
					System.out.println("  - configuration file = " + kbaseYmlFile);
				}
				kyc = new KBaseYmlConfig(module.toPath());
				if (verbose) {
					System.out.println("  - configuration file %s is valid YAML");
				}
			} catch (Exception e) {
				System.err.println("  **ERROR** - configuration file validation failed:");
				System.err.println("                " + e.getMessage());
				errors++;
				continue;
			}
			
			String methodStoreUrl = null;
			try {
				methodStoreUrl = getMethodStoreUrl(module.toPath(), kyc);
			} catch (IOException | IllegalStateException e) {
				System.err.println("  **ERROR** - getting Narrative Method Store URL failed:");
				System.err.println("                " + e.getMessage());
				errors++;
				continue;
			}

			KbModule parsedKidl = null;
            try {
                final String moduleName = kyc.getModuleName();
                File specFile = new File(module, moduleName + ".spec");
                if (!specFile.exists())
                    throw new IllegalStateException("Spec-file isn't found: " + specFile);
                List<KbService> services = KidlParser.parseSpec(KidlParser.parseSpecInt(specFile, null));
                if (services.size() != 1)
                    throw new IllegalStateException("Unexpected number of services found: " + services.size());
                KbService srv = services.get(0);
                if (srv.getModules().size() != 1)
                    throw new IllegalStateException("Unexpected number of modules found: " + srv.getModules().size());
                parsedKidl = srv.getModules().get(0);
            } catch (Exception e) {
                System.err.println("  **ERROR** - KIDL-spec validation failed:");
                System.err.println("                "+e.getMessage());
                errors++; continue;
            }

			// 2) Validate UI components
			
			//     2a) Validate Narrative Methods
			
			File uiNarrativeMethodsDir = new File(new File(new File(module, "ui"), "narrative"), "methods");
			if (uiNarrativeMethodsDir.exists()) {
			    for (File methodDir : uiNarrativeMethodsDir.listFiles()) {
			        if (methodDir.isDirectory()) {
			            System.out.println("\nValidating method in ("+methodDir+")");
			            try {
			                int status = validateMethodSpec(methodDir, parsedKidl, methodStoreUrl);
			                if (status != 0) {
			                    errors++; 
			                    continue;
			                }
			            } catch (Exception e) {
			                System.err.println("  **ERROR** - method-spec validation failed:");
			                System.err.println("                "+e.getMessage());
			                errors++; continue;
			            }
			        }
			    }
			}
			
		}
		if (errors > 0) {
			System.out.println("\n\nThis module contains errors.\n");
			return 1;
		}
		System.out.println("\n\nCongrats- this module is valid.\n");
		return 0;
	}
	
	private int validateMethodSpec(
			final File methodDir,
			final KbModule parsedKidl,
			final String methodStoreUrl
			) throws IOException {
	    NarrativeMethodStoreClient nms = new NarrativeMethodStoreClient(new URL(methodStoreUrl));
	    nms.setAllSSLCertificatesTrusted(true);
	    nms.setIsInsecureHttpConnectionAllowed(true);
	    String spec = FileUtils.readFileToString(new File(methodDir, "spec.json"));
        String display = FileUtils.readFileToString(new File(methodDir, "display.yaml"));
        Map<String, String> extraFiles = new LinkedHashMap<String, String>();
        for (File f : methodDir.listFiles()) {
            if (f.isFile() && f.getName().endsWith(".html"))
                extraFiles.put(f.getName(), FileUtils.readFileToString(f));
        }
        try {
            ValidationResults vr = nms.validateMethod(new ValidateMethodParams().withId(
                    methodDir.getName()).withSpecJson(spec).withDisplayYaml(display)
                    .withExtraFiles(extraFiles));
            if (vr.getIsValid() == 1L) {
                if (vr.getWarnings().size() > 0) {
                    System.err.println("  **WARNINGS** - method-spec validation:");
                    for (int num = 0; num < vr.getWarnings().size(); num++) {
                        String warn = vr.getWarnings().get(num);
                        System.err.println("                (" + (num + 1) + ") " + warn);
                    }
                }
                validateMethodSpecMapping(spec, parsedKidl);
                return 0;
            }
            System.err.println("  **ERROR** - method-spec validation failed:");
            for (int num = 0; num < vr.getErrors().size(); num++) {
                String error = vr.getErrors().get(num);
                System.err.println("                (" + (num + 1) + ") " + error);
            }
            return  1;
        } catch (Exception e) {
            System.err.println("  **ERROR** - method-spec validation failed:");
            System.err.println("                "+e.getMessage());
            return 1;
        }
	}

	public static void validateMethodSpecMapping(String specText, KbModule parsedKidl
            ) throws IOException {
        JsonNode spec = new ObjectMapper().readTree(specText);
        JsonNode behaviorNode = get("/", spec, "behavior");
        if (behaviorNode.get("none") != null) {
            return;  // Don't pay attention at viewer methods (since they don't use back-end)
        }
        final String jobId = get("/", spec, "job_id_output_field").asText();
        if (!jobId.equals("docker")) { // tested manually
            throw new IllegalStateException("  **ERROR** - can't find \"docker\" value within path " +
                    "[job_id_output_field] in spec.json");
        }
        JsonNode parametersNode = get("/", spec, "parameters");
        Map<String, JsonNode> inputParamIdToType = new TreeMap<String, JsonNode>();
        for (int i = 0; i < parametersNode.size(); i++) {
            JsonNode paramNode = parametersNode.get(i);
            String paramPath = "parameters/" + i;
            String paramId = get(paramPath, paramNode, "id").asText();
            inputParamIdToType.put(paramId, paramNode);
        }
        Set<String> paramsUsed = new TreeSet<String>();
        JsonNode groupsNode = spec.get("parameter-groups");
        if (groupsNode != null) {
            for (int i = 0; i < groupsNode.size(); i++) {
                JsonNode groupNode = groupsNode.get(i);
                String groupPath = "parameter-groups/" + i;
                String paramId = get(groupPath, groupNode, "id").asText();
                inputParamIdToType.put(paramId, groupNode);
                JsonNode usedParamIdsNode = groupNode.get("parameters");
                if (usedParamIdsNode != null) {
                    for (int j = 0; j < usedParamIdsNode.size(); j++) {
                        String usedParamId = usedParamIdsNode.get(j).asText();
                        paramsUsed.add(usedParamId);
                    }
                }
            }
        }
        JsonNode serviceMappingNode = get("behavior", behaviorNode, "service-mapping");
        String moduleName = get("behavior/service-mapping", serviceMappingNode, "name").asText();
        String methodName = get("behavior/service-mapping", serviceMappingNode, "method").asText();
        if (methodName.contains(".")) {
            String[] parts = methodName.split(Pattern.quote("."));
            moduleName = parts[0];
            methodName = parts[1];
        }
        KbFuncdef func = null;
        for (KbModuleComp mc : parsedKidl.getModuleComponents()) {
            if (mc instanceof KbFuncdef) {
                KbFuncdef f = (KbFuncdef)mc;
                if (f.getName().equals(methodName)) {
                    func = f;
                    break;
                }
            }
        }
        if (func == null) {
            throw new IllegalStateException("  **ERROR** - unknown method \"" + 
                    methodName + "\" defined within path " +
                    "[behavior/service-mapping/method] in spec.json");
        }
        if (!parsedKidl.getModuleName().equals(moduleName)) {
            throw new IllegalStateException("  **ERROR** - value doesn't match " +
                    "\"" + parsedKidl.getModuleName() + "\" within path " +
                    "[behavior/service-mapping/name] in spec.json");
        }
        String serviceUrl = get("behavior/service-mapping", serviceMappingNode, "url").asText();
        if (serviceUrl.length() > 0) {
            throw new IllegalStateException("  **ERROR** - async method has non-empty value within path " +
                    "[behavior/service-mapping/url] in spec.json");
        }
        JsonNode paramsMappingNode = get("behavior/service-mapping", serviceMappingNode, "input_mapping");
        Set<Integer> argsUsed = new TreeSet<Integer>();
        for (int j = 0; j < paramsMappingNode.size(); j++) {
            JsonNode paramMappingNode = paramsMappingNode.get(j);
            String path = "behavior/service-mapping/input_mapping/" + j;
            JsonNode targetArgPosNode = paramMappingNode.get("target_argument_position");
            int targetArgPos = 0;
            if (targetArgPosNode != null && !targetArgPosNode.isNull())
                targetArgPos = targetArgPosNode.asInt();
            if (targetArgPos >= func.getParameters().size()) {
                throw new IllegalStateException("  **ERROR** - value " + targetArgPos + " within " +
                		"path [" + path + "/target_argument_position] in spec.json is out of " +
                        "bounds (number of arguments defined for function \"" + methodName + "\" " + 
                		"is " + func.getParameters().size() + ")");
            }
            argsUsed.add(targetArgPos);
            KbType argType = func.getParameters().get(targetArgPos).getType();
            while (argType instanceof KbTypedef) {
                KbTypedef ref = (KbTypedef)argType;
                argType = ref.getAliasType();
            }
            JsonNode targetPropNode = paramMappingNode.get("target_property");
            if (targetPropNode != null && !targetPropNode.isNull()) {
                String targetProp = targetPropNode.asText();
                if (argType instanceof KbScalar || argType instanceof KbList ||
                        argType instanceof KbTuple) {
                    throw new IllegalStateException("  **ERROR** - value " + targetProp + " within " +
                            "path [" + path + "/target_property] in spec.json can't be applied to " +
                            "type " + argType.getClass().getSimpleName() + " (defined for argument " + 
                            targetArgPos + ")");
                }
                if (argType instanceof KbStruct) {
                    KbStruct struct = (KbStruct)argType;
                    boolean found = false;
                    for (KbStructItem item : struct.getItems()) {
                        if (item.getName() != null && item.getName().equals(targetProp)) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        System.err.println("  **WARNINGS** - value \"" + targetProp + "\" within " +
                        		"path [" + path + "/target_property] in spec.json doesn't match " +
                        		"any field of structure defined as argument type" + 
                                (struct.getName() != null ? (" (" + struct.getName() + ")") : ""));
                    }
                }
            }
            JsonNode inputParamObj = paramMappingNode.get("input_parameter");
            if (inputParamObj != null && !inputParamObj.isNull()) {
                String inputParamId = inputParamObj.asText();
                if (!inputParamIdToType.containsKey(inputParamId)) {
                    throw new IllegalStateException("  **ERROR** - value \"" + inputParamId + "\" " +
                            "within path [" + path + "/input_parameter] in spec.json is not any " +
                            "input ID listed in \"parameters\" block");
                }
                paramsUsed.add(inputParamId);
            }
        }
        if (func.getParameters().size() != argsUsed.size()) {
            throw new IllegalStateException("  **ERROR** - not all arguments are set for function " +
            		"\"" + func.getName() + "\", list of defined arguments is: " + argsUsed);
        }
        if (inputParamIdToType.size() != paramsUsed.size()) {
            Set<String> paramsNotUsed = new TreeSet<String>(inputParamIdToType.keySet());
            paramsNotUsed.removeAll(paramsUsed);
            System.err.println("  **WARNINGS** - some of input parameters are not used: " + 
                    paramsNotUsed);
        }
	}
	
	private static JsonNode get(String nodePath, JsonNode node, String childName) {
	    JsonNode ret = node.get(childName);
	    if (ret == null)
	        throw new IllegalStateException("  **ERROR** - can't find sub-node [" + childName + 
	                "] within path [" + nodePath + "] in spec.json");
	    return ret;
	}

}
