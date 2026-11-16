# لقطة | Laqta

Laqta is a customized media app maintained in this repository. It is derived from the open-source [NuvioMobile project](https://github.com/NuvioMedia/NuvioMobile); original license notices and contributor history are retained.

## Downloads

Android releases will be published on [GitHub Releases](https://github.com/Amr603241/LaqtaMobile/releases).

## Build from source

```bash
git clone https://github.com/Amr603241/LaqtaMobile.git
cd LaqtaMobile
```

### Android

Android development requires Android Studio and the Android SDK.

```bash
./gradlew :androidApp:assembleFullDebug
```

### iOS

iOS development requires macOS and Xcode.

```bash
env NUVIO_IOS_DISTRIBUTION=full xcodebuild \
  -project iosApp/iosApp.xcodeproj \
  -scheme iosApp \
  -configuration Debug \
  -sdk iphonesimulator \
  -derivedDataPath build/ios-derived-full-simulator \
  CODE_SIGNING_ALLOWED=NO \
  build
```

The shared app is built with Kotlin Multiplatform and Compose Multiplatform.

## License

This project is distributed under the [GNU General Public License v3.0](./LICENSE). Preserve the license and third-party notices when redistributing.
