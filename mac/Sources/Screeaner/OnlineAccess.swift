import Darwin
import Foundation
import Network

/// Use over the internet with no server in between: the tablet connects
/// straight to this Mac, end-to-end encrypted with the keys pinned at pairing.
///
/// This Mac makes itself reachable on its own:
/// - IPv6: its global addresses need no NAT; the router is asked (UPnP IGDv2
///   pinhole) to let the connection in through its IPv6 firewall.
/// - IPv4: the router is asked to forward the port (NAT-PMP, then UPnP IGD) and
///   tells us the public address. Carrier-grade NAT and double NAT are detected.
/// The tablet learns the addresses while on the home network (encrypted), then
/// tries them when it cannot find the Mac nearby.
final class OnlineAccess {
    static let shared = OnlineAccess()

    struct Endpoint: Codable, Equatable {
        let host: String
        let port: Int
        let udp: Int
    }

    struct Status: Equatable {
        var enabled = false
        var checking = false
        /// Global IPv6 addresses of this Mac (stable ones first).
        var ipv6: [String] = []
        /// True when the router confirmed it lets IPv6 in (UPnP pinhole); nil when unknown.
        var ipv6Opened: Bool?
        var publicIPv4: String?
        var tcpPort: UInt16?
        var udpPort: UInt16?
        /// "NAT-PMP" or "UPnP".
        var method: String?
        /// The router's own public address is in 100.64/10: the carrier shares it.
        var cgnat = false
        /// The router's public address is private: another router sits in front.
        var doubleNat = false
        var lastRemote: Date?
        var lastRemoteName: String?

        var ipv4Reachable: Bool { publicIPv4 != nil && tcpPort != nil && !cgnat && !doubleNat }
        var reachable: Bool { ipv4Reachable || !ipv6.isEmpty }
    }

    private let queue = DispatchQueue(label: "screeaner.online", qos: .utility)
    private let lock = NSLock()
    private var current = Status()          // lock
    private var igd: Gateway.UPnP?           // queue
    private var mappedUPnP: [(proto: String, external: UInt16)] = [] // queue
    private var pinholes: [String] = []      // queue, UPnP unique ids
    private var mappedNatPmp = false         // queue
    private var natPmpGateway: String?       // queue
    private var timer: DispatchSourceTimer?  // queue
    private let monitor = NWPathMonitor()
    private var lastPathKey = ""             // queue

    /// Called on the main queue.
    var onChange: ((Status) -> Void)?

    var status: Status {
        lock.lock()
        defer { lock.unlock() }
        return current
    }

    var isEnabled: Bool { status.enabled }

