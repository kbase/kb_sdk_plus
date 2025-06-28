package us.kbase.sdk.common;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Load the SDK YAML config file. */
public class KBaseYmlConfig {

	/** The name of the YAML config file. */
	public static final String KBASE_YAML = "kbase.yml";
	
	private final String moduleName;
	private final String serviceLanguage;
	private final String semanticVersion;
	private final Optional<String> dataVersion;
	
	/** Load the module configuration from the module YAML file.
	 * @param moduleDir the root directory of the module.
	 * @throws KBaseYamlConfigException if the YAML file can't be read or is invalid.
	 */
	public KBaseYmlConfig(final Path moduleDir) throws KBaseYamlConfigException {
		final Path yml = requireNonNull(moduleDir, "moduleDir").resolve(KBASE_YAML);
		final Map<String,Object> config;
		try {
			final String kbaseYml = Files.readString(yml);
			@SuppressWarnings("unchecked")
			final Map<String,Object> config2 = (Map<String, Object>)
				new Yaml(new SafeConstructor()).load(kbaseYml);
			config = config2;
		} catch (NoSuchFileException e) {
			throw new KBaseYamlConfigException(
					String.format("Configuration file does not exist at %s as expected", yml), e
			);
		} catch (IOException e) {
			throw new KBaseYamlConfigException(
					String.format("Unable to read %s file: %s", yml, e.getMessage()), e
			);
		}
		moduleName = getString(config, "module-name", yml);
		serviceLanguage = getString(config, "service-language", yml);
		// TODO QA test valid semantic versions
		semanticVersion = getString(config, "module-version", yml);
		dataVersion = getString(config, "data-version", yml, true);
	}
	
	private String getString(
			final Map<String, Object> config,
			final String key,
			final Path yamlpath
			) throws KBaseYamlConfigException {
		return getString(config, key, yamlpath, false).get();
	}
	
	private Optional<String> getString(
			final Map<String, Object> config,
			final String key,
			final Path ymlpath,
			final boolean optional
			) throws KBaseYamlConfigException {
		final Object putative = config.get(key);
		if (putative == null) {
			if (optional) {
				return Optional.empty();
			}
			throw new KBaseYamlConfigException(String.format(
					"Missing required key '%s' in '%s' file", key, ymlpath));
		}
		// TODO CODE only allow number for versions
		if (!(putative instanceof String || putative instanceof Number)) {
			throw new KBaseYamlConfigException(String.format(
					"Illegal value '%s' for key '%s' in file '%s', must be a string or number",
					putative, key, ymlpath
			));
		}
		final String val = putative.toString().strip();
		if (val.isEmpty()) {
			throw new KBaseYamlConfigException(String.format(
					"Key '%s' in '%s' file may not be whitespace only", key, ymlpath));
		}
		return Optional.of(val);
	}
	
	/** Get the module name.
	 * @return the module name.
	 */
	public String getModuleName() {
		return moduleName;
	}

	/** Get the service language.
	 * @return the service language.
	 */
	public String getServiceLanguage() {
		return serviceLanguage;
	}

	/** Get the module version.
	 * @return the module version.
	 */
	public String getSemanticVersion() {
		return semanticVersion;
	}

	/** Get the reference data version.
	 * @return the reference data version.
	 */
	public Optional<String> getDataVersion() {
		return dataVersion;
	}

	/** An exception thrown when the YAML config file can't be loaded. */
	public static class KBaseYamlConfigException extends Exception {
		
		private static final long serialVersionUID = 1L;

		private KBaseYamlConfigException(final String msg) {
			super(msg);
		}
		
		private KBaseYamlConfigException(final String msg, final Throwable cause) {
			super(msg, cause);
		}

	}
	
}
