import AVFoundation
import Foundation
import Network

/// Mac'te çağrının sesi. Telefon istemcisiyle aynı UDP biçimi: her paket 4 bayt sıra numarası +
/// 20 ms PCM (16 kHz, mono, 16 bit, little-endian).
///   tablet → Mac (47124): karşı tarafın sesi, burada çalınır
///   Mac → tablet (47125): Mac'in mikrofonu (macOS ses işlemesiyle yankı engellenmiş)
/// ~/Library/Logs/CallBridge.log: ses ve bağlantı olayları (sorun giderme için).
func blog(_ s: String) {
    let url = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Logs/CallBridge.log")
    let f = DateFormatter()
    f.dateFormat = "yyyy-MM-dd HH:mm:ss"
    let line = "\(f.string(from: Date())) \(s)\n"
    if let h = try? FileHandle(forWritingTo: url) {
        h.seekToEndOfFile()
        h.write(Data(line.utf8))
        try? h.close()
    } else {
        try? Data(line.utf8).write(to: url)
    }
}

final class CallAudio {
    static let rate = 16000.0
    static let frame = 320 // 20 ms
    static let maxQueued = 1600 // 100 ms; fazlası birikirse paket atlanır

    private(set) var running = false
    var muted = false
    private(set) var packets = 0
    private(set) var dropped = 0
    private(set) var sent = 0
    private var statsTimer: Timer?

    private var engine: AVAudioEngine?
    private var player: AVAudioPlayerNode?
    private var converter: AVAudioConverter?
    private var listener: NWListener?
    private var inbound: [NWConnection] = []
    private var outbound: NWConnection?
    private var pending: [Int16] = []
    private var seq: UInt32 = 0
    private var queued = 0
    private let lock = NSLock()
    private let q = DispatchQueue(label: "callbridge.audio")

