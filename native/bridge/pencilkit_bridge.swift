// The handwriting recognizer of PencilKit (PKStrokeRecognizer, iOS, iPadOS and macOS 27) behind the
// plain C API of pencilkit_bridge.h. PKStrokeRecognizer is a Swift actor, without Objective-C, so
// Kotlin reaches it only through functions like these.
//
// The samples become the control points of a PKDrawing, a stroke a PKStroke; the recognizer splits
// the drawing into lines itself. The calls of the API wait for the recognition on the thread of the
// caller (a thread of Kotlin, never one of Swift concurrency), [timeout_ms] at most.
//
// Built with -D PAGEBOARD_STROKE_RECOGNIZER only when the SDK has PKStrokeRecognizer (Xcode 27):
// with an older SDK every function reads nothing.
import Foundation
import PencilKit
#if os(macOS)
import AppKit
#else
import UIKit
#endif

@_cdecl("pk_bridge_version")
public func pkBridgeVersion() -> Int32 {
    #if PAGEBOARD_STROKE_RECOGNIZER
    if #available(iOS 27.0, macOS 27.0, visionOS 27.0, *) {
        return Int32(clamping: PKStrokeRecognizer.recognitionVersion)
    }
    #endif
    return 0
}

@_cdecl("pk_bridge_languages")
public func pkBridgeLanguages() -> UnsafeMutablePointer<CChar>? {
    var tags: [String] = []
    #if PAGEBOARD_STROKE_RECOGNIZER
    if #available(iOS 27.0, macOS 27.0, visionOS 27.0, *) {
        tags = PKStrokeRecognizer.supportedLanguages.map { $0.minimalIdentifier }.sorted()
    }
    #endif
    return strdup(tags.joined(separator: ","))
}

@_cdecl("pk_bridge_free")
public func pkBridgeFree(_ string: UnsafeMutablePointer<CChar>?) {
    free(string)
}

@_cdecl("pk_bridge_recognize")
public func pkBridgeRecognize(
    _ language: UnsafePointer<CChar>?,
    _ xy: UnsafePointer<Float>?,
    _ times: UnsafePointer<Double>?,
    _ pressures: UnsafePointer<Float>?,
    _ sizes: UnsafePointer<Int32>?,
    _ strokeCount: Int32,
    _ timeoutMs: Int32,
    _ outText: UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>?,
    _ outSearch: UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>?
) -> Int32 {
    outText?.pointee = nil
    outSearch?.pointee = nil
    #if PAGEBOARD_STROKE_RECOGNIZER
    if #available(iOS 27.0, macOS 27.0, visionOS 27.0, *) {
        guard let language, let xy, let times, let pressures, let sizes, strokeCount > 0 else { return 0 }
        let drawing = makeDrawing(xy: xy, times: times, pressures: pressures, sizes: sizes, strokeCount: Int(strokeCount))
        let tag = String(cString: language)
        guard let reading = Recognition.read(drawing, language: tag, timeoutMs: Int(timeoutMs)) else { return 0 }
        outText?.pointee = strdup(reading.text)
        outSearch?.pointee = strdup(reading.search)
        return 1
    }
    #endif
    return 0
}

@_cdecl("pk_bridge_available")
public func pkBridgeAvailable(_ timeoutMs: Int32) -> Int32 {
    #if PAGEBOARD_STROKE_RECOGNIZER
    if #available(iOS 27.0, macOS 27.0, visionOS 27.0, *) {
        let (xy, times, sizes) = hello()
        let pressures = [Float](repeating: 0.5, count: times.count)
        let drawing = makeDrawing(xy: xy, times: times, pressures: pressures, sizes: sizes, strokeCount: sizes.count)
        return Recognition.read(drawing, language: "en", timeoutMs: Int(timeoutMs)) == nil ? 0 : 1
    }
    #endif
    return 0
}

