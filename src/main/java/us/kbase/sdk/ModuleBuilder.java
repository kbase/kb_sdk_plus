package us.kbase.sdk;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import picocli.AutoComplete.GenerateCompletion;
import picocli.CommandLine;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import us.kbase.sdk.common.KBaseYmlConfig;
import us.kbase.sdk.compiler.RunCompileCommand;
import us.kbase.sdk.initializer.ModuleInitializer;
import us.kbase.sdk.installer.ClientInstaller;
import us.kbase.sdk.runner.ModuleRunner;
import us.kbase.sdk.tester.ModuleTester;
import us.kbase.sdk.util.ProcessHelper;
import us.kbase.sdk.validator.ModuleValidator;


@Command(
		name = "kb-sdk",
		description =
				"KBase SDK+ - a developer tool for building and validating KBase modules",
		subcommands = {
				HelpCommand.class,
				ModuleBuilder.InitCommand.class,
				ModuleBuilder.TestCommand.class,
				ModuleBuilder.InstallCommand.class,
				ModuleBuilder.ValidateCommand.class,
				ModuleBuilder.CompileCommand.class,
				ModuleBuilder.RunCommand.class,
				GenerateCompletion.class,
				ModuleBuilder.VersionCommand.class,
		}
)
public class ModuleBuilder implements Runnable{

	private static final String DEFAULT_PARENT_PACKAGE = "us.kbase";

	public static final String GLOBAL_SDK_HOME_ENV_VAR = "KB_SDK_HOME";

	public static final String VERSION = "0.1.0";
	
	// keep a single source of truth for the version info
	private static final String VERSION_INFO =
			"KBase SDK+ version " + VERSION + " (commit " + GitCommit.COMMIT + ")";

	public static void main(final String[] args) throws Exception {
		final int exitCode = new CommandLine(new ModuleBuilder()).execute(args);
		System.exit(exitCode);
	}

	@Override
	public void run() {
		// fallback when no subcommand is used
		CommandLine.usage(this, System.out);
	}

	public static class Verbose {

		@Option(
				names = {"-v", "--verbose"},
				description = "Show verbose output including stack traces when appropriate.",
				defaultValue = "false"
		)
		boolean verbose;
	}
	
	@Command(name = "validate", description = "Validate a module.")
	public static class ValidateCommand extends Verbose implements Callable<Integer> {

		@Parameters(
				paramLabel = "<module_path>",
				description = "Path to  the module directory.",
				arity = "0..1",
				defaultValue = ".",
				showDefaultValue = CommandLine.Help.Visibility.ALWAYS
		)
		Path module;

		@Override
		public Integer call() {
			try {
				final ModuleValidator mv = new ModuleValidator(module.toString(), verbose);
				return mv.validate();
			} catch (Exception e) {
				showError("Error while validating module", e.getMessage());
				if (verbose) {
					e.printStackTrace();
				}
				return 1;
			}
		}
	}

	/**
	 * Runs the module initialization command - this creates a new module in the relative
	 * directory name given.
	 */
	@Command(name = "init", description = "Initialize a module in the current directory.")
	public static class InitCommand extends Verbose implements Callable<Integer> {
		
		@Option(
				paramLabel = "<user_name>",
				names = {"-u", "--user"},
				required = true,
				description = "Provide a username to serve as the owner of this module."
		)
		String userName;

		@Option(
				names = {"-e", "--example"},
				description = """
						Include a fully featured example in your module. \
						This generates an example set of code and configurations.\
						""",
				defaultValue = "false"
		)
		boolean example;
		
		@Option(
				names = {"-l", "--language"},
				description =
						"The language for the module. Valid values: ${COMPLETION-CANDIDATES}.",
				defaultValue = "python",
				showDefaultValue = CommandLine.Help.Visibility.ALWAYS
		)
		Language language;

		@Parameters(
				paramLabel = "<module_name>",
				description = "The name of the module to create.",
				arity = "1"
		)
		String moduleName;

		@Override
		public Integer call() {
			try {
				final ModuleInitializer initer = new ModuleInitializer(
						moduleName, userName, "" + language, verbose
				);
				initer.initialize(example);
			}
			catch (Exception e) {
				showError("Error while initializing module", e.getMessage());
				if (verbose) {
					e.printStackTrace();
				}
				return 1;
			}
			return 0;
		}
	}
	
	public static class ClientVersionsGroup {
		@Option(
				names = {"--clasyncver"},
				description = """
						Set the version of code to run when making asynchronous calls via a \
						client. Valid values are a git commit hash or "dev", "beta", or "release". \
						The targeted version must be registered in the KBase Catalog.\
						"""
		)
		String clAsyncVer;

