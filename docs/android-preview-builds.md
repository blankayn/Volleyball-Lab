# Android preview builds

Use `./gradlew.bat :app:assemblePreview` for Android sideload previews. The APK is
written to `app/build/outputs/apk/preview/app-preview.apk`.

This variant enables R8 optimization and resource shrinking and is not debuggable.
It uses the existing debug signing certificate and development App Check provider
so an installed development copy can be updated with `adb install -r` while
preserving account and training data. It is a development distribution, not the
Play Store release variant; do not use its signing or attestation setup for a
production release. The `release` variant keeps Play Integrity.

For navigation performance, measure on a physical phone after warming the same
tabs. Reset `adb shell dumpsys gfxinfo com.volleyballlab.player reset`, repeat the
same navigation sequence, then read `adb shell dumpsys gfxinfo
com.volleyballlab.player`. Compare UI frame percentiles and jank counts, not only
animation duration. These statistics include all app frames during the run and
are not a measurement of tap-to-content latency.

On 2026-10-28, three cycles through Home, Train, Progress, Coach and Profile on an
Infinix X6833B produced the following results. Runs used the same signed-in
student account; coach back-stack behavior is covered by the navigation tests.

| Build | 99th-percentile UI frame | Janky frames |
| --- | ---: | ---: |
| 0.1.6 debug, warm baseline | 73 ms | 3.17% |
| Optimized preview, original entrances, warm | 48 ms | 3.22% |
| 0.1.7 preview, single page entrance | 34 ms | 1.26% |

These are device-run observations, not a guarantee for other devices or network
conditions. The attempt to repeat the final run was aborted when the app left
the foreground; that partial run is excluded.

The NavHost owns page entrance motion in both app shells. Card entrance sequences
are suppressed within it to avoid another layout/animation sequence after the
page starts fading in. Interactive expansion and other in-place state animations
remain enabled, and page transitions honor the system reduced-motion setting.
