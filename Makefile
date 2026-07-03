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

android: ## Assemble the Android debug APK
	cd $(ANDROID_DIR) && JAVA_HOME="$(JAVA_HOME)" ./gradlew assembleDebug

android-install: ## Build + install the debug APK on a connected device/emulator
	cd $(ANDROID_DIR) && JAVA_HOME="$(JAVA_HOME)" ./gradlew installDebug

android-test: ## Run Android unit tests
	cd $(ANDROID_DIR) && JAVA_HOME="$(JAVA_HOME)" ./gradlew testDebugUnitTest

clean: ## Remove build artifacts on both platforms
	rm -rf $(IOS_DIR)/build $(IOS_DIR)/DerivedData $(IOS_DIR)/Flint.xcodeproj
	cd $(ANDROID_DIR) && [ -x ./gradlew ] && JAVA_HOME="$(JAVA_HOME)" ./gradlew clean || true

.PHONY: help doctor ios-gen ios ios-build android android-install android-test clean