		@Option(
				names = {"--dynservver"},
				description = """
						Set clients to be built for use with KBase dynamic services \
						(e.g. with URL lookup via the Service Wizard) with the specified \
						dynamic service version. \
						Valid values are a git commit hash or "dev", "beta", or "release". \
						The targeted version must be registered in the KBase Catalog.\
						"""
		)
		String dynservVer;
	}

	@Command(name = "compile", description = "Compile a KIDL file into client and server code.")
	public static class CompileCommand extends Verbose implements Callable<Integer> {
		
		@Option(
				names = {"--out"},
				description = "Set the output folder name.",
				defaultValue = ".",
				showDefaultValue = CommandLine.Help.Visibility.ALWAYS
		)
		Path out;

		@Option(
				names = {"--url"},
				description =
						"Set the default url for the target service in generated client code."
		)
		URL url;
		
		@Option(
				names = {"--py"},
				description = "Generate a Python client with a default name.",
				defaultValue = "false"
		)
		boolean pyClientSide;
		
		@Option(
				paramLabel = "<py_client_name>",
				names = {"--pyclname"},
				description = """
						Generate a Python client with the \
						name provided, optionally prefixed by subdirectories separated by '.' \
						as in the standard Python module syntax \
						(e.g. biokbase.mymodule.client). Overrides the --py option.\
						"""
		)
		String pyClientName;
		
		@Option(
				names = {"--pysrv"},
				description = "Generate a Python server with a default name.",
				defaultValue = "false"
		)
		boolean pyServerSide;
		
		@Option(
				names = {"--pysrvname"},
				paramLabel = "<py_server_name>",
				description = """
						Generate a Python server with the \
						name provided, optionally prefixed by subdirectories separated by '.' \
						as in the standard Python module syntax \
						(e.g. biokbase.mymodule.server). Overrides the --pysrv option.\
						"""
		)
		String pyServerName;
		
		@Option(
				paramLabel = "<py_implementation_name>",
				names = {"--pyimplname"},
				description = """
						Generate a Python server implementation with the \
						name provided, optionally prefixed by subdirectories separated by '.' \
						as in the standard Python module syntax \
						(e.g. biokbase.mymodule.impl). \
						If set, Python server code will also be generated.\
						"""
		)
		String pyImplName;

		@Option(
				paramLabel = "<java_source_dir>",
				names = {"--javasrc"},
				description = "Set the output folder for generated Java code.",
				defaultValue = "src",
				showDefaultValue = CommandLine.Help.Visibility.ALWAYS
		)
		String javaSrcDir;
		
		@Option(
				paramLabel = "<java_package>",
				names = {"--javapackage"},
				description = """
						Set the Java package for generated code. Module subpackages are \
						created in this package.\
						""",
				defaultValue = DEFAULT_PARENT_PACKAGE,
				showDefaultValue = CommandLine.Help.Visibility.ALWAYS
		)
		String javaPackageParent;
		
		@Option(
				names = {"--java"},
				description = "Generate Java client code in the directory set by --javasrc.",
				defaultValue = "false"
		)
		boolean javaClientSide;
		
		@Option(
				names = {"--javasrv"},
				description = "Generate Java server code in the directory set by --javasrc.",
				defaultValue = "false"
		)
		boolean javaServerSide;
		
		@Option(
				paramLabel = "<json_schema_dir>",
				names = {"--jsonschema"},
				description = """
					Generate JSON schema documents for the types in the output folder specified.\
					"""
		)
		String jsonSchema;
		
		@ArgGroup(exclusive = true, multiplicity = "0..1")
		final ClientVersionsGroup clientVersions = new ClientVersionsGroup();
		
		@Option(
				names = {"--html"},
				description = "Generate an HTML version of the input spec file.",
				defaultValue = "false"
		)
		boolean html;
		
		@Parameters(
				paramLabel = "<spec_file>",
				description = "The KIDL specification file to process.",
				arity = "1"
		)
		Path specFileName;