    private init() {
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            // New network or new addresses: the router and the prefix may have changed.
            let key = path.availableInterfaces.map(\.name).joined(separator: ",") + "\(path.status)"
            self.queue.async {
                guard key != self.lastPathKey else { return }
                self.lastPathKey = key
                if self.status.enabled { self.refreshNow() }
            }
        }
        monitor.start(queue: queue)
    }

    func setEnabled(_ enabled: Bool) {
        queue.async {
            self.update { $0.enabled = enabled }
            if enabled {
                self.refreshNow()
                self.schedule()
            } else {
                self.timer?.cancel()
                self.timer = nil
                self.removeMappings()
                self.update { status in status = Status() }
            }
        }
    }

    func refresh() {
        queue.async { if self.status.enabled { self.refreshNow() } }
    }

    /// Quitting: close the router ports now.
    func shutdown() {
        queue.sync { removeMappings() }
    }

    /// A paired tablet streamed over the internet.
    func noteRemote(name: String) {
        update {
            $0.lastRemote = Date()
            $0.lastRemoteName = name
        }
    }

    /// Where the tablet can reach this Mac from outside, for the ENDPOINTS message.
    func endpoints() -> [Endpoint] {
        let status = self.status
        guard status.enabled else { return [] }
        var list = status.ipv6.prefix(3).map { Endpoint(host: $0, port: Int(Proto.port), udp: Int(Proto.port)) }
        if let ip = status.publicIPv4, !status.cgnat, !status.doubleNat {
            // Without a mapping the port may still have been forwarded by hand.
            list.append(Endpoint(host: ip, port: Int(status.tcpPort ?? Proto.port), udp: Int(status.udpPort ?? Proto.port)))
        }
        return list
    }

    /// The UDP port the tablet must aim at for a connection that came in over IPv4 from outside.
    func externalUdpPort(forIPv6: Bool) -> UInt16 {
        forIPv6 ? Proto.port : (status.udpPort ?? Proto.port)
    }

    // MARK: - Queue

    private func update(_ change: (inout Status) -> Void) {
        lock.lock()
        let before = current
        change(&current)
        let after = current
        lock.unlock()
        guard after != before else { return }
        DispatchQueue.main.async { self.onChange?(after) }
    }

    private func schedule() {
        timer?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: queue)
        // Leases last an hour; renew well before.
        timer.schedule(deadline: .now() + 20 * 60, repeating: 20 * 60)
        timer.setEventHandler { [weak self] in self?.refreshNow() }
        timer.resume()
        self.timer = timer
    }

    private func refreshNow() {
        update { $0.checking = true }
        let route = Gateway.defaultRoute()
        var found = Status(enabled: true)
        found.lastRemote = status.lastRemote
        found.lastRemoteName = status.lastRemoteName
        found.ipv6 = Interfaces.globalIPv6(preferring: route?.interface)

        if let route {
            // NAT-PMP first (Apple and many other routers): quick and simple.
            if let external = Gateway.NatPmp.externalAddress(gateway: route.gateway) {
                found.publicIPv4 = external
                let tcp = Gateway.NatPmp.map(gateway: route.gateway, tcp: true, port: Proto.port, lifetime: 3600)
                let udp = Gateway.NatPmp.map(gateway: route.gateway, tcp: false, port: Proto.port, lifetime: 3600)
                if let tcp {
                    found.tcpPort = tcp
                    found.udpPort = udp ?? tcp
                    found.method = "NAT-PMP"
                    mappedNatPmp = true
                    natPmpGateway = route.gateway
                }
            }
            // UPnP IGD (most ISP routers and ONTs).
            if found.tcpPort == nil || !found.ipv6.isEmpty {
                if igd == nil || igd?.gatewayHost != route.gateway { igd = Gateway.UPnP.discover(gateway: route.gateway) }
                if let igd {
                    if found.publicIPv4 == nil { found.publicIPv4 = igd.externalAddress() }
                    if found.tcpPort == nil, let local = route.localAddress {
                        mappedUPnP.removeAll()
                        if let tcp = igd.addPortMapping(tcp: true, internalClient: local, port: Proto.port) {
                            mappedUPnP.append(("TCP", tcp))
                            found.tcpPort = tcp
                            found.method = "UPnP"
                            if let udp = igd.addPortMapping(tcp: false, internalClient: local, port: Proto.port, preferred: tcp) {
                                mappedUPnP.append(("UDP", udp))
                                found.udpPort = udp
                            }
                        }
                    }
                    if let address = found.ipv6.first, igd.hasIPv6Firewall {
                        for id in pinholes { igd.deletePinhole(id) }
                        pinholes.removeAll()
                        if let tcp = igd.addPinhole(address: address, port: Proto.port, tcp: true) {
                            pinholes.append(tcp)
                            if let udp = igd.addPinhole(address: address, port: Proto.port, tcp: false) { pinholes.append(udp) }
                            found.ipv6Opened = true
                        }
                    }
                }
            }
        }
        if let ip = found.publicIPv4, let bytes = Interfaces.ipv4Bytes(ip) {
            found.cgnat = bytes[0] == 100 && (bytes[1] & 0xC0) == 64
            found.doubleNat = !found.cgnat && Interfaces.isPrivateIPv4(bytes)
        }
        found.checking = false
        update { $0 = found }
    }

    private func removeMappings() {
        if mappedNatPmp, let gateway = natPmpGateway {
            _ = Gateway.NatPmp.map(gateway: gateway, tcp: true, port: Proto.port, lifetime: 0)
            _ = Gateway.NatPmp.map(gateway: gateway, tcp: false, port: Proto.port, lifetime: 0)
        }
        mappedNatPmp = false
        if let igd {
            for mapping in mappedUPnP { igd.deletePortMapping(proto: mapping.proto, external: mapping.external) }
            for id in pinholes { igd.deletePinhole(id) }
        }
        mappedUPnP.removeAll()
        pinholes.removeAll()
    }
}

// MARK: - Addresses of this Mac

enum Interfaces {
    struct Address {
        let interface: String
        let bytes: [UInt8]
        let mask: [UInt8]
    }

