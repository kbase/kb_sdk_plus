package us.kbase.mobu.compiler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.net.URI;
import java.net.URL;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import us.kbase.kidl.KbAuthdef;
import us.kbase.kidl.KbBasicType;
import us.kbase.kidl.KbFuncdef;
import us.kbase.kidl.KbList;
import us.kbase.kidl.KbMapping;
import us.kbase.kidl.KbModule;
import us.kbase.kidl.KbModuleComp;
import us.kbase.kidl.KbParameter;
import us.kbase.kidl.KbScalar;
import us.kbase.kidl.KbService;
import us.kbase.kidl.KbStruct;
import us.kbase.kidl.KbStructItem;
import us.kbase.kidl.KbTuple;
import us.kbase.kidl.KbType;
import us.kbase.kidl.KbTypedef;
import us.kbase.kidl.KbUnspecifiedObject;
import us.kbase.kidl.KidlParseException;
import us.kbase.kidl.KidlParser;
import us.kbase.kidl.Utils;
import us.kbase.mobu.util.DiskFileSaver;
import us.kbase.mobu.util.FileSaveCodeWriter;
import us.kbase.mobu.util.FileSaver;
import us.kbase.mobu.util.OneFileSaver;
import us.kbase.mobu.util.TextUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.googlecode.jsonschema2pojo.DefaultGenerationConfig;
import com.googlecode.jsonschema2pojo.Jackson2Annotator;
import com.googlecode.jsonschema2pojo.Schema;
import com.googlecode.jsonschema2pojo.SchemaGenerator;
import com.googlecode.jsonschema2pojo.SchemaMapper;
import com.googlecode.jsonschema2pojo.rules.Rule;
import com.googlecode.jsonschema2pojo.rules.RuleFactory;
import com.sun.codemodel.JBlock;
import com.sun.codemodel.JCodeModel;
import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JExpr;
import com.sun.codemodel.JExpression;
import com.sun.codemodel.JFieldVar;
import com.sun.codemodel.JMethod;
import com.sun.codemodel.JMod;
import com.sun.codemodel.JOp;
import com.sun.codemodel.JPackage;
import com.sun.codemodel.JType;

public class JavaTypeGenerator {
	private static final char[] propWordDelim = {'_', '-'};

	private static final String defaultParentPackage = "us.kbase";
	private static final String utilPackage = defaultParentPackage + ".common.service";
	
	public static JavaData processSpec(File specFile, File srcOutDir, String packageParent, 
			boolean createServer, File libOutDir, String gwtPackage, URL url) throws Exception {
		List<KbService> services = KidlParser.parseSpec(specFile, null);
		FileSaver libOut = libOutDir == null ? null : new DiskFileSaver(libOutDir);
		FileSaver buildXmlOut = null == null ? null : new OneFileSaver(null);
		return processSpec(services, new DiskFileSaver(srcOutDir), packageParent, createServer,
				libOut, gwtPackage, url, buildXmlOut, null, null, null, null, null, null, null);
	}

	public static JavaData processSpec(List<KbService> services, FileSaver srcOut, String packageParent, 
			boolean createServer, FileSaver libOut, String gwtPackage, URL url,
			FileSaver buildXml, String clientAsyncVersion, String clientDynservVersion,
			String semanticVersion, String gitUrl, String gitCommitHash) throws Exception {
		return processSpec(services, srcOut, packageParent, createServer, libOut, gwtPackage, url,
				buildXml, clientAsyncVersion, clientDynservVersion, semanticVersion,
				gitUrl, gitCommitHash, null, null);
	}

	public static JavaData processSpec(List<KbService> services, FileSaver srcOut, 
			String packageParent, boolean createServer, FileSaver libOut, String gwtPackage,
			URL url, FileSaver buildXml, String clientAsyncVersion,
			String clientDynservVersion, String semanticVersion, String gitUrl, 
			String gitCommitHash, Map<String, String> originalCode,
			String customClientClassName) throws Exception {
		JavaData data = prepareDataStructures(services);
		outputData(data, srcOut, packageParent, createServer, libOut, gwtPackage, url, buildXml,
				clientAsyncVersion, clientDynservVersion, semanticVersion, gitUrl,
				gitCommitHash, originalCode, customClientClassName);
		return data;
	}

	public static JavaData parseSpec(File specFile) throws Exception {
		List<KbService> services = KidlParser.parseSpec(specFile, null);
		return prepareDataStructures(services);
	}

	private static JavaData prepareDataStructures(List<KbService> services) {
		Set<JavaType> nonPrimitiveTypes = new TreeSet<JavaType>();
		JavaData data = new JavaData();
		for (KbService service: services) {
			for (KbModule module : service.getModules()) {
				List<JavaFunc> funcs = new ArrayList<JavaFunc>();
				Set<Integer> tupleTypes = data.getTupleTypes();
				for (KbModuleComp comp : module.getModuleComponents()) {
					if (comp instanceof KbFuncdef) {
						String moduleName = module.getModuleName();
						KbFuncdef func = (KbFuncdef)comp;
						String funcJavaName = TextUtils.inCamelCase(func.getName());
						List<JavaFuncParam> params = new ArrayList<JavaFuncParam>();
						for (KbParameter param : func.getParameters()) {
							JavaType type = findBasic(param.getType(), module.getModuleName(), 
							        nonPrimitiveTypes, tupleTypes);
							params.add(new JavaFuncParam(param, 
							        TextUtils.inCamelCase(param.getName()), type));
						}
						List<JavaFuncParam> returns = new ArrayList<JavaFuncParam>();
						for (KbParameter param : func.getReturnType()) {
							JavaType type = findBasic(param.getType(), module.getModuleName(), 
							        nonPrimitiveTypes, tupleTypes);
							returns.add(new JavaFuncParam(param, param.getName() == null ? null : 
							    TextUtils.inCamelCase(param.getName()), type));
						}
						JavaType retMultiType = null;
						if (returns.size() > 1) {
							List<KbType> subTypes = new ArrayList<KbType>();
							for (JavaFuncParam retPar : returns)
								subTypes.add(retPar.getOriginal().getType());
							KbTuple tuple = new KbTuple(subTypes);
							retMultiType = new JavaType(null, tuple, moduleName, new ArrayList<KbTypedef>());
							for (JavaFuncParam retPar : returns)
								retMultiType.addInternalType(retPar.getType());
							tupleTypes.add(returns.size());
						}
						funcs.add(new JavaFunc(moduleName, func, funcJavaName, params, returns, retMultiType));
					} else if (comp instanceof KbAuthdef) {
						//skip
					} else {
						findBasic((KbTypedef)comp, module.getModuleName(), nonPrimitiveTypes, tupleTypes);
					}
				}
				data.addModule(module, funcs);
			}
		}
		data.setTypes(nonPrimitiveTypes);
		return data;
	}

	private static void outputData(JavaData data, FileSaver srcOutDir, String packageParent, 
			boolean createServers, FileSaver libOutDir, String gwtPackage, URL url,
			FileSaver buildXml, String clientAsyncVersion,
			String clientDynservVersion, String semanticVersion, String gitUrl, 
			String gitCommitHash, Map<String, String> originalCode,
			String customClientClassName) throws Exception {
	    if (packageParent.equals("."))  // Special value meaning top level package.
	        packageParent = "";
		generatePojos(data, srcOutDir, packageParent);
		generateTupleClasses(data,srcOutDir, packageParent);
		generateClientClass(data, srcOutDir, packageParent, url, clientAsyncVersion, 
		        clientDynservVersion, customClientClassName);
		if (createServers)
			generateServerClass(data, srcOutDir, packageParent, semanticVersion, gitUrl, 
			        gitCommitHash, originalCode);
		List<String> jars = checkLibs(libOutDir, createServers, buildXml);
		generateBuildXml(data, jars, createServers, buildXml, clientAsyncVersion);
		if (gwtPackage != null) {
			GwtGenerator.generate(data, srcOutDir, gwtPackage);
		}
	}

