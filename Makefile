GITCOMMIT := $(shell git rev-parse --short HEAD)
VER := $(GITCOMMIT)

EPOCH := $(shell date +%s)

#EXT_KIDL_JAR = kbase-kidl-parser-$(EPOCH)-$(VER).jar
# TODO BUILD build a jar with the current version in the name.
#            Probably easier to wait for Gradle conversion

ANT ?= ant

# make sure our make test works
.PHONY : test test-python


default: compile

compile:
	$(ANT)

test: test-python
	@echo "Running tests"
	$(ANT) test

test-python:
	@echo "Running python tests"
	PYTHONPATH=./src/java/us/kbase/templates pytest \
		--cov=authclient \
		--cov-report=xml \
		--cov-report=html \
		test_scripts/py_module_tests

clean:
	$(ANT) clean
