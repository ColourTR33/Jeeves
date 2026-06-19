# Xcode Project Setup Guide

Since the `.xcodeproj` file is complex XML that can't be reliably generated outside Xcode,
follow these steps to create the Xcode project:

## Steps

1. Open Xcode and create a new project:
   - Template: iOS > App
   - Product Name: Jeeves
   - Organization Identifier: com.jeeves
   - Interface: SwiftUI
   - Language: Swift
   - Tick "Include Widget Extension" (name it JeevesWidget)

2. Add App Group capability:
   - Select the Jeeves target > Signing & Capabilities
   - Add "App Groups" capability
   - Add group: `group.com.jeeves.app`
   - Do the same for the JeevesWidget target

3. Add Background Modes capability:
   - Select the Jeeves target > Signing & Capabilities
   - Add "Background Modes"
   - Tick "Audio, AirPlay, and Picture in Picture"

4. Replace generated files:
   - Delete the auto-generated Swift files from both targets
   - Drag in the files from `iosApp/Jeeves/` into the Jeeves target
   - Drag in the files from `iosApp/JeevesWidget/` into the JeevesWidget target

5. Add KMP Shared framework (optional - for deeper integration):
   - Build the shared framework: `./gradlew :shared:linkReleaseFrameworkIosArm64`
   - Add the framework to the Xcode project under Frameworks
   - Update the `import Shared` statement in JeevesApp.swift

6. Configure the Widget:
   - Ensure JeevesWidget target has the App Groups capability
   - Set deployment target to iOS 17.0+ (for AppIntents in widgets)

## App Transport Security

For local AI endpoints, you may need to add ATS exceptions in Info.plist
if your Whisper/Ollama servers run on HTTP (not HTTPS).
