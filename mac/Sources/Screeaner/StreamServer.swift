import Foundation
import Network
import ScreeanerCore

/// One connected tablet. Parses incoming messages and sends outgoing ones,
/// tracking how many video frames are still waiting in the socket so the
/// pipeline can drop frames *before* encoding instead of queueing latency.
///
/// Until `establish` is called only handshake messages are accepted; after
/// it, everything is sealed with the session keys in both directions.
final class ClientConnection {
    private let conn: NWConnection
    let queue = DispatchQueue(label: "screeaner.client", qos: .userInteractive)
    private var buffer = Data()
    private let lock = NSLock()
    /// Serializes sealing and sending so message counters match the TCP order.
    private let sendLock = NSLock()
    private var inFlightFrames = 0
    private var closed = false
    private var secure: SecureChannel.Session?   // set under sendLock; read on `queue` after that
    private var receiving: SecureChannel.Session?  // `queue` only
    /// A tablet app from before the secure protocol: only the update messages
    /// may flow, in the clear (Android verifies the APK's signature itself).
    private var legacySend = false   // sendLock
    private var legacyReceive = false  // `queue` only

    let peer: String
    /// True when the tablet reached us through `adb reverse` (USB).
    let isLoopback: Bool
    /// True when the tablet comes from the internet, not from this Mac's own networks.
    let isInternet: Bool
    /// The tablet's address is IPv6 (matters for which router port it reached).
    let isIPv6: Bool
    /// Called on the connection queue.
    var onMessage: ((MsgType, Data) -> Void)?
    var onClose: (() -> Void)?

    init(_ conn: NWConnection) {
        self.conn = conn
        self.peer = "\(conn.endpoint)"
        if case .hostPort(let host, _) = conn.endpoint {
            let text = "\(host)"
            isLoopback = text.hasPrefix("127.") || text.hasPrefix("::ffff:127.") || text == "::1" || text == "localhost"
            isInternet = !Interfaces.isLocal(host)
            if case .ipv6(let address) = host { isIPv6 = address.asIPv4 == nil } else { isIPv6 = false }
        } else {
            isLoopback = false
            isInternet = false
            isIPv6 = false
        }
    }

    func start() {
        conn.stateUpdateHandler = { [weak self] state in
            switch state {
            case .failed, .cancelled: self?.close()
            default: break
            }
        }
        conn.start(queue: queue)
        receive()
    }

    func close() {
        lock.lock()
        let alreadyClosed = closed
        closed = true
        lock.unlock()
        guard !alreadyClosed else { return }
        conn.cancel()
        onClose?()
    }

    /// True when the network is not keeping up; the caller should skip frames.
    var isCongested: Bool {
        lock.lock()
        defer { lock.unlock() }
        return inFlightFrames >= 3
    }

    /// Switches both directions to the session keys. Call on `queue`, right
    /// after sending the last clear handshake message.
    func establish(_ session: SecureChannel.Session) {
        sendLock.lock()
        secure = session
        sendLock.unlock()
        receiving = session
    }

    /// Call on `queue`. Lets the 0.6-era update offer reach an old tablet app.
    func allowLegacyUpdates() {
        sendLock.lock()
        legacySend = true
        sendLock.unlock()
        legacyReceive = true
    }

    var isEstablished: Bool {
        sendLock.lock()
        defer { sendLock.unlock() }
        return secure != nil
    }

    /// Runs work on the connection queue, off the main thread.
    func queueAsync(_ work: @escaping () -> Void) {
        queue.async(execute: work)
    }

    func send(_ type: MsgType, _ payload: Data = Data()) {
        transmit(type, payload, isFrame: false)
    }

    func sendVideo(_ accessUnit: Data, ptsMicros: UInt64, isKey: Bool, encodeMicros: UInt32) {
        var payload = Data(capacity: 13 + accessUnit.count)
        payload.appendBE(ptsMicros)
        payload.append(isKey ? 1 : 0)
        payload.appendBE(encodeMicros)
        payload.append(accessUnit)
        transmit(.video, payload, isFrame: true)
    }

    private func transmit(_ type: MsgType, _ payload: Data, isFrame: Bool) {
        sendLock.lock()
        defer { sendLock.unlock() }
        var wireType = type
        var body = payload
        if let secure {
            guard let sealed = try? secure.seal(type: type.rawValue, payload: payload) else { return }
            wireType = .encrypted
            body = sealed
        } else if !type.isHandshake && !(legacySend && (type == .updateOffer || type == .updateApk)) {
            return // nothing but the handshake leaves before encryption is on
        }
        var message = Data(capacity: Proto.headerSize + body.count)
        message.append(wireType.rawValue)
        message.appendBE(UInt32(body.count))
        message.append(body)

        if isFrame {
            lock.lock()
            inFlightFrames += 1
            lock.unlock()
        }
        conn.send(content: message, completion: .contentProcessed { [weak self] error in
            guard let self else { return }
            if isFrame {
                self.lock.lock()
                self.inFlightFrames -= 1
                self.lock.unlock()
            }
            if error != nil { self.close() }
        })
    }

