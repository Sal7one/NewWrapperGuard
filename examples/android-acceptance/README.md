# Owned Android acceptance app

This small dependency-free Android project is a reproducible device test target.
It is not the IDE plugin or a security scanner installed on the phone.
The IDE/CLI vet projects on the development machine. This app lets their optional
HTTP audit inspect a real Android runtime through an explicitly selected `adb forward`.

## Acceptance status

**Android 16/API 36 emulator acceptance passed; human and physical-phone review remain
unperformed.** [Run 39459625818](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39459625818)
passed at head `76312ace4306c906c329e408346a59d2e893f3c7` using PR merge checkout
`67e613e233904d894d05e1c3cc452c2e4a577695`. The SDK reported zero findings for `secure`
and three expected missing-header findings for `weak`. The owned activity/labels and
nonblank rendering checks passed on the first attempt with eight owned UI nodes and no
blocking dialog. The actual unmodified 720×1280 image received a recorded agent visual
review. [Evidence](../../demo/screenshots/platform-0.7/android-device/evidence.json),
[UI assertions](../../demo/screenshots/platform-0.7/android-device/ui-check.json) and the
[accepted screenshot](../../demo/screenshots/platform-0.7/android-device/device.png)
preserve source identities, APK/image hashes and `humanReviewed:false`.

Earlier [ATD black-frame](../../research/diagnostics/2026-10-20/android-atd-black/status.json)
and [blocking-dialog](../../research/diagnostics/2026-10-20/android-a7f3c7f/status.json)
attempts remain rejected diagnostics; they do not count as visual acceptance.
The [device workflow](../../.github/workflows/android-device.yml)
now uses a standard rendered emulator image. The driver requires the owned activity to
be resumed, the expected owned-package title/ready labels, no blocking system dialog
and a valid nonblank activity screenshot. Failed attempts retain `device-ui.xml`,
`ui-check.json` and the rejected image instead of reporting success. Its initial
`visualReview:PENDING_HUMAN_REVIEW` must be followed by a separately recorded image
review, as the accepted evidence demonstrates. Agent inspection does not establish a
human review or physical-device result.

## Reproduce the checks

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
Use the [Android device workflow](../../.github/workflows/android-device.yml) for a clean
hosted emulator execution. Keep its runtime, automated UI/rendering and human visual
review results separate in the handover.