    static func all() -> [Address] {
        var result: [Address] = []
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return [] }
        defer { freeifaddrs(list) }
        for pointer in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let entry = pointer.pointee
            guard let address = entry.ifa_addr, (entry.ifa_flags & UInt32(IFF_UP)) != 0 else { continue }
            let name = String(cString: entry.ifa_name)
            switch Int32(address.pointee.sa_family) {
            case AF_INET:
                let bytes = address.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { sin in
                    withUnsafeBytes(of: sin.pointee.sin_addr) { Array($0) }
                }
                let mask = entry.ifa_netmask.map { netmask in
                    netmask.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { sin in
                        withUnsafeBytes(of: sin.pointee.sin_addr) { Array($0) }
                    }
                } ?? [255, 255, 255, 0]
                result.append(Address(interface: name, bytes: bytes, mask: mask))
            case AF_INET6:
                let bytes = address.withMemoryRebound(to: sockaddr_in6.self, capacity: 1) { sin6 in
                    withUnsafeBytes(of: sin6.pointee.sin6_addr) { Array($0) }
                }
                result.append(Address(interface: name, bytes: bytes, mask: Array(repeating: 255, count: 8) + Array(repeating: 0, count: 8)))
            default:
                continue
            }
        }
        return result
    }

    static func isGlobalIPv6(_ b: [UInt8]) -> Bool {
        b.count == 16 && (b[0] & 0xE0) == 0x20 // 2000::/3
    }

    static func isPrivateIPv4(_ b: [UInt8]) -> Bool {
        guard b.count == 4 else { return false }
        return b[0] == 10 || (b[0] == 172 && (b[1] & 0xF0) == 16) || (b[0] == 192 && b[1] == 168)
    }

    static func ipv4Bytes(_ text: String) -> [UInt8]? {
        var address = in_addr()
        guard inet_pton(AF_INET, text, &address) == 1 else { return nil }
        return withUnsafeBytes(of: address) { Array($0) }
    }

    static func text(ipv6 bytes: [UInt8]) -> String {
        var address = in6_addr()
        withUnsafeMutableBytes(of: &address) { $0.copyBytes(from: bytes) }
        var buffer = [CChar](repeating: 0, count: Int(INET6_ADDRSTRLEN))
        inet_ntop(AF_INET6, &address, &buffer, socklen_t(buffer.count))
        return String(cString: buffer)
    }

    /// Global IPv6 addresses, the stable ones (not "temporary"/"deprecated") first.
    static func globalIPv6(preferring interface: String?) -> [String] {
        let addresses = all().filter { isGlobalIPv6($0.bytes) && !$0.interface.hasPrefix("utun") }
        let flags = ifconfigFlags()
        func rank(_ address: Address) -> Int {
            let text = Self.text(ipv6: address.bytes)
            let words = flags[text] ?? []
            var score = 0
            if words.contains("temporary") { score += 4 }
            if words.contains("deprecated") { score += 8 }
            if address.interface != interface { score += 2 }
            if !words.contains("secured") && !words.contains("dynamic") { score += 1 }
            return score
        }
        let sorted = addresses.sorted { rank($0) < rank($1) }
        let usable = sorted.filter { !(flags[Self.text(ipv6: $0.bytes)] ?? []).contains("deprecated") }
        var seen = Set<String>()
        return usable.map { Self.text(ipv6: $0.bytes) }.filter { seen.insert($0).inserted }
    }

    /// `ifconfig` knows which IPv6 addresses are temporary (they rotate) and which are stable.
    private static func ifconfigFlags() -> [String: Set<String>] {
        guard let output = Gateway.run("/sbin/ifconfig", []) else { return [:] }
        var result: [String: Set<String>] = [:]
        for line in output.split(separator: "\n") {
            let words = line.split(separator: " ").map(String.init)
            guard let index = words.firstIndex(of: "inet6"), index + 1 < words.count else { continue }
            let address = words[index + 1].split(separator: "%").first.map(String.init) ?? words[index + 1]
            result[address] = Set(words)
        }
        return result
    }

    /// Whether a tablet at `remote` is on one of this Mac's networks (or a VPN / USB),
    /// as opposed to coming in from the internet.
    static func isLocal(_ remote: NWEndpoint.Host) -> Bool {
        switch remote {
        case .ipv4(let address):
            return isLocalIPv4(Array(address.rawValue))
        case .ipv6(let address):
            if let v4 = address.asIPv4 { return isLocalIPv4(Array(v4.rawValue)) }
            let b = Array(address.rawValue)
            guard isGlobalIPv6(b) else { return true } // loopback, link-local, unique local
            // Same /64 as one of ours: the same home network.
            return all().contains { isGlobalIPv6($0.bytes) && Array($0.bytes.prefix(8)) == Array(b.prefix(8)) }
        default:
            return true
        }
    }

    private static func isLocalIPv4(_ b: [UInt8]) -> Bool {
        guard b.count == 4 else { return true }
        if b[0] == 127 || isPrivateIPv4(b) || (b[0] == 169 && b[1] == 254) { return true }
        if b[0] == 100 && (b[1] & 0xC0) == 64 { return true } // carrier/VPN range (e.g. a mesh VPN)
        return all().contains { own in
            own.bytes.count == 4 && (0..<4).allSatisfy { (own.bytes[$0] & own.mask[$0]) == (b[$0] & own.mask[$0]) }
        }
    }
}

