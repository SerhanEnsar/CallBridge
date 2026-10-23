import Contacts
import ServiceManagement
import SwiftUI

// MARK: - ortak

/// Komut satırı araçlarında SwiftUI'nin @State makrosu derlenemiyor; görünüm durumu bu kutuda tutulur.
final class Box<T>: ObservableObject {
    @Published var v: T
    init(_ v: T) { self.v = v }
}

enum Theme {
    static let green = Color(red: 0.19, green: 0.82, blue: 0.35)
    static let red = Color(red: 1.0, green: 0.27, blue: 0.23)
    static let orange = Color(red: 1.0, green: 0.62, blue: 0.04)
}

/// Adın baş harfleri (en çok iki); ad yoksa ya da harfle başlamıyorsa "#".
func initials(_ name: String?) -> String {
    let words = (name ?? "").split(separator: " ").filter { $0.first?.isLetter == true }
    guard let first = words.first else { return "#" }
    if words.count == 1 { return String(first.prefix(1)).uppercased() }
    return (String(first.prefix(1)) + String(words.last!.prefix(1))).uppercased()
}

/// Addan türetilen sabit renk (Android'deki avatarlarla aynı palet).
func avatarColor(_ name: String) -> Color {
    let palette: [(Double, Double, Double)] = [
        (0.37, 0.36, 0.90), (0.04, 0.52, 1.0), (0.19, 0.69, 0.78), (0.20, 0.78, 0.35),
        (1.0, 0.62, 0.04), (1.0, 0.22, 0.37), (0.75, 0.35, 0.95), (0.67, 0.56, 0.41),
    ]
    var h: UInt32 = 5381
    for u in name.unicodeScalars { h = (h &* 33) &+ u.value }
    let c = palette[Int(h % UInt32(palette.count))]
    return Color(red: c.0, green: c.1, blue: c.2)
}

struct Avatar: View {
    let name: String?
    var size: CGFloat = 34
    var body: some View {
        Text(initials(name))
            .font(.system(size: size * 0.4, weight: .bold))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(Circle().fill(avatarColor(name ?? "#")))
    }
}

private let trLocale = Locale(identifier: "tr_TR")

func dayLabel(_ d: Date) -> String {
    let cal = Calendar.current
    if cal.isDateInToday(d) { return "Bugün" }
    if cal.isDateInYesterday(d) { return "Dün" }
    let f = DateFormatter()
    f.locale = trLocale
    f.dateFormat = cal.isDate(d, equalTo: Date(), toGranularity: .year) ? "d MMMM" : "d MMMM yyyy"
    return f.string(from: d)
}

func timeLabel(_ d: Date) -> String {
    let f = DateFormatter()
    f.locale = trLocale
    f.dateFormat = "HH:mm"
    return f.string(from: d)
}

func durationLabel(_ s: Int) -> String { s < 60 ? "\(s) sn" : "\(s / 60) dk" + (s % 60 > 0 ? " \(s % 60) sn" : "") }

func elapsedLabel(_ since: Date) -> String {
    let s = Int(Date().timeIntervalSince(since))
    return String(format: "%d:%02d", s / 60, s % 60)
}

struct StatusPill: View {
    let text: String
    let color: Color
    var body: some View {
        Text(text)
            .font(.system(size: 11, weight: .semibold))
            .foregroundStyle(color)
            .padding(.horizontal, 9).padding(.vertical, 4)
            .background(Capsule().fill(color.opacity(0.15)))
    }
}

/// Yuvarlak simge düğmesi ve altında etiket; active iken ters renkli (açık).
struct CallButton: View {
    let icon: String
    let label: String
    var color: Color = Color.primary.opacity(0.12)
    var foreground: Color = .primary
    var active = false
    var size: CGFloat = 52
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            VStack(spacing: 5) {
                Image(systemName: icon)
                    .font(.system(size: size * 0.38, weight: .semibold))
                    .foregroundStyle(active ? Color(nsColor: .windowBackgroundColor) : foreground)
                    .frame(width: size, height: size)
                    .background(Circle().fill(active ? Color.primary : color))
                Text(label).font(.system(size: 11)).foregroundStyle(.secondary)
            }
        }
        .buttonStyle(.plain)
    }
}

