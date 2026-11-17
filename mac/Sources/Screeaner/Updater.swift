import AppKit
import CryptoKit
import Foundation
import ScreeanerCore
import Security

/// Keeps the Mac app (and the tablet app it carries) up to date from the
/// project's GitHub Releases.
///
/// Every few hours it asks GitHub for the latest release. A newer
/// `Screeaner-mac.zip` is downloaded and checked twice before it replaces
/// this app: the SHA-256 GitHub publishes for the file, and the code
/// signature, which must satisfy this app's own designated requirement
/// (same signing certificate). It installs when no tablet is streaming
/// (or right away when asked), then relaunches.
final class Updater {
    static let shared = Updater()

    static let repository = "luhno/Screeaner"
    static let releasesPage = URL(string: "https://github.com/\(repository)/releases/latest")!

    enum State: Equatable {
        case idle
        case checking
        case upToDate(checkedAt: Date)
        /// A newer release exists (not downloaded yet, or automatic updates are off).
        case available(version: String)
        case downloading(version: String)
        /// Downloaded and verified: installs when no tablet is streaming.
        case ready(version: String)
        case installing(version: String)
        case failed(String)
    }

    private let queue = DispatchQueue(label: "screeaner.updater", qos: .utility)
    private var timer: DispatchSourceTimer?
    private var latest: Release?
    /// The verified new app, next to this one so the swap is a rename.
    private var prepared: (version: String, app: URL)?
    private var etag: String?
    private var cachedRelease: Release?

    private(set) var state = State.idle {
        didSet {
            let value = state
            DispatchQueue.main.async { self.onChange?(value) }
        }
    }

    /// Called on the main queue.
    var onChange: ((State) -> Void)?
    /// Asked on the main queue before installing by itself: true while a tablet is streaming.
    var isBusy: () -> Bool = { false }

    private struct Release {
        let version: String
        let zip: URL
        let size: Int
        let sha256: String?
    }

    private var automatic: Bool { UserDefaults.standard.bool(forKey: SettingsKey.autoUpdate) }

    func start() {
        queue.async {
            let timer = DispatchSource.makeTimerSource(queue: self.queue)
            // A little after launch, then every six hours.
            timer.schedule(deadline: .now() + 20, repeating: 6 * 3600)
            timer.setEventHandler { [weak self] in self?.check(userInitiated: false) }
            timer.resume()
            self.timer = timer
        }
    }

    func checkNow() {
        queue.async { self.check(userInitiated: true) }
    }

    /// The user clicked "Instalar agora" (or "Baixar e instalar").
    func installNow() {
        queue.async {
            if let prepared = self.prepared {
                self.install(prepared)
            } else if let latest = self.latest {
                self.download(latest, thenInstall: true)
            }
        }
    }

    /// Called from the app's 1-second tick: a ready update waits for the streams to end.
    func installIfIdle() {
        guard case .ready = state, automatic, !isBusy() else { return }
        queue.async {
            guard let prepared = self.prepared else { return }
            self.install(prepared)
        }
    }

    // MARK: - Queue

    private func check(userInitiated: Bool) {
        guard userInitiated || automatic else { return }
        if case .installing = state { return }
        if case .downloading = state { return }
        state = .checking
        guard let release = fetchLatest() else { return }
        guard AppVersion.isNewer(release.version, than: Proto.appVersion) else {
            state = .upToDate(checkedAt: Date())
            return
        }
        latest = release
        if let prepared, prepared.version == release.version {
            state = .ready(version: release.version)
            return
        }
        if automatic {
            download(release, thenInstall: false)
        } else {
            state = .available(version: release.version)
        }
    }

