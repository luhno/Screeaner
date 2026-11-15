import Foundation

/// Sets up `adb reverse` so a tablet plugged in over USB reaches the Mac at
/// 127.0.0.1 — the lowest-latency, lowest-jitter link available.
final class AdbBridge {
    private let queue = DispatchQueue(label: "screeaner.adb", qos: .utility)
    private var busy = false

    static let adbPath: String? = {
        let home = FileManager.default.homeDirectoryForCurrentUser.path
        let candidates = [
            "/opt/homebrew/bin/adb",
            "/usr/local/bin/adb",
            "\(home)/Library/Android/sdk/platform-tools/adb",
        ]
        return candidates.first { FileManager.default.isExecutableFile(atPath: $0) }
    }()

    /// Forwards the port on every attached device. Completion (main queue) gets the device count.
    func refresh(completion: @escaping (Int) -> Void) {
        guard let adb = Self.adbPath else {
            completion(0)
            return
        }
        queue.async {
            guard !self.busy else { return }
            self.busy = true
            defer { self.busy = false }

            let listing = Self.run(adb, ["devices"]) ?? ""
            let serials = listing.split(separator: "\n").dropFirst().compactMap { line -> String? in
                let parts = line.split(separator: "\t")
                return parts.count == 2 && parts[1] == "device" ? String(parts[0]) : nil
            }
            var ready = 0
            for serial in serials {
                let port = "tcp:\(Proto.port)"
                if Self.run(adb, ["-s", serial, "reverse", port, port]) != nil { ready += 1 }
            }
            DispatchQueue.main.async { completion(ready) }
        }
    }

    /// Installs the APK on the attached device whose model is `model` and
    /// reopens the app. Completion (main queue) gets whether it worked. It is
    /// skipped (false) when the model is not attached or more than one device has it.
    func install(_ apk: URL, model: String, completion: @escaping (Bool) -> Void) {
        guard let adb = Self.adbPath else {
            completion(false)
            return
        }
        queue.async {
            let listing = Self.run(adb, ["devices"]) ?? ""
            let serials = listing.split(separator: "\n").dropFirst().compactMap { line -> String? in
                let parts = line.split(separator: "\t")
                return parts.count == 2 && parts[1] == "device" ? String(parts[0]) : nil
            }
            let matching = serials.filter { serial in
                let reported = Self.run(adb, ["-s", serial, "shell", "getprop", "ro.product.model"]) ?? ""
                return reported.trimmingCharacters(in: .whitespacesAndNewlines) == model
            }
            guard matching.count == 1, let serial = matching.first else {
                DispatchQueue.main.async { completion(false) }
                return
            }
            // -r keeps the app's data; adb fails if the signature does not match.
            let installed = (Self.run(adb, ["-s", serial, "install", "-r", apk.path]) ?? "").contains("Success")
            if installed {
                _ = Self.run(adb, ["-s", serial, "shell", "am", "start", "-n", "app.screeaner/.MainActivity"])
                // The reinstall drops the reverse forward; set it up again.
                let port = "tcp:\(Proto.port)"
                _ = Self.run(adb, ["-s", serial, "reverse", port, port])
            }
            DispatchQueue.main.async { completion(installed) }
        }
    }

    /// Returns stdout on success, nil on failure.
    private static func run(_ executable: String, _ arguments: [String]) -> String? {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: executable)
        process.arguments = arguments
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = FileHandle.nullDevice
        do {
            try process.run()
        } catch {
            return nil
        }
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else { return nil }
        return String(data: data, encoding: .utf8) ?? ""
    }
}
