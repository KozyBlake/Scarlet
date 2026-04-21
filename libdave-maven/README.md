# libdave-jvm for Android (Maven Build)

This is a Maven-compatible version of Discord's DAVE (Discord Audio & Video End-to-End Encryption) library for Android.

## Project Structure

```
libdave-maven/
├── pom.xml                    # Parent POM
├── api/                       # Core API interfaces
│   ├── pom.xml
│   └── src/main/java/
├── impl-jni/                  # JNI implementation
│   ├── pom.xml
│   └── src/main/java/
├── adapter-jda/               # JDA adapter (optional)
│   ├── pom.xml
│   └── src/main/java/
└── natives-android/           # Native Android libraries
    ├── pom.xml
    └── src/main/resources/native/android/
        ├── arm64-v8a/libdave-jvm.so
        ├── armeabi-v7a/libdave-jvm.so
        ├── x86/libdave-jvm.so
        └── x86_64/libdave-jvm.so
```

## Supported Architectures

- **arm64-v8a**: ARM 64-bit devices (most modern Android devices)
- **armeabi-v7a**: ARM 32-bit devices (older Android devices)
- **x86**: x86 32-bit (Android emulator)
- **x86_64**: x86 64-bit (Android emulator)

## Usage

### 1. Install to Local Maven Repository

```bash
cd libdave-maven
mvn clean install
```

### 2. Add to Your Project's pom.xml

```xml
<dependency>
    <groupId>moe.kyokobot.libdave</groupId>
    <artifactId>api</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>moe.kyokobot.libdave</groupId>
    <artifactId>impl-jni</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>moe.kyokobot.libdave</groupId>
    <artifactId>natives-android</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 3. Load Native Library in Your Android App

```java
static {
    System.loadLibrary("dave-jvm");
}
```

### 4. Initialize DAVE

```java
import moe.kyokobot.libdave.DaveNativeBindings;

// The library will automatically load the correct native for your architecture
DaveNativeBindings bindings = new DaveNativeBindings();
```

## Native Library Loading

The native libraries are packaged inside the `natives-android` JAR under:
`/native/android/{arch}/libdave-jvm.so`

Your application needs to extract and load the appropriate native library for the device's architecture.

## Building from Source

### Prerequisites

- Android NDK r26d or later
- CMake 3.26+
- Ninja build system
- JDK 17+

### Build Steps

```bash
# Set ANDROID_NDK environment variable
export ANDROID_NDK=/path/to/android-ndk

# Build all architectures
cd libdave-jvm-master/natives
./build-android.sh

# Build Maven project
cd ../../libdave-maven
mvn clean install
```

## Features

- End-to-end encryption for audio/video streams
- MLS (Messaging Layer Security) protocol support
- Multiple cipher suites support
- Key ratchet mechanism for forward secrecy

## Dependencies

- BoringSSL (included)
- MLSpp (included)
- HPKE (included)

## License

This project is based on Discord's libdave-jvm project. Please refer to the original license terms.

## Notes

- This is an unofficial Android port
- The native libraries are statically linked with BoringSSL
- LTO (Link Time Optimization) is enabled for smaller binary size
- Minimum Android API level: 21 (Android 5.0 Lollipop)
