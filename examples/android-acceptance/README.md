# Owned Android acceptance app

This small dependency-free Android project is a reproducible device test target.
It is not the IDE plugin or a security scanner installed on the phone.
The IDE/CLI vet projects on the development machine. This app lets their optional
HTTP audit inspect a real Android runtime through an explicitly selected `adb forward`.

Build with JDK 21, Android SDK 36 and Gradle 8.13:

```sh
gradle -p examples/android-acceptance :app:assembleDebug
python scripts/android-device-acceptance.py \
  --adb /absolute/android-sdk/platform-tools/adb \
  --serial emulator-5554 \
  --apk examples/android-acceptance/app/build/outputs/apk/debug/app-debug.apk \
  --output /absolute/new-evidence-directory \
  --sdk-python /absolute/installed-sdk-venv/bin/python
```

The driver only installs/starts `com.wrapperguard.acceptance`, allocates a loopback
forward, checks the UI hierarchy and headers, audits two fixed URLs with the SDK,
captures a screenshot, removes its forward and stops its app. It never wipes an AVD.
An explicit physical serial can be used instead. Evidence identifies emulator versus
physical hardware and records API, ABI, APK checksum and source commit.

The app listens only on device `127.0.0.1:8765`. `/acceptance/secure` sets security
headers; `/acceptance/weak` deliberately omits them. Both return constant inert HTML.
The fixture never starts an external service, accepts commands, or reads project files.
It is for development acceptance only and must not be shipped as a production backend.
See the Android device GitHub workflow for a clean hosted emulator execution.
