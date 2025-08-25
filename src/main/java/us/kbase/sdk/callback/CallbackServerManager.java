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
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;

import us.kbase.auth.AuthToken;
import us.kbase.common.utils.NetUtils;

// TODO CBS python callback server leaves root owned files around after tests
// TODO CBS convert module runner and tester
// TODO CBS delete old callback server code and related code

// could probably make it work in a container if we really needed to, but it'd need to know that
/** 
 * A manager for starting and stopping the Callback server docker image.
 * See https://github.com/kbase/JobRunner?tab=readme-ov-file#container-launch
 * 
 * Note that this code expects to be running on the host, not in a container. If run in a
 * container it will fail.
 */
public class CallbackServerManager implements AutoCloseable {
	
	// may want to make this configurable?
	public static final String CALLBACK_IMAGE = "ghcr.io/kbase/jobrunner:pr-116";
	
	private static final String BUSYBOX_IMAGE = "busybox:1.37.0";
	
	private static final Pattern DEFAULT_ADAPTER_REGEX = Pattern.compile(
			"^default.*?dev\\s+(\\S+)"
	);
	
	private final String containerName;
	private final URL callbackUrl;
	private final int port;
	private final Process proc;
	private final Path initProvFile;
	private final Path workDirRoot;
	
	/**
	 * Create the manager
	 * @param workDirRoot the working directory for the callback server. This directory will
	 * be mounted into the server. Any files outside of this directory will not be visible to
	 * the server. The callback server will write output here.
	 * @param kbaseBaseUrl the base URL for contacting kBase services, e.g. "https://ci.kbase.us"
	 * @param token the user's KBase token for contacting KBase services.
	 * @param prov initial provenance for the callback server. 
	 * @throws IOException if an IO error occurs.
	 */
	public CallbackServerManager(
			final Path workDirRoot,
			URL kbaseBaseUrl,
			final AuthToken token,
			final CallbackProvenance prov
			) throws IOException {
		requireNonNull(token, "token");
		requireNonNull(prov, "prov");
		kbaseBaseUrl = requireNonNull(kbaseBaseUrl, "kbaseBaseUrl").toString().endsWith("/") ?
				kbaseBaseUrl : new URL(kbaseBaseUrl.toString() + "/");
		this.workDirRoot = requireNonNull(workDirRoot, "workDirRoot").toAbsolutePath();
		this.containerName = prov.getModuleMethod().replace(".", "_")
				+ "_test_callback_server_" + UUID.randomUUID().toString();
		final String host = getHost();
		initProvFile = writeProvFile(this.workDirRoot.resolve("workdir"), prov);
		// may want to manually specify? Probably not
		port = NetUtils.findFreePort();
		proc = startCBS(host, token, kbaseBaseUrl);
		callbackUrl = new URL(String.format("http://%s:%s", host, port));
		waitForCBS(Duration.ofSeconds(120), Duration.ofSeconds(2));
	}
	
	/**
	 * Get the URL for the callback server from inside a container.
	 * @return the URL
	 */
	public URL getInContainerCallbackUrl() {
		return callbackUrl;
	}
	
	/**
	 * Get the URL for the callback server from the host machine, e.g. "http://localhost:port"
	 * @return the URL
	 */
	public URL getLocalhostCallbackUrl() {
		try {
			return new URL("http://localhost:" + port);
		} catch (MalformedURLException e) {
			throw new RuntimeException("This should be impossible");
		}
	}
	
	/**
	 * Get the port upon which the callback server is listening.
	 * @return the port.
	 */
	public int getCallbackPort() {
		return port;
	}
	
	/** Get the root of the callback server working directory.
	 * @return the directory.
	 */
	public Path getWorkDirRoot() {
		return workDirRoot;
	}

