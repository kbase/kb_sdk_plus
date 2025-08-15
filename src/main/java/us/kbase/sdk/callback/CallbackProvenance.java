package us.kbase.sdk.callback;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.zafarkhaja.semver.Version;

/**
 * Immutable value class representing initial provenance information for a callback service.
 * Instances are created via {@link #getBuilder(String, Version)}.
 */
public final class CallbackProvenance {

	private static final Pattern MODULE_METHOD_PATTERN =
		Pattern.compile("^[A-Za-z0-9_]+\\.[A-Za-z0-9_]+$");

	private static final Pattern SERVICE_VER_PATTERN =
		Pattern.compile("^(release|beta|dev|[0-9a-f]{7,40})$");

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final String modulemethod;
	private final Version version;
	private final String serviceVer;
	private final List<Object> params;
	private final List<String> workspaceRefs;

	private CallbackProvenance(Builder b) {
		this.modulemethod = b.modulemethod;
		this.version = b.version;
		this.serviceVer = b.serviceVer;
		this.params = Collections.unmodifiableList(b.params);
		this.workspaceRefs = Collections.unmodifiableList(b.workspaceRefs);
	}

	/**
	 * Get the method to be stored in initial callback server provenance as the method run
	 * in "module.method" format.
	 *  @return the SDK method to store in initial callback server provenance.
	 */
	public String getModuleMethod() {
		return modulemethod;
	}

	/**
	 * Get the version of the module to be stored in provenance.
	 *  @return the module version.
	 */
	public Version getVersion() {
		return version;
	}

	/**
	 * Get the service version of the module to be stored in initial callback server provenance
	 * either "relase", "beta', "dev", or a git hash.
	 * @return the service version
	 */
	public String getServiceVer() {
		return serviceVer;
	}

	/**
	 * Get the parameters of the method to be stored in initial callback server provenance.
	 * The parameters are JSON serializable.
	 * 
	 * @return the method parameters.
	 */
	public List<Object> getParams() {
		return params;
	}

	/**
	 * Get the workspace references to be stored in initial callback server provenance.
	 * @return an list of workspace reference strings in the standard ws/obj/ver format.
	 */
	public List<String> getWorkspaceRefs() {
		return workspaceRefs;
	}
	
	@Override
	public int hashCode() {
		return Objects.hash(modulemethod, params, serviceVer, version, workspaceRefs);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		CallbackProvenance other = (CallbackProvenance) obj;
		return Objects.equals(modulemethod, other.modulemethod)
				&& Objects.equals(params, other.params)
				&& Objects.equals(serviceVer, other.serviceVer)
				&& Objects.equals(version, other.version)
				&& Objects.equals(workspaceRefs, other.workspaceRefs
		);
	}

	/**
	 * Get a builder for the {@link CallbackProvenance} class.
	 *
	 * @param modulemethod the module / method string for the SDK method to run,
	 * e.g. "module.method".
	 * @param version the version of the method to run.
	 */
	public static Builder getBuilder(final String modulemethod, final Version semver) {
		return new Builder(modulemethod, semver);
	}

	/**
	 * Builder for {@link CallbackProvenance}.
	 */
	public static final class Builder {
		private final String modulemethod;
		private final Version version;
		private String serviceVer = "dev";
		private List<Object> params = Collections.emptyList();
		private List<String> workspaceRefs = Collections.emptyList();

		private Builder(final String modulemethod, final Version semver) {
			requireNonNull(semver, "semver");
			requireNonNull(modulemethod, "modulemethod");
			if (!MODULE_METHOD_PATTERN.matcher(modulemethod).matches()) {
				throw new IllegalArgumentException("Invalid modulemethod: " + modulemethod);
			}
			this.modulemethod = modulemethod;
			this.version = semver;
		}

		/**
		 * Sets the service version to be stored in the initial callback server provenance.
		 *
		 * @param serviceVer "release", "beta", "dev", or a git hash
		 * @return this Builder
		 */
		public Builder withServiceVer(final String serviceVer) {
			if (!SERVICE_VER_PATTERN.matcher(requireNonNull(serviceVer, "serviceVer")).matches()) {
				throw new IllegalArgumentException("Invalid service version: " + serviceVer);
			}
			this.serviceVer = serviceVer;
			return this;
		}

		/**
		 * Sets the params list to be stored in the initial callback server provenance.
		 * 
		 * Must be JSON serializable.
		 *
		 * @param params the parameters for the method to be stored.
		 * @return this Builder
		 */
		public Builder withParams(final List<Object> params) {
			if (params == null) {
				this.params = Collections.emptyList();
				return this;
			}
			try {
				MAPPER.writeValueAsString(params);
			} catch (JsonProcessingException e) {
				throw new IllegalArgumentException("params are not JSON serializable", e);
			}
			this.params = new ArrayList<>(params);
			return this;
		}

		/**
		 * Sets the list of workspace references to be stored in the initial callback server
		 * provenance.
		 * 
		 * References are in the standard format of ws/obj/ver.
		 *
		 * @param refs the references.
		 * @return this Builder
		 */
		public Builder withWorkspaceRefs(final Collection<String> refs) {
			if (refs == null) {
				this.workspaceRefs = Collections.emptyList();
				return this;
			}
			refs.stream()
				.forEach(ref -> {
					requireNonNull(ref, "workspace object ref");
					// TODO CODE add more validation later, check ref structure
				});
			this.workspaceRefs = new ArrayList<>(refs);
			return this;
		}

		/**
		 * Builds the {@link CallbackProvenance} instance.
		 *
		 * @return the instance
		 */
		public CallbackProvenance build() {
			return new CallbackProvenance(this);
		}
	}
}
