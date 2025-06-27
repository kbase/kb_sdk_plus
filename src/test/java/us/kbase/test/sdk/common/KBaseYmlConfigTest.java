package us.kbase.test.sdk.common;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.kbase.sdk.common.KBaseYmlConfig;
import us.kbase.sdk.common.KBaseYmlConfig.KBaseYamlConfigException;

class KBaseYmlConfigTest {

	private static final String VALID_YAML = String.join("\n",
			"module-name: BBTools",
			"module-description: A suite of sequence data analysis tools developed at the JGI",
			"service-language: python",
			"module-version: 1.0.1",
			"owners: [msneddon, wjriehl, dylan]"
			);

	@TempDir
	private Path tempDir;

	private Path writeYaml(String content) throws IOException {
		Path yml = tempDir.resolve(KBaseYmlConfig.KBASE_YAML);
		Files.writeString(yml, content);
		return yml;
	}

	@Test
	void testValidYamlWithoutDataVersion() throws Exception {
		writeYaml(VALID_YAML);
		KBaseYmlConfig cfg = new KBaseYmlConfig(tempDir);
		assertThat(cfg.getModuleName(), is("BBTools"));
		assertThat(cfg.getServiceLanguage(), is("python"));
		assertThat(cfg.getSemanticVersion(), is("1.0.1"));
		assertThat(cfg.getDataVersion(), is(Optional.empty()));
	}

	@Test
	void testValidYamlWithNumericDataVersion() throws Exception {
		writeYaml(VALID_YAML + "\ndata-version: 0.3");
		KBaseYmlConfig cfg = new KBaseYmlConfig(tempDir);
		assertThat(cfg.getDataVersion(), is(Optional.of("0.3")));
	}
	
	@Test
	void testValidYamlWithNumericTrailingZeroDataVersion() throws Exception {
		writeYaml(VALID_YAML + "\ndata-version: 3.0");
		KBaseYmlConfig cfg = new KBaseYmlConfig(tempDir);
		assertThat(cfg.getDataVersion(), is(Optional.of("3.0")));
	}

	@Test
	void testValidYamlWithStringDataVersion() throws Exception {
		writeYaml(VALID_YAML + "\ndata-version: 0.3.0");
		KBaseYmlConfig cfg = new KBaseYmlConfig(tempDir);
		assertThat(cfg.getDataVersion(), is(Optional.of("0.3.0")));
	}

	@Test
	void testNullModuleDir() {
		Exception e = assertThrows(NullPointerException.class, () -> {
			new KBaseYmlConfig(null);
		});
		assertThat(e.getMessage(), is("moduleDir"));
	}

	@Test
	void testMissingFile() {
		final Path yml = tempDir.resolve(KBaseYmlConfig.KBASE_YAML);
		 // no file created
		final String err = "Configuration file does not exist at %s as expected";
		assertThrowsYmlException(yml, err);
	}

	@Test
	void testFileIsDirectory() throws IOException {
		final Path yml = tempDir.resolve(KBaseYmlConfig.KBASE_YAML);
		Files.createDirectory(yml);
		final String err = "Unable to read %s file: Is a directory";
		assertThrowsYmlException(yml, err);
	}

	@Test
	void testMissingModuleName() throws IOException {
		final Path yml = writeYaml("service-language: python\nmodule-version: 1.0.1");
		final String err = "Missing required key 'module-name' in '%s' file";
		assertThrowsYmlException(yml, err);
	}

	@Test
	void testMissingServiceLanguage() throws IOException {
		final Path yml = writeYaml("module-name: BBTools\nmodule-version: 1.0.1");
		final String err = "Missing required key 'service-language' in '%s' file";
		assertThrowsYmlException(yml, err);
	}

	@Test
	void testMissingModuleVersion() throws IOException {
		final Path yml = writeYaml("module-name: BBTools\nservice-language: python");
		final String err = "Missing required key 'module-version' in '%s' file";
		assertThrowsYmlException(yml, err);
	}

	@Test
	void testWhitespaceOnlyRequiredKey() throws IOException {
		final Path yml = writeYaml("module-name: '   '\nservice-language: python\n"
				+ "module-version: 1.0.1"
		);
		final String err = "Key 'module-name' in '%s' file may not be whitespace only";
		assertThrowsYmlException(yml, err);
	}

	@Test
	void testWhitespaceOnlyOptionalKey() throws IOException {
		final Path yml = writeYaml(VALID_YAML + "\ndata-version: '   '");
		final String err = "Key 'data-version' in '%s' file may not be whitespace only";
		assertThrowsYmlException(yml, err);
	}

	@Test
	void testNonStringKeyValue() throws IOException {
		final Path yml = writeYaml("module-name: BBTools\nservice-language: [python]\n"
				+ "module-version: 1.0.1"
		);
		final String err = "Illegal value '[python]' for key 'service-language' in "
				+ "file '%s', must be a string or number";
		assertThrowsYmlException(yml, err);
	}

	private void assertThrowsYmlException(final Path yml, final String err) {
		Exception e = assertThrows(KBaseYamlConfigException.class, () -> {
			new KBaseYmlConfig(tempDir);
		});
		assertThat(e.getMessage(), is(String.format(err, yml)));
	}
}