/// HELLO in block capitals 50 points high, a stroke a line: the ink of pk_bridge_available.
private func hello() -> (xy: [Float], times: [Double], sizes: [Int32]) {
    let strokes: [[(Float, Float)]] = [
        [(0, 0), (0, 1)], [(0, 0.5), (0.6, 0.5)], [(0.6, 0), (0.6, 1)],
        [(0.6, 0), (0, 0), (0, 1), (0.6, 1)], [(0, 0.5), (0.5, 0.5)],
        [(0, 0), (0, 1), (0.6, 1)],
        [(0, 0), (0, 1), (0.6, 1)],
        [(0.3, 0), (0, 0.3), (0, 0.7), (0.3, 1), (0.6, 0.7), (0.6, 0.3), (0.3, 0)]
    ]
    let letterOf = [0, 0, 0, 1, 1, 2, 3, 4]
    var xy: [Float] = []
    var times: [Double] = []
    var sizes: [Int32] = []
    var clock = 0.0
    for (s, stroke) in strokes.enumerated() {
        let left = Float(letterOf[s]) * 0.9
        // Ten samples a segment, then the end of the last one
        var points: [(Float, Float)] = []
        for k in 0..<(stroke.count - 1) {
            let (a, b) = (stroke[k], stroke[k + 1])
            for i in 0..<10 {
                let f = Float(i) / 10
                points.append((a.0 + (b.0 - a.0) * f, a.1 + (b.1 - a.1) * f))
            }
        }
        points.append(stroke[stroke.count - 1])
        for (x, y) in points {
            xy.append(50 + (left + x) * 50)
            xy.append(50 + y * 50)
            times.append(clock)
            clock += 12
        }
        sizes.append(Int32(points.count))
        clock += 200
    }
    return (xy, times, sizes)
}

/// The strokes of the samples, in black pen: the recognizer reads their shape and times only. Each
/// path has an identity of its own, which the recognizer keys its strokes by.
@available(iOS 27.0, macOS 27.0, visionOS 27.0, *)
private func makeDrawing(
    xy: UnsafePointer<Float>,
    times: UnsafePointer<Double>,
    pressures: UnsafePointer<Float>,
    sizes: UnsafePointer<Int32>,
    strokeCount: Int
) -> PKDrawing {
    #if os(macOS)
    let ink = PKInk(.pen, color: NSColor.black)
    #else
    let ink = PKInk(.pen, color: UIColor.black)
    #endif
    let origin = Date(timeIntervalSinceReferenceDate: 0)
    var strokes: [PKStroke] = []
    strokes.reserveCapacity(strokeCount)
    var index = 0
    for s in 0..<strokeCount {
        let count = Int(max(0, sizes[s]))
        guard count > 0 else { continue }
        let start = times[index]
        var points: [PKStrokePoint] = []
        points.reserveCapacity(count)
        for i in index..<(index + count) {
            points.append(PKStrokePoint(
                location: CGPoint(x: CGFloat(xy[i * 2]), y: CGFloat(xy[i * 2 + 1])),
                timeOffset: (times[i] - start) / 1000,
                size: CGSize(width: 2, height: 2),
                opacity: 1,
                force: CGFloat(pressures[i] > 0 ? pressures[i] : 0.5),
                azimuth: 0,
                altitude: .pi / 2
            ))
        }
        index += count
        let path = PKStrokePath(controlPoints: points, creationDate: origin.addingTimeInterval(start / 1000), id: UUID())
        strokes.append(PKStroke(ink: ink, path: path))
    }
    return PKDrawing(strokes: strokes)
}

#if PAGEBOARD_STROKE_RECOGNIZER
/// One recognition waited for by a thread that is not one of Swift concurrency.
@available(iOS 27.0, macOS 27.0, visionOS 27.0, *)
private enum Recognition {
    struct Reading: Sendable {
        let text: String
        let search: String
    }

    /// The result of the task, handed to the waiting thread.
    final class Box: @unchecked Sendable {
        private let lock = NSLock()
        private var value: Reading?

        func set(_ reading: Reading?) {
            lock.lock()
            value = reading
            lock.unlock()
        }

        func get() -> Reading? {
            lock.lock()
            defer { lock.unlock() }
            return value
        }
    }

    static func read(_ drawing: PKDrawing, language: String, timeoutMs: Int) -> Reading? {
        let box = Box()
        let done = DispatchSemaphore(value: 0)
        let task = Task.detached {
            // A recognizer a block: its drawing is the block, nothing else interleaves with it
            let recognizer = PKStrokeRecognizer(preferredLanguages: [Locale.Language(identifier: language)])
            await recognizer.updateDrawing(drawing)
            let text = await recognizer.recognizedText() ?? ""
            let search = await recognizer.indexableContent ?? text
            box.set(text.isEmpty ? nil : Reading(text: text, search: search))
            done.signal()
        }
        if done.wait(timeout: .now() + .milliseconds(max(1, timeoutMs))) == .timedOut {
            task.cancel()
            return nil
        }
        return box.get()
    }
}
#endif
