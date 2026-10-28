# Versioned APKs

Keep `naginatorVersionCode` and `naginatorVersionName` in `gradle.properties`.
Increment the code for every APK distributed to users, including debug updates.
Use `major.minor.patch` for the name; codes must increase even when the name is unchanged.
The initial scheme is code **2**, name **1.1.0**, following the previous code 1 / name 1.0.

Build with `./gradlew.bat assembleDebug`. For an explicitly assigned build:

```
./gradlew.bat assembleDebug -PnaginatorVersionCode=3 -PnaginatorVersionName=1.1.1
```

About Naginator shows the name, code, and build-time abbreviated Git revision.
`-dirty` means tracked source/configuration changes were uncommitted when built;
the revision identifies the parent commit, not the exact uncommitted source.
Git's dirty detection does not include untracked files. Build distributed artifacts
from a committed, clean tree for reproducible identification.
Git is needed for automatic revision capture. Source archives can supply
`-PnaginatorGitRevision=397e7b8b` (or `unknown` if unavailable).

Every APK packaging run automatically creates a companion copy in the same output
directory, named `Naginator-<versionName>-b<versionCode>-<buildType>.apk` using the
actual variant's APK metadata. For example, a debug build produces
`app/build/outputs/apk/debug/Naginator-1.1.0-b2-debug.apk` alongside `app-debug.apk`.
The standard APK and output metadata are preserved. Rebuilding the same version
overwrites its companion copy; increment the build code for each distributed APK.
Version names must use filename-safe letters, digits, dots, underscores, pluses, or hyphens.
Keep the original signing key;
version codes do not make differently signed APKs compatible updates.
Application ID, signing, reminder schemas, and existing reminder storage are unchanged.
