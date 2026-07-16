# Flint — Peak Focus · convenience targets for a two-native-app monorepo.
# The two apps use independent toolchains (Xcode/SwiftPM and Gradle); this is thin glue.

IOS_DIR     := ios-app
ANDROID_DIR := android-app
# Homebrew keg-only JDK 21 (see `make doctor`). Override if yours lives elsewhere.
JAVA_HOME   ?= /opt/homebrew/opt/openjdk@21
# Auto-detect a full Xcode. `xcodes` installs versioned apps (e.g. Xcode-26.5.0.app); the
# generic /Applications/Xcode.app may not exist. Override XCODE_DEV if needed.
XCODE_APP   := $(shell ls -d /Applications/Xcode*.app 2>/dev/null | sort | tail -1)
XCODE_DEV   ?= $(XCODE_APP)/Contents/Developer

.DEFAULT_GOAL := help

help: ## List available targets
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}'

doctor: ## Check the toolchain (Xcode, XcodeGen, Android SDK, JDK)
	@echo "== Flint toolchain =="
	@printf "%-14s " "Xcode:";      DEVELOPER_DIR="$(XCODE_DEV)" xcodebuild -version 2>/dev/null | head -1 || echo "NOT INSTALLED (App Store / xcodes)"
	@printf "%-14s " "XcodeGen:";   xcodegen --version 2>/dev/null   || echo "NOT INSTALLED (brew install xcodegen)"
	@printf "%-14s " "Swift:";      swift --version 2>/dev/null | head -1 || echo "missing"
	@printf "%-14s " "JDK:";        "$(JAVA_HOME)/bin/java" -version 2>&1 | head -1 || echo "NOT FOUND at JAVA_HOME=$(JAVA_HOME)"
	@printf "%-14s " "Android SDK:"; [ -n "$$ANDROID_HOME" ] && echo "$$ANDROID_HOME" || ([ -d "$$HOME/Library/Android/sdk" ] && echo "$$HOME/Library/Android/sdk" || echo "NOT FOUND (set ANDROID_HOME)")
	@printf "%-14s " "adb:";        adb --version 2>/dev/null | head -1 || echo "not on PATH"

ios-gen: ## Generate ios-app/Flint.xcodeproj from project.yml (XcodeGen)
	cd $(IOS_DIR) && xcodegen generate

ios: ios-gen ## Generate the Xcode project and open it
	open $(IOS_DIR)/Flint.xcodeproj

ios-build: ios-gen ## Compile the iOS app (app + 4 extensions) for the simulator, no signing
	DEVELOPER_DIR="$(XCODE_DEV)" xcodebuild -project $(IOS_DIR)/Flint.xcodeproj -scheme Flint \
		-sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
		CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build

# Mirrors the `Unit tests` step in .github/workflows/ios.yml, including the simulator pick.
# An empty DEVICE would reach xcodebuild as `name=` and fail with an opaque destination
# error, so name the real problem instead.
ios-test: ios-gen ## Run the iOS unit tests on the first available iPhone simulator
	@set -e; \
	DEVICE=$$(DEVELOPER_DIR="$(XCODE_DEV)" xcrun simctl list devices available | \
		grep -oE 'iPhone [^(]+' | head -1 | sed 's/ *$$//'); \
	if [ -z "$$DEVICE" ]; then \
		echo "No iPhone simulator available (Xcode > Settings > Platforms)." >&2; \
		exit 1; \
	fi; \
	echo "Using simulator: $$DEVICE"; \
	DEVELOPER_DIR="$(XCODE_DEV)" xcodebuild test -project $(IOS_DIR)/Flint.xcodeproj -scheme Flint \
		-destination "platform=iOS Simulator,name=$$DEVICE" CODE_SIGNING_ALLOWED=NO

android: ## Assemble the Android debug APK
	cd $(ANDROID_DIR) && JAVA_HOME="$(JAVA_HOME)" ./gradlew assembleDebug

android-install: ## Build + install the debug APK on a connected device/emulator
	cd $(ANDROID_DIR) && JAVA_HOME="$(JAVA_HOME)" ./gradlew installDebug

# `test` (not `testDebugUnitTest`) — matches CI: the pure-Kotlin modules (blocking-engine,
# core-model) are kotlin.jvm projects whose test task is `test`; the old task name silently
# skipped them, so the engine tests never ran under this target.
android-test: ## Run Android unit tests
	cd $(ANDROID_DIR) && JAVA_HOME="$(JAVA_HOME)" ./gradlew test

verify-android: android android-test ## Run the Android CI gate locally
	@echo "Android gate passed."

verify-ios: ios-build ios-test ## Run the iOS CI gate locally
	@echo "iOS gate passed."

verify: verify-android verify-ios ## Run both platform gates
	@echo "All gates passed."

selftest: ## Run the device-free script selftests (no emulator, Gradle, or Xcode needed)
	@bash scripts/android-verify-selftest.sh
	@bash scripts/check-release-version-selftest.sh

# Run this BEFORE you cut the tag, while version mistakes are still a working-tree edit.
release-check: ## Check the tree is consistent with a release tag: make release-check TAG=v0.2.0
	@[ -n "$(TAG)" ] || { echo "usage: make release-check TAG=v0.2.0" >&2; exit 2; }
	@bash scripts/check-release-version.sh "$(TAG)"

clean: ## Remove build artifacts on both platforms
	rm -rf $(IOS_DIR)/build $(IOS_DIR)/DerivedData $(IOS_DIR)/Flint.xcodeproj
	cd $(ANDROID_DIR) && [ -x ./gradlew ] && JAVA_HOME="$(JAVA_HOME)" ./gradlew clean || true

.PHONY: help doctor ios-gen ios ios-build ios-test android android-install android-test \
	verify verify-ios verify-android selftest release-check clean
