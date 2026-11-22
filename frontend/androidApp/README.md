# Android

This host opens the shared Kotlin Compose Plan B application on Android 8 or newer.

Install Android SDK Platform 35, Build Tools 35.0.0, and a JDK 17, then configure `ANDROID_HOME` or the standard `sdk.dir` entry in `frontend/local.properties`. From `frontend/`:

```bash
./gradlew -PenableAndroid=true :androidApp:assembleDebug
```

The APK is `androidApp/build/outputs/apk/debug/androidApp-debug.apk`. With an emulator or device attached, use `./gradlew -PenableAndroid=true :androidApp:installDebug` to install it. Use `gradlew.bat` on Windows.

Without a configured API origin, the app uses labeled demo data. To connect to Ktor on your computer from the Android Studio emulator, run:

```bash
./gradlew -PenableAndroid=true :androidApp:installDebug -PapiBaseUrl=http://10.0.2.2:8080
```

For a release, set `API_BASE_URL` or `-PapiBaseUrl` to the deployed public HTTPS origin before building. The Gradle property takes precedence. The URL is bundled in the APK. Keep secrets on the backend. Release signing uses the deployment owner's signing configuration.
