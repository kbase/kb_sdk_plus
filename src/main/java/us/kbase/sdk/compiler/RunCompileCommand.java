package us.kbase.sdk.compiler;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.commons.io.FileUtils;

import us.kbase.jkidl.FileIncludeProvider;
import us.kbase.jkidl.IncludeProvider;
import us.kbase.jkidl.ParseException;
import us.kbase.jkidl.SpecParser;
import us.kbase.kidl.KbModule;
import us.kbase.kidl.KbService;
import us.kbase.kidl.KidlParseException;
import us.kbase.kidl.KidlParser;
import us.kbase.sdk.compiler.html.HTMLGenerator;
import us.kbase.sdk.compiler.report.CompilationReporter;
import us.kbase.sdk.compiler.report.SpecFile;
import us.kbase.sdk.util.DiskFileSaver;
import us.kbase.sdk.util.FileSaver;

public class RunCompileCommand {

    public static void generate(
            // this is repulsive and needs a builder
            final File specFile,
            final URL url,
            final boolean pyClientSide, 
            final String pyClientName,
            boolean pyServerSide,
            final String pyServerName, 
            final String pyImplName,
            boolean javaClientSide,
            final boolean javaServerSide, 
            final String javaPackageParent,
            final String javaSrcPath,
            final File outDir,
            final String jsonSchemaPath, 
            final String clientAsyncVer,
            final String dynservVer,
            final boolean html,
            final String semanticVersion,
            final String gitUrl,
            final String gitCommitHash
            ) throws Exception {
        // TODO CODE this looks similar to code in the client installer, might be duplicated
        FileSaver javaSrcDir = null;
        // TODO CODE make javasrc path required if java processing is requested. Make a builder
        //           Later spots in the code expect a non-null value for javaSrcDir
        if (javaSrcPath != null)
            javaSrcDir = new DiskFileSaver(correctRelativePath(javaSrcPath, outDir));
        final File dir = specFile.getCanonicalFile().getParentFile();
        final List<SpecFile> specFiles = new ArrayList<SpecFile>();
        SpecFile mainSpec = new SpecFile();
        mainSpec.fileName = specFile.getName();
        mainSpec.isMain = 1;
        mainSpec.content = FileUtils.readFileToString(specFile);
        specFiles.add(mainSpec);
        IncludeProvider ip = new IncludeProvider() {
            @Override
            public Map<String, KbModule> parseInclude(String includeLine) throws KidlParseException {
                String specPath = includeLine.trim();
                if (specPath.startsWith("#include"))
                    specPath = specPath.substring(8).trim();
                if (specPath.startsWith("<"))
                    specPath = specPath.substring(1).trim();
                if (specPath.endsWith(">"))
                    specPath = specPath.substring(0, specPath.length() - 1).trim();
                File specFile = new File(specPath);
                if (!specFile.isAbsolute())
                    specFile = new File(dir, specPath);
                if (!specFile.exists())
                    throw new KidlParseException("Can not find included spec-file: " + specFile.getAbsolutePath());
                try {
                    SpecFile spec = new SpecFile();
                    spec.fileName = specFile.getName();
                    spec.isMain = 0;
                    spec.content = FileUtils.readFileToString(specFile);
                    specFiles.add(spec);
                    SpecParser p = new SpecParser(new DataInputStream(new FileInputStream(specFile)));
                    return p.SpecStatement(this);
                } catch (IOException e) {
                    throw new IllegalStateException("Unexpected error", e);
                } catch (ParseException e) {
                    throw new KidlParseException("Error parsing spec-file [" + specFile.getAbsolutePath() + "]: " + e.getMessage());
                }
            }
        };
        FileSaver output = new DiskFileSaver(outDir);
        if (html) {
            try (final FileReader r = new FileReader(specFile)) {
                new HTMLGenerator().generate(r, new FileIncludeProvider(dir),
                        output);
            }
        }
        FileSaver jsonSchemas = null;
        if (jsonSchemaPath != null) {
            jsonSchemas = new DiskFileSaver(correctRelativePath(jsonSchemaPath, outDir));
        }
        Reader specReader = new FileReader(specFile);
        Map<String, Map<String, String>> modelToTypeJsonSchemaReturn = null;
        if (jsonSchemas != null)
            modelToTypeJsonSchemaReturn = new TreeMap<String, Map<String, String>>();
        List<KbService> services = KidlParser.parseSpec(KidlParser.parseSpecInt(specReader, 
                modelToTypeJsonSchemaReturn, ip));
        if (jsonSchemas != null) {
            for (String module : modelToTypeJsonSchemaReturn.keySet()) {
                Map<String, String> typeToSchema = modelToTypeJsonSchemaReturn.get(module);
                for (String type : typeToSchema.keySet()) {
                    Writer w = jsonSchemas.openWriter(module + "/" + type + ".json");
                    w.write(typeToSchema.get(type));
                    w.close();
                }
            }
        }
        JavaData javaParsingData = null;
        if (javaClientSide) {
            //TODO DYNSERV add dynamic service client generation to all clients except Python
            javaParsingData = JavaTypeGenerator.processSpec(services, javaSrcDir, 
                    javaPackageParent, javaServerSide, url,
                    clientAsyncVer, dynservVer, semanticVersion, gitUrl, gitCommitHash);
        }
        TemplateBasedGenerator.generate(services, url == null ? null : url.toString(),
                pyClientSide, pyClientName, 
                pyServerSide, pyServerName, pyImplName, ip, output, clientAsyncVer,
                dynservVer, semanticVersion, gitUrl, gitCommitHash);
        String reportFile = System.getenv("KB_SDK_COMPILE_REPORT_FILE");
        if (reportFile == null || reportFile.isEmpty())
            reportFile = System.getProperty("KB_SDK_COMPILE_REPORT_FILE");
        if (reportFile != null && !reportFile.isEmpty()) {
            pyServerSide = TemplateBasedGenerator.genPythonServer(pyServerSide, 
                    pyServerName, pyImplName);
            try {
                CompilationReporter.prepareCompileReport(outDir, services, 
                        pyServerSide, pyImplName, 
                        javaServerSide, javaPackageParent, javaSrcPath, 
                        javaParsingData, specFiles, new File(reportFile));
            } catch (Exception ex) {
                ex.printStackTrace();
                throw ex;
            }
        }
    }
	
    private static File correctRelativePath(String javaSrcPath, File outDir) {
        File javaSrcDir = new File(javaSrcPath);
        if (!javaSrcDir.isAbsolute())
            javaSrcDir = new File(outDir, javaSrcPath);
        return javaSrcDir;
    }
}
