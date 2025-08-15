package us.kbase.test.sdk.callback;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.zafarkhaja.semver.Version;

import nl.jqno.equalsverifier.EqualsVerifier;
import us.kbase.sdk.callback.CallbackProvenance;

public class CallbackProvenanceTest {

	@Test
	public void equalsAndHashCodeContract() {
		EqualsVerifier.forClass(CallbackProvenance.class).verify();
	}
	
	@Test
	public void buildMinimalValidInstance() {
		final CallbackProvenance cp = CallbackProvenance
				.getBuilder("module.method", Version.parse("1.0.0"))
				.build();

		assertThat(cp.getModuleMethod(), is("module.method"));
		assertThat(cp.getVersion(), is(Version.parse("1.0.0")));
		assertThat(cp.getServiceVer(), is("dev"));
		assertThat(cp.getParams(), empty());
		assertThat(cp.getWorkspaceRefs(), empty());
	}
	
	@Test
	public void buildMinimalValidInstanceOverwriteWithNulls() throws Exception {
		final CallbackProvenance cp = CallbackProvenance
				.getBuilder("module2.method2", Version.parse("1.0.0"))
				.withParams(Arrays.asList("foo"))
				.withWorkspaceRefs(Arrays.asList("1/2/3"))
				.withParams(null)
				.withWorkspaceRefs(null)
				.build();

		assertThat(cp.getModuleMethod(), is("module2.method2"));
		assertThat(cp.getVersion(), is(Version.parse("1.0.0")));
		assertThat(cp.getServiceVer(), is("dev"));
		assertThat(cp.getParams(), empty());
		assertThat(cp.getWorkspaceRefs(), empty());
	}

	@Test
	public void buildFullValidInstance() throws Exception {
		final CallbackProvenance cp = CallbackProvenance
				.getBuilder("f_oo.ba_r", Version.parse("2.3.4"))
				.withServiceVer("release")
				.withParams(Arrays.asList("x", 123))
				.withWorkspaceRefs(Arrays.asList("1/1/3", "w._-s1/ob|._-j1/15", "65/143154/1"))
				.build();

		assertThat(cp.getModuleMethod(), is("f_oo.ba_r"));
		assertThat(cp.getVersion(), is(Version.parse("2.3.4")));
		assertThat(cp.getServiceVer(), is("release"));
		assertThat(cp.getParams(), contains("x", 123));
		assertThat(cp.getWorkspaceRefs(), contains("1/1/3", "w._-s1/ob|._-j1/15", "65/143154/1"));
	}
	
	@Test
	public void serviceVerVariations() {
		final List<String> testCases = Arrays.asList(
				"dev", "beta", "release", "7f8e19a", "ed2ceca5db5fdd3e69ba619bc901e9eaf3d4da0d"
		);
		for (final String t: testCases) {
			final CallbackProvenance cp = CallbackProvenance
					.getBuilder("module.method", Version.parse("1.0.0"))
					.withServiceVer(t)
					.build();
			assertThat(cp.getServiceVer(), is(t));
		}
	}

	@Test
	public void moduleMethodValidationFails() {
		Exception e = assertThrows(NullPointerException.class,
				() -> CallbackProvenance.getBuilder(null, Version.parse("1.0.0"))
		);
		assertThat(e.getMessage(), is("modulemethod"));

		final List<String> testCases = Arrays.asList(
				"foo", "foo$bar.meth", "bad method", "goodness.gra*cious"
		);
		for (final String t: testCases) {
			e = assertThrows(IllegalArgumentException.class,
					() -> CallbackProvenance.getBuilder(t, Version.parse("1.0.0"))
			);
			assertThat(e.getMessage(), is("Invalid modulemethod: " + t));
		}
	}

	@Test
	public void semverValidationFails() {
		final Exception e = assertThrows(NullPointerException.class,
				() -> CallbackProvenance.getBuilder("mod.meth", null)
		);
		assertThat(e.getMessage(), is("semver"));
	}

	@Test
	public void serviceVerValidationFails() {
		Exception e = assertThrows(NullPointerException.class,
				() -> CallbackProvenance.getBuilder("mod.meth", Version.parse("1.0.0"))
							.withServiceVer(null)
		);
		assertThat(e.getMessage(), is("serviceVer"));

		final List<String> testCases = Arrays.asList(
				"not_valid", "7fa8af", "1234567u", "1".repeat(41)
		);
		for (final String t: testCases) {
			e = assertThrows(IllegalArgumentException.class,
					() -> CallbackProvenance.getBuilder("mod.meth", Version.parse("1.0.0"))
								.withServiceVer(t)
			);
			assertThat(e.getMessage(), is("Invalid service version: " + t));	
		}
	}

	@Test
	public void paramsMustBeJsonSerializable() {
		final Exception e = assertThrows(IllegalArgumentException.class, () -> {
			CallbackProvenance.getBuilder("mod.meth", Version.parse("1.0.0"))
				.withParams(Arrays.asList(new ByteArrayOutputStream()));
		});
		assertThat(e.getMessage(), is("params are not JSON serializable"));
	}

	@Test
	public void refsValidationFails() {
		Exception e = assertThrows(NullPointerException.class, () -> 
				CallbackProvenance.getBuilder("mod.meth", Version.parse("1.0.0"))
				.withWorkspaceRefs(Arrays.asList("foo", null, "bar"))
		);
		assertThat(e.getMessage(), is("workspace object ref"));
	}

	@Test
	public void containersAreImmutable() throws Exception {
		// make sure inputs are mutable, Arrays.asList() output is immutable
		final List<Object> params = new LinkedList<>(Arrays.asList("x"));
		final List<String> refs = new LinkedList<>(Arrays.asList("1/2/3"));

		final CallbackProvenance cp = CallbackProvenance
				.getBuilder("mod.meth", Version.parse("1.0.0"))
				.withParams(params)
				.withWorkspaceRefs(refs)
				.build();

		assertThrows(UnsupportedOperationException.class, () -> cp.getParams().add("y"));
		assertThrows(
				UnsupportedOperationException.class, () -> cp.getWorkspaceRefs().add("4/5/6")
		);

		assertThat(cp.getParams(), contains("x"));
		assertThat(cp.getWorkspaceRefs(), contains("1/2/3"));
	}
}
