import AppKit
import AVFoundation
import SwiftUI
import UserNotifications

/// CallBridge Mac: menü çubuğunda durur; tablete gelen çağrı Mac'te de çalar, buradan
/// yanıtlanıp konuşulur, buradan aranır. Mac tabletin hotspot'una bağlı olmalıdır.
@main
struct CallBridgeApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @ObservedObject private var bridge = BridgeClient.shared

    var body: some Scene {
        MenuBarExtra {
            MenuView(openCall: { delegate.showCallWindow() }).environmentObject(bridge)
        } label: {
            Image(systemName: menuIcon)
        }
        .menuBarExtraStyle(.window)
    }

    /// Şekli duruma göre değişir: hazır (bağlantı), bağlı değil, çalıyor, görüşmede.
    private var menuIcon: String {
        if let c = bridge.call { return c.ringing ? "phone.arrow.down.left.fill" : "phone.fill" }
        return bridge.connected ? "link" : "wifi.slash"
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate, UNUserNotificationCenterDelegate {
    private let bridge = BridgeClient.shared
    private var panel: NSPanel?
    private var ringer: NSSound?

    func applicationDidFinishLaunching(_ notification: Notification) {
        // tel: bağlantıları (tarayıcıda ya da Kişiler'de bir numaraya tıklayınca)
        NSAppleEventManager.shared().setEventHandler(
            self, andSelector: #selector(openURL(_:withReply:)),
            forEventClass: AEEventClass(kInternetEventClass), andEventID: AEEventID(kAEGetURL)
        )

        let center = UNUserNotificationCenter.current()
        center.delegate = self
        let answer = UNNotificationAction(identifier: "answer", title: "Yanıtla", options: [])
        let decline = UNNotificationAction(identifier: "decline", title: "Reddet", options: [.destructive])
        center.setNotificationCategories([UNNotificationCategory(identifier: "call", actions: [answer, decline], intentIdentifiers: [])])
        center.requestAuthorization(options: [.alert, .sound]) { _, _ in }
        AVCaptureDevice.requestAccess(for: .audio) { _ in }

        bridge.onCallChanged = { [weak self] prev, new in self?.callChanged(prev, new) }
        bridge.start()
    }

    @objc private func openURL(_ event: NSAppleEventDescriptor, withReply reply: NSAppleEventDescriptor) {
        guard let s = event.paramDescriptor(forKeyword: keyDirectObject)?.stringValue,
              let url = URL(string: s), url.scheme == "tel" else { return }
        let number = (url.absoluteString.dropFirst(4).removingPercentEncoding ?? "")
        bridge.dial(number)
        showCallWindow()
    }

    // MARK: - çağrı

    private func callChanged(_ prev: CallState?, _ new: CallState?) {
        guard let new else {
            stopRinging()
            UNUserNotificationCenter.current().removeAllDeliveredNotifications()
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { [weak self] in
                if self?.bridge.call == nil { self?.panel?.orderOut(nil) }
            }
            return
        }
        if new.ringing && prev?.ringing != true {
            startRinging()
            notifyIncoming(new)
            showCallWindow()
        } else if !new.ringing {
            stopRinging()
            UNUserNotificationCenter.current().removeAllDeliveredNotifications()
            if prev == nil { showCallWindow() } // buradan aranınca
        }
    }

    func showCallWindow() {
        if panel == nil {
            let p = NSPanel(
                contentRect: NSRect(x: 0, y: 0, width: 320, height: 200),
                styleMask: [.titled, .closable, .fullSizeContentView, .nonactivatingPanel],
                backing: .buffered, defer: false
            )
            p.titleVisibility = .hidden
            p.titlebarAppearsTransparent = true
            p.isMovableByWindowBackground = true
            p.level = .floating
            p.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
            p.isReleasedWhenClosed = false
            let host = NSHostingView(rootView: CallView().environmentObject(bridge))
            host.sizingOptions = [.preferredContentSize]
            p.contentView = host
            panel = p
        }
        guard let p = panel else { return }
        if let screen = NSScreen.main {
            let f = screen.visibleFrame
            p.setFrameTopLeftPoint(NSPoint(x: f.maxX - 340, y: f.maxY - 16))
        }
        p.orderFrontRegardless()
    }

    private func startRinging() {
        guard ringer == nil else { return }
        let s = NSSound(named: "Glass") ?? NSSound(named: "Ping")
        s?.loops = true
        s?.play()
        ringer = s
    }

    private func stopRinging() {
        ringer?.stop()
        ringer = nil
    }

    private func notifyIncoming(_ c: CallState) {
        let content = UNMutableNotificationContent()
        content.title = c.title
        content.body = (c.name != nil ? (c.number ?? "") + " · " : "") + "Tablete gelen çağrı"
        content.categoryIdentifier = "call"
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "incoming", content: content, trigger: nil))
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler done: @escaping () -> Void) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            switch response.actionIdentifier {
            case "answer": self.bridge.command("accept")
            case "decline": self.bridge.command("end")
            default: self.showCallWindow()
            }
            done()
        }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler done: @escaping (UNNotificationPresentationOptions) -> Void) {
        done([.banner, .list])
    }
}
