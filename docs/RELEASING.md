# Release signing

WaveFerry accepts release signing credentials from an ignored root-level
`keystore.properties` file or from environment variables. Never commit the
keystore or its passwords.

Create `keystore.properties` from `keystore.properties.example` and point
`storeFile` at the long-lived upload keystore. Alternatively, set:

- `WAVEFERRY_RELEASE_STORE_FILE`
- `WAVEFERRY_RELEASE_STORE_PASSWORD`
- `WAVEFERRY_RELEASE_KEY_ALIAS`
- `WAVEFERRY_RELEASE_KEY_PASSWORD`

Then build the GitHub APK and bundle:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:bundleRelease
```

When no complete signing configuration is present, `assembleRelease` still
builds an unsigned APK so pull-request CI can validate the release variant.
Back up the upload keystore and passwords separately before publishing the
first Play Store release.

## GitHub update assets

The native updater expects a numeric release tag (`v0.2.4` or `0.2.4`) and an
APK attachment named `WaveFerry-0.2.4-release.apk`. Keep this naming convention
for later versions. The APK must keep the package name and current signing
certificate set and have a strictly greater `versionCode`. Key rotation is not
supported by the in-app downloader's certificate check.

Downloads use Android DownloadManager, remain available after leaving About,
and can be canceled or deleted there. No storage-wide permission is required.
Installing an update still requires the Android unknown-source permission and
system confirmation. If the system service or installer is unavailable, use the
release-page fallback.