	private static void generatePojos(JavaData data, FileSaver srcOutDir, 
	        String packageParent) throws Exception {
        InMemorySchemaStore ss = new InMemorySchemaStore();
		for (JavaType type : data.getTypes()) {
		    URI id = new URI("file:/" + type.getModuleName() + "/" + type.getJavaClassName() + 
		            ".json");
			Set<Integer> tupleTypes = data.getTupleTypes();
			ByteArrayOutputStream jsonFile = new ByteArrayOutputStream();
			writeJsonSchema(jsonFile, packageParent, type, tupleTypes);
			jsonFile.close();
			InputStream is = new ByteArrayInputStream(jsonFile.toByteArray());
			ss.addSchema(id, is);
			is.close();
		}
		JCodeModel codeModel = new JCodeModel();
		DefaultGenerationConfig cfg = new DefaultGenerationConfig() {
			@Override
			public char[] getPropertyWordDelimiters() {
				return propWordDelim;
			}
			@Override
			public boolean isIncludeHashcodeAndEquals() {
				return false;
			}
			@Override
			public boolean isIncludeToString() {
				return false;
			}
			@Override
			public boolean isIncludeJsr303Annotations() {
				return false;
			}
			@Override
			public boolean isGenerateBuilders() {
				return true;
			}
			@Override
			public boolean isUseLongIntegers() {
				return true;
			}
		};
		RuleFactory rf = new RuleFactory(cfg, new Jackson2Annotator(), ss) {
			@Override
			public Rule<JPackage, JType> getObjectRule() {
				return new JsonSchemaToPojoCustomObjectRule(this) {
					@Override
					public JType apply(String nodeName, JsonNode node,
							JPackage _package, Schema schema) {
						JType jclass = super.apply(nodeName, node, _package, schema);
						if (jclass instanceof JDefinedClass) {
							addToString((JDefinedClass)jclass);
						}
						return jclass;
					}
					
				    private void addToString(JDefinedClass jclass) {
				    	if (jclass.getMethod("toString", new JType[0]) != null)
				    		return;
				        JMethod toString = jclass.method(JMod.PUBLIC, String.class, "toString");
				        JBlock body = toString.body();
				        JExpression ret = JExpr.lit(jclass.name());
				        boolean firstField = true;
				        for (Map.Entry<String, JFieldVar> entry : jclass.fields().entrySet()) {
				        	ret = JOp.plus(ret, JExpr.lit((firstField ? " [" : ", ") + entry.getKey() + "="));
				        	ret = JOp.plus(ret, entry.getValue());
				        	firstField = false;
				        }
				        ret = JOp.plus(ret, JExpr.lit("]"));
				        body._return(ret);
				        toString.annotate(Override.class);
				    }
				};
			}
			
			@Override
			public Rule<JFieldVar, JFieldVar> getDefaultRule() {
				return new Rule<JFieldVar, JFieldVar>() {
					@Override
					public JFieldVar apply(String nodeName, JsonNode node,
							JFieldVar field, Schema currentSchema) {
						return field;
					}
				};
			}
		};
		SchemaGenerator sg = new SchemaGenerator();
		SchemaMapper sm = new SchemaMapper(rf, sg);
		for (JavaType type : data.getTypes()) {
			URL source = new URL("file:/" + type.getModuleName() + "/" + type.getJavaClassName() + ".json");
			sm.generate(codeModel, type.getJavaClassName(), "", source);
		}
		FileSaveCodeWriter codeWriter = new FileSaveCodeWriter(srcOutDir);
		codeModel.build(codeWriter, codeWriter);
	}
	
	private static void generateTupleClasses(JavaData data, FileSaver srcOutDir, String packageParent) throws Exception {
		Set<Integer> tupleTypes = data.getTupleTypes();
		if (tupleTypes.size() > 0) {
			String utilDir = utilPackage.replace('.', '/');
			for (int tupleType : tupleTypes) {
				if (tupleType < 1)
					throw new KidlParseException("Wrong tuple type: " + tupleType);
				String tupleFile = utilDir + "/Tuple" + tupleType + ".java";
				StringBuilder sb = new StringBuilder();
				for (int i = 0; i < tupleType; i++) {
					if (sb.length() > 0)
						sb.append(", ");
					sb.append('T').append(i+1);
				}
				List<String> classLines = new ArrayList<String>(Arrays.asList(
						"package " + utilPackage + ";",
						"",
						"import java.util.HashMap;",
						"import java.util.Map;",
						"import com.fasterxml.jackson.annotation.JsonAnyGetter;",
						"import com.fasterxml.jackson.annotation.JsonAnySetter;",
						"",
						"public class Tuple" + tupleType + " <" + sb + "> {"
						));
				for (int i = 0; i < tupleType; i++) {
					classLines.add("    private T" + (i + 1) + " e" + (i + 1) + ";");
				}
				classLines.add("    private Map<String, Object> additionalProperties = new HashMap<String, Object>();");
				StringBuilder toStr = new StringBuilder();
				for (int i = 0; i < tupleType; i++) {
					classLines.addAll(Arrays.asList(
							"",
							"    public T" + (i + 1) + " getE" + (i + 1) + "() {",
							"        return e" + (i + 1) + ";",
							"    }",
							"",
							"    public void setE" + (i + 1) + "(T" + (i + 1) + " e" + (i + 1) + ") {",
							"        this.e" + (i + 1) + " = e" + (i + 1) + ";",
							"    }",
							"",
							"    public Tuple" + tupleType + "<" + sb + "> withE" + (i + 1) + "(T" + (i + 1) + " e" + (i + 1) + ") {",
							"        this.e" + (i + 1) + " = e" + (i + 1) + ";",
							"        return this;",
							"    }"
							));
					if (i > 0)
						toStr.append(", ");
					toStr.append("e").append(i + 1).append("=\" + ").append("e").append(i + 1).append(" + \"");
				}
				classLines.addAll(Arrays.asList(
						"",
						"    @Override",
						"    public String toString() {",
						"        return \"Tuple" + tupleType + " [" + toStr + "]\";",
						"    }",
						"",
						"    @JsonAnyGetter",
						"    public Map<String, Object> getAdditionalProperties() {",
						"        return this.additionalProperties;",
						"    }",
						"",
						"    @JsonAnySetter",
						"    public void setAdditionalProperties(String name, Object value) {",
						"        this.additionalProperties.put(name, value);",
						"    }",
						"}"
						));
				TextUtils.writeFileLines(classLines, srcOutDir.openWriter(tupleFile));
			}
		}
	}

