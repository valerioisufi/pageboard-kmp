import SwiftUI

@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup("PencilNotes", id: "library") {
            ContentView()
        }
        WindowGroup("Note", id: "note", for: String.self) { $documentId in
            if let id = documentId {
                NoteDocumentView(documentId: id)
            } else {
                ContentView()
            }
        }
    }
}

