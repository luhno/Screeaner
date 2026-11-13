import Foundation
import Network

/// One connected tablet. Parses incoming messages and sends outgoing ones,
/// tracking how many video frames are still waiting in the socket so the
/// pipeline can drop frames *before* encoding instead of queueing latency.
final class ClientConnection {
    private let conn: NWConnection
    private let queue = DispatchQueue(label: "screeaner.client", qos: .userInteractive)
    private var buffer = Data()
    private let lock = NSLock()
    private var inFlightFrames = 0
    private var closed = false

    let peer: String
    /// Called on the connection queue.
    var onMessage: ((MsgType, Data) -> Void)?
    var onClose: (() -> Void)?

    init(_ conn: NWConnection) {
        self.conn = conn
        self.peer = "\(conn.endpoint)"
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

    func send(_ type: MsgType, _ payload: Data = Data()) {
        var message = Data(capacity: Proto.headerSize + payload.count)
        message.append(type.rawValue)
        message.appendBE(UInt32(payload.count))
        message.append(payload)
        transmit(message, isFrame: false)
    }

    func sendVideo(_ accessUnit: Data, ptsMicros: UInt64, isKey: Bool, encodeMicros: UInt32) {
        let payloadSize = 13 + accessUnit.count
        var message = Data(capacity: Proto.headerSize + payloadSize)
        message.append(MsgType.video.rawValue)
        message.appendBE(UInt32(payloadSize))
        message.appendBE(ptsMicros)
        message.append(isKey ? 1 : 0)
        message.appendBE(encodeMicros)
        message.append(accessUnit)
        transmit(message, isFrame: true)
    }

    private func transmit(_ message: Data, isFrame: Bool) {
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
            if let type = MsgType(rawValue: rawType) {
                onMessage?(type, payload)
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
