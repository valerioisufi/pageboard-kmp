import Shared
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

struct SampleDocumentComposeView: UIViewControllerRepresentable {
    let documentId: String
    var onClose: (() -> Void)?

    func makeUIViewController(context: Self.Context) -> UIViewController {
        MainViewControllerKt.SampleDocumentViewController(
            documentId: documentId,
            onClose: onClose ?? {}
        )
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Self.Context) {}
}

struct SampleDocumentView: View {
    let documentId: String
    @Environment(\.dismissWindow) private var dismissWindow

    var body: some View {
        SampleDocumentComposeView(documentId: documentId, onClose: {
            dismissWindow(id: "sample-note", value: documentId)
        })
        .ignoresSafeArea()
        .persistentSystemOverlays(.hidden)
    }
}

struct ContentView: View {
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        ComposeView(onOpenDocumentInNewWindow: { docId in
            openWindow(id: "sample-note", value: docId)
        })
        .ignoresSafeArea()
        .onOpenURL { url in
            if url.scheme == "pageboard", url.host == "document" {
                let docId = url.lastPathComponent
                openWindow(id: "sample-note", value: docId)
            }
        }
    }
}

