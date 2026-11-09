import Cocoa
import FlutterMacOS
import window_manager
import LaunchAtLogin

class MainFlutterWindow: NSWindow {
  private var vpnController: MacOSVPNController?
  override func awakeFromNib() {
    do {
      // Ask once at app launch. The helper stays open for this app session;
      // Dart enables the system proxy only after the local listener is ready.
      try MacSystemProxy.shared.prepareForLaunch()
      try MacSystemProxy.shared.recoverAtLaunch()
    } catch {
      let alert = NSAlert()
      alert.messageText = "Не удалось получить доступ к системному прокси"
      alert.informativeText = error.localizedDescription
      alert.runModal()
    }
    let flutterViewController = FlutterViewController()
    let windowFrame = self.frame
    self.contentViewController = flutterViewController
    self.setFrame(windowFrame, display: true)
    vpnController = MacOSVPNController(messenger: flutterViewController.engine.binaryMessenger)
    MacSystemProxy.shared.attach(messenger: flutterViewController.engine.binaryMessenger)


 // Add FlutterMethodChannel platform code
    FlutterMethodChannel(
      name: "launch_at_startup", binaryMessenger: flutterViewController.engine.binaryMessenger
    )
    .setMethodCallHandler { (_ call: FlutterMethodCall, result: @escaping FlutterResult) in
      switch call.method {
      case "launchAtStartupIsEnabled":
        result(LaunchAtLogin.isEnabled)
      case "launchAtStartupSetEnabled":
        if let arguments = call.arguments as? [String: Any] {
          LaunchAtLogin.isEnabled = arguments["setEnabledValue"] as! Bool
        }
        result(nil)
      default:
        result(FlutterMethodNotImplemented)
      }
    }

    FlutterMethodChannel(
      name: "com.hiddify.app/desktop_status",
      binaryMessenger: flutterViewController.engine.binaryMessenger
    ).setMethodCallHandler { (call: FlutterMethodCall, result: @escaping FlutterResult) in
      switch call.method {
      case "setStatus":
        let status = (call.arguments as? [String: Any])?["status"] as? String ?? "none"
        DesktopStatusBadge.setStatus(status)
        result(nil)
      case "setConnected":
        // Older Dart builds; prefer setStatus.
        let connected = (call.arguments as? [String: Any])?["connected"] as? Bool ?? false
        DesktopStatusBadge.setConnected(connected)
        result(nil)
      default:
        result(FlutterMethodNotImplemented)
      }
    }
    //
    RegisterGeneratedPlugins(registry: flutterViewController)

    super.awakeFromNib()
  }

  // window manager hidden at launch
  override public func order(_ place: NSWindow.OrderingMode, relativeTo otherWin: Int) {
    super.order(place, relativeTo: otherWin)
    hiddenWindowAtLaunch()
  }
}