	private static void generateClientClass(JavaData data, FileSaver srcOutDir,
			String packageParent, URL url, String asyncVersion, String dynservVersion,
			String clientClassName) throws Exception {
        if (asyncVersion != null) {
            if (Pattern.compile("[^a-zA-Z0-9]").matcher(asyncVersion).find())
                throw new IllegalStateException("Unsupported non-alfanumeric characters in client " +
                        "asynchronous version: " + asyncVersion);
        }
		Map<String, JavaType> originalToJavaTypes = getOriginalToJavaTypesMap(data);
		for (JavaModule module : data.getModules()) {
			String moduleDir = sub(packageParent, module.getModulePackage()).replace('.', '/');
			JavaImportHolder model = new JavaImportHolder(sub(packageParent, module.getModulePackage()));
			if (clientClassName == null) {
			    clientClassName = TextUtils.capitalize(module.getModuleName()) + "Client";
			}
			String classFile = moduleDir + "/" + clientClassName + ".java";
			String callerClass = model.ref(utilPackage + ".JsonClientCaller");
			boolean anyAuth = false;
			for (JavaFunc func : module.getFuncs()) {
				if (func.isAuthCouldBeUsed()) {
					anyAuth = true;
					break;
				}
			}
            boolean anyAsync = asyncVersion != null;
            String serviceVersion = null;
            if (asyncVersion != null) {
                serviceVersion = asyncVersion;
            } else if (dynservVersion != null) {
                serviceVersion = dynservVersion;
            }
			List<String> classLines = new ArrayList<String>();
			String urlClass = model.ref("java.net.URL");
			String tokenClass = model.ref("us.kbase.auth.AuthToken");
			printModuleComment(module, classLines);
			classLines.addAll(Arrays.asList(
					"public class " + clientClassName + " {",
					"    private " + callerClass + " caller;"
					));
			if (anyAsync) {
			    classLines.add("    private long asyncJobCheckTimeMs = 100;");
                classLines.add("    private int asyncJobCheckTimeScalePercent = 150;");
                classLines.add("    private long asyncJobCheckMaxTimeMs = 300000;  // 5 minutes");
			}
            classLines.add("    private String serviceVersion = " + 
                    (serviceVersion == null ? "null" : ("\"" + serviceVersion + "\"")) + ";");
            if (url != null) {
                classLines.addAll(Arrays.asList(
                        "    private static URL DEFAULT_URL = null;",
                        "    static {",
                        "        try {",
                        "            DEFAULT_URL = new URL(\"" + url + "\");",
                        "        } catch (" + model.ref("java.net.MalformedURLException") + " mue) {",
                        "            throw new RuntimeException(\"Compile error in client - bad url compiled\");",
                        "        }",
                        "    }",
                        "",
                        "    /** Constructs a client with the default url and no user credentials.*/",
                        "    public " + clientClassName + "() {",
                        "       caller = new " + callerClass + "(DEFAULT_URL);"
                        ));
                if (dynservVersion != null) {
                    classLines.add(
                            "        caller.setDynamic(true);"
                            );
                }
                classLines.addAll(Arrays.asList(
                        "    }"
                        ));
			}
			classLines.addAll(Arrays.asList(
					"",
					"",
					"    /** Constructs a client with a custom URL and no user credentials.",
					"     * @param url the URL of the service.",
					"     */",
					"    public " + clientClassName + "(" + urlClass + " url) {",
					"        caller = new " + callerClass + "(url);"
                    ));
            if (dynservVersion != null) {
                classLines.add(
                        "        caller.setDynamic(true);"
                        );
            }
            classLines.addAll(Arrays.asList(
                    "    }"
                    ));
            if (anyAuth) {
                //TODO update java common & remove exceptions
                classLines.addAll(Arrays.asList(
                        "    /** Constructs a client with a custom URL.",
                        "     * @param url the URL of the service.",
                        "     * @param token the user's authorization token.",
                        "     * @throws UnauthorizedException if the token is not valid.",
                        "     * @throws IOException if an IOException occurs when checking the token's",
                        "     * validity.",
                        "     */",
                        "    public " + clientClassName + "(" + urlClass + " url, " +
                                model.ref("us.kbase.auth.AuthToken") + " token) throws " +
                                model.ref(utilPackage + ".UnauthorizedException") + ", " +
                                model.ref("java.io.IOException") + " {",
                        "        caller = new " + callerClass + "(url, token);"
                        ));
                if (dynservVersion != null) {
                    classLines.add(
                            "        caller.setDynamic(true);"
                            );
                }
                classLines.addAll(Arrays.asList(
                        "    }",
                        "",
                        "    /** Constructs a client with a custom URL.",
                        "     * @param url the URL of the service.",
                        "     * @param user the user name.",
                        "     * @param password the password for the user name.",
                        "     * @throws UnauthorizedException if the credentials are not valid.",
                        "     * @throws IOException if an IOException occurs when checking the user's",
                        "     * credentials.",
                        "     */",
                        "    public " + clientClassName + "(" + urlClass +
                                " url, String user, String password) throws " +
                                model.ref(utilPackage + ".UnauthorizedException") + ", " +
                                model.ref("java.io.IOException") + " {",
                        "        caller = new " + callerClass + "(url, user, password);"
                        ));
                if (dynservVersion != null) {
                    classLines.add(
                            "        caller.setDynamic(true);"
                            );
                }
                classLines.addAll(Arrays.asList(
                        "    }",
                        "",
                        "    /** Constructs a client with a custom URL",
                        "     * and a custom authorization service URL.",
                        "     * @param url the URL of the service.",
                        "     * @param user the user name.",
                        "     * @param password the password for the user name.",
                        "     * @param auth the URL of the authorization server.",
                        "     * @throws UnauthorizedException if the credentials are not valid.",
                        "     * @throws IOException if an IOException occurs when checking the user's",
                        "     * credentials.",
                        "     */",
                        "    public " + clientClassName + "(" + urlClass + 
                                " url, String user, String password, " +
                                urlClass + " auth) throws " + 
                                model.ref(utilPackage + ".UnauthorizedException") + ", " +
                                model.ref("java.io.IOException") + " {",
                        "        caller = new " + callerClass + "(url, user, password, auth);"
                        ));
                if (dynservVersion != null) {
                    classLines.add(
                            "        caller.setDynamic(true);"
                            );
                }
                classLines.addAll(Arrays.asList(
						"    }"
						));
				if (url != null) {
				//TODO update java common & remove exceptions
					classLines.addAll(Arrays.asList(
						"",
						"    /** Constructs a client with the default URL.",
						"     * @param token the user's authorization token.",
						"     * @throws UnauthorizedException if the token is not valid.",
						"     * @throws IOException if an IOException occurs when checking the token's",
						"     * validity.",
						"     */",
						"    public " + clientClassName + "(" + tokenClass + " token) throws " + 
								model.ref(utilPackage + ".UnauthorizedException") + ", " +
								model.ref("java.io.IOException") + " {",
						"        caller = new " + callerClass + "(DEFAULT_URL, token);"
	                        ));
	                if (dynservVersion != null) {
	                    classLines.add(
	                            "        caller.setDynamic(true);"
	                            );
	                }
	                classLines.addAll(Arrays.asList(
						"    }",
						"",
						"    /** Constructs a client with the default URL.",
						"     * @param user the user name.",
						"     * @param password the password for the user name.",
						"     * @throws UnauthorizedException if the credentials are not valid.",
						"     * @throws IOException if an IOException occurs when checking the user's",
						"     * credentials.",
						"     */",
						"    public " + clientClassName + "(String user, String password) throws " + 
								model.ref(utilPackage + ".UnauthorizedException") + ", " +
								model.ref("java.io.IOException") + " {",
						"        caller = new " + callerClass + "(DEFAULT_URL, user, password);"
	                        ));
	                if (dynservVersion != null) {
	                    classLines.add(
	                            "        caller.setDynamic(true);"
	                            );
	                }
	                classLines.addAll(Arrays.asList(
						"    }"
						));
				}
				classLines.addAll(Arrays.asList(
						"",
						"    /** Get the token this client uses to communicate with the server.",
						"     * @return the authorization token.",
						"     */",
						"    public " + tokenClass + " getToken() {",
						"        return caller.getToken();",
						"    }"
						));
			}
			classLines.addAll(Arrays.asList(
					"",
					"    /** Get the URL of the service with which this client communicates.",
					"     * @return the service URL.",
					"     */",
					"    public " + urlClass + " getURL() {",
					"        return caller.getURL();",
					"    }",
					"",
					"    /** Set the timeout between establishing a connection to a server and",
					"     * receiving a response. A value of zero or null implies no timeout.",
					"     * @param milliseconds the milliseconds to wait before timing out when",
					"     * attempting to read from a server.",
					"     */",
					"    public void setConnectionReadTimeOut(Integer milliseconds) {",
					"        this.caller.setConnectionReadTimeOut(milliseconds);",
					"    }",
					"",
					"    /** Check if this client allows insecure http (vs https) connections.",
					"     * @return true if insecure connections are allowed.",
					"     */",
					"    public boolean isInsecureHttpConnectionAllowed() {",
					"        return caller.isInsecureHttpConnectionAllowed();",
					"    }",
					"",
					"    /** Deprecated. Use isInsecureHttpConnectionAllowed().",
					"     * @deprecated",
					"     */",
					"    @Deprecated",
					"    public boolean isAuthAllowedForHttp() {",
					"        return caller.isAuthAllowedForHttp();",
					"    }",
					"",
					"    /** Set whether insecure http (vs https) connections should be allowed by",
					"     * this client.",
					"     * @param allowed true to allow insecure connections. Default false",
					"     */",
					"    public void setIsInsecureHttpConnectionAllowed(boolean allowed) {",
					"        caller.setInsecureHttpConnectionAllowed(allowed);",
					"    }",
					"",
					"    /** Deprecated. Use setIsInsecureHttpConnectionAllowed().",
					"     * @deprecated",
					"     */",
					"    @Deprecated",
					"    public void setAuthAllowedForHttp(boolean isAuthAllowedForHttp) {",
					"        caller.setAuthAllowedForHttp(isAuthAllowedForHttp);",
					"    }",
					"",
					"    /** Set whether all SSL certificates, including self-signed certificates,",
					"     * should be trusted.",
					"     * @param trustAll true to trust all certificates. Default false.",
					"     */",
					"    public void setAllSSLCertificatesTrusted(final boolean trustAll) {",
					"        caller.setAllSSLCertificatesTrusted(trustAll);",
					"    }",
					"    ",
					"    /** Check if this client trusts all SSL certificates, including",
					"     * self-signed certificates.",
					"     * @return true if all certificates are trusted.",
					"     */",
					"    public boolean isAllSSLCertificatesTrusted() {",
					"        return caller.isAllSSLCertificatesTrusted();",
					"    }",
					"    /** Sets streaming mode on. In this case, the data will be streamed to",
					"     * the server in chunks as it is read from disk rather than buffered in",
					"     * memory. Many servers are not compatible with this feature.",
					"     * @param streamRequest true to set streaming mode on, false otherwise.",
					"     */",
					"    public void setStreamingModeOn(boolean streamRequest) {",
					"        caller.setStreamingModeOn(streamRequest);",
					"    }",
					"",
					"    /** Returns true if streaming mode is on.",
					"     * @return true if streaming mode is on.",
					"     */",
					"    public boolean isStreamingModeOn() {",
					"        return caller.isStreamingModeOn();",
					"    }",
					"",
					"    public void _setFileForNextRpcResponse(" + model.ref("java.io.File") + " f) {",
					"        caller.setFileForNextRpcResponse(f);",
					"    }"
					));
			if (anyAsync) {
			    classLines.addAll(Arrays.asList(
			            "",
			            "    public long getAsyncJobCheckTimeMs() {",
			            "        return this.asyncJobCheckTimeMs;",
			            "    }",
                        "",
                        "    public void setAsyncJobCheckTimeMs(long newValue) {",
                        "        this.asyncJobCheckTimeMs = newValue;",
                        "    }",
                        "",
                        "    public int getAsyncJobCheckTimeScalePercent() {",
                        "        return this.asyncJobCheckTimeScalePercent;",
                        "    }",
                        "",
                        "    public void setAsyncJobCheckTimeScalePercent(int newValue) {",
                        "        this.asyncJobCheckTimeScalePercent = newValue;",
                        "    }",
                        "",
                        "    public long getAsyncJobCheckMaxTimeMs() {",
                        "        return this.asyncJobCheckMaxTimeMs;",
                        "    }",
                        "",
                        "    public void setAsyncJobCheckMaxTimeMs(long newValue) {",
                        "        this.asyncJobCheckMaxTimeMs = newValue;",
                        "    }"
			            ));
			}
            classLines.addAll(Arrays.asList(
                    "",
                    "    public String getServiceVersion() {",
                    "        return this.serviceVersion;",
                    "    }",
                    "",
                    "    public void setServiceVersion(String newValue) {",
                    "        this.serviceVersion = newValue;",
                    "    }"
                    ));
			if (anyAsync) {
                String exceptions = "throws " + model.ref("java.io.IOException") 
                        + ", " + model.ref(utilPackage + ".JsonClientException");
                String listClass = model.ref("java.util.List");
                String typeReferenceClass = model.ref("com.fasterxml.jackson.core.type.TypeReference");
                String arrayListClass = model.ref("java.util.ArrayList");
                classLines.add("");
                String jobStateType = model.ref(utilPackage + ".JobState");
                String trFull = typeReferenceClass + "<" + listClass + "<" + jobStateType + "<T>>>";
                classLines.add("    protected <T> " + jobStateType + "<T> _checkJob(String jobId, " + trFull + " retType) " + exceptions+ " {");
                classLines.add("        " + listClass + "<Object> args = new " + arrayListClass + "<Object>();");
                classLines.add("        args.add(jobId);");
                classLines.addAll(Arrays.asList(
                        "        " + listClass + "<" + jobStateType + "<T>> res = caller.jsonrpcCall(\"" + 
                                module.getOriginal().getModuleName() + "._check_job" + "\", args, retType, true, true);",
                        "        return res.get(0);",
                        "    }"
                        ));
			}
			boolean isStatusInKidl = false;
            String listClass = model.ref("java.util.List");
			for (JavaFunc func : module.getFuncs()) {
                if (func.getOriginal().getName().equals("status"))
                    isStatusInKidl = true;
                JavaType retType = null;
                if (func.getRetMultyType() == null) {
                    if (func.getReturns().size() > 0) {
                        retType = func.getReturns().get(0).getType();
                    }
                } else {
                    retType = func.getRetMultyType();
                }
                StringBuilder funcParams = new StringBuilder();
                StringBuilder funcParamNames = new StringBuilder();
                for (JavaFuncParam param : func.getParams()) {
                    appendWithComma(funcParams, getJType(param.getType(), packageParent, model)).append(" ").append(param.getJavaName());
                    appendWithComma(funcParamNames, param.getJavaName());
                }
                String contextType = model.ref(utilPackage + ".RpcContext");
                String contextField = "jsonRpcContext";
                appendWithComma(funcParams, contextType).append("... ").append(contextField);
                appendWithComma(funcParamNames, contextField);
                String retTypeName = retType == null ? "void" : getJType(retType, packageParent, model);
                String arrayListClass = model.ref("java.util.ArrayList");
                String exceptions = "throws " + model.ref("java.io.IOException") 
                        + ", " + model.ref(utilPackage + ".JsonClientException");
			    if (asyncVersion != null) {
			        if (!func.isAuthCouldBeUsed())
			            throw new IllegalStateException("Function " + func.getOriginal().getName() + " is async but doesn't allow authentication");
			        classLines.add("");
			        printFuncComment(func, originalToJavaTypes, packageParent, classLines, true);
			        classLines.add("    protected String _" + func.getJavaName() + "Submit(" + funcParams + ") " + exceptions+ " {");
			        if (asyncVersion != null) {
	                    classLines.addAll(Arrays.asList(
	                            "        if (this.serviceVersion != null) {",
	                            "            if (" + contextField + " == null || " + contextField + ".length == 0 || " + contextField + "[0] == null)",
	                            "                " + contextField + " = new " + contextType + "[] {new " + contextType + "()};",
	                            "            " + contextField + "[0].getAdditionalProperties().put(\"service_ver\", this.serviceVersion);",
	                            "        }"
	                            ));
			        }
			        classLines.add("        " + listClass + "<Object> args = new " + arrayListClass + "<Object>();");
			        for (JavaFuncParam param : func.getParams()) {
			            classLines.add("        args.add(" + param.getJavaName() + ");");
			        }
			        String typeReferenceClass = model.ref("com.fasterxml.jackson.core.type.TypeReference");
			        String trFull = typeReferenceClass + "<" + listClass + "<String>>";
			        classLines.addAll(Arrays.asList(
			                "        " + trFull + " retType = new " + trFull + "() {};",
			                "        " + listClass + "<String> res = caller.jsonrpcCall(\"" + module.getOriginal().getModuleName() + "._" + 
			                        func.getOriginal().getName() + "_submit" + "\", args, retType, true, true, " + contextField + ");",
                            "        return res.get(0);",
			                "    }"
			                ));
                    String jobStateType = model.ref(utilPackage + ".JobState");
                    String innerRetType = (func.getRetMultyType() == null) ? ((retType == null) ? "Object" : (listClass + "<" + retTypeName + ">")) : retTypeName;
                    classLines.add("");
                    printFuncComment(func, originalToJavaTypes, packageParent, classLines, true);
                    trFull = typeReferenceClass + "<" + listClass + "<" + jobStateType + "<" + innerRetType + ">>>";
                    classLines.addAll(Arrays.asList(
                            "    public " + retTypeName + " " + func.getJavaName() + "(" + funcParams + ") " + exceptions+ " {",
                            "        String jobId = _" + func.getJavaName() + "Submit(" + funcParamNames + ");",
                            "        " + trFull + " retType = new " + trFull + "() {};",
                            "        long asyncJobCheckTimeMs = this.asyncJobCheckTimeMs;",
                            "        while (true) {",
                            "            if (Thread.currentThread().isInterrupted())",
                            "                throw new " + model.ref(utilPackage + ".JsonClientException") + "(\"Thread was interrupted\");",
                            "            try { ",
                            "                Thread.sleep(asyncJobCheckTimeMs);",
                            "            } catch(Exception ex) {",
                            "                throw new " + model.ref(utilPackage + ".JsonClientException") + "(\"Thread was interrupted\", ex);",
                            "            }",
                            "            asyncJobCheckTimeMs = Math.min(asyncJobCheckTimeMs * this.asyncJobCheckTimeScalePercent / 100, this.asyncJobCheckMaxTimeMs);",
                            "            " + jobStateType + "<" + innerRetType + "> res = _checkJob(jobId, retType);",
                            "            if (res.getFinished() != 0L)",
                            "                return" + (func.getRetMultyType() == null ? (retType == null ? "" : " res.getResult().get(0)") : " res.getResult()") + ";",
                            "        }",
                            "    }"
                            ));
			    } else {
			        classLines.add("");
			        printFuncComment(func, originalToJavaTypes, packageParent, classLines, true);
			        classLines.add("    public " + retTypeName + " " + func.getJavaName() + "(" + funcParams + ") " + exceptions+ " {");
			        classLines.add("        " + listClass + "<Object> args = new " + arrayListClass + "<Object>();");
			        for (JavaFuncParam param : func.getParams()) {
			            classLines.add("        args.add(" + param.getJavaName() + ");");
			        }
			        String typeReferenceClass = model.ref("com.fasterxml.jackson.core.type.TypeReference");
			        boolean authRequired = func.isAuthRequired();
			        boolean needRet = retType != null;
			        if (func.getRetMultyType() == null) {
			            if (retType == null) {
			                String trFull = typeReferenceClass + "<Object>";
			                classLines.addAll(Arrays.asList(
			                        "        " + trFull + " retType = new " + trFull + "() {};",
			                        "        caller.jsonrpcCall(\"" + module.getOriginal().getModuleName() + "." + func.getOriginal().getName() + "\", args, retType, " + needRet + ", " + authRequired + ", " + contextField + ", this.serviceVersion);",
			                        "    }"
			                        ));
			            } else {
			                String trFull = typeReferenceClass + "<" + listClass + "<" + retTypeName + ">>";
			                classLines.addAll(Arrays.asList(
			                        "        " + trFull + " retType = new " + trFull + "() {};",
			                        "        " + listClass + "<" + retTypeName + "> res = caller.jsonrpcCall(\"" + module.getOriginal().getModuleName() + "." + func.getOriginal().getName() + "\", args, retType, " + needRet + ", " + authRequired + ", " + contextField + ", this.serviceVersion);",
			                        "        return res.get(0);",
			                        "    }"
			                        ));
			            }
			        } else {
			            String trFull = typeReferenceClass + "<" + retTypeName + ">";
			            classLines.addAll(Arrays.asList(
			                    "        " + trFull + " retType = new " + trFull + "() {};",
			                    "        " + retTypeName + " res = caller.jsonrpcCall(\"" + module.getOriginal().getModuleName() + "." + func.getOriginal().getName() + "\", args, retType, " + needRet + ", " + authRequired + ", " + contextField + ", this.serviceVersion);",
			                    "        return res;",
			                    "    }"
			                    ));					
			        }
			    }
			}
			if (!isStatusInKidl) {
                classLines.add("");
			    String mapType = model.ref("java.util.Map") + "<String, Object>";
                String contextType = model.ref(utilPackage + ".RpcContext");
                String contextField = "jsonRpcContext";
                StringBuilder funcParams = new StringBuilder();
                StringBuilder funcParamNames = new StringBuilder();
                appendWithComma(funcParams, contextType).append("... ").append(contextField);
                appendWithComma(funcParamNames, contextField);
                String arrayListClass = model.ref("java.util.ArrayList");
                String exceptions = "throws " + model.ref("java.io.IOException") 
                        + ", " + model.ref(utilPackage + ".JsonClientException");
			    if (asyncVersion != null) {
                    String typeReferenceClass = model.ref("com.fasterxml.jackson.core.type.TypeReference");
                    String trFull1 = typeReferenceClass + "<" + listClass + "<String>>";
                    String jobStateType = model.ref(utilPackage + ".JobState");
                    String innerRetType = listClass + "<" + mapType + ">";
			        String trFull2 = typeReferenceClass + "<" + listClass + "<" + jobStateType + "<" + innerRetType + ">>>";
			        classLines.addAll(Arrays.asList(
			                "    public " + mapType + " status(" + funcParams + ") " + exceptions+ " {",
                            "        if (this.serviceVersion != null) {",
                            "            if (" + contextField + " == null || " + contextField + ".length == 0 || " + contextField + "[0] == null)",
                            "                " + contextField + " = new " + contextType + "[] {new " + contextType + "()};",
                            "            " + contextField + "[0].getAdditionalProperties().put(\"service_ver\", this.serviceVersion);",
                            "        }",
                            "        " + listClass + "<Object> args = new " + arrayListClass + "<Object>();",
                            "        " + trFull1 + " retType1 = new " + trFull1 + "() {};",
                            "        " + listClass + "<String> res1 = caller.jsonrpcCall(\"" + module.getOriginal().getModuleName() + "._" + 
                                    "status_submit" + "\", args, retType1, true, true, " + contextField + ");",
                            "        String jobId = res1.get(0);",
			                "        " + trFull2 + " retType2 = new " + trFull2 + "() {};",
                            "        long asyncJobCheckTimeMs = this.asyncJobCheckTimeMs;",
			                "        while (true) {",
			                "            if (Thread.currentThread().isInterrupted())",
			                "                throw new " + model.ref(utilPackage + ".JsonClientException") + "(\"Thread was interrupted\");",
			                "            try { ",
			                "                Thread.sleep(asyncJobCheckTimeMs);",
			                "            } catch(Exception ex) {",
			                "                throw new " + model.ref(utilPackage + ".JsonClientException") + "(\"Thread was interrupted\", ex);",
			                "            }",
                            "            asyncJobCheckTimeMs = Math.min(asyncJobCheckTimeMs * this.asyncJobCheckTimeScalePercent / 100, this.asyncJobCheckMaxTimeMs);",
			                "            " + jobStateType + "<" + innerRetType + "> res2 = _checkJob(jobId, retType2);",
			                "            if (res2.getFinished() != 0L)",
			                "                return res2.getResult().get(0);",
			                "        }",
			                "    }"
			                ));
			    } else {
                    String typeReferenceClass = model.ref("com.fasterxml.jackson.core.type.TypeReference");
                    String trFull = typeReferenceClass + "<" + listClass + "<" + mapType + ">>";
                    classLines.addAll(Arrays.asList(
                            "    public " + mapType + " status(" + funcParams + ") " + exceptions+ " {",
                            "        " + listClass + "<Object> args = new " + arrayListClass + "<Object>();",
                            "        " + trFull + " retType = new " + trFull + "() {};",
                            "        " + listClass + "<" + mapType + "> res = caller.jsonrpcCall(\"" + module.getOriginal().getModuleName() + "." +
                                    "status\", args, retType, true, false, " + contextField + ", this.serviceVersion);",
                            "        return res.get(0);",
                            "    }"
                            ));
			    }
			}
			classLines.add("}");
			List<String> headerLines = new ArrayList<String>(Arrays.asList(
					"package " + sub(packageParent, module.getModulePackage()) + ";",
					""
					));
			headerLines.addAll(model.generateImports());
			headerLines.add("");
			classLines.addAll(0, headerLines);
			TextUtils.writeFileLines(classLines, srcOutDir.openWriter(classFile));
		}
	}