    private func fetchLatest() -> Release? {
        var request = URLRequest(url: URL(string: "https://api.github.com/repos/\(Self.repository)/releases/latest")!)
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        request.setValue("Screeaner-Mac/\(Proto.appVersion)", forHTTPHeaderField: "User-Agent")
        if let etag { request.setValue(etag, forHTTPHeaderField: "If-None-Match") }
        request.timeoutInterval = 15
        guard let reply = Self.load(request) else {
            state = .failed("Sem conexão com o GitHub")
            return nil
        }
        let (status, data, headers) = reply
        if status == 304, let cachedRelease { return cachedRelease }
        guard status == 200,
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let tag = json["tag_name"] as? String,
              let assets = json["assets"] as? [[String: Any]]
        else {
            state = .failed(status == 404 ? "Nenhum release público no GitHub" : "GitHub respondeu \(status)")
            return nil
        }
        guard let asset = assets.first(where: { $0["name"] as? String == "Screeaner-mac.zip" }),
              let link = asset["browser_download_url"] as? String, let url = URL(string: link)
        else {
            state = .failed("O release \(tag) não tem o app do Mac")
            return nil
        }
        let digest = (asset["digest"] as? String).flatMap { $0.hasPrefix("sha256:") ? String($0.dropFirst(7)) : nil }
        let release = Release(
            version: tag.hasPrefix("v") ? String(tag.dropFirst()) : tag,
            zip: url, size: asset["size"] as? Int ?? 0, sha256: digest?.lowercased()
        )
        etag = headers["Etag"] ?? headers["ETag"]
        cachedRelease = release
        return release
    }

    private func download(_ release: Release, thenInstall: Bool) {
        state = .downloading(version: release.version)
        let current = Bundle.main.bundleURL
        let parent = current.deletingLastPathComponent()
        guard current.pathExtension == "app", FileManager.default.isWritableFile(atPath: parent.path) else {
            state = .failed("Sem permissão para atualizar em \(parent.path): baixe o release à mão")
            return
        }
        var request = URLRequest(url: release.zip)
        request.setValue("Screeaner-Mac/\(Proto.appVersion)", forHTTPHeaderField: "User-Agent")
        request.timeoutInterval = 120
        guard let reply = Self.load(request), reply.0 == 200 else {
            state = .failed("Não deu para baixar a v\(release.version)")
            return
        }
        let data = reply.1
        // 1. The file is the one GitHub published.
        if release.size > 0 && data.count != release.size {
            state = .failed("Download incompleto da v\(release.version)")
            return
        }
        if let expected = release.sha256 {
            let actual = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
            guard actual == expected else {
                state = .failed("O download da v\(release.version) não confere com o GitHub")
                return
            }
        }
        // 2. Unpack next to this app (same volume: the swap is a rename).
        let staging = parent.appendingPathComponent(".Screeaner-update", isDirectory: true)
        let fm = FileManager.default
        try? fm.removeItem(at: staging)
        do {
            try fm.createDirectory(at: staging, withIntermediateDirectories: true)
            let zip = staging.appendingPathComponent("Screeaner-mac.zip")
            try data.write(to: zip)
            guard Self.run("/usr/bin/ditto", ["-x", "-k", zip.path, staging.path]) == 0 else {
                throw CocoaError(.fileReadCorruptFile)
            }
            try? fm.removeItem(at: zip)
        } catch {
            try? fm.removeItem(at: staging)
            state = .failed("Não deu para abrir o pacote da v\(release.version)")
            return
        }
        let app = staging.appendingPathComponent("Screeaner.app")
        // 3. Signed with the same certificate as this app, intact, and really this app.
        if let problem = Self.verify(app, version: release.version, digestChecked: release.sha256 != nil) {
            try? fm.removeItem(at: staging)
            state = .failed(problem)
            return
        }
        prepared = (release.version, app)
        state = .ready(version: release.version)
        if thenInstall {
            install(prepared!)
        } else {
            DispatchQueue.main.async { self.installIfIdle() }
        }
    }