// MARK: - çağrı penceresi

/// Ekranın sağ üstündeki çağrı penceresi; telefondaki çağrı ekranıyla aynı düzen.
struct CallView: View {
    @EnvironmentObject var bridge: BridgeClient
    @StateObject private var keypad = Box(false)
    @StateObject private var dtmf = Box("")
    @StateObject private var tick = Box(Date())
    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        if let call = bridge.call {
            VStack(spacing: 10) {
                HStack(spacing: 12) {
                    Avatar(name: call.name, size: 52)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(call.title).font(.system(size: 17, weight: .bold)).lineLimit(1)
                        if call.name != nil, let n = call.number {
                            Text(n).font(.system(size: 12)).foregroundStyle(.secondary)
                        }
                        Text(status(call)).font(.system(size: 12, weight: .medium)).foregroundStyle(statusColor(call))
                    }
                    Spacer()
                }
                if !dtmf.v.isEmpty { Text(dtmf.v).font(.system(size: 18, design: .monospaced)) }
                if let e = bridge.audioError ?? bridge.lastError {
                    Text(e).font(.system(size: 11)).foregroundStyle(Theme.red).multilineTextAlignment(.center)
                }
                controls(call)
            }
            .padding(16)
            .frame(width: 320)
            .onReceive(timer) { tick.v = $0 }
        }
    }

    private func status(_ c: CallState) -> String {
        _ = tick.v
        if c.ringing { return "Tablete gelen çağrı" + (c.test ? " · test" : "") }
        if bridge.audioHere { return (c.outgoing ? "Giden arama · " : "Görüşmede · ") + elapsedLabel(c.since) }
        if c.answeredBy == "tablet" && c.audioTo == nil { return "Tablette yanıtlandı · " + elapsedLabel(c.since) }
        return "Görüşme \(bridge.audioPlace) sürüyor"
    }

    private func statusColor(_ c: CallState) -> Color {
        c.ringing || bridge.audioHere ? Theme.green : .secondary
    }

    @ViewBuilder private func controls(_ c: CallState) -> some View {
        if c.ringing {
            HStack(spacing: 28) {
                CallButton(icon: "phone.down.fill", label: "Reddet", color: Theme.red, foreground: .white) { bridge.command("end") }
                CallButton(icon: "bell.slash.fill", label: "Sessiz") { bridge.command("silence") }
                CallButton(icon: "phone.fill", label: "Yanıtla", color: Theme.green, foreground: .white) { bridge.command("accept") }
            }
        } else if bridge.audioHere {
            if keypad.v {
                VStack(spacing: 6) {
                    ForEach(["123", "456", "789", "*0#"], id: \.self) { row in
                        HStack(spacing: 10) {
                            ForEach(Array(row), id: \.self) { k in
                                Button { dtmf.v.append(k); bridge.audio.dtmf(k) } label: {
                                    Text(String(k)).font(.system(size: 18, weight: .medium))
                                        .frame(width: 46, height: 46)
                                        .background(Circle().fill(Color.primary.opacity(0.1)))
                                }.buttonStyle(.plain)
                            }
                        }
                    }
                    Button("Tuş takımını gizle") { keypad.v = false }.buttonStyle(.link)
                }
            } else {
                HStack(spacing: 18) {
                    CallButton(icon: "mic.slash.fill", label: bridge.audio.muted ? "Sessizde" : "Sessiz", active: bridge.audio.muted) {
                        bridge.audio.muted.toggle()
                        tick.v = Date()
                    }
                    CallButton(icon: "circle.grid.3x3.fill", label: "Tuşlar") { keypad.v = true }
                    CallButton(icon: "ipad", label: "Tablete bırak") { bridge.command("audio_off") }
                }
            }
            CallButton(icon: "phone.down.fill", label: "Kapat", color: Theme.red, foreground: .white) { bridge.command("end") }
        } else {
            HStack(spacing: 12) {
                Button { bridge.command("audio_on") } label: {
                    Label("Bu Mac'e al", systemImage: "laptopcomputer").frame(maxWidth: .infinity)
                }.buttonStyle(.borderedProminent).tint(Theme.green)
                Button(role: .destructive) { bridge.command("end") } label: {
                    Label("Kapat", systemImage: "phone.down.fill").frame(maxWidth: .infinity)
                }.buttonStyle(.bordered)
            }
        }
    }
}