    private static StringBuilder appendWithComma(StringBuilder text, String item) {
        if (text.length() > 0)
            text.append(", ");
        text.append(item);
        return text;
    }

	private static void printFuncComment(JavaFunc func, Map<String, JavaType> originalToJavaTypes, 
			String packageParent, List<String> classLines, boolean client) {
		List<String> funcCommentLines = new ArrayList<String>();
		funcCommentLines.add("<p>Original spec-file function name: " + func.getOriginal().getName() + "</p>");
		funcCommentLines.add("<pre>");
		funcCommentLines.addAll(Utils.parseCommentLines(func.getOriginal().getComment()));
		funcCommentLines.add("</pre>");
		for (JavaFuncParam param : func.getParams()) {
			String descr = getTypeDescr(originalToJavaTypes, param.getOriginal().getType(), packageParent, null);
			funcCommentLines.add("@param   " + param.getJavaName() + "   " + descr);
		}
		if (func.getReturns().size() > 0) {
			if (func.getRetMultyType() == null) {
				JavaFuncParam ret = func.getReturns().get(0);
				String descr = getTypeDescr(originalToJavaTypes, ret.getOriginal().getType(), packageParent, 
						ret.getOriginal().getName());
				funcCommentLines.add("@return   " + descr);
			} else {
				StringBuilder descr = new StringBuilder();
				for (int i = 0; i < func.getReturns().size(); i++) {
					JavaFuncParam ret = func.getReturns().get(i);
					if (descr.length() > 0)
						descr.append(", ");
					descr.append('(').append((i + 1)).append(") ");
					descr.append(getTypeDescr(originalToJavaTypes, ret.getOriginal().getType(), packageParent, 
							ret.getOriginal().getName()));
				}
				funcCommentLines.add("@return   multiple set: " + descr);
			}
		}
		if (client) {
			funcCommentLines.add("@throws IOException if an IO exception occurs");
			funcCommentLines.add("@throws JsonClientException if a JSON RPC exception occurs");
		}
		printCommentLines("    ", funcCommentLines, classLines);
	}