    private let playFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: rate, channels: 1, interleaved: false)!
    private let micFormat = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: rate, channels: 1, interleaved: true)!

    func start(host: String) {
        guard !running else { return }
        running = true
        packets = 0
        dropped = 0
        sent = 0
        muted = false
        queued = 0
        pending = []
        blog("ses başlıyor → \(host), mikrofon izni: \(AVCaptureDevice.authorizationStatus(for: .audio).rawValue) (3=izinli)")

        // Gelen sesi dinlemeye önce başla: ses motoru açılmasa da paketler sayılsın
        do {
            let l = try NWListener(using: .udp, on: 47124)
            l.stateUpdateHandler = { st in blog("UDP 47124 dinleyici: \(st)") }
            l.newConnectionHandler = { [weak self] c in
                guard let self else { return }
                blog("ses akışı geldi: \(c.endpoint)")
                self.inbound.append(c)
                c.start(queue: self.q)
                self.receive(c)
            }
            l.start(queue: q)
            listener = l
        } catch {
            blog("UDP 47124 açılamadı: \(error)")
        }

        let out = NWConnection(host: NWEndpoint.Host(host), port: 47125, using: .udp)
        out.start(queue: q)
        outbound = out

        // Önce yankı engellemeyle; olmazsa onsuz (ses yine de gelsin)
        if !startEngine(voiceProcessing: true) {
            blog("yankı engellemesiz yeniden deneniyor")
            _ = startEngine(voiceProcessing: false)
        }

        statsTimer?.invalidate()
        statsTimer = Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { [weak self] _ in
            guard let self else { return }
            blog("ses: ↓ \(self.packets) paket (\(self.dropped) atlandı), ↑ \(self.sent) paket")
        }
    }

    /**
     * Ses motorunu kurar. macOS'te ses işleme (voice processing), çıkış tarafı kurulmadan açılırsa
     * çıkış 0 kanallı kalır ve motor -10875 ile başlamaz; bu yüzden önce oynatıcı → karıştırıcı →
     * çıkış bağlanır, sonra ses işleme açılır.
     */
    private func startEngine(voiceProcessing: Bool) -> Bool {
        let engine = AVAudioEngine()
        let player = AVAudioPlayerNode()
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: playFormat)
        engine.connect(engine.mainMixerNode, to: engine.outputNode, format: nil)
        let input = engine.inputNode
        if voiceProcessing {
            do {
                try input.setVoiceProcessingEnabled(true) // yankı engelleme ve gürültü azaltma
            } catch {
                blog("ses işleme açılamadı: \(error)")
                return false
            }
        }
        let inFormat = input.outputFormat(forBus: 0)
        blog("giriş: \(inFormat.channelCount) kanal \(Int(inFormat.sampleRate)) Hz, çıkış: \(engine.outputNode.outputFormat(forBus: 0).channelCount) kanal, ses işleme: \(voiceProcessing)")
        converter = AVAudioConverter(from: inFormat, to: micFormat)
        if converter == nil { blog("mikrofon dönüştürücüsü kurulamadı") }
        if inFormat.sampleRate > 0 && inFormat.channelCount > 0 {
            input.installTap(onBus: 0, bufferSize: 1024, format: inFormat) { [weak self] buf, _ in self?.mic(buf) }
        }
        do {
            try engine.start()
        } catch {
            blog("ses motoru başlatılamadı: \(error)")
            input.removeTap(onBus: 0)
            if voiceProcessing { try? input.setVoiceProcessingEnabled(false) }
            return false
        }
        player.play()
        self.engine = engine
        self.player = player
        blog("ses motoru başladı")
        return true
    }

    func stop() {
        guard running else { return }
        running = false
        statsTimer?.invalidate()
        statsTimer = nil
        blog("ses durdu: ↓ \(packets) paket (\(dropped) atlandı), ↑ \(sent) paket")
        engine?.inputNode.removeTap(onBus: 0)
        player?.stop()
        engine?.stop()
        try? engine?.inputNode.setVoiceProcessingEnabled(false)
        engine = nil
        player = nil
        listener?.cancel()
        listener = nil
        inbound.forEach { $0.cancel() }
        inbound = []
        outbound?.cancel()
        outbound = nil
    }

    // MARK: - çalma

    private func receive(_ c: NWConnection) {
        c.receiveMessage { [weak self] data, _, _, error in
            guard let self, self.running else { return }
            if let data, data.count > 4 { self.play(data.dropFirst(4)) }
            if error == nil { self.receive(c) }
        }
    }

    private func play(_ pcm: Data) {
        packets += 1 // ses motoru açılmamış olsa da gelen paketi say
        guard let player else { return }
        let n = pcm.count / 2
        lock.lock()
        let tooMuch = queued > Self.maxQueued
        if !tooMuch { queued += n }
        lock.unlock()
        if tooMuch {
            dropped += 1
            return
        }
        guard let buf = AVAudioPCMBuffer(pcmFormat: playFormat, frameCapacity: AVAudioFrameCount(n)) else { return }
        buf.frameLength = AVAudioFrameCount(n)
        let out = buf.floatChannelData![0]
        pcm.withUnsafeBytes { raw in
            let s = raw.bindMemory(to: Int16.self)
            for i in 0..<n { out[i] = Float(Int16(littleEndian: s[i])) / 32768 }
        }
        player.scheduleBuffer(buf) { [weak self] in
            guard let self else { return }
            self.lock.lock()
            self.queued -= n
            self.lock.unlock()
        }
    }

    // MARK: - mikrofon

    private func mic(_ buf: AVAudioPCMBuffer) {
        guard running, let converter else { return }
        let cap = AVAudioFrameCount(Double(buf.frameLength) * Self.rate / buf.format.sampleRate) + 64
        guard let out = AVAudioPCMBuffer(pcmFormat: micFormat, frameCapacity: cap) else { return }
        var given = false
        var err: NSError?
        converter.convert(to: out, error: &err) { _, status in
            if given {
                status.pointee = .noDataNow
                return nil
            }
            given = true
            status.pointee = .haveData
            return buf
        }
        guard err == nil, let ch = out.int16ChannelData?[0] else { return }
        pending.append(contentsOf: UnsafeBufferPointer(start: ch, count: Int(out.frameLength)))
        while pending.count >= Self.frame {
            var frame = Array(pending.prefix(Self.frame))
            pending.removeFirst(Self.frame)
            if muted { frame = [Int16](repeating: 0, count: Self.frame) }
            mixTone(&frame) // sessizdeyken de tuş sesi gitsin
            var packet = Data(count: 4)
            packet[0] = UInt8(seq >> 24 & 0xff); packet[1] = UInt8(seq >> 16 & 0xff)
            packet[2] = UInt8(seq >> 8 & 0xff); packet[3] = UInt8(seq & 0xff)
            frame.withUnsafeBufferPointer { packet.append(Data(buffer: $0)) }
            seq &+= 1
            sent += 1
            outbound?.send(content: packet, completion: .idempotent)
        }
    }

    // MARK: - tuşlama (DTMF)
    // Çağrıya tuş sinyali gönderilemiyor; tuşun iki frekanslı sesi mikrofon akışına karıştırılır.

    private static let dtmfFreqs: [Character: (Double, Double)] = [
        "1": (697, 1209), "2": (697, 1336), "3": (697, 1477),
        "4": (770, 1209), "5": (770, 1336), "6": (770, 1477),
        "7": (852, 1209), "8": (852, 1336), "9": (852, 1477),
        "*": (941, 1209), "0": (941, 1336), "#": (941, 1477),
    ]
    private var tone: [Int16] = []
    private let toneLock = NSLock()

    func dtmf(_ key: Character) {
        guard let (low, high) = Self.dtmfFreqs[key] else { return }
        let n = Int(Self.rate * 0.16)
        var samples = [Int16](repeating: 0, count: n + Int(Self.rate * 0.06)) // 160 ms ses + 60 ms boşluk
        for i in 0..<n {
            let t = Double(i) / Self.rate
            samples[i] = Int16((sin(2 * .pi * low * t) + sin(2 * .pi * high * t)) * 6000)
        }
        toneLock.lock()
        tone.append(contentsOf: samples)
        toneLock.unlock()
    }

    private func mixTone(_ frame: inout [Int16]) {
        toneLock.lock()
        defer { toneLock.unlock() }
        guard !tone.isEmpty else { return }
        let k = min(tone.count, frame.count)
        for i in 0..<k { frame[i] = Int16(clamping: Int(frame[i]) + Int(tone[i])) }
        tone.removeFirst(k)
    }
}