// MARK: - menü çubuğu paneli

struct MenuView: View {
    @EnvironmentObject var bridge: BridgeClient
    @StateObject private var contacts = ContactsStore()
    @StateObject private var tab = Box(0)
    @StateObject private var settings = Box(false)
    let openCall: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                Image(systemName: "phone.connection.fill").font(.system(size: 20)).foregroundStyle(Theme.green)
                VStack(alignment: .leading, spacing: 1) {
                    Text("CallBridge").font(.system(size: 15, weight: .bold))
                    Text(bridge.status).font(.system(size: 11)).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer()
                pill
            }

            if let call = bridge.call {
                Button(action: openCall) {
                    HStack {
                        Avatar(name: call.name, size: 30)
                        VStack(alignment: .leading) {
                            Text(call.title).font(.system(size: 13, weight: .semibold))
                            Text(call.ringing ? "Çalıyor" : (bridge.audioHere ? "Görüşme bu Mac'te" : "Görüşme \(bridge.audioPlace)"))
                                .font(.system(size: 11)).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Text("Aç").font(.system(size: 12, weight: .semibold)).foregroundStyle(Theme.green)
                    }
                    .padding(10)
                    .background(RoundedRectangle(cornerRadius: 10).fill(Theme.green.opacity(0.12)))
                }.buttonStyle(.plain)
            }

            if bridge.relayReady == false && bridge.connected {
                Text("Tablette ses aktarıcısı kapalı: ses tablette kalır.")
                    .font(.system(size: 11)).foregroundStyle(Theme.orange)
            }

            Picker("", selection: $tab.v) {
                Text("Son aramalar").tag(0)
                Text("Rehber").tag(1)
                Text("Tuş takımı").tag(2)
            }
            .pickerStyle(.segmented)
            .labelsHidden()

            Group {
                switch tab.v {
                case 0: RecentsList()
                case 1: ContactsList(store: contacts)
                default: KeypadView()
                }
            }
            .frame(height: 320)

            if let e = bridge.lastError, bridge.call == nil {
                Text(e).font(.system(size: 11)).foregroundStyle(Theme.red)
            }

            Divider()
            DisclosureGroup("Ayarlar", isExpanded: $settings.v) { SettingsView().padding(.top, 6) }
                .font(.system(size: 12))
            HStack {
                Spacer()
                Button("Çık") { NSApp.terminate(nil) }.buttonStyle(.link).font(.system(size: 12))
            }
        }
        .padding(14)
        .frame(width: 350)
        .onAppear { bridge.refreshHistory() }
    }

    private var pill: some View {
        if bridge.call != nil { return StatusPill(text: "Çağrıda", color: Theme.green) }
        if bridge.connected { return StatusPill(text: "Hazır", color: Theme.green) }
        return StatusPill(text: bridge.pin.count == 6 ? "Bağlanıyor" : "Kurulum", color: Theme.orange)
    }
}