// MARK: - The home router

enum Gateway {
    struct Route {
        let gateway: String
        let interface: String?
        let localAddress: String?
    }

    static func run(_ path: String, _ arguments: [String]) -> String? {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: path)
        process.arguments = arguments
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = FileHandle.nullDevice
        do { try process.run() } catch { return nil }
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        return String(data: data, encoding: .utf8)
    }

    /// The IPv4 default route: the router's address and our address on that network.
    static func defaultRoute() -> Route? {
        guard let output = run("/sbin/route", ["-n", "get", "default"]) else { return nil }
        var gateway: String?
        var interface: String?
        for line in output.split(separator: "\n") {
            let parts = line.split(separator: ":", maxSplits: 1).map { $0.trimmingCharacters(in: .whitespaces) }
            guard parts.count == 2 else { continue }
            if parts[0] == "gateway" { gateway = parts[1] }
            if parts[0] == "interface" { interface = parts[1] }
        }
        guard let gateway, Interfaces.ipv4Bytes(gateway) != nil else { return nil }
        let local = Interfaces.all().first { $0.interface == interface && $0.bytes.count == 4 }
            .map { $0.bytes.map(String.init).joined(separator: ".") }
        return Route(gateway: gateway, interface: interface, localAddress: local)
    }

    /// Sends one UDP datagram and waits for an answer (RFC 6886 retry schedule, shortened).
    static func udpExchange(host: String, port: UInt16, payload: [UInt8]) -> [UInt8]? {
        let fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard fd >= 0 else { return nil }
        defer { Darwin.close(fd) }
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = port.bigEndian
        guard inet_pton(AF_INET, host, &address.sin_addr) == 1 else { return nil }
        var reply = [UInt8](repeating: 0, count: 128)
        var wait: Int32 = 250_000
        for _ in 0..<3 {
            var timeout = timeval(tv_sec: 0, tv_usec: wait)
            setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
            let sent = payload.withUnsafeBytes { raw in
                withUnsafePointer(to: &address) { pointer in
                    pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                        sendto(fd, raw.baseAddress, raw.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                    }
                }
            }
            guard sent == payload.count else { return nil }
            let count = recv(fd, &reply, reply.count, 0)
            if count > 0 { return Array(reply[0..<count]) }
            wait = min(wait * 2, 900_000)
        }
        return nil
    }

    // MARK: NAT-PMP (RFC 6886)

    enum NatPmp {
        static func externalAddress(gateway: String) -> String? {
            guard let reply = udpExchange(host: gateway, port: 5351, payload: [0, 0]),
                  reply.count >= 12, reply[0] == 0, reply[1] == 128, reply[2] == 0, reply[3] == 0
            else { return nil }
            return reply[8..<12].map(String.init).joined(separator: ".")
        }

        /// Returns the external port, or nil. lifetime 0 removes the mapping.
        static func map(gateway: String, tcp: Bool, port: UInt16, lifetime: UInt32) -> UInt16? {
            var request: [UInt8] = [0, tcp ? 2 : 1, 0, 0]
            request += [UInt8(port >> 8), UInt8(port & 0xFF)]
            request += lifetime == 0 ? [0, 0] : [UInt8(port >> 8), UInt8(port & 0xFF)]
            request += [UInt8(lifetime >> 24), UInt8((lifetime >> 16) & 0xFF), UInt8((lifetime >> 8) & 0xFF), UInt8(lifetime & 0xFF)]
            guard let reply = udpExchange(host: gateway, port: 5351, payload: request),
                  reply.count >= 16, reply[1] == 128 + (tcp ? 2 : 1), reply[2] == 0, reply[3] == 0
            else { return nil }
            return UInt16(reply[10]) << 8 | UInt16(reply[11])
        }
    }

    // MARK: UPnP Internet Gateway Device

    final class UPnP {
        typealias Service = (url: URL, service: String)
        let gatewayHost: String
        private let connection: Service?
        private let firewall: Service?

        var hasIPv6Firewall: Bool { firewall != nil }

        private init(gatewayHost: String, connection: Service?, firewall: Service?) {
            self.gatewayHost = gatewayHost
            self.connection = connection
            self.firewall = firewall
        }

        /// SSDP search on the local network, then the router's device description.
        static func discover(gateway: String) -> UPnP? {
            for location in ssdpLocations() {
                guard let url = URL(string: location), let reply = http(URLRequest(url: url)), reply.0 == 200,
                      let xml = String(data: reply.1, encoding: .utf8)
                else { continue }
                let base = value("URLBase", in: xml).flatMap { URL(string: $0) } ?? url
                var connection: Service?
                var firewall: Service?
                for block in blocks("service", in: xml) {
                    guard let type = value("serviceType", in: block), let control = value("controlURL", in: block),
                          let controlURL = URL(string: control, relativeTo: base)?.absoluteURL
                    else { continue }
                    if connection == nil, type.contains("WANIPConnection") || type.contains("WANPPPConnection") {
                        connection = (url: controlURL, service: type)
                    }
                    if firewall == nil, type.contains("WANIPv6FirewallControl") { firewall = (url: controlURL, service: type) }
                }
                if connection != nil || firewall != nil {
                    return UPnP(gatewayHost: url.host ?? gateway, connection: connection, firewall: firewall)
                }
            }
            return nil
        }

        func externalAddress() -> String? {
            guard let connection,
                  let reply = Self.soap(connection.url, connection.service, "GetExternalIPAddress", [])
            else { return nil }
            return Self.value("NewExternalIPAddress", in: reply)
        }

        /// Maps `port` to the same external port (or a fallback); returns the external port.
        func addPortMapping(tcp: Bool, internalClient: String, port: UInt16, preferred: UInt16? = nil) -> UInt16? {
            guard let connection else { return nil }
            var candidates: [UInt16] = [port, port &+ 100, port &+ 1000]
            if let preferred { candidates.insert(preferred, at: 0) }
            for external in candidates {
                // Some routers only take permanent mappings (lease 0).
                for lease in ["3600", "0"] {
                    let args = [
                        ("NewRemoteHost", ""), ("NewExternalPort", "\(external)"), ("NewProtocol", tcp ? "TCP" : "UDP"),
                        ("NewInternalPort", "\(port)"), ("NewInternalClient", internalClient), ("NewEnabled", "1"),
                        ("NewPortMappingDescription", "Screeaner"), ("NewLeaseDuration", lease),
                    ]
                    if Self.soap(connection.url, connection.service, "AddPortMapping", args) != nil { return external }
                }
            }
            return nil
        }

        func deletePortMapping(proto: String, external: UInt16) {
            guard let connection else { return }
            _ = Self.soap(connection.url, connection.service, "DeletePortMapping", [
                ("NewRemoteHost", ""), ("NewExternalPort", "\(external)"), ("NewProtocol", proto),
            ])
        }

        /// IGDv2: lets inbound IPv6 reach `address:port` through the router's firewall.
        func addPinhole(address: String, port: UInt16, tcp: Bool) -> String? {
            guard let firewall,
                  let reply = Self.soap(firewall.url, firewall.service, "AddPinhole", [
                      ("RemoteHost", ""), ("RemotePort", "0"), ("InternalClient", address),
                      ("InternalPort", "\(port)"), ("Protocol", tcp ? "6" : "17"), ("LeaseTime", "3600"),
                  ])
            else { return nil }
            return Self.value("UniqueID", in: reply)
        }

        func deletePinhole(_ id: String) {
            guard let firewall else { return }
            _ = Self.soap(firewall.url, firewall.service, "DeletePinhole", [("UniqueID", id)])
        }

        // MARK: Plumbing

        private static func ssdpLocations() -> [String] {
            let fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
            guard fd >= 0 else { return [] }
            defer { Darwin.close(fd) }
            var timeout = timeval(tv_sec: 0, tv_usec: 300_000)
            setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
            var address = sockaddr_in()
            address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
            address.sin_family = sa_family_t(AF_INET)
            address.sin_port = UInt16(1900).bigEndian
            inet_pton(AF_INET, "239.255.255.250", &address.sin_addr)
            for target in ["urn:schemas-upnp-org:device:InternetGatewayDevice:2",
                           "urn:schemas-upnp-org:device:InternetGatewayDevice:1"] {
                let message = Array(("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n"
                    + "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: \(target)\r\n\r\n").utf8)
                _ = message.withUnsafeBytes { raw in
                    withUnsafePointer(to: &address) { pointer in
                        pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                            sendto(fd, raw.baseAddress, raw.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                        }
                    }
                }
            }
            var locations: [String] = []
            var buffer = [UInt8](repeating: 0, count: 2048)
            let deadline = Date().addingTimeInterval(2.5)
            while Date() < deadline {
                let count = recv(fd, &buffer, buffer.count, 0)
                guard count > 0, let text = String(bytes: buffer[0..<count], encoding: .utf8) else { continue }
                for line in text.split(separator: "\r\n") where line.lowercased().hasPrefix("location:") {
                    let location = line.dropFirst("location:".count).trimmingCharacters(in: .whitespaces)
                    if !locations.contains(location) { locations.append(location) }
                }
            }
            return locations
        }

        /// Synchronous request with a short timeout (called on the online queue only).
        private static func http(_ request: URLRequest) -> (Int, Data)? {
            var request = request
            request.timeoutInterval = 4
            let done = DispatchSemaphore(value: 0)
            var result: (Int, Data)?
            URLSession.shared.dataTask(with: request) { data, response, _ in
                if let response = response as? HTTPURLResponse { result = (response.statusCode, data ?? Data()) }
                done.signal()
            }.resume()
            _ = done.wait(timeout: .now() + 6)
            return result
        }

        /// A SOAP action; returns the reply body when the router said yes.
        private static func soap(_ url: URL, _ service: String, _ action: String, _ args: [(String, String)]) -> String? {
            let body = args.map { "<\($0.0)>\(escape($0.1))</\($0.0)>" }.joined()
            let envelope = "<?xml version=\"1.0\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<u:\(action) xmlns:u=\"\(service)\">\(body)</u:\(action)></s:Body></s:Envelope>"
            var request = URLRequest(url: url)
            request.httpMethod = "POST"
            request.setValue("text/xml; charset=\"utf-8\"", forHTTPHeaderField: "Content-Type")
            request.setValue("\"\(service)#\(action)\"", forHTTPHeaderField: "SOAPAction")
            request.httpBody = Data(envelope.utf8)
            guard let reply = http(request), reply.0 == 200 else { return nil }
            return String(data: reply.1, encoding: .utf8) ?? ""
        }

        private static func escape(_ text: String) -> String {
            text.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;")
                .replacingOccurrences(of: ">", with: "&gt;")
        }

        /// Contents of the first <tag>…</tag> (namespace prefixes ignored).
        static func value(_ tag: String, in xml: String) -> String? {
            guard let regex = try? NSRegularExpression(
                pattern: "<(?:\\w+:)?\(tag)>([^<]*)</(?:\\w+:)?\(tag)>", options: [.caseInsensitive]
            ),
                let match = regex.firstMatch(in: xml, range: NSRange(xml.startIndex..., in: xml)),
                let range = Range(match.range(at: 1), in: xml)
            else { return nil }
            return xml[range].trimmingCharacters(in: .whitespacesAndNewlines)
        }

        static func blocks(_ tag: String, in xml: String) -> [String] {
            guard let regex = try? NSRegularExpression(
                pattern: "<\(tag)>(.*?)</\(tag)>", options: [.caseInsensitive, .dotMatchesLineSeparators]
            ) else { return [] }
            return regex.matches(in: xml, range: NSRange(xml.startIndex..., in: xml)).compactMap {
                Range($0.range(at: 1), in: xml).map { String(xml[$0]) }
            }
        }
    }
}
