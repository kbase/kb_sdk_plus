package us.kbase.sdk.compiler.report;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.LineNumberReader;
import java.io.StringReader;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;

import us.kbase.common.service.UObject;
import us.kbase.kidl.KbFuncdef;
import us.kbase.kidl.KbModule;
import us.kbase.kidl.KbModuleComp;
import us.kbase.kidl.KbParameter;
import us.kbase.kidl.KbService;
import us.kbase.kidl.KbTypedef;
import us.kbase.sdk.GitCommit;
import us.kbase.sdk.ModuleBuilder;
import us.kbase.sdk.compiler.JavaData;
import us.kbase.sdk.compiler.JavaModule;
import us.kbase.sdk.compiler.JavaTypeGenerator;
import us.kbase.sdk.compiler.TemplateBasedGenerator;
import us.kbase.sdk.util.DiskFileSaver;
import us.kbase.sdk.util.FileSaver;
import us.kbase.sdk.util.TextUtils;

public class CompilationReporter {

    public static void prepareCompileReport(File codeDir, List<KbService> services,
            boolean pyServerSide, String pyImplName,
            boolean javaServerSide, String javaPackageParent, String javaSrcPath, 
            JavaData javaParsingData, List<SpecFile> specFiles, File reportFile) throws Exception {
        String sdkVersion = ModuleBuilder.VERSION;
        String sdkGitCommit = GitCommit.COMMIT;
        String moduleName = null;
        KbModule module = null;
        for (KbService srv : services)
            module = srv.getModules().get(0);
        moduleName = module.getModuleName();
        FileSaver output = new DiskFileSaver(codeDir);
        File implFile = null;
        String comment = null;
        if (pyServerSide) {
            if (pyImplName == null)
                pyImplName = moduleName + "Impl";
            implFile = output.getAsFileOrNull(TemplateBasedGenerator.fixPath(
                    pyImplName, ".") + ".py");
            comment = "#";
        } else if (javaServerSide) {
            if (javaPackageParent.equals("."))  // Special value meaning top level package.
                javaPackageParent = "";
            File javaSrc = new File(javaSrcPath);
            if (!javaSrc.isAbsolute())
                javaSrc = new File(codeDir, javaSrcPath);
            FileSaver javaSrcDir = new DiskFileSaver(javaSrc);
            for (JavaModule jmodule : javaParsingData.getModules()) {
                if (jmodule.getOriginal().getModuleName().equals(moduleName)) {
                    String moduleDir = JavaTypeGenerator.sub(javaPackageParent, 
                            jmodule.getModulePackage()).replace('.', '/');
                    String serverClassName = TextUtils.capitalize(jmodule.getModuleName()) + 
                            "Server";
                    implFile = javaSrcDir.getAsFileOrNull(moduleDir + "/" + 
                            serverClassName + ".java");
                }
            }
            comment = "//";
        }
        if (implFile == null) {
            return;  // No server generation here
        }
        if (reportFile.exists()) {
            System.out.println("Skipping SDK compilation report generation: report file already exists");
            return;
        }
        System.out.println("Preparing SDK compilation report...");
        String implText = IOUtils.toString(new LineNumberReader(new FileReader(
                implFile)));
        Report report = createReport(specFiles, sdkVersion, sdkGitCommit,
                moduleName, module, implFile.getCanonicalPath(), comment, implText);
        UObject.getMapper().writeValue(reportFile, report);
        System.out.println("Report was stored into " + reportFile.getAbsolutePath());
    }

    public static Report createReport(List<SpecFile> specFiles,
            String sdkVersion, String sdkGitCommit, String moduleName,
            KbModule module, String implFilePath, String implCommentPrefix, 
            String implText) throws Exception, IOException {
        Map<String, FunctionPlace> funcPositions = new LinkedHashMap<String, FunctionPlace>();
        String commentPrefix = Pattern.quote(implCommentPrefix);
        Map<String, Function> functions = new LinkedHashMap<String, Function>();
        for (KbModuleComp comp : module.getModuleComponents()) {
            if (comp instanceof KbFuncdef) {
                KbFuncdef func = (KbFuncdef)comp;
                String funcName = func.getName();
                Pattern p = Pattern.compile(MessageFormat.format(".*" + commentPrefix + 
                        "BEGIN {0}\n(.*\n)?[ \t]*" + commentPrefix + "END {0}\n.*", 
                        funcName), Pattern.DOTALL);
                FunctionPlace place = checkMatch(funcPositions, p, implText);
                if (place != null)
                    funcPositions.put(funcName, place);
                Function f = new Function();
                f.name = funcName;
                f.comment = func.getComment();
                f.input = new ArrayList<Parameter>();
                for (KbParameter param : func.getParameters()) {
                    Parameter prm = new Parameter();
                    prm.type = param.getType().getSpecName();
                    if (param.getType() instanceof KbTypedef)
                    prm.comment = ((KbTypedef)param.getType()).getComment();
                    f.input.add(prm);
                }
                f.output = new ArrayList<Parameter>();
                if (func.getReturnType() != null)
                    for (KbParameter param : func.getReturnType()) {
                        Parameter prm = new Parameter();
                        prm.type = param.getType().getSpecName();
                        if (param.getType() instanceof KbTypedef)
                        prm.comment = ((KbTypedef)param.getType()).getComment();
                        f.output.add(prm);
                    }
                f.place = place;
                functions.put(funcName, f);
            }
        }
        Report report = new Report();
        report.moduleName = moduleName;
        report.sdkVersion = sdkVersion;
        report.sdkGitCommit = sdkGitCommit;
        String implPath = implFilePath;
        String rootPath = new File(".").getCanonicalPath();
        if (implPath.startsWith(rootPath + "/"))
            rootPath += "/";
        if (implPath.startsWith(rootPath))
            implPath = implPath.substring(rootPath.length());
        report.implFilePath = implPath;
        report.specFiles = specFiles;
        report.functionPlaces = funcPositions;
        report.functions = functions;
        return report;
    }

    private static FunctionPlace checkMatch(Map<String, FunctionPlace> code, Pattern matcher,
            String fullText) throws Exception {
        Matcher m = matcher.matcher(fullText);
        if (m.matches()) {
            int start = m.start(1);
            int end = m.end(1);
            if (start >= 0 && end >= start) {
                LineNumberReader lnr = new LineNumberReader(new StringReader(fullText));
                lnr.skip(start);
                int l1 = lnr.getLineNumber();
                lnr.skip(end - start);
                int l2 = lnr.getLineNumber() + 1;
                return new FunctionPlace().with(l1, l2);
            }
        }
        return null;
    }
}