struct RecentsList: View {
    @EnvironmentObject var bridge: BridgeClient
    var body: some View {
        if bridge.history.isEmpty {
            Text(bridge.connected ? "Henüz arama yok" : "Tablete bağlanınca son aramalar burada görünür")
                .font(.system(size: 12)).foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 2) {
                    ForEach(Array(bridge.history.enumerated()), id: \.element.id) { i, h in
                        if i == 0 || dayLabel(bridge.history[i - 1].date) != dayLabel(h.date) {
                            Text(dayLabel(h.date).uppercased()).font(.system(size: 10, weight: .bold)).foregroundStyle(.secondary)
                                .padding(.top, i == 0 ? 0 : 8)
                        }
                        Row(name: h.name, title: h.title, color: h.type == "missed" ? Theme.red : .primary,
                            icon: h.type == "out" ? "arrow.up.right" : (h.type == "missed" ? "arrow.down.left" : "arrow.down.left"),
                            iconColor: h.type == "missed" ? Theme.red : (h.type == "out" ? .secondary : Theme.green),
                            sub: timeLabel(h.date) + (h.duration > 0 ? " · " + durationLabel(h.duration) : "")) {
                            bridge.dial(h.number, name: h.name)
                        }
                    }
                }
            }
        }
    }
}

/// Avatar, başlık ve alt satırlı liste satırı; tıklayınca arar.
struct Row: View {
    let name: String?
    let title: String
    var color: Color = .primary
    var icon: String? = nil
    var iconColor: Color = .secondary
    let sub: String
    let action: () -> Void
    @StateObject private var hover = Box(false)
    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Avatar(name: name, size: 30)
                VStack(alignment: .leading, spacing: 1) {
                    Text(title).font(.system(size: 13, weight: .semibold)).foregroundStyle(color).lineLimit(1)
                    HStack(spacing: 4) {
                        if let icon { Image(systemName: icon).font(.system(size: 9, weight: .bold)).foregroundStyle(iconColor) }
                        Text(sub).font(.system(size: 11)).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
                Spacer()
                Image(systemName: "phone.fill").font(.system(size: 12)).foregroundStyle(Theme.green).opacity(hover.v ? 1 : 0.5)
            }
            .padding(.vertical, 5).padding(.horizontal, 6)
            .background(RoundedRectangle(cornerRadius: 8).fill(hover.v ? Color.primary.opacity(0.07) : .clear))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hover.v = $0 }
    }
}

final class ContactsStore: ObservableObject {
    struct Item: Identifiable {
        let id = UUID()
        let name: String
        let number: String
        let label: String
    }
    @Published var items: [Item] = []
    @Published var denied = false
    private var loaded = false

    func load() {
        guard !loaded else { return }
        loaded = true
        let store = CNContactStore()
        store.requestAccess(for: .contacts) { ok, _ in
            guard ok else {
                DispatchQueue.main.async { self.denied = true }
                return
            }
            DispatchQueue.global(qos: .userInitiated).async {
                let keys = [CNContactGivenNameKey, CNContactFamilyNameKey, CNContactOrganizationNameKey, CNContactPhoneNumbersKey] as [CNKeyDescriptor]
                let req = CNContactFetchRequest(keysToFetch: keys)
                req.sortOrder = .givenName
                var out: [Item] = []
                try? store.enumerateContacts(with: req) { c, _ in
                    let full = [c.givenName, c.familyName].filter { !$0.isEmpty }.joined(separator: " ")
                    let name = full.isEmpty ? c.organizationName : full
                    for p in c.phoneNumbers {
                        out.append(Item(name: name, number: p.value.stringValue, label: Self.label(p.label)))
                    }
                }
                DispatchQueue.main.async { self.items = out }
            }
        }
    }

    private static func label(_ l: String?) -> String {
        switch l {
        case CNLabelPhoneNumberMobile: return "Mobil"
        case CNLabelPhoneNumberiPhone: return "iPhone"
        case CNLabelHome: return "Ev"
        case CNLabelWork: return "İş"
        case CNLabelPhoneNumberMain: return "Ana"
        case nil: return "Telefon"
        case let l?: return CNLabeledValue<NSString>.localizedString(forLabel: l)
        }
    }
}

