import AppKit
import CryptoKit
import Foundation
import Network

/// Tabletten gelen çağrı durumu.
struct CallState: Equatable {
    var state: String // "ringing" | "offhook"
    var number: String?
    var name: String?
    var test: Bool
    var outgoing: Bool
    var answeredBy: String? // çağrıyı açan cihaz ya da "tablet"
    var audioTo: String? // ses hangi cihazda; nil: tablette
    var since: Date

    var title: String { name ?? number ?? "Bilinmeyen numara" }
    var ringing: Bool { state == "ringing" }
}

/// Tabletin arama kaydından bir satır.
struct HistoryEntry: Identifiable {
    let id = UUID()
    let number: String
    let name: String?
    let type: String // "in" | "out" | "missed"
    let date: Date
    let duration: Int
    var title: String { name ?? number }
}

/// Tablete bağlantı: Android istemcisiyle aynı protokol (TCP 47123, satır başına bir JSON,
/// PIN'den türetilen HMAC ile kimlik doğrulama). Ağ olayları ana kuyrukta işlenir.
final class BridgeClient: ObservableObject {
    static let shared = BridgeClient()
    /// Bu cihazın tabletteki adı (answered_by / audio_to ile karşılaştırılır).
    static let me = Host.current().localizedName ?? "Mac"

    @Published private(set) var status = "Kapalı"
    @Published private(set) var connected = false
    @Published private(set) var call: CallState?
    @Published private(set) var history: [HistoryEntry] = []
    @Published private(set) var audioActive = false
    @Published private(set) var audioError: String?
    @Published private(set) var relayReady: Bool?
    @Published var lastError: String?

    @Published var pin: String = UserDefaults.standard.string(forKey: "pin") ?? "" {
        didSet {
            UserDefaults.standard.set(pin, forKey: "pin")
            if pin.count == 6 { restart() }
        }
    }
    @Published var manualHost: String = UserDefaults.standard.string(forKey: "host") ?? "" {
        didSet { UserDefaults.standard.set(manualHost, forKey: "host") }
    }

    /// Çağrı değişince (App: pencere, zil, bildirim).
    var onCallChanged: ((CallState?, CallState?) -> Void)?

    let audio = CallAudio()

    private var conn: NWConnection?
    private var buffer = Data()
    private var hostIP: String?
    private var authed = false
    private var audioReleased = false // bu görüşmede ses buradan bırakıldı
    private var lostAt: Date?
    private var pingTimer: Timer?
    private var retryDelay: TimeInterval = 1

    // MARK: - bağlantı

    func start() {
        guard conn == nil else { return }
        connect()
    }

    func restart() {
        conn?.cancel()
        conn = nil
        retryDelay = 1
        connect()
    }

    private func connect() {
        guard pin.count == 6 else {
            status = "Tabletteki 6 haneli PIN'i gir"
            return
        }
        guard let host = resolveHost() else {
            status = "Tablet bulunamadı: tabletin hotspot'una bağlı mısın?"
            scheduleReconnect()
            return
        }
        status = "Bağlanıyor: \(host)"
        hostIP = host
        authed = false
        buffer = Data()
        let c = NWConnection(host: NWEndpoint.Host(host), port: 47123, using: .tcp)
        conn = c
        c.stateUpdateHandler = { [weak self, weak c] state in
            guard let self, let c, c === self.conn else { return }
            switch state {
            case .failed(let e), .waiting(let e): self.drop("Bağlantı yok: \(e.localizedDescription)")
            default: break
            }
        }
        c.start(queue: .main)
        receive(c)
    }

