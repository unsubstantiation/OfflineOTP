import Foundation
import CoreNFC
import CryptoTokenKit
import YubiKit
import Logging

enum HardwareTransport { case nfc, usbC }

/// One explicit native operation. Main-queue ownership; cancellation and timeout invalidate delivery
/// before closing the native connection. The SDK handles CTAP/OATH; no APDU crypto is implemented here.
final class HardwareNativeOperation {
    // Transport availability is distinct from the connected key's OATH/PRF/UV support.
    static var supportsNFC: Bool { NFCTagReaderSession.readingAvailable }
    static var supportsUSBC: Bool { TKSmartCardSlotManager.default != nil }
    private static let silenceSDK: Void = YubiKit.Logs.configure { _ in SwiftLogNoOpLogHandler() }
    private let supports: (HardwareTransport) -> Bool
    private let connect: (HardwareTransport) async throws -> any SmartCardConnection
    private let timeoutSeconds: TimeInterval
    init(timeoutSeconds: TimeInterval = 30,
         supports: @escaping (HardwareTransport) -> Bool = { transport in
             transport == .nfc ? HardwareNativeOperation.supportsNFC : HardwareNativeOperation.supportsUSBC
         },
         connect: @escaping (HardwareTransport) async throws -> any SmartCardConnection = { transport in
             if transport == .nfc { return try await NFCSmartCardConnection.makeConnection() }
             return try await USBSmartCardConnection.makeConnection()
         }) {
        _ = Self.silenceSDK
        self.supports = supports; self.connect = connect; self.timeoutSeconds = timeoutSeconds
    }
    private var epoch = UUID()
    private var task: Task<Void, Never>?
    private var deadline: DispatchWorkItem?
    private var connection: (any SmartCardConnection)?

    func cancel() {
        epoch = UUID()
        deadline?.cancel(); deadline = nil
        task?.cancel(); task = nil
        let prior = connection; connection = nil
        if let prior = prior { Task { await prior.close(error: nil) } }
    }

    func start<Value>(_ transport: HardwareTransport,
                      operation: @escaping (any SmartCardConnection) async throws -> Value,
                      dispose: @escaping (inout Value) -> Void = { _ in },
                      completion: @escaping (Result<Value, Error>) -> Void) {
        cancel()
        guard supports(transport) else {
            completion(.failure(CocoaError(.featureUnsupported))); return
        }
        let id = epoch
        let timeout = DispatchWorkItem { [weak self] in
            guard let self = self, self.epoch == id else { return }
            self.cancel()
            completion(.failure(CocoaError(.userCancelled)))
        }
        deadline = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + timeoutSeconds, execute: timeout)
        let connect = self.connect
        task = Task { @MainActor [weak self] in
            var opened: (any SmartCardConnection)?
            do {
                let connection = try await connect(transport)
                opened = connection
                try Task.checkCancellation()
                guard let self = self, self.epoch == id else { await connection.close(error: nil); return }
                self.connection = connection
                var result = try await operation(connection)
                defer { dispose(&result) } // Includes results arriving after cancel/timeout/replacement.
                await connection.close(error: nil)
                try Task.checkCancellation()
                guard self.epoch == id else { return }
                self.connection = nil; self.task = nil; self.deadline?.cancel(); self.deadline = nil
                completion(.success(result))
            } catch {
                if let opened = opened { await opened.close(error: nil) }
                guard let self = self, self.epoch == id else { return }
                self.connection = nil; self.task = nil; self.deadline?.cancel(); self.deadline = nil
                completion(.failure(error))
            }
        }
    }
    deinit { deadline?.cancel(); task?.cancel() }
}