struct ContactsList: View {
    @EnvironmentObject var bridge: BridgeClient
    @ObservedObject var store: ContactsStore
    @StateObject private var query = Box("")
    var body: some View {
        VStack(spacing: 8) {
            TextField("Ad ya da numara ara", text: $query.v).textFieldStyle(.roundedBorder)
            if store.denied {
                Text("Rehber izni yok: Sistem Ayarları → Gizlilik ve Güvenlik → Kişiler")
                    .font(.system(size: 11)).foregroundStyle(.secondary).frame(maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 2) {
                        ForEach(filtered.prefix(300)) { c in
                            Row(name: c.name, title: c.name, sub: "\(c.label) · \(c.number)") { bridge.dial(c.number, name: c.name) }
                        }
                    }
                }
            }
        }
        .onAppear { store.load() }
    }

    private var filtered: [ContactsStore.Item] {
        let q = query.v.trimmingCharacters(in: .whitespaces).lowercased()
        if q.isEmpty { return store.items }
        let digits = q.filter(\.isNumber)
        return store.items.filter {
            $0.name.lowercased().contains(q) || (digits.count >= 3 && $0.number.filter(\.isNumber).contains(digits))
        }
    }
}

struct KeypadView: View {
    @EnvironmentObject var bridge: BridgeClient
    @StateObject private var number = Box("")
    var body: some View {
        VStack(spacing: 10) {
            TextField("Numara", text: $number.v)
                .font(.system(size: 22, weight: .semibold))
                .multilineTextAlignment(.center)
                .textFieldStyle(.plain)
                .onSubmit { bridge.dial(number.v) }
            ForEach([["1", "2", "3"], ["4", "5", "6"], ["7", "8", "9"], ["*", "0", "#"]], id: \.self) { row in
                HStack(spacing: 16) {
                    ForEach(row, id: \.self) { k in
                        Button { number.v += k } label: {
                            Text(k).font(.system(size: 20, weight: .medium))
                                .frame(width: 52, height: 52)
                                .background(Circle().fill(Color.primary.opacity(0.08)))
                        }.buttonStyle(.plain)
                    }
                }
            }
            HStack(spacing: 16) {
                Color.clear.frame(width: 52, height: 52)
                Button { bridge.dial(number.v); number.v = "" } label: {
                    Image(systemName: "phone.fill").font(.system(size: 20)).foregroundStyle(.white)
                        .frame(width: 52, height: 52).background(Circle().fill(Theme.green))
                }.buttonStyle(.plain)
                Button { if !number.v.isEmpty { number.v.removeLast() } } label: {
                    Image(systemName: "delete.left").font(.system(size: 18)).foregroundStyle(.secondary).frame(width: 52, height: 52)
                }.buttonStyle(.plain)
            }
        }
    }
}

struct SettingsView: View {
    @EnvironmentObject var bridge: BridgeClient
    @StateObject private var pin = Box(BridgeClient.shared.pin)
    @StateObject private var host = Box(BridgeClient.shared.manualHost)
    @StateObject private var atLogin = Box(SMAppService.mainApp.status == .enabled)

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("PIN").frame(width: 90, alignment: .leading)
                SecureField("Tabletteki 6 hane", text: $pin.v).textFieldStyle(.roundedBorder)
                    .onSubmit { bridge.pin = pin.v }
            }
            HStack {
                Text("Tablet adresi").frame(width: 90, alignment: .leading)
                TextField("Otomatik: \(BridgeClient.gateway() ?? "—")", text: $host.v).textFieldStyle(.roundedBorder)
                    .onSubmit { bridge.manualHost = host.v; bridge.restart() }
            }
            Toggle("Mac açılınca başlat", isOn: $atLogin.v)
                .onChange(of: atLogin.v) { on in
                    do {
                        if on { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
                    } catch {
                        bridge.lastError = "Açılışta başlatma ayarlanamadı: \(error.localizedDescription)"
                    }
                }
            HStack {
                Button("Kaydet ve bağlan") {
                    bridge.manualHost = host.v
                    bridge.pin = pin.v
                    bridge.restart()
                }
                Spacer()
                Text("Mac tabletin hotspot'una bağlı olmalı").font(.system(size: 10)).foregroundStyle(.secondary)
            }
        }
        .font(.system(size: 12))
    }
}
