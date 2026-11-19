import SwiftUI

@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup("Pageboard", id: "library") {
            ContentView()
        }
        WindowGroup("Note", id: "sample-note", for: String.self) { $documentId in
            if let id = documentId {
                SampleDocumentView(documentId: id)
            } else {
                ContentView()
            }
        }
    }
}