    private func receive(_ c: NWConnection) {
        c.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self, weak c] data, _, done, error in
            guard let self, let c, c === self.conn else { return }
            if let data {
                self.buffer.append(data)
                while let i = self.buffer.firstIndex(of: 10) {
                    let line = self.buffer[self.buffer.startIndex..<i]
                    self.buffer.removeSubrange(self.buffer.startIndex...i)
                    if let obj = try? JSONSerialization.jsonObject(with: line) as? [String: Any] { self.handle(obj) }
                }
            }
            if done || error != nil {
                self.drop(self.authed ? "Tablet bağlantıyı kapattı" : "Bağlantı yok")
            } else {
                self.receive(c)
            }
        }
    }

    /// Bağlantı koptu. Görüşme sürerken çağrı hemen bırakılmaz; 10 sn içinde yeniden bağlanılırsa tablet durumu belirler.
    private func drop(_ reason: String) {
        conn?.cancel()
        conn = nil
        connected = false
        authed = false
        pingTimer?.invalidate()
        if !status.hasPrefix("PIN") { status = reason }
        if call?.state == "offhook" {
            if lostAt == nil { lostAt = Date() }
            if let t = lostAt, Date().timeIntervalSince(t) > 10 { setCall(nil) }
        } else {
            setCall(nil)
        }
        scheduleReconnect()
    }

    private func scheduleReconnect() {
        let inCall = call?.state == "offhook"
        let delay = inCall ? 0.5 : retryDelay
        retryDelay = min(retryDelay * 2, 10)
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
            guard let self, self.conn == nil else { return }
            self.connect()
        }
    }

    private func send(_ obj: [String: Any]) {
        guard let c = conn, var data = try? JSONSerialization.data(withJSONObject: obj) else { return }
        data.append(10)
        c.send(content: data, completion: .contentProcessed { _ in })
    }

    func command(_ cmd: String, _ extra: [String: Any] = [:]) {
        guard connected else {
            lastError = "Tablete bağlı değil"
            return
        }
        if cmd == "audio_on" { audioReleased = false }
        if cmd == "audio_off" { audioReleased = true }
        var m: [String: Any] = ["t": "cmd", "cmd": cmd]
        extra.forEach { m[$0] = $1 }
        send(m)
    }

    /// Tabletin bu numarayı aramasını ister; çağrı hemen giden arama olarak kurulur.
    func dial(_ number: String, name: String? = nil) {
        let n = number.filter { $0.isNumber || "+*#".contains($0) }
        guard !n.isEmpty else { return }
        guard connected else {
            lastError = "Tablete bağlı değil"
            return
        }
        guard call == nil else {
            lastError = "Süren bir çağrı var"
            return
        }
        lastError = nil
        setCall(CallState(state: "offhook", number: n, name: name, test: false, outgoing: true,
                          answeredBy: Self.me, audioTo: nil, since: Date()))
        var extra: [String: Any] = ["number": n]
        if let name { extra["name"] = name }
        command("dial", extra)
    }

    func refreshHistory() {
        if connected { send(["t": "cmd", "cmd": "history"]) }
    }

    // MARK: - mesajlar

    private func handle(_ m: [String: Any]) {
        switch m["t"] as? String {
        case "hello":
            let nonce = m["nonce"] as? String ?? ""
            send(["t": "auth", "mac": Self.mac(pin: pin, nonce: nonce), "name": Self.me, "kind": "mac"])
        case "auth_ok":
            authed = true
            connected = true
            lostAt = nil
            retryDelay = 1
            status = "Bağlı: \(hostIP ?? "")"
            refreshHistory()
            pingTimer?.invalidate()
            pingTimer = Timer.scheduledTimer(withTimeInterval: 20, repeats: true) { [weak self] _ in self?.send(["t": "ping"]) }
        case "auth_fail":
            status = "PIN hatalı: tabletteki PIN'i gir"
            conn?.cancel()
            conn = nil
            retryDelay = 10
            scheduleReconnect()
        case "state":
            onState(m)
        case "result":
            let ok = m["ok"] as? Bool ?? false
            let cmd = m["cmd"] as? String
            if cmd == "history" { return }
            lastError = ok ? nil : (m["error"] as? String ?? "Komut başarısız")
            if cmd == "dial", !ok, call?.outgoing == true {
                let e = lastError
                setCall(nil)
                lastError = e
            }
        case "audio":
            let active = m["active"] as? Bool ?? false
            if audioActive && !active && call?.state == "offhook" { audioReleased = true }
            audioActive = active
            audioError = (m["error"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            syncAudio()
        case "history":
            let calls = m["calls"] as? [[String: Any]] ?? []
            history = calls.map {
                HistoryEntry(
                    number: $0["number"] as? String ?? "",
                    name: ($0["name"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                    type: $0["type"] as? String ?? "in",
                    date: Date(timeIntervalSince1970: ($0["date"] as? Double ?? 0) / 1000),
                    duration: $0["duration"] as? Int ?? 0
                )
            }
        default:
            break
        }
    }

    private func onState(_ m: [String: Any]) {
        if let r = m["relay"] as? Bool { relayReady = r }
        let state = m["state"] as? String ?? "idle"
        if state == "idle" {
            setCall(nil)
            return
        }
        let prev = call
        func str(_ k: String) -> String? { (m[k] as? String).flatMap { $0.isEmpty ? nil : $0 } }
        setCall(CallState(
            state: state,
            number: str("number") ?? prev?.number,
            name: str("name") ?? prev?.name,
            test: m["test"] as? Bool ?? false,
            outgoing: m["outgoing"] as? Bool ?? false,
            answeredBy: str("answered_by") ?? (prev?.state == state ? prev?.answeredBy : nil),
            audioTo: str("audio_to"),
            since: prev?.state == state ? prev!.since : Date()
        ))
    }

    private func setCall(_ new: CallState?) {
        let prev = call
        call = new
        if new == nil {
            audioReleased = false
            audioActive = false
            audioError = nil
            lostAt = nil
            if prev != nil { refreshHistory() } // yeni arama kayda düştü
        }
        syncAudio()
        if prev != new { onCallChanged?(prev, new) }
    }

    /// Ses bu Mac'te mi: tablet aktarıyorsa ya da çağrıyı biz açtık/aradık ve bırakmadık.
    var audioHere: Bool {
        guard let c = call, c.state == "offhook" else { return false }
        if let to = c.audioTo { return to == Self.me }
        if audioActive { return true }
        return c.answeredBy == Self.me && !audioReleased && audioError == nil
    }

    /// Ses başka yerdeyse nerede olduğu ("tablette" / "<cihaz> cihazında").
    var audioPlace: String {
        guard let c = call else { return "" }
        switch c.audioTo ?? c.answeredBy {
        case nil, "tablet": return "tablette"
        case let who?: return "\(who) cihazında"
        }
    }

    private func syncAudio() {
        if audioHere, let ip = hostIP, call?.test == false {
            audio.start(host: ip)
        } else {
            audio.stop()
        }
    }

    // MARK: - yardımcılar

    /// HMAC-SHA256(anahtar = SHA256("callbridge-v1:" + PIN), nonce), onaltılık.
    static func mac(pin: String, nonce: String) -> String {
        let key = SymmetricKey(data: SHA256.hash(data: Data("callbridge-v1:\(pin)".utf8)))
        let code = HMAC<SHA256>.authenticationCode(for: Data(nonce.utf8), using: key)
        return code.map { String(format: "%02x", $0) }.joined()
    }

    /// Elle girilen adres, yoksa varsayılan ağ geçidi (hotspot'u açan tablet).
    private func resolveHost() -> String? {
        let manual = manualHost.trimmingCharacters(in: .whitespaces)
        if !manual.isEmpty { return manual }
        return Self.gateway()
    }

    static func gateway() -> String? {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/sbin/route")
        p.arguments = ["-n", "get", "default"]
        let pipe = Pipe()
        p.standardOutput = pipe
        p.standardError = FileHandle.nullDevice
        guard (try? p.run()) != nil else { return nil }
        p.waitUntilExit()
        let out = String(data: pipe.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8) ?? ""
        for line in out.split(separator: "\n") {
            let parts = line.split(separator: ":", maxSplits: 1).map { $0.trimmingCharacters(in: .whitespaces) }
            if parts.count == 2, parts[0] == "gateway" { return parts[1] }
        }
        return nil
    }
}
