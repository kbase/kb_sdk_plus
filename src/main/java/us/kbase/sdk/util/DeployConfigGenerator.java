package us.kbase.sdk.util;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.ini4j.Ini;
import org.ini4j.Profile.Section;

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
	 * "global".
	 * @throws IOException if file reads or writes fail.
	 */
	public static void generateDeployConfig(final Path templatePath, final Path propertiesPath)
			throws IOException {
		requireNonNull(templatePath, "templatePath");
		requireNonNull(propertiesPath, "propertiesPath");

		final String templateText = Files.readString(templatePath, StandardCharsets.UTF_8);

		// this is recapitulating the behavior or the original prepare_deploy_cfg.py code
		// for now. We might want to be more stringent about the input vs. silently accepting
		// whatever.
		final Ini props = new Ini(propertiesPath.toFile());
		final Section sec = props.get("global");
		final Map<String, String> renderprops = new HashMap<>();
		if (sec != null) {
			renderprops.putAll(sec);
		}
		final String output = renderTemplate(templateText, renderprops);

		// Create human-readable backup filename
		final String timestamp = LocalDateTime.now()
				.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss-SSS"));
		final Path backupPath = templatePath.resolveSibling(
				templatePath.getFileName() + ".bak." + timestamp
		);

		Files.writeString(backupPath, templateText, StandardCharsets.UTF_8);
		Files.writeString(templatePath, output, StandardCharsets.UTF_8);
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