	private static String getTypeDescr(Map<String, JavaType> originalToJavaTypes,
			KbType type, String packageParent, String paramName) {
		StringBuilder sb = new StringBuilder();
		if (paramName == null)
			sb.append("instance of ");
		createTypeDescr(originalToJavaTypes, type, packageParent, paramName, sb);
		return sb.toString();
	}
	
	private static void createTypeDescr(Map<String, JavaType> originalToJavaTypes,
			KbType type, String packageParent, String paramName, StringBuilder sb) {
		if (paramName != null)
			sb.append("parameter \"").append(paramName).append("\" of ");
		if (type instanceof KbTypedef) {
			KbTypedef ref = (KbTypedef)type;
			String originalTypeKey = TextUtils.capitalize(ref.getModule()).toLowerCase() + "." + ref.getName();
			if (originalToJavaTypes.containsKey(originalTypeKey)) {
				JavaType refJavaType = originalToJavaTypes.get(originalTypeKey);
				sb.append("type {@link ").append(getPackagePrefix(packageParent, refJavaType))
				.append(refJavaType.getJavaClassName()).append(' ').append(refJavaType.getJavaClassName())
				.append("}");
				if (!refJavaType.getJavaClassName().equals(ref.getName()))
					sb.append(" (original type \"").append(ref.getName()).append("\")");
			} else {
				sb.append("original type \"").append(ref.getName()).append("\"");
				List<String> refCommentLines = Utils.parseCommentLines(ref.getComment());
				if (refCommentLines.size() > 0) {
					StringBuilder concatLines = new StringBuilder();
					for (String l : refCommentLines) {
						if (concatLines.length() > 0 && concatLines.charAt(concatLines.length() - 1) != ' ')
							concatLines.append(' ');
						concatLines.append(l.trim());
					}
					sb.append(" (").append(concatLines).append(")");
				}
				if (!(ref.getAliasType() instanceof KbScalar)) {
					sb.append(" &rarr; ");
					createTypeDescr(originalToJavaTypes, ref.getAliasType(), packageParent, null, sb);
				}
			}
		} else if (type instanceof KbStruct) {
			throw new IllegalStateException("Inline structures are not allowed in spec-syntax");
		} else if (type instanceof KbList) {
			KbList list = (KbList)type;
			sb.append("list of ");
			createTypeDescr(originalToJavaTypes, list.getElementType(), packageParent, null, sb);			
		} else if (type instanceof KbMapping) {
			KbMapping map = (KbMapping)type;
			sb.append("mapping from ");
			createTypeDescr(originalToJavaTypes, map.getKeyType(), packageParent, null, sb);
			sb.append(" to ");
			createTypeDescr(originalToJavaTypes, map.getValueType(), packageParent, null, sb);
		} else if (type instanceof KbTuple) {
			KbTuple tuple = (KbTuple)type;
			sb.append("tuple of size ").append(tuple.getElementTypes().size()).append(": ");
			for (int i = 0; i < tuple.getElementTypes().size(); i++) {
				if (i > 0)
					sb.append(", ");
				String tupleParamName = tuple.getElementNames().get(i);
				if (tupleParamName.equals("e_" + (i + 1)))
					tupleParamName = null;
				createTypeDescr(originalToJavaTypes, tuple.getElementTypes().get(i), packageParent, 
						tupleParamName, sb);		
			}
		} else if (type instanceof KbUnspecifiedObject) {
			sb.append("unspecified object");
		} else if (type instanceof KbScalar) {
			sb.append(((KbScalar)type).getJavaStyleName());
		}
	}

