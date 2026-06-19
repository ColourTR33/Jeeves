import SwiftUI

struct ContentView: View {
    @EnvironmentObject var appState: AppStateManager

    var body: some View {
        TabView {
            RecordingView()
                .tabItem {
                    Image(systemName: "mic.fill")
                    Text("Record")
                }

            RecordingsListView()
                .tabItem {
                    Image(systemName: "list.bullet")
                    Text("Recordings")
                }

            SettingsView()
                .tabItem {
                    Image(systemName: "gear")
                    Text("Settings")
                }
        }
    }
}
