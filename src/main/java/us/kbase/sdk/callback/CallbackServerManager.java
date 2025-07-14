package us.kbase.sdk.callback;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;

import us.kbase.auth.AuthToken;
import us.kbase.common.executionengine.ModuleMethod;
// TODO NOW once this works on mac / linux / GHA, create a simplified version for this module
import us.kbase.common.executionengine.ModuleRunVersion;
import us.kbase.common.utils.NetUtils;

// TODO NOW JAVADOC - add notes about expects to be running outside a container
// TODO NOW TEST
// TODO NOW this won't work inside a container, need to make a call; container vs. native code
//          or go to the trouble to make it work in both somehow...?


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
	public static final String CALLBACK_IMAGE = "ghcr.io/kbase/jobrunner:pr-85";
	
	private final String containerName;
	private final URL callbackUrl;
	private final int port;
	private final Process proc;
	private final Path initProvFile;
	
	public CallbackServerManager(
			Path workDir,
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
		workDir = requireNonNull(workDir, "workDir").toAbsolutePath();
		this.containerName = mrv.getModuleMethod().getModuleDotMethod().replace(".", "_")
				+ "_test_catllback_server_" + UUID.randomUUID().toString();
		final ObjectMapper mapper = new ObjectMapper();
		final String host = getHost(mapper);
		initProvFile = writeProvFile(mapper, workDir, mrv, params, inputWorkspaceRefs);
		// may want to manually specify? Probably not
		port = NetUtils.findFreePort();
		proc = startCBS(initProvFile, token, kbaseBaseURL, workDir);
		callbackUrl = new URL(String.format("http://%s:%s", host, port));
	}
	
	public URL getCallbackUrl() {
		return callbackUrl;
	}
	
	public int getCallbackPort() {
		return port;
	}

	private Path writeProvFile(
			final ObjectMapper mapper,
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
		 final Path initProvFile = Files.createTempFile(
				 workDir, "callback_initial_provenance", ".json");
		Files.write(initProvFile, mapper.writeValueAsBytes(initProv));
		return initProvFile;
	}
	
	private Process startCBS(
			final Path initProvFile,
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
				"-e", "CALLBACK_IP=0.0.0.0",
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
	
	@Override
	public void close() throws IOException {
		try {
			proc.destroy();
			final boolean finished;
			// these error condition are essentially impossible to test under normal conditions
			try {
				finished = proc.waitFor(15L, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
			if (!finished) {
				// TODO CBS this line is currently hit every time, I think the CBS container
				//          doesn't handle sigterm correctly
				//          https://github.com/kbase/JobRunner/issues/92
				System.err.println(
						"Callback server container didn't stop after asking nicely. "
						+ "Force destoying callback server container"
				);
				proc.destroyForcibly();
			}
			else if (proc.exitValue() != 0) {
				System.err.println(String.format(
						"Unexpected exit code from docker: %s", proc.exitValue()
				));
			}
		} finally {
			Files.delete(initProvFile);
		}
	}
	
	private String getHost(final ObjectMapper mapper) throws IOException {
		final String host;
		if (isDockerDesktop(mapper)) {
			// probably a better way to do this. Maybe pass in a stream to write to?
			System.out.println(
					"Detected Docker Desktop, using docker internal host for Callback Server"
			);
			host = "host.docker.internal";  // points to host inside container
		} else {
			System.out.println(
					"Docker Desktop not detected, getting docker bridge network gateway for "
					+ "Callback Server"
			);
			// this all seems pretty fragile
			@SuppressWarnings("unchecked")
			final List<Map<String, Object>> dockerInfo =
					(List<Map<String, Object>>) runJSONCommand(
							mapper, "docker",  "network",  "inspect", "bridge"
			);
			@SuppressWarnings("unchecked")
			final Map<String, Object> ipam = (Map<String, Object>) dockerInfo.get(0).get("IPAM");
			@SuppressWarnings("unchecked")
			final List<Map<String, Object>> config =
					(List<Map<String, Object>>) ipam.get("Config");
			host = (String) config.get(0).get("Gateway");
		}
		return host;
	}
	
	private boolean isDockerDesktop(final ObjectMapper mapper) throws IOException {
		@SuppressWarnings("unchecked")
		final Map<String, Object> dockerInfo = (Map<String, Object>) runJSONCommand(
				mapper, "docker",  "info",  "-f", "json"
		);
		final String os = (String) dockerInfo.get("OperatingSystem");
		if (os == null) {
			throw new IOException("docker info missing operating system information");
		}
		return os.equals("Docker Desktop");  // TODO NOW check with bill
	}
	
	private Object runJSONCommand(final ObjectMapper mapper, final String... command)
			throws IOException {
		final Process proc = new ProcessBuilder(command).start();
		// these error condition are essentially impossible to test under normal conditions
		final boolean finished;
		try {
			finished = proc.waitFor(2L, TimeUnit.SECONDS);
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
		return mapper.readValue(proc.getInputStream().readAllBytes(), Object.class);
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
