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

Then build the APK and Play Store bundle:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:bundleRelease
```

When no complete signing configuration is present, `assembleRelease` still
builds an unsigned APK so pull-request CI can validate the release variant.
Back up the upload keystore and passwords separately before publishing the
first Play Store release.
