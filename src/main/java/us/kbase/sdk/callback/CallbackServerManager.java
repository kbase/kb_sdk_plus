package us.kbase.sdk.callback;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;

import us.kbase.auth.AuthToken;
import us.kbase.common.executionengine.ModuleMethod;
// TODO NOW once this works on mac / linux / GHA, create a simplified version for this module
import us.kbase.common.executionengine.ModuleRunVersion;
import us.kbase.common.utils.NetUtils;

// TODO NOW TEST
// TODO NOW python callback server leaves root owned files around after tests
// TODO NOW convert module runner
// TODO NOW delete old callback server code and related code
// TODO NOW update CallbackServerTest to use this module to run the CBS. Needs CBS set_provenance

/** 
 * A manager for starting and stopping the Callback server docker image.
 * See https://github.com/kbase/JobRunner?tab=readme-ov-file#container-launch
 */
public class CallbackServerManager implements AutoCloseable {
	
	/* The python dockerized callback server does actually have the ability to perform arbitrary
	 * mounts on containers it runs; it's just not exposed in the container API. So, theoretically
	 * we could restore that feature for kb-sdk run. However:
	 * 
	 * * we shouldn't do it until there's a very clear use case showing it's necessary
	 *   * Why would you need more than the workdir? Just put everything there
	 * * we should think hard about allowing arbitrary mounts into containers from a security
	 *   perspective, since the CBS pulls docker containers that the user may not expect
	 */
	
	// may want to make this configurable?
	public static final String CALLBACK_IMAGE = "ghcr.io/kbase/jobrunner:pr-97";
	
	private static final String BUSYBOX_IMAGE = "busybox:1.37.0";
	
	private static final Pattern DEFAULT_ADAPTER_REGEX = Pattern.compile(
			"^default.*?dev\\s+(\\S+)"
	);
	
	private final String containerName;
	private final URL callbackUrl;
	private final int port;
	private final Process proc;
	private final Path initProvFile;
	
	public CallbackServerManager(
			Path workDirRoot, // TODO note must be mounted into container if running in container
			URL kbaseBaseURL,
			final AuthToken token,
			// TODO NOW make a provenance class w/ builder for these
			final ModuleRunVersion mrv,
			final List<Object> params, // TODO NOW note that these must be jsonable
			final List<String> inputWorkspaceRefs
			) throws IOException {
		requireNonNull(token, "token");
		try {
			kbaseBaseURL = requireNonNull(kbaseBaseURL, "kbaseBaseURL").toString().endsWith("/") ?
					kbaseBaseURL : new URL(kbaseBaseURL.toString() + "/");
		} catch (MalformedURLException e) {
			throw new RuntimeException(e);
		}
		workDirRoot = requireNonNull(workDirRoot, "workDir").toAbsolutePath();
		this.containerName = mrv.getModuleMethod().getModuleDotMethod().replace(".", "_")
				+ "_test_catllback_server_" + UUID.randomUUID().toString();
		final String host = getHost();
		initProvFile = writeProvFile(
				workDirRoot.resolve("workdir"), mrv, params, inputWorkspaceRefs
		);
		// may want to manually specify? Probably not
		port = NetUtils.findFreePort();
		proc = startCBS( host, token, kbaseBaseURL, workDirRoot);
		callbackUrl = new URL(String.format("http://%s:%s", host, port));
		waitForCBS(Duration.ofSeconds(120), Duration.ofSeconds(2));
	}
	
	public URL getCallbackUrl() {
		return callbackUrl;
	}
	
	public int getCallbackPort() {
		return port;
	}

	private Path writeProvFile(
			final Path workDir,
			final ModuleRunVersion mrv,
			final List<Object> params,
			final List<String> inputWorkspaceRefs
			) throws IOException {
		// see https://github.com/kbase/JobRunner/blob/main/JobRunner/provenance.py#L4-L21
		final Map<String, Object> initProv = new HashMap<>();
		initProv.put("method", requireNonNull(mrv, "mrv").getModuleMethod().getModuleDotMethod());
		initProv.put("service_ver", mrv.getRelease());
		initProv.put("params", requireNonNull(params, "params"));
		initProv.put("source_ws_objects",
				requireNonNull(inputWorkspaceRefs, "inputWorkspaceRefs"));
		Files.createDirectories(workDir);
		final Path initProvFile = Files.createTempFile(
				 workDir, "callback_initial_provenance", ".json");
		Files.write(initProvFile, new ObjectMapper().writeValueAsBytes(initProv));
		return initProvFile;
	}
	
