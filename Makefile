# BioDex — build entry points.
#
# Gradle is the build system; this file exists because using it needs two
# environment variables that are easy to forget and fail confusingly when they
# are missing. Every target resolves them the same way, so `make test` works in
# a fresh shell with nothing exported.
#
# Run `make` or `make help` for the target list.

SHELL := /bin/bash

# JDK 17. macOS ships java_home; elsewhere, an already-exported JAVA_HOME wins.
JAVA_HOME ?= $(shell /usr/libexec/java_home -v 17 2>/dev/null)

# The SDK location lives in local.properties, which is git-ignored and absent
# from a fresh clone — see the `doctor` target, which says so in plain words.
SDK_DIR := $(shell sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null)

GRADLE := JAVA_HOME="$(JAVA_HOME)" ANDROID_HOME="$(SDK_DIR)" ./gradlew
ADB := $(SDK_DIR)/platform-tools/adb

.DEFAULT_GOAL := help
.PHONY: help doctor debug release install test test-device check catalogue catalogue-test screenshot clean

help: ## List the targets
	@echo "BioDex — make targets"
	@echo
	@grep -hE '^[a-z-]+:.*?## ' $(MAKEFILE_LIST) \
		| awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'
	@echo
	@echo "Setup and signing:  docs/BUILD.md"

doctor: ## Check that the toolchain is actually usable, and say what is missing
	@ok=1; \
	if [ -z "$(JAVA_HOME)" ]; then \
		echo "✗ JDK 17 not found. Install Temurin 17, or export JAVA_HOME yourself."; ok=0; \
	else echo "✓ JDK 17    $(JAVA_HOME)"; fi; \
	if [ ! -f local.properties ]; then \
		echo "✗ local.properties is missing. It is git-ignored, so a fresh clone has none."; \
		echo "  Create it with one line naming your SDK, e.g.:"; \
		echo "      sdk.dir=/path/to/android-commandlinetools"; ok=0; \
	elif [ ! -d "$(SDK_DIR)" ]; then \
		echo "✗ sdk.dir in local.properties points at nothing: $(SDK_DIR)"; ok=0; \
	else echo "✓ Android SDK   $(SDK_DIR)"; fi; \
	if [ -f keystore.properties ]; then echo "✓ release signing configured"; \
	else echo "· no keystore.properties — 'make release' will produce an UNSIGNED apk (fine, and expected on a fresh clone)"; fi; \
	if [ -x "$(ADB)" ] && [ -n "$$($(ADB) devices | sed '1d;/^$$/d')" ]; then echo "✓ a device is attached"; \
	else echo "· no device attached — 'make install' and 'make test-device' need one"; fi; \
	[ $$ok = 1 ] || { echo; echo "Fix the ✗ lines above, then re-run. docs/BUILD.md has the detail."; exit 1; }

debug: ## Build the debug APK
	@$(GRADLE) assembleDebug
	@echo "APK: app/build/outputs/apk/debug/app-debug.apk"

release: ## Build the release APK (unsigned without keystore.properties)
	@$(GRADLE) assembleRelease
	@echo "APK: app/build/outputs/apk/release/app-release.apk"

install: ## Build and install onto the attached phone
	@$(GRADLE) installDebug

test: ## Run the JVM tests (no device needed)
	@$(GRADLE) testDebugUnitTest

test-device: ## Run the instrumented tests (needs a phone; UNINSTALLS the app afterwards)
	@$(GRADLE) connectedDebugAndroidTest
	@echo "Note: that run uninstalled BioDex from the phone. 'make install' puts it back."

check: catalogue-test ## Everything runnable without a phone: JVM tests + catalogue tests
	@$(GRADLE) testDebugUnitTest --rerun-tasks

catalogue: ## Rebuild the bundled catalogue asset (network on a cold cache; slow)
	@cd tools/catalogue && python3 build_catalogue.py \
		--out ../../app/src/main/assets/catalogue/pacific.json

catalogue-test: ## Run the catalogue pipeline's Python tests
	@cd tools/catalogue && python3 -m unittest test_build_catalogue

screenshot: ## Grab the phone's screen to shot.png
	@$(ADB) exec-out screencap -p > shot.png && echo "wrote shot.png"

clean: ## Delete build outputs
	@$(GRADLE) clean