    private func install(_ update: (version: String, app: URL)) {
        state = .installing(version: update.version)
        let current = Bundle.main.bundleURL.path
        // After this process quits: swap the bundles (keeping the old one if anything fails) and relaunch.
        let script = """
        while kill -0 "$1" 2>/dev/null; do sleep 0.2; done
        rm -rf "$2.old"
        if mv "$2" "$2.old" && mv "$3" "$2"; then rm -rf "$2.old"; else mv "$2.old" "$2"; fi
        rm -rf "$4"
        open "$2"
        """
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/bin/sh")
        process.arguments = [
            "-c", script, "screeaner-update", "\(ProcessInfo.processInfo.processIdentifier)",
            current, update.app.path, update.app.deletingLastPathComponent().path,
        ]
        do {
            try process.run()
        } catch {
            state = .failed("Não deu para instalar a v\(update.version)")
            return
        }
        DispatchQueue.main.async { NSApp.terminate(nil) }
    }

    // MARK: - Checks

    /// nil when `app` may replace this one.
    private static func verify(_ app: URL, version: String, digestChecked: Bool) -> String? {
        guard let bundle = Bundle(url: app),
              bundle.bundleIdentifier == Bundle.main.bundleIdentifier,
              bundle.infoDictionary?["CFBundleShortVersionString"] as? String == version
        else { return "O pacote da v\(version) não é o Screeaner esperado" }

        var newCode: SecStaticCode?
        guard SecStaticCodeCreateWithPath(app as CFURL, [], &newCode) == errSecSuccess, let newCode else {
            return "Não deu para ler a assinatura da v\(version)"
        }
        let strict = SecCSFlags(rawValue: kSecCSCheckAllArchitectures | kSecCSStrictValidate | kSecCSCheckNestedCode)
        // Intact, whoever signed it.
        guard SecStaticCodeCheckValidity(newCode, strict, nil) == errSecSuccess else {
            return "A assinatura da v\(version) é inválida"
        }
        var selfCode: SecCode?
        var selfStatic: SecStaticCode?
        var requirement: SecRequirement?
        guard SecCodeCopySelf([], &selfCode) == errSecSuccess, let selfCode,
              SecCodeCopyStaticCode(selfCode, [], &selfStatic) == errSecSuccess, let selfStatic
        else { return "Não deu para ler a assinatura deste app" }
        if isAdHoc(selfStatic) {
            // A locally built app has no certificate to compare: rely on GitHub's checksum.
            return digestChecked ? nil : "Sem como verificar a v\(version): baixe o release à mão"
        }
        guard SecCodeCopyDesignatedRequirement(selfStatic, [], &requirement) == errSecSuccess, let requirement,
              SecStaticCodeCheckValidity(newCode, strict, requirement) == errSecSuccess
        else { return "A v\(version) foi assinada por outro certificado: não instalada" }
        return nil
    }

    private static func isAdHoc(_ code: SecStaticCode) -> Bool {
        var info: CFDictionary?
        guard SecCodeCopySigningInformation(code, SecCSFlags(rawValue: kSecCSSigningInformation), &info) == errSecSuccess,
              let dictionary = info as? [String: Any]
        else { return true }
        let flags = (dictionary[kSecCodeInfoFlags as String] as? NSNumber)?.uint32Value ?? 0
        return flags & 0x2 != 0 || dictionary[kSecCodeInfoCertificates as String] == nil // kSecCodeSignatureAdhoc
    }

    // MARK: - Plumbing

    private static func load(_ request: URLRequest) -> (Int, Data, [String: String])? {
        let done = DispatchSemaphore(value: 0)
        var result: (Int, Data, [String: String])?
        URLSession.shared.dataTask(with: request) { data, response, _ in
            if let response = response as? HTTPURLResponse {
                var headers: [String: String] = [:]
                for (key, value) in response.allHeaderFields {
                    if let key = key as? String, let value = value as? String { headers[key] = value }
                }
                result = (response.statusCode, data ?? Data(), headers)
            }
            done.signal()
        }.resume()
        _ = done.wait(timeout: .now() + request.timeoutInterval + 5)
        return result
    }

    private static func run(_ path: String, _ arguments: [String]) -> Int32 {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: path)
        process.arguments = arguments
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        do { try process.run() } catch { return -1 }
        process.waitUntilExit()
        return process.terminationStatus
    }
}