	private Path writeProvFile(final Path workDir, final CallbackProvenance prov
			) throws IOException {
		// see https://github.com/kbase/JobRunner/blob/main/JobRunner/provenance.py
		final String servver = String.format("%s-%s", prov.getVersion(), prov.getServiceVer());
		final Map<String, Object> initProv = Map.of(
				"method", prov.getModuleMethod(),
				"service_ver", servver, 
				"params", prov.getParams(),
				"source_ws_objects", prov.getWorkspaceRefs(),
				"subactions", Arrays.asList(
					Map.of(
							"name", prov.getModule(),
							"code_url", prov.getCodeUrl().toExternalForm(),
							"commit", prov.getCommit(),
							"ver", servver
					)
				)
		);
		Files.createDirectories(workDir);
		final Path initProvFile = Files.createTempFile(
				 workDir, "callback_initial_provenance", ".json");
		Files.write(initProvFile, new ObjectMapper().writeValueAsBytes(initProv));
		return initProvFile;
	}
	
	private Process startCBS(
			final String host,
			final AuthToken token,
			final URL kbaseBaseURL
			) throws IOException {
		final List<String> command = new LinkedList<>();
		command.addAll(Arrays.asList(
				"docker", "run",
				"--platform=linux/amd64",  // until we have multiarch images
				"--name", containerName,
				"--rm",  // make configuratble?
				// TODO SECURITY when CBS allows, use a file instead
				//               https://github.com/kbase/JobRunner/issues/90
				"-e", String.format("KB_AUTH_TOKEN=%s", token.getToken()),
				"-e", String.format("KB_BASE_URL=%s", kbaseBaseURL),
				"-e", String.format("PROV_FILE=%s", initProvFile),
				"-e", "CALLBACK_ALLOW_SET_PROVENANCE=true",
				"-e", String.format("JOB_DIR=%s", this.workDirRoot),
				// Note there's some code in the callback server container that causes the
				// server itself to always bind to 0.0.0.0, but we still need to pass in
				// the IP to correctly set the callback URL for containers spawned by the callback
				// server
				"-e", String.format("CALLBACK_IP=%s", host),
				"-e", String.format("CALLBACK_PORT=%s", port),
				"-e", "DEBUG_RUNNER=true", // prints logs from containers
				// Apparently this is consistent across platforms. Let's find out
				// May need to make the socket file configurable
				// Put it in sdk.cfg?
				"-v", "/var/run/docker.sock:/run/docker.sock",
				"-v", String.format("%s:%s", this.workDirRoot, this.workDirRoot),
				"-p", String.format("%s:%s", port, port),
				CALLBACK_IMAGE
		));
		final ProcessBuilder pb = new ProcessBuilder(command);
		// Let the user see the docker output for debugging.
		// Could make this configurable if it's annoying
		// Another case where we may want to pass in IO streams and pipe
		pb.inheritIO();
		return pb.start();
	}

	private void waitForCBS(final Duration timeout, final Duration interval) throws IOException {
		final URI uri;
		try {
			uri = new URI("http://localhost:" + port);
		} catch (URISyntaxException e) {
			throw new RuntimeException(e);
		}
		System.out.println("Waiting for Callback Server to start at " + uri);
		final HttpClient httpClient = HttpClient.newHttpClient();
		final HttpRequest request = HttpRequest.newBuilder()
				.uri(uri)
				.GET()
				.timeout(interval)
				.build();

		final long deadline = System.currentTimeMillis() + timeout.toMillis();
		Exception err = null;
		String responseText = null;
		
		// the error handling code is not worth writing unit tests for
		while (System.currentTimeMillis() < deadline) {
			try {
				final HttpResponse<String> response = httpClient.send(
						request, HttpResponse.BodyHandlers.ofString()
				);
				if (response.statusCode() == 200 && "[{}]".equals(response.body().strip())) {
					System.out.println("Callback Server is up.");
					return;
				}
				else {
					responseText = response.body().strip();
				}
			} catch (IOException | InterruptedException e) {
				err = e;
				// Server not ready yet; ignore and retry
			}
			try {
				Thread.sleep(interval.toMillis());
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
		}
		throw new IOException(
				"Callback Server did not start within the timeout period. Last response: "
				+ responseText,
				err
		);
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
		System.out.println("Looking for host ip in `ip route` output:\n" + out);
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
		throw new IOException( // no good way to test this
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
		// these error conditions are essentially impossible to test under normal conditions
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
}
