# NFC Writer (Android)

This repository contains the Android NFC Writer app source, Gradle build files, and tests. It does not contain the TapTag website, Supabase database migrations, credentials, signing keys, or built APKs.

## Build

1. Install Android Studio and Android SDK 36.
2. Copy `local.properties.example` to `local.properties` and set `sdk.dir` to your local Android SDK path.
3. For a configured app, set `SUPABASE_URL` and the **public** `SUPABASE_ANON_KEY` for a compatible backend. Never put a service-role key or private access key in this file or the repository.
4. Run `gradlew.bat :app:assembleDebug` on Windows, or `./gradlew :app:assembleDebug` on macOS/Linux.

Without a configured backend, the app shows its setup-required screen. Administrative access requires a valid server-side TapTag access key. Publishing this source does not make the code exclusive: anyone can read or build a public repository, while access to a particular backend remains controlled by that backend.