    private func receive() {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let data, !data.isEmpty {
                self.buffer.append(data)
                if !self.drainBuffer() {
                    self.close()
                    return
                }
            }
            if isComplete || error != nil {
                self.close()
            } else {
                self.receive()
            }
        }
    }

    /// Returns false on a protocol violation.
    private func drainBuffer() -> Bool {
        while buffer.count >= Proto.headerSize {
            let rawType = buffer[buffer.startIndex]
            let length = Int(buffer.readBE(UInt32.self, at: 1))
            guard length <= Proto.maxPayload else { return false }
            let total = Proto.headerSize + length
            guard buffer.count >= total else { break }
            let payload = Data(buffer.dropFirst(Proto.headerSize).prefix(length))
            buffer = Data(buffer.dropFirst(total))
            guard let type = MsgType(rawValue: rawType) else { continue }
            if let session = receiving {
                // Once encrypted, anything else is an attack or a bug.
                guard type == .encrypted,
                      let opened = try? session.open(payload),
                      let innerType = MsgType(rawValue: opened.0)
                else { return false }
                onMessage?(innerType, opened.1)
            } else if type.isHandshake || type == .hello || (legacyReceive && type == .updateRequest) {
                // A clear HELLO is an old tablet app; the session decides what to do with it.
                onMessage?(type, payload)
            } else if !legacyReceive {
                return false
            }
        }
        return true
    }
}

/// Listens for tablets over TCP (USB via `adb reverse`, or Wi-Fi) and
/// advertises itself over Bonjour so the tablet finds the Mac without typing an IP.
final class StreamServer {
    private var listener: NWListener?
    var onClient: ((ClientConnection) -> Void)?
    var onError: ((String) -> Void)?

    func start() {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        tcp.enableKeepalive = true
        tcp.keepaliveIdle = 5
        let parameters = NWParameters(tls: nil, tcp: tcp)
        parameters.allowLocalEndpointReuse = true
        parameters.serviceClass = .interactiveVideo

        do {
            let listener = try NWListener(using: parameters, on: NWEndpoint.Port(rawValue: Proto.port)!)
            let name = Host.current().localizedName ?? "Mac"
            listener.service = NWListener.Service(name: name, type: Proto.serviceType)
            listener.newConnectionHandler = { [weak self] connection in
                self?.onClient?(ClientConnection(connection))
            }
            listener.stateUpdateHandler = { [weak self] state in
                if case .failed(let error) = state {
                    self?.onError?("Servidor falhou: \(error.localizedDescription)")
                }
            }
            listener.start(queue: .main)
            self.listener = listener
        } catch {
            onError?("Não foi possível abrir a porta \(Proto.port): \(error.localizedDescription)")
        }
    }
}

/// Receives the tablets' UDP hellos. Replying on the flow the tablet opened
/// means the video path works through firewalls that only allow replies.
final class UdpHub {
    private var listener: NWListener?
    private let queue = DispatchQueue(label: "screeaner.udp", qos: .userInteractive)
    /// (token, flow), called on the main queue once per new flow.
    var onFlow: ((UInt32, NWConnection) -> Void)?

    func start() {
        let parameters = NWParameters.udp
        parameters.allowLocalEndpointReuse = true
        parameters.serviceClass = .interactiveVideo
        guard let listener = try? NWListener(using: parameters, on: NWEndpoint.Port(rawValue: Proto.port)!) else {
            return // UDP unavailable: sessions simply stay on TCP
        }
        listener.newConnectionHandler = { [weak self] connection in self?.adopt(connection) }
        listener.start(queue: queue)
        self.listener = listener
    }

    private func adopt(_ connection: NWConnection) {
        connection.start(queue: queue)
        connection.receiveMessage { [weak self] data, _, _, _ in
            guard let self, let data, data.count >= 8,
                  data.readBE(UInt32.self, at: 0) == Proto.udpMagic
            else {
                connection.cancel()
                return
            }
            let token = data.readBE(UInt32.self, at: 4)
            DispatchQueue.main.async { self.onFlow?(token, connection) }
            self.drain(connection)
        }
    }

    /// Keep reading the tablet's keep-alives so the flow stays open.
    private func drain(_ connection: NWConnection) {
        connection.receiveMessage { [weak self] _, _, _, error in
            if error == nil { self?.drain(connection) }
        }
    }
}
