import PencilNotesShared
import SwiftUI
import UIKit

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Self.Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Self.Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea()
            // Invitations to the shared documents (drawview://join links)
            .onOpenURL { url in
                PencilNotesAppKt.openInvitation(link: url.absoluteString)
            }
    }
}
