package us.kbase.test.sdk.compiler.html;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import name.fraser.neil.plaintext.diff_match_patch;
import name.fraser.neil.plaintext.diff_match_patch.Diff;
import name.fraser.neil.plaintext.diff_match_patch.Operation;
import us.kbase.jkidl.IncludeProvider;
import us.kbase.jkidl.ParseException;
import us.kbase.jkidl.SpecParser;
import us.kbase.kidl.KbModule;
import us.kbase.kidl.KidlParseException;
import us.kbase.sdk.compiler.html.HTMLGenVisitor;
import us.kbase.sdk.compiler.html.HTMLGenerator;
import us.kbase.sdk.util.FileSaver;

public class HTMLGenTest {

	private static List<String> HTML_FILES;
	private static String CSS = "KIDLspec.css";
	private static String CSS_FILE;
	
	@BeforeAll
	public static void beforeClass() throws Exception {
		CSS_FILE = getFile(CSS);
		HTML_FILES = listFiles();
	}
	
	public static String getFile(final String filename) throws Exception {
	try (final InputStream is = HTMLGenTest.class.getResourceAsStream(
			filename)) {
		return IOUtils.toString(is); 
	}
	}
	
	@Test
	public void testImports() throws Exception {
		test();
	}
	
	@Test
	public void testScriptTag() throws Exception {
		test();
	}
	
	@Test
	public void testAuth() throws Exception {
		test();
	}
	
	@Test
	public void testLinks() throws Exception {
		test();
	}
	
	@Test
	public void testTypesAndFuncs() throws Exception {
		test();
	}
	
	@Test
	public void testWhitespace() throws Exception {
		test();
	}
	
	@Test
	public void testBadSpec() throws Exception {
		try {
			test();
			fail("tested bad spec");
		} catch (KidlParseException k) {
			assertThat("Wrong exception message", k.getLocalizedMessage(),
					is("Encountered \" \"}\" \"} \"\" at line 6, column " +
							"1.\nWas expecting:\n    \";\" ...\n    "));
		}
	}
	
	@Test
	public void testEmptySpec() throws Exception {
		try {
			test();
			fail("tested bad spec");
		} catch (KidlParseException k) {
			assertThat("Wrong exception message", k.getLocalizedMessage(),
					is("There should be exactly one module in the spec"));
		}
	}
	
	@Test
	public void testMultiModuleSpec() throws Exception {
		try {
			test();
			fail("tested bad spec");
		} catch (KidlParseException k) {
			assertThat("Wrong exception message", k.getLocalizedMessage(),
					is("There should be exactly one module in the spec"));
		}
	}
	
	@Test
	public void testHTMLVisitorConstructor() throws Exception {
		try {
			new HTMLGenVisitor(null);
			fail("constructed bad html visitor");
		} catch (IllegalArgumentException e) {
			assertThat("Incorrect exception message", e.getLocalizedMessage(),
					is("moduleName cannot be null or empty"));
		}
		try {
			new HTMLGenVisitor("");
			fail("constructed bad html visitor");
		} catch (IllegalArgumentException e) {
			assertThat("Incorrect exception message", e.getLocalizedMessage(),
					is("moduleName cannot be null or empty"));
		}
	}

	private void test() throws Exception {
		final Exception e = new Exception();
		e.fillInStackTrace();
		final String testMethod = e.getStackTrace()[1].getMethodName();

		final String spec = testMethod + ".spec";
		final TestFileSaver saver = new TestFileSaver();
		try (final InputStream is =
				HTMLGenTest.class.getResourceAsStream(spec)) {
			
			new HTMLGenerator().generate(new InputStreamReader(is),
					new TestIncludeProvider(), saver);
		}
		
		checkFile(CSS, CSS_FILE, saver);
		checkFiles(testMethod, saver);
	}
	
	private void checkFiles(final String testName, final TestFileSaver saver)
			throws Exception {
		
		for (final String f: HTML_FILES) {
			if (f.startsWith(testName)) {
				final String expectedFile = getFile(f);
				checkFile(f.replace(testName, ""), expectedFile, saver);
			}
		}
		assertThat("Extra files were generated", saver.files.keySet(),
				is((Set<String>) new HashSet<String>()));
	}
	
	private void checkFile(final String filename, final String expectedFile,
			final TestFileSaver saver) {
		if (!saver.files.containsKey(filename)) {
			fail(String.format(
					"Expected creation of file %s but does not exist",
					filename));
		}
		final String gotFile = saver.files.get(filename).toString();
		saver.files.remove(filename);
		final List<Diff> res = new diff_match_patch().diff_main(
				expectedFile, gotFile);
		final Iterator<Diff> iter = res.iterator();
		while (iter.hasNext()) {
			final Diff d = iter.next();
			if (d.operation.equals(Operation.EQUAL)) {
				iter.remove();
			}
		}
		assertThat("found differences between expected and generated version of file " +
				filename, res, is((List<Diff>) new LinkedList<Diff>()));
		
	}

	private static class TestIncludeProvider implements IncludeProvider {

		@Override
		public Map<String, KbModule> parseInclude(String includeLine)
				throws KidlParseException {
			includeLine = includeLine.trim();
			final int strt = includeLine.indexOf("<");
			final String spc = includeLine.substring(strt + 1,
					includeLine.length() - 1);
			try (final InputStream is =
					HTMLGenTest.class.getResourceAsStream(spc)) {
				final SpecParser p = new SpecParser(is); 
				return p.SpecStatement(this);
			} catch (IOException | ParseException e) {
				throw new KidlParseException(e.getMessage(), e);
			}
		}
	}
	
	private static class TestFileSaver implements FileSaver {

		final Map<String, StringWriter> files =
				new HashMap<String, StringWriter>();
		@Override
		public Writer openWriter(String path) throws IOException {
			final StringWriter w = new StringWriter();
			files.put(path, w);
			return w;
		}

		@Override
		public OutputStream openStream(String path) throws IOException {
			throw new IllegalStateException("unimplemented");
		}

		@Override
		public File getAsFileOrNull(String path) throws IOException {
			throw new IllegalStateException("unimplemented");
		}
	}
	
	private static List<String> listFiles() throws Exception {
		// trying to get "." doesn't work here
		final URL url = HTMLGenTest.class.getResource(CSS);
		final Path dir = Paths.get(url.toURI()).getParent();
		final List<String> files;
		try (final Stream<Path> stream = Files.list(dir)) {
			files = stream
				.filter(file -> file.toString().endsWith(".html"))
				.map(file -> file.getFileName().toString())
				.collect(Collectors.toList());
		}
		return files;
	}
}
