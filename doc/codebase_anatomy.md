# Codebase Anatomy

This document describes the the file structure of the `kb_sdk` codebase.

TODO DOCS either redo or remove this. Is it really necessary?

#### Root level

* `doc/` - additional documentation about this codebase
* `Dockerfile` - the docker configuration for the container that runs the SDK
* `entrypoint` - the entrypoint bash script that is run for the SDK docker container
* `pyproject.toml` and `uv.lock` - python dependencies for `uv`
* `src/` - the main source code for this project; see below
* `test_scripts/` - test helpers in perl, python, and js

#### Source code in `/src/java/us/kbase`

* `catalog/` - A client for the KBase catalog service compiled from the catalog service KIDL specification
* `common/executionengine/` - Code for executing jobs and sub-jobs. All the code here is duplicated in the `njs_wrapper` repo
* `common/service/` - Some tuple datatypes
* `common/utils/` - NetUtils for working with IP addresses and ports
* `jkidl/` - Functionality for parsing KIDL spec files
* `kidl/` - KIDL parser syntax types
* `mobu/` - Module Builder (see below)
* `narrativemethodstore/` - A client for the KBase narrative method store service compiled from the NMS service KIDL specification
* `templates/` - Template files for use in generating SDK app codebases on `kb-sdk init`

#### Module builder in `src/java/us/kbase/mobu`

* `ModuleBuilder` - handles CLI commands and dispatches them to one of the below packages
* `compiler/` - parses the KIDL spec and compiles code in an SDK app
* `initializer/` - Initializes a new app, generating all templated files
* `installer/` - Installs other SDK apps as dependencies under the current one
* `runner/` - Runs an app in its docker container and the callback server
* `tester/` - Runs the test suite for an app
* `util/` - Generic utilities used by the module builder
* `validator/` - Validates an app using its KIDL spec, spec.json, etc

#### Miscellania

* `src/java/name/fraser/neil/plaintext/diff_match_patch.java` - A utility computing the difference between two texts to create a patch. This is used in `src/java/us/kbase/mobu/compiler/test/html/HTMLGenTest.java`.
