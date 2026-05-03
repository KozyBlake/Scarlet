# scarlet-android

Android APK that mirrors VRChat's own `output_log_*.txt` files off the
device so Scarlet's existing log tailer can read them. The APK runs an
embedded ADB client (`libadb-android`) that talks to the phone's own
`adbd` over the loopback Wireless Debugging socket.

The app does **not** need root, and **no PC is required at any point**.
It works on any Android 11+ device that can run VRChat mobile.

## Why Wireless Debugging instead of `adb tcpip 5555`

Earlier iterations of this app used the classic ADB transport that `adb
tcpip 5555` exposes. That worked, but it required plugging the phone
into a PC once per reboot to re-issue the `tcpip` command, because the
classic-TCP listener does not survive a power cycle.

`libadb-android` (3.1.1) implements the modern Wireless Debugging
protocol - ADB-over-TLS plus the SPAKE2 pairing handshake - which lives
entirely on the phone. Once you've paired Scarlet with the on-device
adbd one time, the cert is persisted in adbd's `adb_pairing_keys` and
every subsequent connect happens silently.

## Build

```bash
cd scarlet-android
ANDROID_HOME=/path/to/Android/Sdk mvn package
```

Requires an SDK install containing:

- `platforms/android-34`
- `build-tools/34.0.0`
- `platform-tools`

(Older build-tools work for compiling but `30.0.3`/`31.0.0` ship a d8
that predates multi-release JAR support and crashes on BouncyCastle's
`META-INF/versions/11/` overlays. `33.0.2` also works; `34.0.0` is the
recommended floor.)

The build pulls these libraries (all pre-resolved through the JitPack +
Google + Maven Central repos declared in the pom):

- `com.github.MuntashirAkon:libadb-android:3.1.1` - the ADB client itself
- `org.bouncycastle:bcprov-jdk15to18:1.81` - TLS layer for adb-over-TLS
- `com.github.MuntashirAkon.spake2-java:spake2-android:2.2.1` - SPAKE2
  pairing (ships native `libspake2.so` for arm64-v8a, armeabi-v7a, x86,
  x86_64)

Important: `android-maven-plugin 4.6.0` still expects the legacy SDK
metadata file `tools/source.properties`. Modern SDK installs often omit
the old `tools/` package entirely, so you may need either:

- an SDK installation that still contains `tools/source.properties`, or
- a compatibility shim directory at `<sdk>/tools/source.properties`

This module is configured to use `d8` for dexing with `--min-api 24`,
which forces lambda desugaring down to anonymous inner classes (so
nothing in the dex references `java.lang.invoke.LambdaMetafactory` and
the app runs identically on every Android 11+ device including stock
Samsung firmware that locks down the hidden API).

You can also override the SDK path explicitly with:

```bash
mvn package -Dandroid.sdk.path=/path/to/Android/Sdk
```

Output: `target/scarlet-android-<ver>.apk` (debug-signed).

Google's Windows distribution of `lib/d8.jar` is missing its
`Main-Class` manifest entry across every build-tools version we've
tested (30.0.3 through 34.0.0). The d8.bat launcher works around it
with `-classpath` + explicit main, but android-maven-plugin invokes
`java -jar d8.jar` and bombs with "no main manifest attribute". One-
time fix per build-tools dir:

```powershell
& "$env:JAVA_HOME\bin\jar.exe" ufe D:\androidsfk\build-tools\<ver>\lib\d8.jar com.android.tools.r8.D8
```

Also: android-maven-plugin's SDK validator hard-requires the legacy
`dx` files even though we use d8. Build-tools 31.0.0+ removed them, so
copy from an older install (e.g. 30.0.3) into the newer dir:

```powershell
Copy-Item D:\androidsfk\build-tools\30.0.3\dx.bat     D:\androidsfk\build-tools\<ver>\
Copy-Item D:\androidsfk\build-tools\30.0.3\lib\dx.jar D:\androidsfk\build-tools\<ver>\lib\
```

These shims are never executed (we configure `<dexCompiler>d8</dexCompiler>`),
they just satisfy the validator's "is this a real build-tools install?"
check.

After `mvn package` finishes, run `scripts/repair_apk.py` against the
output APK to ensure `resources.arsc` is uncompressed and 4-byte
aligned (required by Android API 30+) and to re-sign the result. The
installable file is `target/scarlet-android-<ver>-signed.apk`.

To release-sign instead of debug-sign, flip
`<sign><debug>false</debug></sign>` in the pom and pass
`-Dandroid.sign.storepass=... -Dandroid.sign.keypass=...
-Dandroid.sign.alias=...`.

## Install

```bash
adb install target/scarlet-android-<ver>-signed.apk
```

Or sideload the APK the normal way.

## First-run setup

Everything happens on the phone. There is no PC step.

1. In **Settings -> About phone**, tap **Build number** seven times to
   unlock Developer options.
2. In **Developer options**, turn **Wireless debugging** on. (Leave it
   on; it survives reboots on most Android skins.)
3. Open Scarlet. The pair screen shows a 6-digit code field and a
   **Pair** button.
4. On the phone, in **Developer options -> Wireless debugging**, tap
   **Pair device with pairing code**. The phone shows a 6-digit code.
5. Type that code into Scarlet and tap **Pair**.
6. Scarlet runs the SPAKE2 handshake against the loopback adbd, the
   phone persists Scarlet's certificate, and the pair UI is replaced by
   a **Connect** button.
7. Tap **Connect**. Scarlet opens a TLS session to adbd, starts the
   foreground service, and begins tailing VRChat's logs.

From launch #2 onward the pair UI is gone for good - you only ever see
the Connect button. If Wireless Debugging gets turned off (some OEMs do
this on reboot), just toggle it back on and tap Connect again.

If you ever revoke the pairing on the phone (Wireless debugging ->
forget device) or want to start over, tap **Reset pairing** on Scarlet.
That wipes the on-disk keypair + cert, drops the "paired" flag, and
brings the pair UI back.

The persistent notification shows the current status. Tap it to open
the app and see the most-recent log lines.

## Integrating with Scarlet core

The service mirrors each `output_log_<ts>.txt` file that VRChat writes
into one of:

- the path pointed at by `-Dscarlet.vrcAppData.dir=...` (or the
  `SCARLET_VRC_APPDATA_DIR` environment variable), **or**
- `/data/data/net.sybyline.scarlet.android/files/vrchat` if no override
  is set.

To hook the Scarlet core up, run it with the same `scarlet.vrcAppData.dir`
system property. `VrcAppData.resolve(...)` honors the override.

## Architecture

```text
  VRChat Android app
         |
         v
  /storage/emulated/0/Android/data/com.vrchat.mobile.playstore/files/output_log_*.txt
         |
         | (shell:tail -F)  via ADB-over-TLS on 127.0.0.1:<Wireless Debugging port>
         v
  libadb-android (in-app, TLS + SPAKE2 pairing)
         |
         v
  ScarletLogService  --->  mirror directory
         |
         v
  desktop Scarlet's existing VrcAppData tailer
```
