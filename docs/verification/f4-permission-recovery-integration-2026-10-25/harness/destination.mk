# Use the full repository gate on the one parent-authorized guest, without test filtering.
ios-test: ios-gen
	DEVELOPER_DIR="$(XCODE_DEV)" xcodebuild test -project $(IOS_DIR)/Flint.xcodeproj -scheme Flint \
		-destination 'platform=iOS Simulator,id=3B8730F1-B59C-46D1-9ADA-ADC0FE7EA473' \
		-parallel-testing-enabled NO -maximum-concurrent-test-simulator-destinations 1 \
		-resultBundlePath $(F4_GATE_RESULT) CODE_SIGNING_ALLOWED=NO
