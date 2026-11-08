# Release artifact checks

The project has native code, so Debug compilation alone cannot verify the Release
variant or packaged library alignment. CI now builds both variants, runs their JVM
tests and inspects the actual unsigned Release APK. No signing material is used.

```sh
./gradlew assembleRelease bundleRelease testReleaseUnitTest
python3 tools/release/check_apk.py app/build/outputs/apk/release/app-release-unsigned.apk \
  --source-revision "$(git rev-parse HEAD)" --output /path/to/release-inspection.json
"$ANDROID_HOME/build-tools/36.0.0/zipalign" -c -P 16 -v 4 \
  app/build/outputs/apk/release/app-release-unsigned.apk
```

On Windows use `gradlew.bat`, `python` and the corresponding SDK `zipalign.exe`.
The output parent directory must exist. A source revision records the checkout
being inspected; it is not an independent build attestation.

`check_apk.py` checks every packaged `.so`, requiring ELF64 arm64 shared libraries,
valid LOAD segment bounds and at least 16 KiB ELF alignment. It requires the actual
Persona JNI library, rejects duplicate ZIP names and records APK/library SHA-256s.
The separate SDK `zipalign` command checks ZIP alignment. Its CI log remains a
separate receipt; the Python report does not infer that result.

```sh
python3 -B -m unittest discover -s tools/release/tests -v
```

CI retains the unsigned APK and AAB in `release-unsigned-not-for-installation` for
seven days. They are inspection artifacts: they have no distribution signature, no configured service
acceptance and no device execution proof. Do not install it over the existing App
or describe these checks as 16 KiB runtime acceptance. ELF/ZIP inspection here
targets the APK; AAB delivery configuration and generated split APKs need their
own validation when the target store is selected.

Before a distributable candidate, select the audience/package identity, signing
owner, version and backend mode. Signed installation, upgrade/data retention,
model import and actual chat still require the target environment. Existing
Debug/QA and model quality results keep their original scope.

Sources:
- [Android ELF, ZIP alignment and runtime verification](https://developer.android.com/guide/practices/page-sizes)
- [SDK zipalign check command](https://developer.android.com/tools/zipalign)
- [Release builds and signing](https://developer.android.com/build/building-cmdline)
