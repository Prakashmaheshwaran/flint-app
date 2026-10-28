# Flint — Peak Focus · convenience targets for a two-native-app monorepo.
# The two apps use independent toolchains (Xcode/SwiftPM and Gradle); this is thin glue.

IOS_DIR     := ios-app
ANDROID_DIR := android-app
# Keep Xcode outputs with the checkout so archiving a worktree reclaims them.
IOS_DERIVED_DATA ?= $(IOS_DIR)/DerivedData
IOS_SIM ?= iPhone 17
AVD ?= flint-test
# One resolver for recipes, parse-time shell calls, setup, and sourced terminals.
# The wrapper accepts GNU Make 3.81's ordinary -c interface, without .SHELLFLAGS.
SHELL := "$(CURDIR)/scripts/flint-shell.sh"
# Keep historical Make aliases (including archived verification harnesses).
ifeq ($(origin JAVA_HOME),undefined)
JAVA_HOME := $(shell printf '%s' "$$JAVA_HOME")
unexport JAVA_HOME
endif
ifeq ($(origin XCODE_DEV),undefined)
XCODE_DEV := $(shell printf '%s' "$$DEVELOPER_DIR")
unexport XCODE_DEV
endif
export ANDROID_HOME ANDROID_SDK_ROOT DEVELOPER_DIR
ifeq ($(origin XCODE_DEV),command line)
export DEVELOPER_DIR := $(XCODE_DEV)
endif

.DEFAULT_GOAL := help

help: ## List available targets
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}'

env: ## Show resolved tool paths, their sources, and missing tools
	@bash scripts/flint-env-report.sh

doctor: env ## Check the toolchain (same diagnostic as make env)

ios-gen: ## Generate ios-app/Flint.xcodeproj from project.yml (XcodeGen)
	cd $(IOS_DIR) && xcodegen generate

ios: ios-gen ## Generate the Xcode project and open it
	open $(IOS_DIR)/Flint.xcodeproj

ios-build: ios-gen ## Compile the iOS app (app + 4 extensions) for the simulator, no signing
	xcodebuild -project $(IOS_DIR)/Flint.xcodeproj -scheme Flint \
		-derivedDataPath "$(IOS_DERIVED_DATA)" \
		-sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
		CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build

# Mirrors the `Unit tests` step in .github/workflows/ios.yml, including the simulator pick.
# An empty DEVICE would reach xcodebuild as `name=` and fail with an opaque destination
# error, so name the real problem instead.
ios-test: ios-gen ## Run the iOS unit tests on the first available iPhone simulator
	@set -e; \
	DEVICE=$$(xcrun simctl list devices available | \
		grep -oE 'iPhone [^(]+' | head -1 | sed 's/ *$$//'); \
	if [ -z "$$DEVICE" ]; then \
		echo "No iPhone simulator available (Xcode > Settings > Platforms)." >&2; \
		exit 1; \
	fi; \
	echo "Using simulator: $$DEVICE"; \
	xcodebuild test -project $(IOS_DIR)/Flint.xcodeproj -scheme Flint \
		-derivedDataPath "$(IOS_DERIVED_DATA)" \
		-destination "platform=iOS Simulator,name=$$DEVICE" CODE_SIGNING_ALLOWED=NO

android: ## Assemble the Android debug APK
	@bash scripts/flint-env-report.sh --check-android
	cd $(ANDROID_DIR) && ./gradlew assembleDebug

android-release: ## Build and verify the account-free, development-signed release APK
	@bash scripts/flint-env-report.sh --check-android
	cd $(ANDROID_DIR) && ./gradlew :app:assembleRelease
	python3 scripts/verify_local_first_apk.py $(ANDROID_DIR)/app/build/outputs/apk/release/app-release.apk

android-install: ## Build + install the debug APK on a connected device/emulator
	@bash scripts/flint-env-report.sh --check-android
	cd $(ANDROID_DIR) && ./gradlew installDebug

# `test` (not `testDebugUnitTest`) — matches CI: the pure-Kotlin modules (blocking-engine,
# core-model) are kotlin.jvm projects whose test task is `test`; the old task name silently
# skipped them, so the engine tests never ran under this target.
android-test: ## Run Android unit tests
	@bash scripts/flint-env-report.sh --check-android
	cd $(ANDROID_DIR) && ./gradlew test

verify-android: android android-release android-test ## Run the Android CI gate locally
	@echo "Android gate passed."

verify-ios: ios-build ios-test ## Run the iOS CI gate locally
	@echo "iOS gate passed."

verify: verify-android verify-ios ## Run both platform gates
	@echo "All gates passed."

gates: ## Run AGENTS.md gates for changed paths vs origin/main (DRY_RUN=1 plans only)
	@DRY_RUN="$(DRY_RUN)" FLINT_MAKE="$(MAKE)" bash scripts/agent/gates.sh

ios-run: ios-gen ## Build, attach, install and launch on IOS_SIM (default: iPhone 17)
	@IOS_DIR="$(IOS_DIR)" IOS_DERIVED_DATA="$(IOS_DERIVED_DATA)" IOS_SIM="$(IOS_SIM)" \
		bash scripts/agent/ios-run.sh

android-run: ## Attach AVD (default: flint-test), build, install and launch debug
	@ANDROID_DIR="$(ANDROID_DIR)" AVD="$(AVD)" FLINT_MAKE="$(MAKE)" bash scripts/agent/android-run.sh

logs-ios: ## Stream Flint process logs from IOS_SIM (does not boot the simulator)
	@IOS_SIM="$(IOS_SIM)" bash scripts/agent/logs-ios.sh

logs-android: ## Dump recent logs for Flint's running Android process on AVD
	@AVD="$(AVD)" bash scripts/agent/logs-android.sh

pr-status: ## Read current-branch PR, checks and recent CI runs (requires gh)
	@bash scripts/agent/pr-status.sh

ancestry: ## Fetch main and report ahead/behind and origin/main ancestry (no remote writes)
	@bash scripts/agent/ancestry.sh

selftest: ## Run the device-free script selftests (no emulator, Gradle, or Xcode needed)
	@bash scripts/flint-env-selftest.sh
	@bash scripts/orca-setup-selftest.sh
	@bash scripts/android-verify-selftest.sh
	@bash scripts/check-release-version-selftest.sh
	@python3 -m unittest discover -s scripts -p 'test_*.py'
	@python3 scripts/agent/selftest.py

# Run this BEFORE you cut the tag, while version mistakes are still a working-tree edit.
release-check: ## Check the tree is consistent with a release tag: make release-check TAG=v0.2.0
	@[ -n "$(TAG)" ] || { echo "usage: make release-check TAG=v0.2.0" >&2; exit 2; }
	@bash scripts/check-release-version.sh "$(TAG)"

clean: ## Remove worktree build outputs/caches; preserve local.properties and shared caches
	@python3 scripts/clean-worktree.py artifacts --derived-data "$(IOS_DERIVED_DATA)"

clean-devices: ## Release only provably owned worktree devices/helpers (DRY_RUN=1 previews)
	@bash scripts/clean-devices.sh $(if $(filter 1,$(DRY_RUN)),--dry-run,)

clean-orphans: ## Preview stale Flint host artifacts/devices; delete only with CONFIRM=1
	@bash scripts/clean-devices.sh --orphans $(if $(filter 1,$(CONFIRM)),--confirm,)

.PHONY: help env doctor ios-gen ios ios-build ios-test android android-release android-install android-test \
	verify verify-ios verify-android selftest release-check clean clean-devices clean-orphans \
	gates ios-run android-run logs-ios logs-android pr-status ancestry