		@Override
		public Integer call() {
			// TODO CODE this method is 100 lines, split it up (pretty basic though)
			System.out.println(VERSION_INFO);
			
			final Path specFile = specFileName.toAbsolutePath();
			if (!Files.exists(specFile) || !Files.isRegularFile(specFile)) {
				showError(
						"Error accessing input KIDL spec file",
						"File does not exist or is not a regular file"
				);
				return 1;
			}
			
			final Path outDir = out.toAbsolutePath();
			try {
				Files.createDirectories(outDir);
			} catch (IOException e) {
				showError("Error creating output directory", e.getMessage());
				if (verbose) {
					e.printStackTrace();
				}
				return 1;
			}

			final Path moduleDir = specFile.getParent();
			String semanticVersion = null;
			try {
				if (Files.exists(moduleDir.resolve(KBaseYmlConfig.KBASE_YAML))) {
					semanticVersion = new KBaseYmlConfig(moduleDir).getSemanticVersion();
				}
			} catch (Exception ex) {
				System.out.println(
						"WARNING! Couldn't collect semantic version: " + ex.getMessage()
				);
			}
			if (semanticVersion == null) {
				semanticVersion = "0.1.0";
			}
			String gitUrl = "";
			String gitCommitHash = "";
			if (Files.exists(moduleDir.resolve(".git"))) {
				try {
					gitUrl = getCmdOutput(
							moduleDir.toFile(), "git", "config", "--get", "remote.origin.url"
					);
				} catch (Exception ex) {
					System.out.println("WARNING! Couldn't collect git URL: " + ex.getMessage());
				}
				try {
					gitCommitHash = getCmdOutput(moduleDir.toFile(), "git", "rev-parse", "HEAD");
				} catch (Exception ex) {
					System.out.println(
							"WARNING! Couldn't collect git commit hash: " + ex.getMessage()
					);
				}
			}
			try {
				// TODO CODE start using Path instead of File
				// TODO CODE this needs a freakin builder
				RunCompileCommand.generate(
						specFile.toFile(),
						url,
						pyClientSide,
						pyClientName, 
						pyServerSide,
						pyServerName,
						pyImplName,
						javaClientSide, 
						javaServerSide,
						javaPackageParent,
						javaSrcDir,
						outDir.toFile(),
						jsonSchema,
						clientVersions.clAsyncVer,
						clientVersions.dynservVer,
						html,
						semanticVersion,
						gitUrl,
						gitCommitHash
				);
			} catch (Throwable e) {
				showError("Error compiling KIDL specfication:", e.getMessage());
				if (verbose) {
					e.printStackTrace();
				}
				return 1;
			}
			return 0;
		}
	}
	
	
	private static String getCmdOutput(File workDir, String... cmd) throws Exception {
		final StringWriter sw = new StringWriter();
		final PrintWriter pw = new PrintWriter(sw);
		ProcessHelper.cmd(cmd).exec(workDir, null, pw);
		return sw.toString().trim();
	}

	public static class SetDockerOutputWriteable {

		@Option(
				names = {"--set-output-globally-readwrite"},
				description = """
						WARNING: Setting this flag will make files containing your token \
						globally readable and writeable - ensure the parent directory of the \
						workdir and subjobs directories is not readable, writeable, or \
						executable. If set, sets all files in the workdir and subjobs \
						directories to globally readable and writeable after the run is complete. \
						Docker containers may run as root and leave root-owned files on the \
						file system. This flag allows the files to be modified or deleted.
						""",
				defaultValue = "false"
		)
		boolean setGloballyWriteable;
	}
	
	/**
	 * Runs the module test command - this runs tests in a local docker container.
	 */
	@Command(name = "test", description = "Test a module with local Docker.")
	public static class TestCommand extends Verbose implements Callable<Integer> {
		
		@Mixin SetDockerOutputWriteable gl;
		
		@Option(
				names = {"-s", "--skip_validation"},
				description = "Skip module validation.",
				defaultValue = "false"
		)
		boolean skipValidation;
		
		@Override
		public Integer call() {
			try {
				final ModuleTester tester = new ModuleTester();
				return tester.runTests(skipValidation, gl.setGloballyWriteable);
			}
			catch (Exception e) {
				showError("Error while testing module", e.getMessage());
				if (verbose) {
					e.printStackTrace();
				}
				return 1;
			}
		}
	}
	
	public static class TagAndVerbose extends Verbose {
		
		@Option(
				paramLabel = "<tag_or_version>",
				names = {"-t", "--tag-or-ver"},
				description = """
						Set the version of the module to run on a call - \
						one of the "dev", "beta", or "release" tags or a git hash. \
						The targeted version must be registered in the KBase Catalog. \
						The default is based on the latest release in the KBase Catalog.\
						"""
		)
		String tagVer;
	}
	
	@Command(name = "install", description = "Install a client for a KBase module.")
	public static class InstallCommand extends TagAndVerbose implements Callable<Integer> {
		
		@Option(
				names = {"-l", "--language"},
				description = """
						Language for the generated client. Defaults to the module langage. \
						Valid values: ${COMPLETION-CANDIDATES}.\
						"""
		)
		Language language;
		
		@Option(
				names = {"-a", "--async"},
				description = """
						Force generation of asynchronous calls. Default is the KBase Catalog \
						setting for the module.\
						""",
				defaultValue = "false"
		)
		boolean async;
		
		@Option(
				names = {"-c", "--core"},
				description = """
						Force generation of synchronous calls for KBase core services. \
						In almost all cases specifying this argument is a mistake.\
						""",
				defaultValue = "false"
		)
		boolean core;
		
