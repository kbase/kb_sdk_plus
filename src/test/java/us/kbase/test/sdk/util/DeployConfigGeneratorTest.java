package us.kbase.test.sdk.util;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.kbase.sdk.util.DeployConfigGenerator;

public class DeployConfigGeneratorTest {

	@TempDir
	Path tempDir;

	@Test
	public void testGenerateDeployConfig() throws Exception {
		Path templateFile = tempDir.resolve("template.cfg");
		Path propertiesFile = tempDir.resolve("props.ini");

		String templateContent = """
				endpoint={{ kbase_endpoint }}
				job={{ job_service_url }}
				foo=bar
				""";
		Files.writeString(templateFile, templateContent);
		final String props = """
				[global]
				kbase_endpoint=https://example.org
				job_service_url=https://example.org/job
				""";
		Files.writeString(propertiesFile, props);

		DeployConfigGenerator.generateDeployConfig(templateFile, propertiesFile);

		String rendered = Files.readString(templateFile);
		final String expected = """
				endpoint=https://example.org
				job=https://example.org/job
				foo=bar
				""";
		assertThat("Incorrect template render", rendered, is(expected));

		final boolean backupExists = Files.list(tempDir)
				.anyMatch(p -> p.getFileName().toString().startsWith("template.cfg.bak."));
		assertThat("Backup file should exist with timestamped name", backupExists, is(true));
	}
	
	@Test
	public void testGenerateDeployConfigNoGlobalSection() throws Exception {
		Path templateFile = tempDir.resolve("template.cfg");
		Path propertiesFile = tempDir.resolve("props.ini");

		String templateContent = "endpoint={{ kbase_endpoint }}\njob={{ job_service_url }}";
		Files.writeString(templateFile, templateContent);

		final String props = """
				[global_fake]
				kbase_endpoint=https://example.org
				job_service_url=https://example.org/job
				""";
		Files.writeString(propertiesFile, props);
		
		DeployConfigGenerator.generateDeployConfig(templateFile, propertiesFile);

		String rendered = Files.readString(templateFile);
		final String expected = """
				endpoint=
				job=""";
		assertThat("Incorrect template render", rendered, is(expected));

		final boolean backupExists = Files.list(tempDir)
				.anyMatch(p -> p.getFileName().toString().startsWith("template.cfg.bak."));
		assertThat("Backup file should exist with timestamped name", backupExists, is(true));
	}
	
	@Test
	public void testGenerateDeployConfigNoMustache() throws Exception {
		Path templateFile = tempDir.resolve("template.cfg");
		Path propertiesFile = tempDir.resolve("props.ini");

		String templateContent = "endpoint=hardcoded\njob=also_hardcoded";
		Files.writeString(templateFile, templateContent);
		Files.writeString(propertiesFile, "");

		DeployConfigGenerator.generateDeployConfig(templateFile, propertiesFile);

		String rendered = Files.readString(templateFile);
		final String expected = """
				endpoint=hardcoded
				job=also_hardcoded""";
		assertThat("Incorrect template render", rendered, is(expected));

		final boolean backupExists = Files.list(tempDir)
				.anyMatch(p -> p.getFileName().toString().startsWith("template.cfg.bak."));
		assertThat("Backup file should exist with timestamped name", backupExists, is(true));
	}
	
	@Test
	public void testGenerateDeployConfigFail() throws Exception {
		Path t = tempDir.resolve("template.cfg");

		failGenerateDeployConfig(null, t, "templatePath");
		failGenerateDeployConfig(t, null, "propertiesPath");
	}

	private void failGenerateDeployConfig(
			final Path template, final Path props, final String expected)
			throws Exception {
		try {
			DeployConfigGenerator.generateDeployConfig(template, props);
			fail("expected exception");
		} catch (NullPointerException got) {
			assertThat("incorrect exception message", got.getMessage(), is(expected));
		}
	}

}
