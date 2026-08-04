# Jeeves Installer Packaging

This directory contains scripts and resources for building platform-specific installers.

## Quick Start

### Windows (MSI/EXE)

```batch
cd packaging
build-windows-installer.bat
```

Or build specific formats:
```batch
build-windows-installer.bat msi
build-windows-installer.bat exe
```

**Prerequisites:**
- JDK 17+ (with jpackage)
- [WiX Toolset 3.x](https://wixtoolset.org/releases/) for MSI creation
- Add WiX `bin` folder to PATH

### macOS (DMG)

```bash
cd packaging
chmod +x build-macos-installer.sh
./build-macos-installer.sh
```

With code signing:
```bash
./build-macos-installer.sh --sign "Developer ID Application: Your Name"
```

**Prerequisites:**
- JDK 17+ (with jpackage)
- Xcode Command Line Tools
- (Optional) Apple Developer certificate for code signing

### iOS (App Store / TestFlight)

See `iosApp/README.md` for Xcode build and distribution instructions.

## Output Locations

All installers are generated in:
```
desktopApp/build/compose/binaries/main/
```

| Format | File |
|--------|------|
| Windows MSI | `Jeeves-1.2.0.msi` |
| Windows EXE | `Jeeves-1.2.0.exe` |
| macOS DMG | `Jeeves-1.2.0.dmg` |

## Configuration

Installer settings are configured in `desktopApp/build.gradle.kts`:

```kotlin
compose.desktop {
    application {
        nativeDistributions {
            packageName = "Jeeves"
            packageVersion = "1.2.0"
            // ... platform-specific settings
        }
    }
}
```

## Versioning

Update the version in:
1. `desktopApp/build.gradle.kts` → `packageVersion`
2. `shared/.../domain/Models.kt` → `APP_VERSION` constant (if added)
3. `iosApp/Info.plist` → `CFBundleShortVersionString`

## Code Signing

### Windows
For SmartScreen approval, sign with an EV Code Signing certificate:
```batch
signtool sign /tr http://timestamp.digicert.com /td SHA256 /fd SHA256 /a Jeeves-1.2.0.msi
```

### macOS
1. Sign with Developer ID certificate during build (see above)
2. Notarize with Apple:
```bash
xcrun notarytool submit Jeeves-1.2.0.dmg --apple-id "your@email.com" --team-id "TEAMID" --password "app-specific-password"
xcrun stapler staple Jeeves-1.2.0.dmg
```

## Troubleshooting

### "jpackage not found"
Ensure JDK 17+ is installed and `JAVA_HOME/bin` is in PATH.

### "candle.exe not found" (Windows MSI)
Install WiX Toolset and add its `bin` folder to PATH.

### "App damaged" on macOS
The app is unsigned. Either sign it or right-click → Open to bypass Gatekeeper on first launch.

### Build fails with "module not found"
The Compose plugin includes all JVM modules by default (`includeAllModules = true`). If issues persist, check `desktopApp/build.gradle.kts`.