		@Option(
				names = {"-d", "--dynamic"},
				description = """
						Force generation of dynamic service calls. Default is the KBase Catalog \
						setting for the module.\
						""",
				defaultValue = "false"
		)
		boolean dynamic;
		
		@Option(
				paramLabel = "<client_name>",
				names = {"-n", "--clientname"},
				description = """
						Set a custom name for the client. The default is the module name.\
						"""
		)
		String clientName;
		
		@Parameters(
				paramLabel = "<module_name_or_path_or_url>",
				description = """
						Either the module name of the client to install, or a file path or URL \
						to a KIDL spec for the client to install.\
						""",
				arity = "1"
		)
		String moduleName;

		@Override
		public Integer call() {
			try {
				return new ClientInstaller().install(
						language == null ? null: language.toString(),
						async,
						core,
						dynamic, 
						tagVer,
						verbose,
						moduleName,
						null,       // libDirName
						clientName
				);
			} catch (Exception e) {
				showError("Error while installing client", e.getMessage());
				if (verbose) {
					e.printStackTrace();
				}
				return 1;
			}
		}
	}
	
	public static class RunInput {
		@Option(
				paramLabel = "<input_file>",
				names = {"-i", "--input"},
				description = "The JSON file containing the input for the method to run."
		)
		Path inputFile;
		
		@Option(
				names = {"-s", "--stdin"},
				description = "Read the JSON input for the method from STDIN.",
				defaultValue = "false"
		)
		boolean stdin;
		
		@Option(
				paramLabel = "<input_json>",
				names = {"-j", "--json"},
				description = "A JSON string containing the input for the method to run."
		)
		String inputJson;
	}
	
	@Command(
			name = "run",
			description = "Run a SDK method locally."
	)
	public static class RunCommand extends TagAndVerbose implements Callable<Integer> {
		
		@ArgGroup(exclusive = true, multiplicity = "1")
		final RunInput input = new RunInput();
		
		@Option(
				paramLabel = "<output_file>",
				names = {"-o", "--output"},
				description = """
						The file where output should be written. If unspecified, output is \
						written to STDOUT.\
						"""
		)
		Path output;
		
		@Mixin SetDockerOutputWriteable gl;
		
		@Option(
				names = {"-k", "--keep-tmp"},
				description = """
						Keep temporary files from the run rather than deleting them. \
						Note that if docker written root owned files are on disk they \
						will not be able to be deleted.
						""",
				defaultValue = "false"
		)
		boolean keepTempFiles;
		
		@Option(
				paramLabel = "<sdk_home_dir>",
				names = {"-h", "--sdk-home"},
				description = """
						The folder containing the sdk.cfg and run_local folders. \
						If they do not exist, they will be created. The default is loaded \
						from the KB_SDK_HOME environment variable, which must be set if this \
						argument is not supplied. \
						Any input files must be placed in run_local/workdir/tmp for them to \
						be visible to the SDK module containers. "workdir" will be mounted into \
						the containers at "/kb/module/work", so any input file paths in the \
						method input must be prefixed with "/kb/module/work/tmp/".
						"""
		)
		Path sdkHome;
		
		@Option(
				paramLabel = "<provenance_references>",
				names = {"-r","--prov-refs"},
				description = """
						A comma-separated list of KBase workspace object addresses in the format \
						<numerical workspace ID>/<numerical object ID>/<version>. \
						They will be included in the provenance of any saved workspace objects.\
						"""
		)
		String provRefs;

		@Parameters(
				paramLabel = "<method_name>",
				description = """
						The fully qualified name of the method to run, e.g. \
						"module_name.method_name". The method must be registered in the KBase \
						catalog.\
						""",
				arity = "1"
		)
		String methodName;

		@Override
		public Integer call() {
			try {
				return new ModuleRunner(sdkHome == null ? null : sdkHome.toString()).run(
						methodName,
						input.inputFile == null ? null : input.inputFile.toFile(),
						input.stdin,
						input.inputJson,
						output == null ? null : output.toFile(), 
						tagVer,
						verbose,
						keepTempFiles,
						provRefs,
						gl.setGloballyWriteable
				);
			} catch (Exception e) {
				showError("Error while running method", e.getMessage());
				if (verbose) {
					e.printStackTrace();
				}
				return 1;
			}
		}
	}
	
	@Command(name = "version", description = "Print the program version and exit.")
	public static class VersionCommand implements Callable<Integer> {
		
		// could add some different args for more parseable formats (e.g. JSON)
		
		@Override
		public Integer call() {
			System.out.println(VERSION_INFO);
			return 0;
		}
	}
	
	private static void showError(final String error, final String message) {
		System.err.println(error + ": " + message + "\n");
	}
}
