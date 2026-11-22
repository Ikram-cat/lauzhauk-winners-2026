# ALT for iOS (Compose Multiplatform)

The iOS app is the shared Compose UI of `frontend/kmp/composeApp`. This folder holds only:
- a small SwiftUI host (`iosApp/iOSApp.swift`) that shows `MainViewController()`;
- the scripted demo UI test (`iosAppUITests/DemoScenarioUITests.swift`).

**Kotlin side.** `composeApp` declares `iosArm64` and `iosSimulatorArm64` with a static framework `ComposeApp`. The iOS actuals are in `composeApp/src/iosMain`:
- `ALT_API_BASE` comes from the launch environment, else the deployed backend;
- times use Europe/Zurich via kotlinx-datetime;
- "reduce motion" follows the system setting;
- persistent values go to `NSUserDefaults`.

**Xcode project.** It is generated from `project.yml` with [xcodegen](https://github.com/yonaskolb/XcodeGen) and not committed. Its pre-build phase runs `./gradlew :composeApp:embedAndSignAppleFrameworkForXcode`, which also copies the Compose resources.

## Without a Mac: demo video in GitHub Actions

Workflow `.github/workflows/ios-demo-video.yml` (manual, macOS runner). It:
1. builds the app and the UI tests for the simulator;
2. boots an iPhone simulator;
3. starts `xcrun simctl io booted recordVideo`;
4. runs `DemoScenarioUITests` (Lausanne → Genève, results, scroll through disruption status, recommendation and alternatives);
5. stops the recording and uploads `ios-demo.mp4`.

## With a Mac

```sh
brew install xcodegen
cd frontend/kmp/iosApp && xcodegen generate && open iosApp.xcodeproj   # run the iosApp scheme on a simulator
```
