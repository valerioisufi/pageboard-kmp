import PencilNotesShared
import SwiftUI
import UIKit

struct ComposeView: UIViewControllerRepresentable {
    var onOpenDocumentInNewWindow: ((String) -> Void)?

    func makeUIViewController(context: Self.Context) -> UIViewController {
        MainViewControllerKt.MainViewController(
            onOpenDocumentInNewWindow: onOpenDocumentInNewWindow
        )
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Self.Context) {}
}

struct NoteDocumentComposeView: UIViewControllerRepresentable {
    let documentId: String
    var onClose: (() -> Void)?

    func makeUIViewController(context: Self.Context) -> UIViewController {
        MainViewControllerKt.NoteDocumentViewController(
            documentId: documentId,
            onClose: onClose ?? {}
        )
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Self.Context) {}
}

struct NoteDocumentView: View {
    let documentId: String
    @Environment(\.dismissWindow) private var dismissWindow

    var body: some View {
        NoteDocumentComposeView(documentId: documentId, onClose: {
            dismissWindow(id: "note", value: documentId)
        })
        .ignoresSafeArea()
        .persistentSystemOverlays(.hidden)
    }
}

struct ContentView: View {
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        ComposeView(onOpenDocumentInNewWindow: { docId in
            openWindow(id: "note", value: docId)
        })
        .ignoresSafeArea()
        // Invitations to the shared documents (drawview://join links) and document links
        .onOpenURL { url in
            if url.scheme == "drawview", url.host == "document" {
                let docId = url.lastPathComponent
                openWindow(id: "note", value: docId)
            } else {
                PencilNotesAppKt.openInvitation(link: url.absoluteString)
            }
        }
    }
}

