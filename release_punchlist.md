# Release punchlist

We're getting to a point where a release, while a way out, is in sight. Let's figure out what
still needs to be done, shall we?

* Gradleize Java SDK modules
  * No longer clone the entire jars repo
  * Security concerns
  * Allows dependabot to work
* Test if the binary works on docker03
  * Apparently issues with the current sdk there
* Have Argonne staff try the SDK
  * Docker desktop is banned, what do
* Add Java tests where needed
  * Check code coverage
  * ModuleBuilder and ModuleRunne have 0 tests
* Add tests for the compiled kb-sdk binary
  * Tests reflection configs
  * Run on GHA builds - Mac too
  * remove travis.yml
* Update dependencies
  * Could require extensive work in this and other modules
* Rework module documentation
  * Point to current SDK docs but have users read local docs first
  * Incorporate kb_sdk_plus.md, or parts of it
  * Dropped features
    * Windows
      * Use a VM or linux subsystem
    * Langs - Perl, JS, R
      * Workarounds for Perl and JS
    * Check PRs for other dropped features
  * kb-sdk -> kb-sdk+ guide
    * Python dockerfile update on recompile
    * Differences in setup
  * Command differences
  * Developer expectations
    * If code you're changing is not well tested, you MUST add tests first
* Remove Docker infrastructure
  * No longer needed with native binaries