	private static Map<String, JavaType> getOriginalToJavaTypesMap(JavaData data) {
		Map<String, JavaType> originalToJavaTypes = new HashMap<String, JavaType>();
		for (JavaType type : data.getTypes()) {
			originalToJavaTypes.put(type.getModuleName() + "." + type.getOriginalTypeName(), type);
		}
		return originalToJavaTypes;
	}

	private static void printModuleComment(JavaModule module, List<String> classLines) {
		List<String> lines = new ArrayList<String>();
		lines.add("<p>Original spec-file module name: " + module.getOriginal().getModuleName() + "</p>");
		lines.add("<pre>");
		lines.addAll(Utils.parseCommentLines(module.getOriginal().getComment()));
		lines.add("</pre>");
		printCommentLines("", lines, classLines);
	}
	
	private static HashMap<String, String> parsePrevCode(File classFile, List<JavaFunc> funcs)
		throws IOException, ParseException {
	    List<String> funcNames = new ArrayList<String>();
		for (JavaFunc func: funcs)
			funcNames.add(func.getOriginal().getName());
		return PrevCodeParser.parsePrevCode(classFile, "//", funcNames, true);
	}

	private static List<String> splitCodeLines(String code) {
		LinkedList<String> l = new LinkedList<String>();
		if (code.length() == 0) { //returns empty string otherwise
			return l;
		}
		return Arrays.asList(code.split("\n"));
	}
	
	private static void generateServerClass(JavaData data, FileSaver srcOutDir, String packageParent,
	        String semanticVersion, String gitUrl, String gitCommitHash, 
	        Map<String, String> originalCode) throws Exception {
	    if (semanticVersion == null)
	        semanticVersion = "";
	    if (gitUrl == null)
	        gitUrl = "";
	    if (gitCommitHash == null)
	        gitCommitHash = "";
		Map<String, JavaType> originalToJavaTypes = getOriginalToJavaTypesMap(data);
		for (JavaModule module : data.getModules()) {
			String moduleDir = sub(packageParent, module.getModulePackage()).replace('.', '/');
			JavaImportHolder model = new JavaImportHolder(sub(packageParent, module.getModulePackage()));
			String serverClassName = TextUtils.capitalize(module.getModuleName()) + "Server";
			String classFile = moduleDir + "/" + serverClassName + ".java";
			if (originalCode == null)
			    originalCode = parsePrevCode(srcOutDir.getAsFileOrNull(classFile), module.getFuncs());
			List<String> classLines = new ArrayList<String>();
			printModuleComment(module, classLines);
			classLines.addAll(Arrays.asList(
					"public class " + serverClassName + " extends " + model.ref(utilPackage + ".JsonServerServlet") + " {",
					"    private static final long serialVersionUID = 1L;",
                    "    private static final String version = \"" + semanticVersion + "\";",
                    "    private static final String gitUrl = \"" + gitUrl + "\";",
                    "    private static final String gitCommitHash = \"" + gitCommitHash + "\";",
					""
					));
			classLines.add("    //BEGIN_CLASS_HEADER");
			classLines.addAll(splitCodeLines(originalCode.get(PrevCodeParser.CLSHEADER)));
			classLines.add("    //END_CLASS_HEADER");
			classLines.add("");
			classLines.add("    public " + serverClassName + "() throws Exception {");
			String serviceName = module.getOriginal().getServiceName();
			if (serviceName == null || serviceName.trim().isEmpty())
				serviceName = module.getOriginal().getModuleName();
			classLines.add("        super(\"" + serviceName + "\");");
			classLines.add("        //BEGIN_CONSTRUCTOR");
			classLines.addAll(splitCodeLines(originalCode.get(PrevCodeParser.CONSTRUCTOR)));
			classLines.addAll(Arrays.asList(
					"        //END_CONSTRUCTOR",
					"    }"
					));
			boolean isStatusInKidl = false;
			for (JavaFunc func : module.getFuncs()) {
			    if (func.getOriginal().getName().equals("status"))
			        isStatusInKidl = true;
				JavaType retType = null;
				if (func.getRetMultyType() == null) {
					if (func.getReturns().size() > 0) {
						retType = func.getReturns().get(0).getType();
					}
				} else {
					retType = func.getRetMultyType();
				}
				StringBuilder funcParams = new StringBuilder();
				for (JavaFuncParam param : func.getParams())
				    appendWithComma(funcParams, getJType(param.getType(), packageParent, model)).append(" ").append(param.getJavaName());
				if (func.isAuthCouldBeUsed())
				    appendWithComma(funcParams, model.ref("us.kbase.auth.AuthToken")).append(" authPart");
                appendWithComma(funcParams, model.ref(utilPackage + ".RpcContext")).append(" ").append("jsonRpcContext");
				String retTypeName = retType == null ? "void" : getJType(retType, packageParent, model);
				classLines.add("");
				printFuncComment(func, originalToJavaTypes, packageParent, classLines, false);
				classLines.add("    @" + model.ref(utilPackage + ".JsonServerMethod") + "(" +
						"rpc = \"" + module.getOriginal().getModuleName() + "." + func.getOriginal().getName() + "\"" +
						(func.getRetMultyType() == null ? "" : ", tuple = true") + 
						(func.isAuthOptional() ? ", authOptional=true" : "") + ")");
				classLines.add("    public " + retTypeName + " " + func.getJavaName() + "(" + funcParams + ") throws Exception {");
				
				List<String> funcLines = new LinkedList<String>();
				String name = func.getOriginal().getName();
				if (originalCode.containsKey(PrevCodeParser.METHOD + name)) {
					funcLines.addAll(splitCodeLines(originalCode.get(PrevCodeParser.METHOD + name)));
				}
				funcLines.add(0, "        //BEGIN " + name);
				funcLines.add("        //END " + name);
				
				if (func.getRetMultyType() == null) {
					if (retType == null) {
						classLines.addAll(funcLines);
						classLines.add("    }");
					} else {
						classLines.add("        " + retTypeName + " returnVal = null;");
						classLines.addAll(funcLines);
						classLines.addAll(Arrays.asList(
								"        return returnVal;",
								"    }"
								));
					}
				} else {
					for (int retPos = 0; retPos < func.getReturns().size(); retPos++) {
						String retInnerType = getJType(func.getReturns().get(retPos).getType(), packageParent, model);
						classLines.add("        " + retInnerType + " return" + (retPos + 1) + " = null;");
					}
					classLines.addAll(funcLines);
					classLines.add("        " + retTypeName + " returnVal = new " + retTypeName + "();");
					for (int retPos = 0; retPos < func.getReturns().size(); retPos++) {
						classLines.add("        returnVal.setE" + (retPos + 1) + "(return" + (retPos + 1) + ");");
					}					
					classLines.add("        return returnVal;");
					classLines.add("    }");					
				}
			}
			if (!isStatusInKidl) {
			    model.ref("java.util.LinkedHashMap");
                classLines.addAll(Arrays.asList(
                        "    @" + model.ref(utilPackage + ".JsonServerMethod") + "(" +
                        		"rpc = \"" + module.getOriginal().getModuleName() + ".status\")",
                        "    public " + model.ref("java.util.Map") + "<String, Object> status() {",
                        "        " + model.ref("java.util.Map") + "<String, Object> returnVal = null;",
                        "        //BEGIN_STATUS"
                        ));
                if (originalCode.containsKey(PrevCodeParser.STATUS)) {
                    classLines.addAll(splitCodeLines(originalCode.get(PrevCodeParser.STATUS)));
                } else {
                    classLines.addAll(Arrays.asList(
                            "        returnVal = new " + model.ref("java.util.LinkedHashMap") + "<String, Object>();",
                            "        returnVal.put(\"state\", \"OK\");",
                            "        returnVal.put(\"message\", \"\");",
                            "        returnVal.put(\"version\", version);",
                            "        returnVal.put(\"git_url\", gitUrl);",
                            "        returnVal.put(\"git_commit_hash\", gitCommitHash);"
                            ));
                }
                classLines.addAll(Arrays.asList(
                        "        //END_STATUS",
                        "        return returnVal;",
                        "    }"
                        ));
			}
			String fileType = model.ref("java.io.File");
			String JsonServerSyslogType = model.ref(utilPackage + ".JsonServerSyslog");
			classLines.addAll(Arrays.asList(
					"",
					"    public static void main(String[] args) throws Exception {",
					"        if (args.length == 1) {",
                    "            new " + serverClassName + "().startupServer(Integer.parseInt(args[0]));",
					"        } else if (args.length == 3) {",
					"            " + JsonServerSyslogType + ".setStaticUseSyslog(false);",
					"            " + JsonServerSyslogType + ".setStaticMlogFile(args[1] + \".log\");",
					"            new " + serverClassName + "().processRpcCall(new " + fileType + "(args[0]), new " + fileType + "(args[1]), args[2]);",
					"        } else {",
					"            System.out.println(\"Usage: <program> <server_port>\");",
                    "            System.out.println(\"   or: <program> <context_json_file> <output_json_file> <token>\");",
					"            return;",
					"        }",
					"    }",
					"}"));
			List<String> headerLines = new ArrayList<String>(Arrays.asList(
					"package " + sub(packageParent, module.getModulePackage()) + ";",
					""
					));
			headerLines.addAll(model.generateImports());
			headerLines.add("");
			headerLines.add("//BEGIN_HEADER");
			headerLines.addAll(splitCodeLines(originalCode.get(PrevCodeParser.HEADER)));
			headerLines.add("//END_HEADER");
			headerLines.add("");
			classLines.addAll(0, headerLines);
			TextUtils.writeFileLines(classLines, srcOutDir.openWriter(classFile));
		}
	}

