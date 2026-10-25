# Same repository test gate, pinned to the parent-authorized temporary simulator.
# No test filtering. Disable parallel simulator clones to honor the one-guest constraint.
ios-test: ios-gen
	DEVELOPER_DIR="$(XCODE_DEV)" xcodebuild test -project $(IOS_DIR)/Flint.xcodeproj -scheme Flint \
		-destination 'platform=iOS Simulator,id=3B8730F1-B59C-46D1-9ADA-ADC0FE7EA473' \
		-parallel-testing-enabled NO -maximum-concurrent-test-simulator-destinations 1 \
		-resultBundlePath $(F5_GATE_RESULT) CODE_SIGNING_ALLOWED=NO
