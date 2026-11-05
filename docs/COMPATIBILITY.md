# Compatibility

## Android versions and devices

Anilife targets Android 5.1 and newer (`minSdk 22`). This includes compatible Fire OS 5 devices.
The project maintains separate phone/tablet and TV flavours so touch and remote-first interfaces
can evolve without changing the application ID.

## APK selection

| Device | Preferred APK | Fallback |
| --- | --- | --- |
| 64-bit ARM phone/tablet | `Anilife_arm64-v8a.apk` | `Anilife.apk` |
| 32-bit ARM phone/tablet | `Anilife_armeabi-v7a.apk` | `Anilife.apk` |
| 64-bit ARM TV | `Anilife_tv_arm64-v8a.apk` | `Anilife_tv.apk` |
| 32-bit ARM TV | `Anilife_tv_armeabi-v7a.apk` | `Anilife_tv.apk` |

Universal APKs contain both supported ARM ABIs. The TV APK is intended for Android TV, Google TV,
and Fire TV; use the phone/tablet APK on touch-first devices.

## Compatibility reports

Provider availability can change independently of the application. A provider-specific failure is
not necessarily an Android compatibility failure. Bug reports should identify the device, OS/API
level, selected APK, form factor, and whether the problem affects one provider or the whole app.