	private static void printCommentLines(String intend, List<String> commentLines, List<String> classLines) {
		if (commentLines.size() > 0) {
			classLines.add(intend + "/**");
			for (String commentLine : commentLines) {
				classLines.add(intend + " * " + commentLine);
			}
			classLines.add(intend + " */");
		}
	}

	private static List<String> checkLibs(
			final FileSaver libOutDir,
			final boolean createServers,
			final FileSaver buildXml)
			throws Exception {
		// TODO CODECLEANUP remove this method and figure out some other way of handling test deps
		if (libOutDir == null && buildXml == null)
			return null;
		List<String> jars = new ArrayList<String>();
		jars.add(checkLib(libOutDir, "jackson-annotations-2.2.3"));
		jars.add(checkLib(libOutDir, "jackson-core-2.2.3"));
		jars.add(checkLib(libOutDir, "jackson-databind-2.2.3"));
		jars.add(checkLib(libOutDir, "kbase-auth-0.4.4"));
		jars.add(checkLib(libOutDir, "kbase-common"));
		jars.add(checkLib(libOutDir, "javax.annotation-api-1.3.2"));
		if (createServers) {
			jars.add(checkLib(libOutDir, "servlet-api-2.5"));
			jars.add(checkLib(libOutDir, "jetty-all-7.0.0"));
			jars.add(checkLib(libOutDir, "ini4j-0.5.2"));
			jars.add(checkLib(libOutDir, "syslog4j-0.9.46"));
			jars.add(checkLib(libOutDir, "jna-3.4.0"));
			jars.add(checkLib(libOutDir, "joda-time-2.2"));
			jars.add(checkLib(libOutDir, "logback-core-1.1.2"));
			jars.add(checkLib(libOutDir, "logback-classic-1.1.2"));
			jars.add(checkLib(libOutDir, "slf4j-api-1.7.7"));
		}
		return jars;
	}

	private static void generateBuildXml(
			final JavaData data,
			final List<String> jars,
			final boolean createServers,
			final FileSaver buildXml,
			final String clientAsyncVersion
			) throws Exception {
		if (buildXml != null) {
			JavaModule module = data.getModules().get(0);
			List<String> lines = new ArrayList<String>(Arrays.asList(
					"<project name=\"Generated automatically\" default=\"compile\" basedir=\".\">",
					"  <property environment=\"env\"/>",
					"  <property name=\"src\"     location=\"src\"/>",
					"  <property name=\"classes\" location=\"classes\"/>",
					"  <property name=\"dist\"    location=\"dist\"/>",
					"  <property name=\"lib\"     location=\"${env.KB_TOP}/modules/jars/lib/jars/\"/>",
					"  <property name=\"module\"  value=\"" + module.getModuleName() + "\"/>",
					"  <property name=\"jar.file\" value=\"${module}.jar\"/>",
					"",
					"  <path id=\"compile.classpath\">",
					"    <fileset dir=\"${lib}\">"
					));
			for (String jar : jars)
				if (!new File(jar).isAbsolute())
					lines.add("      <include name=\"" + jar + "\"/>");

			lines.add("    </fileset>");
			for (String jar : jars)
				if (new File(jar).isAbsolute())
					lines.addAll(Arrays.asList(
							"    <fileset dir=\"" + new File(jar).getParent() + "\">",
							"      <include name=\"" + new File(jar).getName() + "\"/>",
							"    </fileset>"
							));
			lines.addAll(Arrays.asList(
					"  </path>",
					"",
					"  <target name=\"compile\" description=\"compile the source\">",
					"    <mkdir dir=\"${classes}\"/>",
					"    <javac srcdir=\"${src}\" destdir=\"${classes}\" includeantruntime=\"false\" debug=\"true\" classpathref=\"compile.classpath\"/>",
					"    <copy todir=\"${classes}\">",
					"      <fileset dir=\"${src}\">",
					"        <patternset>",
					"          <include name=\"**/*\"/>",
					"        </patternset>",
					"      </fileset>",
					"    </copy>",
					"    <jar destfile=\"${dist}/${jar.file}\" basedir=\"${classes}\">",
					"      <manifest>",
					"        <!-- attribute name=\"Main-Class\" value=\"us.kbase." + module.getModulePackage() + "." + module.getModuleName() + "\"/ -->",
					"      </manifest>",
					"    </jar>",
					"    <delete dir=\"${classes}\"/>",
					"  </target>",
					"",
					"  <target name=\"preparejunitreportdir\" if=\"env.JENKINS_REPORT_DIR\">",
					"    <delete dir=\"${env.JENKINS_REPORT_DIR}\"/>",
					"    <mkdir dir=\"${env.JENKINS_REPORT_DIR}\"/>",
					"  </target>",
					"",
					"  <target name=\"test\" depends=\"compile, preparejunitreportdir\" description=\"run all tests\">",
					"    <!-- Define absolute path to main jar file-->",
					"    <junit printsummary=\"yes\" haltonfailure=\"yes\" fork=\"true\">",
					"      <classpath>",
					"        <pathelement location=\"${dist}/${jar.file}\"/>",
					"        <path refid=\"compile.classpath\"/>",
					"      </classpath>",
					"      <formatter type=\"plain\" usefile=\"false\" />",
					"      <formatter type=\"xml\" usefile=\"true\" if=\"env.JENKINS_REPORT_DIR\"/>",
					"      <batchtest todir=\"${env.JENKINS_REPORT_DIR}\">",
					"        <fileset dir=\"${src}\">",
					"          <include name=\"**/test/**/**Test.java\"/>",
					"        </fileset>",
					"      </batchtest>",
					"    </junit>",
					"  </target>"
					));
			if (createServers) {
				if (clientAsyncVersion != null) {
					String shellFileName = "run_" + module.getModuleName() + "_async_job.sh";
					lines.addAll(Arrays.asList(
							"",
							"  <target name=\"make_async_job_script\" depends=\"compile\" description=\"make batch script for async job running\">",
							"    <property name=\"jar.absolute.path\" location=\"${dist}/${jar.file}\"/>",
							"    <pathconvert targetos=\"unix\" property=\"lib.classpath\" refid=\"compile.classpath\"/>",
							"    <echo file=\"${dist}/" + shellFileName + "\">#!/bin/bash",
							"java -cp ${jar.absolute.path}:${lib.classpath} us.kbase." + module.getModulePackage() + "." + module.getModuleName() + "Server" + " $1 $2 $3",
							"    </echo>",
							"<chmod file=\"${dist}/" + shellFileName + "\" perm=\"a+x\"/>",
							"  </target>"
							));
				}
			}
			lines.addAll(Arrays.asList(
					"</project>"
					));
			Writer w = buildXml.openWriter("*");
			for (String l : lines)
				w.write(l + "\n");
			w.close();
		}
	}

	public static String checkLib(FileSaver libDir, String libName) throws Exception {
		// TODO CODECLEANUP try to eliminate this method entirely. It seems to be used to move
		//                  urls to the lib directory which is never specified unless the
		//                  --javalib argument is used with compile, which I wasn't even aware of
		//                  and is never used in kbase AFAICT
		File libFile = null;
		final String classpath = System.getProperty("java.class.path");
		final String sep = System.getProperty("path.separator");
		for (final String cp: classpath.split(sep)) {
			final File maybelibFile = new File(cp);
			if (maybelibFile.isFile() && maybelibFile.getName().startsWith(libName) && 
					maybelibFile.getName().endsWith(".jar"))
				libFile = maybelibFile;
		}
		if (libFile == null)
			throw new KidlParseException("Can't find lib-file for: " + libName);
		if (libDir != null) {
		    InputStream is = new FileInputStream(libFile);
		    OutputStream os = libDir.openStream(libFile.getName());
		    TextUtils.copyStreams(is, os);
		}
		String ret = libFile.getCanonicalPath();
		if (ret.contains("lib/jars/"))
		    ret = ret.substring(ret.lastIndexOf("lib/jars/") + 9);
		return ret;
	}
	
