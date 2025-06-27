package us.kbase.test.sdk.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import us.kbase.kidl.KbModule;
import us.kbase.kidl.KbService;
import us.kbase.kidl.KidlParser;
import us.kbase.sdk.compiler.report.CompilationReporter;
import us.kbase.sdk.compiler.report.Function;
import us.kbase.sdk.compiler.report.FunctionPlace;
import us.kbase.sdk.compiler.report.Report;
import us.kbase.sdk.compiler.report.SpecFile;

public class CompileReporterTest {
    
    @Test
    public void testLines() throws Exception {
        String moduleName = "PangenomeOrthomcl";
        String methodName = "build_pangenome_with_orthomcl";
        String server = "" +
                "class " + moduleName + ":\n" +
                "\n" +
                "    def " + methodName + "(self, ctx, params):\n" +
                "        #BEGIN " + methodName + "\n" +
                "        log = \"\"\n" + 
                "        #END " + methodName + "\n" +
                "        return [log]\n";
        String kidlSpec = "" +
        		"module " + moduleName + " {\n" +
                "    /* Test that! */\n" + 
        		"    typedef tuple<int,string> MyType;\n" +
                "    /* Super func! */" +
                "    funcdef " + methodName + " (int, string, list<string>, mapping<string, float>) \n" +
                "        returns (UnspecifiedObject, MyType);\n" +
                "};";
        List<SpecFile> specs = new ArrayList<SpecFile>();
        List<KbService> services = KidlParser.parseSpec(KidlParser.parseSpecInt(new StringReader(kidlSpec), 
                null, null));
        KbModule module = services.get(0).getModules().get(0);
        Report rpt = CompilationReporter.createReport(specs, "", "", moduleName, module, "/kb/dev_forgetter", 
                "#", server);
        //System.out.println(UObject.transformObjectToString(rpt));
        Map<String, FunctionPlace> pos = rpt.functionPlaces;
        assertEquals(1, pos.size());
        assertEquals(4, (int)pos.get(methodName).startLine);
        assertEquals(6, (int)pos.get(methodName).endLine);
        Function f = rpt.functions.get(methodName);
        assertEquals("Super func!", f.comment);
        assertEquals(4, f.input.size());
        assertEquals(2, f.output.size());
        assertEquals("Test that!", f.output.get(1).comment);
    }
}
