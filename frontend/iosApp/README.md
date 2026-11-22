# Plan B for iOS

The Xcode app loads the shared Kotlin Compose screen from `PlanBKit`. It uses the same Ktor endpoints and labeled demo mode as the other frontend targets.

## Build and run

Use an Apple Silicon Mac with Xcode, an iOS Simulator runtime, and JDK 17 or 21 available to Xcode. Open `PlanB.xcodeproj`, choose the **PlanB** scheme and an iPhone simulator, then press Run. The first build downloads Kotlin/Native dependencies. Xcode runs the Gradle wrapper to create the static framework before it compiles the Swift host.

For a command-line simulator build, run from `frontend/`.

```sh
xcodebuild -project iosApp/PlanB.xcodeproj -scheme PlanB \
  -configuration Debug -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO build
```

## Backend and deployment

- With an empty `API_BASE_URL`, the app starts in labeled demo mode.
- For a simulator against Ktor running on the same Mac, set the scheme's **Run → Arguments → Environment Variables** value `API_BASE_URL` to `http://localhost:8080`.
- For a distributed build, set the target build setting `API_BASE_URL` to the verified backend HTTPS origin, with no API path. It is packaged in the app's Info.plist. Keep provider keys on the backend.
- Physical-device installs and TestFlight require a development team and a provisioned bundle identifier. An App Store submission also needs release assets and metadata.

Windows checks can validate the checked-in project and host files. The simulator and signed application need macOS and Xcode. [Kotlin/Xcode integration](https://kotlinlang.org/docs/multiplatform/multiplatform-direct-integration.html) · [Apple local network transport settings](https://developer.apple.com/documentation/bundleresources/information-property-list/nsapptransportsecurity/nsallowslocalnetworking)
