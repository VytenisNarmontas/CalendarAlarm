# Third-party notices

The root MIT license applies to the author's project code and documentation. It does not replace the licenses of the tools, libraries or adapted artwork below.

## Gradle wrapper

`gradlew.bat` and `gradle/wrapper/gradle-wrapper.jar` are Gradle wrapper components from the Gradle project, licensed under Apache License 2.0. Existing notices in the script and JAR are retained.

- [Gradle project](https://github.com/gradle/gradle)
- [Apache License 2.0](LICENSES/Apache-2.0.txt)

## Alarm icon

`app/src/main/res/drawable/ic_alarm.xml` uses modified Material Design alarm-icon geometry: the bell and hand shapes correspond to Google's `access_alarm` icon, while the clock dial differs. Credit is retained here for the underlying Material Design artwork by Google, distributed under Apache License 2.0.

- [Upstream Material Design icon](https://github.com/google/material-design-icons/blob/master/src/device/access_alarm/materialicons/24px.svg)
- [Upstream license](https://github.com/google/material-design-icons/blob/master/LICENSE)
- [Included Apache License 2.0](LICENSES/Apache-2.0.txt)

## Build and runtime libraries

Dependencies are declared in the Gradle build files and downloaded separately. They include Kotlin, kotlinx.coroutines, AndroidX/Jetpack Compose, WorkManager, DataStore, JUnit and Espresso. Their own licenses and notices continue to apply. This source repository does not bundle their compiled libraries, other than the Gradle wrapper noted above.

No compiled app is distributed with this portfolio update. A future APK release should include the required dependency notices. The current build configuration excludes generic `META-INF/AL2.0` and `META-INF/LGPL2.1` resources from the APK; the root MIT license alone is not a substitute for dependency notices.

## Demo screenshots

The images in `media/` are authentic screenshots of this app running in an isolated Android emulator with fictional calendar data. They include Android system UI, which belongs to its respective owners. They are provided to demonstrate the app; no rights to third-party UI, names or trademarks are granted by the project's MIT license. Small versions are resized previews of the original captures.
