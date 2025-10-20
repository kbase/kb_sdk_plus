package us.kbase.test.sdk.util;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import uk.org.webcompere.systemstubs.environment.EnvironmentVariables;
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension;
import us.kbase.sdk.util.DeployConfigGenerator;

@ExtendWith(SystemStubsExtension.class)
public class DeployConfigGeneratorTest {

	@TempDir
	Path tempDir;

	private void assertBackupExists() throws IOException {
		final boolean backupExists = Files.list(tempDir)
				.anyMatch(p -> p.getFileName().toString().startsWith("template.cfg.bak."));
		assertThat("Backup file should exist with timestamped name", backupExists, is(true));
	}

	@Test
	public void testGenerateDeployConfig() throws Exception {
		final Path templateFile = tempDir.resolve("template.cfg");
		final Path propertiesFile = tempDir.resolve("props.ini");

		final String templateContent = """
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

		final String rendered = Files.readString(templateFile);
		final String expected = """
				endpoint=https://example.org
				job=https://example.org/job
				foo=bar
				""";
		assertThat("Incorrect template render", rendered, is(expected));

		assertBackupExists();
	}
	
	@Test
	public void testGenerateDeployConfigNoGlobalSection() throws Exception {
		final Path templateFile = tempDir.resolve("template.cfg");
		final Path propertiesFile = tempDir.resolve("props.ini");

		final String templateContent = "endpoint={{ kbase_endpoint }}\njob={{ job_service_url }}";
		Files.writeString(templateFile, templateContent);

		final String props = """
				[global_fake]
				kbase_endpoint=https://example.org
				job_service_url=https://example.org/job
				""";
		Files.writeString(propertiesFile, props);
		
		DeployConfigGenerator.generateDeployConfig(templateFile, propertiesFile);

		final String rendered = Files.readString(templateFile);
		final String expected = """
				endpoint=
				job=""";
		assertThat("Incorrect template render", rendered, is(expected));

		assertBackupExists();
	}
	
	@Test
	public void testGenerateDeployConfigNoMustache() throws Exception {
		final Path templateFile = tempDir.resolve("template.cfg");
		final Path propertiesFile = tempDir.resolve("props.ini");

		final String templateContent = "endpoint=hardcoded\njob=also_hardcoded";
		Files.writeString(templateFile, templateContent);
		Files.writeString(propertiesFile, "");

		DeployConfigGenerator.generateDeployConfig(templateFile, propertiesFile);

		final String rendered = Files.readString(templateFile);
		final String expected = """
				endpoint=hardcoded
				job=also_hardcoded""";
		assertThat("Incorrect template render", rendered, is(expected));

		assertBackupExists();
	}
	
	private static String ENV_TEMPLATE = """
			endpoint={{ kbase_endpoint }}
			ws={{ workspace_url }}
			auth={{ auth_service_url }}
			secure={{ auth_service_url_allow_insecure }}
			othervar={{ othervar }}
			secure1={{ one }}
			secure2={{ two }}
			foo=bar
			""";
	
	@Test
	public void testGenerateDeployConfigEnvVarsMinimal() throws Exception {
		final Path templateFile = tempDir.resolve("template.cfg");

		Files.writeString(templateFile, ENV_TEMPLATE);
		
		new EnvironmentVariables().set("KBASE_ENDPOINT", "https://ci.kbase.us/services")
			// test w/ null props file
			.execute(() -> {DeployConfigGenerator.generateDeployConfig(templateFile, null);});

		String rendered = Files.readString(templateFile);
		final String expected = """
				endpoint=https://ci.kbase.us/services
				ws=https://ci.kbase.us/services/ws
				auth=
				secure=false
				othervar=
				secure1=
				secure2=
				foo=bar
				""";
		assertThat("Incorrect template render", rendered, is(expected));

		assertBackupExists();
	}
	
	@Test
	public void testGenerateDeployConfigEnvVarsFull() throws Exception {
		final Path templateFile = tempDir.resolve("template.cfg");
		final Path propsFile = tempDir.resolve("config.props");

		Files.writeString(templateFile, ENV_TEMPLATE);
		
		new EnvironmentVariables()
			.set("KBASE_ENDPOINT", "https://ci.kbase.us/services")
			.set("AUTH_SERVICE_URL", "https://kbase_auth.us")
			.set("AUTH_SERVICE_URL_ALLOW_INSECURE", "totes mcgoats")
			.set("OTHER_VAR", "shouldn't appear")
			.set("KBASE_SECURE_CONFIG_PARAM_one", "super_secure1")
			.set("KBASE_SECURE_CONFIG_PARAM_two", "super_secure2")
			// test w/ missing props file
			.execute(() -> {DeployConfigGenerator.generateDeployConfig(templateFile, propsFile);});

		final String rendered = Files.readString(templateFile);
		final String expected = """
				endpoint=https://ci.kbase.us/services
				ws=https://ci.kbase.us/services/ws
				auth=https://kbase_auth.us
				secure=totes mcgoats
				othervar=
				secure1=super_secure1
				secure2=super_secure2
				foo=bar
				""";
		assertThat("Incorrect template render", rendered, is(expected));

		assertBackupExists();
	}
	
	@Test
	public void testGenerateDeployConfigFail() throws Exception {
		final Path t = tempDir.resolve("template.cfg");
		final Path p = tempDir.resolve("config.properties");

		failGenerateDeployConfig(null, null, new NullPointerException("templatePath"));
		failGenerateDeployConfig(t, null, new NoSuchFileException(tempDir + "/template.cfg"));
		
		Files.writeString(t, "foo");
		
		failGenerateDeployConfig(t, null, new IllegalArgumentException(
				"Properties file was not provided and KBASE_ENDPOINT environment variable "
				+ "not found"
		));
		failGenerateDeployConfig(t, p, new IllegalArgumentException(String.format(
				"Neither %s/config.properties file nor KBASE_ENDPOINT environment variable found",
				tempDir
		)));
	}

	private void failGenerateDeployConfig(
			final Path template, final Path props, final Exception expected)
			throws Exception {
		final Exception e = assertThrows(expected.getClass(),
				() -> DeployConfigGenerator.generateDeployConfig(template, props)
		);
		assertThat(e.getMessage(), is(expected.getMessage()));
	}

}
