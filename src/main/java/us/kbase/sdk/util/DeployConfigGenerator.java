package us.kbase.sdk.util;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.ini4j.Ini;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Generates a deploy.cfg file for a KBase service or app */
public class DeployConfigGenerator {
	
	private static final Pattern MUSTACHE_ENTRY = Pattern.compile(
			"\\{\\{\\s*([a-zA-Z0-9_]+)\\s*\\}\\}"
	);
	
	/** Generate a KBase service / app deploy.cfg file given a Mustache template and a properties
	 * ini file.
	 * The template file will be backed up and then overwritten in place.
	 * @param templatePath - the path to the template file.
	 * @param propertiesPath - the path to the properties / .ini file containing the properties
	 * to be inserted into the mustache template. The properties must be in a section called
	 * "global". If not provided or the file doesn't exist, a KBASE_ENDPOINT environment variable
	 * must exist with the kbase endpoint url as the value, which will be used to build the
	 * properties.
	 * @throws IOException if file reads or writes fail.
	 */
	public static void generateDeployConfig(final Path templatePath, final Path propertiesPath)
			throws IOException {
		requireNonNull(templatePath, "templatePath");

		final String templateText = Files.readString(templatePath, StandardCharsets.UTF_8);

		// this is recapitulating the behavior or the original prepare_deploy_cfg.py code
		// for now. We might want to be more stringent about the input vs. silently accepting
		// whatever.
		final Map<String, String> props;
		if (propertiesPath != null && Files.exists(propertiesPath)) {
			props = new HashMap<>();
			final Ini ini = new Ini(propertiesPath.toFile());
			if (ini.get("global") != null) {
				ini.get("global").forEach(props::put);
			}
		} else if (System.getenv("KBASE_ENDPOINT") != null) {
			props = loadFromEnv();
		} else {
			if (propertiesPath == null) {
				throw new IllegalArgumentException(
						"Properties file was not provided and KBASE_ENDPOINT environment "
						+ "variable not found"
				);
			} else {
				throw new IllegalArgumentException(String.format(
						"Neither %s file nor KBASE_ENDPOINT environment variable found",
						propertiesPath
				));
			}
		}
		final String output = renderTemplate(templateText, props);

		// Create human-readable backup filename
		final String timestamp = LocalDateTime.now()
				.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss-SSS"));
		final Path backupPath = templatePath.resolveSibling(
				templatePath.getFileName() + ".bak." + timestamp
		);

		Files.writeString(backupPath, templateText, StandardCharsets.UTF_8);
		Files.writeString(templatePath, output, StandardCharsets.UTF_8);
	}

	private static Map<String, String> loadFromEnv() {
		final Map<String, String> props = new HashMap<>();
		final String kbaseEndpoint = System.getenv("KBASE_ENDPOINT");

		props.put("kbase_endpoint", kbaseEndpoint);
		props.put("workspace_url", kbaseEndpoint + "/ws");
		props.put("shock_url", kbaseEndpoint + "/shock-api");
		props.put("handle_url", kbaseEndpoint + "/handle_service");
		props.put("srv_wiz_url", kbaseEndpoint + "/service_wizard");
		props.put("njsw_url", kbaseEndpoint + "/njs_wrapper");

		final String authServiceUrl = System.getenv("AUTH_SERVICE_URL");
		if (authServiceUrl != null) {
			props.put("auth_service_url", authServiceUrl);
		}

		final String insecure = System.getenv("AUTH_SERVICE_URL_ALLOW_INSECURE");
		props.put("auth_service_url_allow_insecure", insecure == null ? "false" : insecure);

		System.getenv().forEach((key, value) -> {
			if (key.startsWith("KBASE_SECURE_CONFIG_PARAM_")) {
				String paramName = key.substring("KBASE_SECURE_CONFIG_PARAM_".length());
				props.put(paramName, value);
			}
		});

		return props;
	}

	private static String renderTemplate(final String template, final Map<String, String> props) {
		final Matcher matcher = MUSTACHE_ENTRY.matcher(template);
		final StringBuffer sb = new StringBuffer();
		while (matcher.find()) {
			final String key = matcher.group(1);
			final String value = props.getOrDefault(key, "");
			matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
		}
		matcher.appendTail(sb);
		return sb.toString();
	}
}
