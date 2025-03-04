# KB-SDK plus

## Why?

The KBase SDK is a core piece of infrastructure, but it has become exceedingly difficult to
maintain and expand as the test suite is exceptionally complex and currently does not pass.
As such, large changes to the codebase are risky and no updates have been released for 5+ years.
Furthermore, due to outdated dependencies, the SDK will not run on modern machines.

To rectify this while still supporting older apps, we have elected to fork the
https://github.com/kbase/kb_sdk repo to `kb_sdk_plus`. Version 1.2.1 is the last `kb_sdk`
release and will be kept as is. Future SDK development will occur in `kb_sdk_plus`.

## Why not kb_sdk2?

`<repo>2` generally signifies a greenfield rewrite of a codebase in KBase,
which `kb_sdk_plus` is not. It's forking a codebase to leave the original codebase untouched
and runnable while we make changes to the forked codebase.

## Guidelines

* Backwards incompatible changes are expected in some respects:
  * R code generation will be removed
    * Unused in KBase or anywhere else as far as we know other than one test app
  * Perl code generation will be removed
    * Perl apps can continue to be supported with the original SDK, or wrapped with
      Python / Java
      * https://github.com/boriel/perlfunc for instance
  * Javascript code generation will be removed
     * The KBase UI uses its own clients as far as we know.
     * The JS clients don't really do much.
     * If JS client support is needed they can be generated with the original SDK
  * The `rename` command will be removed.
* Otherwise `kb_sdk_plus` needs to be backwards compatible with `kb_sdk`
  * It must work with the current KBase backend services
  * Python and Java apps must continue to be supported
    * It might be possible to make backwards incompatible changes that are fixed
      with a recompile of an app or a simple migration guide.
      This would need careful thought and implementation.
* Generally simplify the codebase where possible and document where needed
      
## Prior to releasing for general development

* Get the tests to pass locally
  * Remove R support, probably remove Perl support
  * Do we still need Javascript support?
* Remove the git submodules and `submodules_hacks` directory
* See if the `sdkbase` directory is still needed, remove if not
* Try to remove the `lib` directory
* Remove `JAR_DEPS`, `JAR_DEPS_BIN`, and `DEPENDENCIES`
* Understand the purpose of the code in the `entrypoint` script and simplify if possible
* Use the dockerized JobRunner callback server for tests
  * E.g. remove the Java callback server implementation
* Ensure tests are running against EE2 vs. EE1 (e.g. the `KBaseJobService.spec`
  file is suspicious)
* Fix docker version issues (see Dakota's patch: https://github.com/kbase/kb_sdk_patch)
* Convert the build to Gradle
  * Remove `build.xml`, `travis.yml` and `.classpath`
* Update to Java 11 at least. Test with newer Java versions if possible
* Get the tests running in GHA with coverage, dependabot, and trivy
  * At least look into running macOS and windows tests in GHA
    * If not, clearly document manual testing requirements before a release
    * Do we need to support windows?
    * Resources:
      * https://docs.github.com/en/actions/using-github-hosted-runners/using-github-hosted-runners/about-github-hosted-runners#standard-github-hosted-runners-for-public-repositories
      * https://github.com/orgs/community/discussions/69211#discussioncomment-7197681
      * https://github.com/actions/runner/issues/904
      
* Get `kb_sdk_plus` image build running in GHA
  * Or we could just call it `kb_sdk` since the original container is on Dockerhub
* Add tests that run `kb-sdk` from the command line vs. just testing java classes
  * See examples in .travis.yml but do much more than that
  * Many issues crop up here
* Get the SDK running on docker03
  * Not sure what the issues are here, need to investigate
* Figure out how to support Argonne staff, where Docker Desktop is banned
  * Is the docker engine CLI not enough?

## Post general development release

* Before making changes to code / templates, the code needs to be covered with tests
* Triage and fix CVEs
* Look into easier ways to deal with documentation and keep the documentation and source in
  the same repo (e.g. deprecate https://github.com/kbase/kb_sdk_docs)
* Simplify the menagerie of code / scripts / `Makefiles` calling and generating each other
  if possible
* Make the interface consistent - e.g. no using `kb-sdk <command>` for some tasks
  and `make <command>` for others
* Check the shell completion code is up to date and see if there's a simpler way to implement
  without having to duplicate commands in the shell script
* Port over useful changes in the `kb_sdk` `develop` branch
* Extract the KIDL parsing code into its own repo as it’s used in the workspace
  * But see https://github.com/kbase/java_kidl/blob/e569971cf0eab60c3702a2fe1fc181ce63a175c0/TODO.md
* Assess and migrate issues from https://github.com/kbase/kb_sdk/issues
* Check the KBase tech debt document appendices

## Code origin

The initial commit of the `kb_sdk_plus` code was copied from the `master` branch of
https://github.com/kbase/kb_sdk, release 1.2.1, commit `80aebc4`, with the following changes:

* All `.gitignore` files were removed in favor of a to be implemented ignore strategy
* The `.project` and `.pydevproject` files were removed
* The `RELEASE_NOTES.txt` file was removed and `RELEASE_NOTES.md` added