	private Process startCBS(
			final String host,
			final AuthToken token,
			final URL kbaseBaseURL,
			final Path workDir
			) throws IOException {
		final List<String> command = new LinkedList<>();
		command.addAll(Arrays.asList(
				"docker", "run",
				"--name", containerName,
				"--rm",  // make configuratble?
				// TODO SECURITY when CBS allows, use a file instead
				//               https://github.com/kbase/JobRunner/issues/90
				"-e", String.format("KB_AUTH_TOKEN=%s", token.getToken()),
				"-e", String.format("KB_BASE_URL=%s", kbaseBaseURL),
				"-e", String.format("PROV_FILE=%s", initProvFile),
				"-e", String.format("JOB_DIR=%s", workDir),
				"-e", String.format("CALLBACK_IP=%s", host),
				"-e", String.format("CALLBACK_PORT=%s", port),
				// Apparently this is consistent across platforms. Let's find out
				// May need to make the socket file configurable
				// Put it in sdk.cfg?
				"-v", "/var/run/docker.sock:/run/docker.sock",
				"-v", String.format("%s:%s", workDir, workDir),
				"-p", String.format("%s:%s", port, port),
				CALLBACK_IMAGE
		));
		final ProcessBuilder pb = new ProcessBuilder(command);
		// Let the user see the docker commands for debugging.
		// Could make this configurable if it's annoying
		// Another case where we may want to pass in IO streams and pipe
		pb.inheritIO();
		return pb.start();
	}

	private void waitForCBS(final Duration timeout, final Duration interval) throws IOException {
		System.out.println("Waiting for Callback Server to start");
		final HttpClient httpClient = HttpClient.newHttpClient();
		final URI uri;
		try {
			uri = callbackUrl.toURI();
		} catch (URISyntaxException e) {
			throw new RuntimeException(e);
		}
		final HttpRequest request = HttpRequest.newBuilder()
				.uri(uri)
				.GET()
				.timeout(interval)
				.build();

		final long deadline = System.currentTimeMillis() + timeout.toMillis();

		while (System.currentTimeMillis() < deadline) {
			try {
				final HttpResponse<String> response = httpClient.send(
						request, HttpResponse.BodyHandlers.ofString()
				);
				if (response.statusCode() == 200 && "[{}]".equals(response.body().trim())) {
					System.out.println("Callback Server is up.");
					return;
				}
			} catch (IOException | InterruptedException e) {
				// Server not ready yet; ignore and retry
			}
			try {
				Thread.sleep(interval.toMillis());
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
		}
		throw new IOException("Callback Server did not start within the timeout period.");
	}
	
	@Override
	public void close() throws IOException {
		try {
			// proc.destroyForcibly() doesn't seem to work, container keeps running
			runQuickCommand(15L, "docker", "stop", containerName);
			proc.destroyForcibly();  // why not
		} finally {
			Files.delete(initProvFile);
		}
	}
	
	private String getHost() throws IOException {
		// runs a container to see what the container thinks the host address might be.
		// should work on linux (on bare machine) or mac (in docker VM)
		final Process p = runQuickCommand(
				15L,
				"docker", "run",
				"--rm",
				"--network", "host",
				BUSYBOX_IMAGE,
				"ip", "route"
		);
		final String out = new String(
				p.getInputStream().readAllBytes(), StandardCharsets.UTF_8
		);
		final String[] lines = out.split("\n");
		final String adapter = getRegexTarget(lines, DEFAULT_ADAPTER_REGEX);
		final Pattern hostIPPattern = Pattern.compile(
				"dev\\s+" + Pattern.quote(adapter) + ".*?src\\s+(\\S+)"
		);
		return getRegexTarget(lines, hostIPPattern);
	}

	private String getRegexTarget(final String[] lines, final Pattern regex) throws IOException {
		for (final String line: lines) {
			final Matcher matcher = regex.matcher(line);
			if (matcher.find()) {
				return matcher.group(1);
			}
		}
		throw new IOException(
				"Unexpected output from busybox ip route command when attempting to determine "
				+ "docker host:\n" + String.join("\n", lines)
		);
	}
	
	private Process runQuickCommand(
			final long timeout,
			final String... command
			) throws IOException {
		final ProcessBuilder pb = new ProcessBuilder(command);
		pb.redirectError(ProcessBuilder.Redirect.INHERIT);
		final Process proc = pb.start();
		// these error condition are essentially impossible to test under normal conditions
		final boolean finished;
		try {
			finished = proc.waitFor(timeout, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
		if (!finished) {
			proc.destroyForcibly();
			throw new IOException("Docker process unexpected timeout");
		}
		if (proc.exitValue() != 0) {
			throw new IOException(String.format(
					"Unexpected exit code from docker: %s. Error stream:%s",
					proc.exitValue(),
					new String(proc.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)
			));
		}
		return proc;
	}
	
	public static void main(String[] args) throws Exception {
		final CallbackServerManager csm = new CallbackServerManager(
				Paths.get("."),
				new URL("https://ci.kbase.us/services"),
				new AuthToken(args[0], "user"),
				new ModuleRunVersion(
						new URL("https://github.com/kbaseapps/DatafileUtil.git"),
						new ModuleMethod("foo.bar"),
						"githashhere",
						"0.1.0",
						"dev"
				),
				Arrays.asList("foo", 1),
				Arrays.asList("3/4/5")
		);
		try (csm) {
			System.out.println(csm.getCallbackUrl());
	
			System.out.println("Press Enter to exit...");
			try (final Scanner scanner = new Scanner(System.in)) {
				scanner.nextLine();  // Waits for Enter
			}
		}
	}
}