	private static void writeJsonSchema(OutputStream jsonFile, String packageParent, JavaType type, 
			Set<Integer> tupleTypes) throws Exception {
		LinkedHashMap<String, Object> tree = new LinkedHashMap<String, Object>();
		tree.put("$schema", "http://json-schema.org/draft-04/schema#");
		tree.put("id", type.getModuleName() + "." + type.getJavaClassName());
		StringBuilder descr = new StringBuilder("<p>Original spec-file type: ").append(type.getOriginalTypeName()).append("</p>\n");
		List<String> descrLines = new ArrayList<String>();
		if (type.getAliasHistoryOuterToDeep().size() > 0) {
			descrLines.addAll(Utils.parseCommentLines(type.getAliasHistoryOuterToDeep().get(0).getComment()));
			if (descrLines.size() > 0) {
				descr.append("<pre>\n");
				for (String l : descrLines) {
					descr.append(l).append("\n");
				}
				descr.append("</pre>");
			}
		}
		tree.put("description", descr.toString());
		tree.put("type", "object");
		tree.put("javaType", sub(packageParent, type.getModuleName()) + "." + type.getJavaClassName());
		if (type.getMainType() instanceof KbStruct) {
			LinkedHashMap<String, Object> props = new LinkedHashMap<String, Object>();
			for (int itemPos = 0; itemPos < type.getInternalTypes().size(); itemPos++) {
				JavaType iType = type.getInternalTypes().get(itemPos);
				String field = type.getInternalFields().get(itemPos);
				props.put(field, createJsonRefTypeTree(type.getModuleName(), iType, 
						type.getInternalComment(itemPos), false, packageParent, tupleTypes));
			}
			tree.put("properties", props);
			tree.put("additionalProperties", true);
		} else {
			throw new KidlParseException("Type " + type.getMainType().getClass().getSimpleName() + " is not " +
					"supported for POJO generation");
		}
		ObjectMapper mapper = new ObjectMapper();
		mapper.configure(SerializationFeature.INDENT_OUTPUT, true);
		mapper.writeValue(jsonFile, tree);
	}

	public static String sub(String packageParent, String moduleName) {
	    return (packageParent.isEmpty() ? "" : (packageParent + ".")) + moduleName;
	}
	
	private static LinkedHashMap<String, Object> createJsonRefTypeTree(String module, JavaType type, String comment, 
			boolean insideTypeParam, String packageParent, Set<Integer> tupleTypes) {
		LinkedHashMap<String, Object> typeTree = new LinkedHashMap<String, Object>();
		if (comment != null && comment.trim().length() > 0)
			typeTree.put("description", comment);
		if (type.needClassGeneration()) {
			if (insideTypeParam) {
				typeTree.put("type", "object");
				typeTree.put("javaType", sub(packageParent, type.getModuleName()) + "." + type.getJavaClassName());
			} else {
				String modulePrefix = type.getModuleName().equals(module) ? "" : ("../" + type.getModuleName() + "/");
				typeTree.put("$ref", modulePrefix + type.getJavaClassName() + ".json");
			}
		} else if (type.getMainType() instanceof KbScalar) {
			if (insideTypeParam) {
				typeTree.put("type", "object");
				typeTree.put("javaType", ((KbScalar)type.getMainType()).getJavaStyleName());
			} else {
				typeTree.put("type", ((KbScalar)type.getMainType()).getJsonStyleName());
			}
		} else if (type.getMainType() instanceof KbList) {
			LinkedHashMap<String, Object> subType = createJsonRefTypeTree(module, type.getInternalTypes().get(0), null, 
					true, packageParent, tupleTypes);
				typeTree.put("type", "object");
				typeTree.put("javaType", "java.util.List");
				typeTree.put("javaTypeParams", subType);
		} else if (type.getMainType() instanceof KbMapping) {
			typeTree.put("type", "object");
			typeTree.put("javaType", "java.util.Map");
			List<LinkedHashMap<String, Object>> subList = new ArrayList<LinkedHashMap<String, Object>>();
			for (JavaType iType : type.getInternalTypes())
				subList.add(createJsonRefTypeTree(module, iType, null, true, packageParent, tupleTypes));
			typeTree.put("javaTypeParams", subList);
		} else if (type.getMainType() instanceof KbTuple) {
			typeTree.put("type", "object");
			int tupleType = type.getInternalTypes().size();
			if (tupleType < 1)
				throw new IllegalStateException("Wrong count of tuple parameters: " + tupleType);
			typeTree.put("javaType", utilPackage + ".Tuple" + tupleType);
			tupleTypes.add(tupleType);
			List<LinkedHashMap<String, Object>> subList = new ArrayList<LinkedHashMap<String, Object>>();
			for (JavaType iType : type.getInternalTypes())
				subList.add(createJsonRefTypeTree(module, iType, null, true, packageParent, tupleTypes));
			typeTree.put("javaTypeParams", subList);
		} else if (type.getMainType() instanceof KbUnspecifiedObject) {
			typeTree.put("type", "object");
			typeTree.put("javaType", utilPackage + ".UObject");
		} else {
			throw new IllegalStateException("Unknown type: " + type.getMainType().getClass().getName());
		}
		return typeTree;
	}

	private static JavaType findBasic(KbType type, String moduleName, Set<JavaType> nonPrimitiveTypes, Set<Integer> tupleTypes) {
		JavaType ret = findBasic(null, type, moduleName, null, new ArrayList<KbTypedef>(), nonPrimitiveTypes, tupleTypes);
		return ret;
	}

	private static JavaType findBasic(String typeName, KbType type, String defaultModuleName, String typeModuleName, 
			List<KbTypedef> aliases, Set<JavaType> nonPrimitiveTypes, Set<Integer> tupleTypes) {
		if (type instanceof KbBasicType) {
			JavaType ret = new JavaType(typeName, (KbBasicType)type, 
					typeModuleName == null ? defaultModuleName : typeModuleName, aliases);
			if (!(type instanceof KbScalar || type instanceof KbUnspecifiedObject))
				if (type instanceof KbStruct) {
					for (KbStructItem item : ((KbStruct)type).getItems()) {
						ret.addInternalType(findBasic(null, item.getItemType(), defaultModuleName, null, 
								new ArrayList<KbTypedef>(), nonPrimitiveTypes, tupleTypes));
						ret.addInternalField(item.getName(), "");
					}
				} else if (type instanceof KbList) {
					ret.addInternalType(findBasic(null, ((KbList)type).getElementType(), defaultModuleName, null, 
							new ArrayList<KbTypedef>(), nonPrimitiveTypes, tupleTypes));
				} else if (type instanceof KbMapping) {
					ret.addInternalType(findBasic(null, ((KbMapping)type).getKeyType(), defaultModuleName, null, 
							new ArrayList<KbTypedef>(), nonPrimitiveTypes, tupleTypes));
					ret.addInternalType(findBasic(null, ((KbMapping)type).getValueType(), defaultModuleName, null, 
							new ArrayList<KbTypedef>(), nonPrimitiveTypes, tupleTypes));
				} else if (type instanceof KbTuple) {
					tupleTypes.add(((KbTuple)type).getElementTypes().size());
					for (KbType iType : ((KbTuple)type).getElementTypes())
						ret.addInternalType(findBasic(null, iType, defaultModuleName, null, 
								new ArrayList<KbTypedef>(), nonPrimitiveTypes, tupleTypes));
				} else {
					throw new IllegalStateException("Unknown basic type: " + type.getClass().getSimpleName());
				}
			if (ret.needClassGeneration())
				nonPrimitiveTypes.add(ret);
			return ret;
		} else {
			KbTypedef typeRef = (KbTypedef)type;
			aliases.add(typeRef);
			return findBasic(typeRef.getName(), typeRef.getAliasType(), defaultModuleName, typeRef.getModule(), 
					aliases, nonPrimitiveTypes, tupleTypes);
		}
	}

	private static String getJType(JavaType type, String packageParent, JavaImportHolder codeModel) throws Exception {
		KbBasicType kbt = type.getMainType();
		if (type.needClassGeneration()) {
			return codeModel.ref(getPackagePrefix(packageParent, type) + type.getJavaClassName());
		} else if (kbt instanceof KbScalar) {
			return codeModel.ref(((KbScalar)kbt).getFullJavaStyleName());
		} else if (kbt instanceof KbList) {
			return codeModel.ref("java.util.List") + "<" + getJType(type.getInternalTypes().get(0), packageParent, codeModel) + ">";
		} else if (kbt instanceof KbMapping) {
			return codeModel.ref("java.util.Map")+ "<" + getJType(type.getInternalTypes().get(0), packageParent, codeModel) + "," +
					getJType(type.getInternalTypes().get(1), packageParent, codeModel) + ">";
		} else if (kbt instanceof KbTuple) {
			int paramCount = type.getInternalTypes().size();
			StringBuilder narrowParams = new StringBuilder();
			for (JavaType iType : type.getInternalTypes()) {
				if (narrowParams.length() > 0)
					narrowParams.append(", ");
				narrowParams.append(getJType(iType, packageParent, codeModel));
			}
			return codeModel.ref(utilPackage + ".Tuple" + paramCount) + "<" + narrowParams + ">";
		} else if (kbt instanceof KbUnspecifiedObject) {
			return codeModel.ref(utilPackage + ".UObject");
	    } else {
			throw new IllegalStateException("Unknown data type: " + kbt.getClass().getName());
		}
	}

	private static String getPackagePrefix(String packageParent, JavaType type) {
		return sub(packageParent, type.getModuleName()) + ".";
	}
}

