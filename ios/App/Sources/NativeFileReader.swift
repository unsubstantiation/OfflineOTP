import Foundation
import OfflineOTPKit

/// Worker-only document reads with revocable callbacks and owned, bounded result bytes.
/// A started provider read may finish after cancellation; stale data is never delivered to the UI.
final class NativeFileReader {
    private final class Scope {
        let url: URL
        let granted: Bool
        init(_ url: URL) { self.url = url; granted = url.startAccessingSecurityScopedResource() }
        deinit { if granted { url.stopAccessingSecurityScopedResource() } }
    }
    private final class Work {
        private let lock = NSLock()
        private var cancelled = false
        private var completion: ((Data?) -> Void)?
        private var result: Data?
        private var scope: Scope?
        init(_ scope: Scope, _ completion: @escaping (Data?) -> Void) { self.scope = scope; self.completion = completion }
        func takeScope() -> Scope? {
            lock.lock(); defer { lock.unlock() }
            guard !cancelled else { return nil }
            let value = scope; scope = nil; return value
        }
        func active() -> Bool { lock.lock(); defer { lock.unlock() }; return !cancelled }
        func offer(_ data: Data?) -> Bool {
            lock.lock(); defer { lock.unlock() }
            guard !cancelled else { return false }
            result = data; return true
        }
        func deliver() {
            lock.lock()
            guard !cancelled else { lock.unlock(); return }
            let callback = completion; let data = result
            completion = nil; result = nil; cancelled = true
            lock.unlock()
            callback?(data)
        }
        func cancel() {
            lock.lock(); defer { lock.unlock() }
            cancelled = true; completion = nil; scope = nil
            if var bytes = result { bytes.resetBytes(in: 0..<bytes.count) }
            result = nil
        }
    }
    private static let worker = DispatchQueue(label: "OfflineOTP.file-import", qos: .userInitiated)
    private let enqueue: (@escaping () -> Void) -> Void
    private let dispatch: (@escaping () -> Void) -> Void
    private let read: (URL, Int) throws -> Data
    private var work: Work?
    init(enqueue: ((@escaping () -> Void) -> Void)? = nil,
         dispatch: ((@escaping () -> Void) -> Void)? = nil,
         read: ((URL, Int) throws -> Data)? = nil) {
        self.enqueue = enqueue ?? { Self.worker.async(execute: $0) }
        self.dispatch = dispatch ?? { DispatchQueue.main.async(execute: $0) }
        self.read = read ?? { try BoundedFileReader.read($0, maxBytes: $1) }
    }
    func cancel() { work?.cancel(); work = nil }
    func start(_ url: URL, maxBytes: Int = InputPolicy.maxDocumentBytes, completion: @escaping (Data?) -> Void) {
        cancel()
        guard maxBytes > 0, maxBytes <= InputPolicy.maxDocumentBytes else { completion(nil); return }
        let task = Work(Scope(url), completion); work = task
        let read = self.read; let dispatch = self.dispatch
        enqueue { [weak self] in
            guard let scope = task.takeScope(), task.active() else { return }
            defer { withExtendedLifetime(scope) {} }
            var bytes: Data?
            do {
                let data = try read(url, maxBytes)
                if !data.isEmpty, data.count <= maxBytes { bytes = data }
            } catch { /* No provider diagnostics or filenames cross into presentation. */ }
            guard task.offer(bytes) else {
                if var bytes { bytes.resetBytes(in: 0..<bytes.count) }
                return
            }
            dispatch { [weak self] in
                guard let self, self.work === task else { task.cancel(); return }
                self.work = nil
                task.deliver()
            }
        }
    }
    deinit { work?.cancel() }
}
