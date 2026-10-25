# Same full repository gate, with isolated output and the one authorized guest.
ios-build: ios-gen
	DEVELOPER_DIR="$(XCODE_DEV)" xcodebuild -project $(IOS_DIR)/Flint.xcodeproj -scheme Flint \
		-sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
		-derivedDataPath $(F6_REVIEW_ROOT)/DerivedData \
		CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build

ios-test: ios-gen
	DEVELOPER_DIR="$(XCODE_DEV)" xcodebuild test -project $(IOS_DIR)/Flint.xcodeproj -scheme Flint \
		-destination 'platform=iOS Simulator,id=3B8730F1-B59C-46D1-9ADA-ADC0FE7EA473' \
		-derivedDataPath $(F6_REVIEW_ROOT)/DerivedData \
		-parallel-testing-enabled NO -maximum-concurrent-test-simulator-destinations 1 \
		-resultBundlePath $(F6_GATE_RESULT) -collect-test-diagnostics never CODE_SIGNING_ALLOWED=NO
