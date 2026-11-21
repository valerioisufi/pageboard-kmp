// Reads every InkML file of a directory with the handwriting recognizer of PencilKit, through the
// bridge of Pageboard (native/bridge/pencilkit_bridge.swift), and writes a line a file: its name, the
// reading, the candidates (the indexable content, its lines split by "|"), the milliseconds. The
// format the bench of pageboard-handwriting scores (HandwritingBench, `score`).
//
// PencilKit reads only for an application in the foreground (handwritingd refuses the others), so
// the bench is an application with a window, built and opened by run.sh:
//
//   tools/handwriting-bench/apple/run.sh macos <directory of InkML> <readings.tsv> [points per mm]
//   tools/handwriting-bench/apple/run.sh simulator <directory of InkML> <readings.tsv> [points per mm]
//
// The language of a file is its annotation "language" (it, en).
import Foundation
#if os(macOS)
import AppKit
#else
import UIKit
#endif

enum Bench {
    static func run(arguments: [String]) {
        let directory = arguments[0]
        let output = arguments[1]
        let pointsPerMm = arguments.count > 2 ? Float(arguments[2]) ?? 5.2 : 5.2
        var lines: [String] = []
        let files = ((try? FileManager.default.contentsOfDirectory(atPath: directory)) ?? [])
            .filter { $0.hasSuffix(".inkml") }
            .sorted()
        for file in files {
            guard let xml = try? String(contentsOfFile: directory + "/" + file, encoding: .utf8) else { continue }
            let language = annotation("language", in: xml) ?? "en"
            var xy: [Float] = []
            var times: [Double] = []
            var pressures: [Float] = []
            var sizes: [Int32] = []
            for trace in traces(in: xml) {
                for sample in trace {
                    xy.append(sample[0] * pointsPerMm)
                    xy.append(sample[1] * pointsPerMm)
                    times.append(sample.count > 2 ? Double(sample[2]) : 0)
                    pressures.append(0.5)
                }
                sizes.append(Int32(trace.count))
            }
            var text: UnsafeMutablePointer<CChar>?
            var search: UnsafeMutablePointer<CChar>?
            let start = DispatchTime.now()
            let read = pk_bridge_recognize(language, xy, times, pressures, sizes, Int32(sizes.count), 10_000, &text, &search)
            let ms = Double(DispatchTime.now().uptimeNanoseconds - start.uptimeNanoseconds) / 1_000_000
            let reading = read == 1 ? String(cString: text!) : ""
            let candidates = read == 1 ? String(cString: search!) : ""
            pk_bridge_free(text)
            pk_bridge_free(search)
            let clean = { (s: String) in s.replacingOccurrences(of: "\t", with: " ") }
            lines.append([
                file,
                clean(reading.replacingOccurrences(of: "\n", with: " ")),
                clean(candidates.split(separator: "\n").joined(separator: "|")),
                String(format: "%.1f", ms)
            ].joined(separator: "\t"))
        }
        let header = "# PencilKit, recognition \(pk_bridge_version()), \(pointsPerMm) points a millimeter\n"
        try? (lines.joined(separator: "\n") + "\n").write(toFile: output, atomically: true, encoding: .utf8)
        try? header.write(toFile: output + ".info", atomically: true, encoding: .utf8)
    }

    static func annotation(_ type: String, in xml: String) -> String? {
        guard let open = xml.range(of: "<annotation type=\"\(type)\">"),
              let close = xml.range(of: "</annotation>", range: open.upperBound..<xml.endIndex) else { return nil }
        return String(xml[open.upperBound..<close.lowerBound])
    }

    /// The samples of each trace: x and y in millimeters, the time in milliseconds.
    static func traces(in xml: String) -> [[[Float]]] {
        var result: [[[Float]]] = []
        var rest = xml[...]
        while let open = rest.range(of: "<trace"), let start = rest.range(of: ">", range: open.upperBound..<rest.endIndex),
              let close = rest.range(of: "</trace>", range: start.upperBound..<rest.endIndex) {
            let samples = rest[start.upperBound..<close.lowerBound].split(separator: ",").map { sample in
                sample.split(separator: " ").compactMap { Float($0) }
            }.filter { $0.count >= 2 }
            if !samples.isEmpty { result.append(samples) }
            rest = rest[close.upperBound...]
        }
        return result
    }
}

@main
enum Main {
    static func main() {
        #if os(macOS)
        let app = NSApplication.shared
        app.setActivationPolicy(.regular)
        let window = NSWindow(contentRect: NSRect(x: 200, y: 200, width: 360, height: 80), styleMask: [.titled], backing: .buffered, defer: false)
        window.title = "PencilKit bench"
        DispatchQueue.main.async {
            window.makeKeyAndOrderFront(nil)
            app.activate(ignoringOtherApps: true)
            // The bridge waits on the thread of its caller, never the main one
            Thread.detachNewThread {
                Thread.sleep(forTimeInterval: 1)
                Bench.run(arguments: Array(CommandLine.arguments.dropFirst()))
                DispatchQueue.main.async { app.terminate(nil) }
            }
        }
        app.run()
        #else
        UIApplicationMain(CommandLine.argc, CommandLine.unsafeArgv, nil, NSStringFromClass(AppDelegate.self))
        #endif
    }
}

#if os(iOS)
final class AppDelegate: UIResponder, UIApplicationDelegate {
    func application(_ application: UIApplication, configurationForConnecting connectingSceneSession: UISceneSession, options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(name: nil, sessionRole: connectingSceneSession.role)
        configuration.delegateClass = SceneDelegate.self
        return configuration
    }
}

final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let scene = scene as? UIWindowScene else { return }
        let window = UIWindow(windowScene: scene)
        let controller = UIViewController()
        controller.view.backgroundColor = .white
        window.rootViewController = controller
        window.makeKeyAndVisible()
        self.window = window
        Thread.detachNewThread {
            Thread.sleep(forTimeInterval: 1)
            Bench.run(arguments: Array(CommandLine.arguments.dropFirst()))
            exit(0)
        }
    }
}
#endif
