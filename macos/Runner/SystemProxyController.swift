import Foundation
import FlutterMacOS
import Security
import Darwin

// One privileged child per app process; its private pipe is the session capability.
final class MacSystemProxy {
  static let shared = MacSystemProxy()
  private var pipe: UnsafeMutablePointer<FILE>?
  private let backupKey = "proxy4you.systemProxyBackup"
  private let portKey = "proxy4you.systemProxyPort"
  private var channel: FlutterMethodChannel?
  private let requests = DispatchQueue(label: "dev.proxy4you.system-proxy", qos: .userInitiated)

  // Recover network preferences left by an interrupted session before Flutter
  // restores the previous connection. An unrelated active proxy is left alone:
  // the helper restores only settings still pointing at our saved port.
  func recoverAtLaunch() throws {
    guard let backups = UserDefaults.standard.dictionary(forKey: backupKey), !backups.isEmpty else { return }
    try restore()
  }

  private func failure(_ message: String) -> NSError {
    NSError(domain: "proxy4you", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
  }

  func prepareForLaunch() throws {
    guard pipe == nil else { return }
    guard let path = Bundle.main.path(forAuxiliaryExecutable: "Proxy4YouProxyHelper") else {
      throw failure("В приложении отсутствует помощник системного прокси.")
    }
    var authorization: AuthorizationRef?
    guard AuthorizationCreate(nil, nil, [], &authorization) == errAuthorizationSuccess,
      let authorization = authorization
    else { throw failure("Не удалось подготовить запрос разрешения macOS.") }
    defer { AuthorizationFree(authorization, [.destroyRights]) }
    var communications: UnsafeMutablePointer<FILE>?
    // The local build uses Apple's legacy launch API; only this launch may show authorization UI.
    let status = path.withCString { executable in
      Proxy4YouLaunchSessionHelper(authorization, executable, &communications)
    }
    guard status == errAuthorizationSuccess, let communications = communications else {
      throw failure(
        "Разрешение не получено. Для повторного запроса полностью закройте и снова откройте приложение. Можно использовать режим «Прокси»."
      )
    }
    pipe = communications
    let fd = fileno(communications)
    _ = fcntl(fd, F_SETFD, FD_CLOEXEC)
    var noSignal: Int32 = 1
    _ = setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &noSignal, socklen_t(MemoryLayout<Int32>.size))
    let response = try readResponse()
    guard response["ready"] as? Bool == true else {
      closePipe()
      throw failure("Помощник системного прокси не запустился.")
    }
  }

  func attach(messenger: FlutterBinaryMessenger) {
    channel = FlutterMethodChannel(name: "dev.proxy4you/system-proxy", binaryMessenger: messenger)
    channel?.setMethodCallHandler { [weak self] call, result in
      guard let self = self else { return }
      self.requests.async {
        do {
          switch call.method {
          case "enable":
            guard let args = call.arguments as? [String: Any], let port = args["port"] as? Int,
              (1...65535).contains(port)
            else { throw self.failure("Неверный порт прокси.") }
            try self.prepareForLaunch()
            try self.restore()
            let response = try self.request(["command": "enable", "port": port])
            guard let backups = response["backups"] as? [String: Any] else {
              throw self.failure("Не удалось сохранить прежние сетевые настройки.")
            }
            UserDefaults.standard.set(backups, forKey: self.backupKey)
            UserDefaults.standard.set(port, forKey: self.portKey)
          case "restore": try self.restore()
          default:
            DispatchQueue.main.async { result(FlutterMethodNotImplemented) }
            return
          }
          DispatchQueue.main.async { result(nil) }
        } catch {
          let message = error.localizedDescription
          DispatchQueue.main.async { result(FlutterError(code: "system_proxy", message: message, details: nil)) }
        }
      }
    }
  }

  private func closePipe() {
    if let pipe = pipe { fclose(pipe) }
    pipe = nil
  }

  private func readResponse() throws -> [String: Any] {
    guard let pipe = pipe else {
      throw failure(
        "Помощник системного прокси недоступен. Полностью закройте и снова откройте приложение.")
    }
    let fd = fileno(pipe)
    let deadline = Date().addingTimeInterval(15)
    var data = Data()
    var byte: UInt8 = 0
    while data.count <= 262144 {
      var descriptor = pollfd(fd: fd, events: Int16(POLLIN), revents: 0)
      let remaining = max(0, Int32(deadline.timeIntervalSinceNow * 1000))
      guard remaining > 0 else {
        closePipe()
        throw failure("Помощник системного прокси не ответил вовремя. Повторите после перезапуска приложения.")
      }
      let polled = poll(&descriptor, 1, remaining)
      if polled < 0 && errno == EINTR { continue }
      let received = polled > 0 ? read(fd, &byte, 1) : -1
      if received < 0 && errno == EINTR { continue }
      guard polled > 0, received == 1 else {
        closePipe()
        throw failure(
          "Помощник системного прокси завершился или не ответил. Перезапустите приложение.")
      }
      if byte == 10 {
        guard let response = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
          closePipe()
          throw failure("Некорректный ответ помощника системного прокси.")
        }
        if response["ok"] as? Bool != true {
          // Preserve recovery data even when commit/apply only partially succeeds.
          if let backups = response["backups"] as? [String: Any], !backups.isEmpty,
            let port = response["port"] as? Int
          {
            UserDefaults.standard.set(backups, forKey: backupKey)
            UserDefaults.standard.set(port, forKey: portKey)
          }
          throw failure("Не удалось применить настройки системного прокси macOS.")
        }
        return response
      }
      data.append(byte)
    }
    closePipe()
    throw failure("Некорректный ответ помощника системного прокси.")
  }

  private func request(_ message: [String: Any]) throws -> [String: Any] {
    guard let pipe = pipe else {
      throw failure(
        "Разрешение на системный прокси отсутствует. Полностью закройте и снова откройте приложение."
      )
    }
    var data = try JSONSerialization.data(withJSONObject: message, options: [.sortedKeys])
    data.append(10)
    let fd = fileno(pipe)
    let sent = data.withUnsafeBytes { buffer -> Bool in
      var offset = 0
      while offset < buffer.count {
        let count = write(fd, buffer.baseAddress!.advanced(by: offset), buffer.count - offset)
        if count < 0 && errno == EINTR { continue }
        if count <= 0 { return false }
        offset += count
      }
      return true
    }
    guard sent else {
      closePipe()
      throw failure("Соединение с помощником системного прокси прервано.")
    }
    return try readResponse()
  }

  func restore() throws {
    guard let backups = UserDefaults.standard.dictionary(forKey: backupKey), !backups.isEmpty else {
      return
    }
    try prepareForLaunch()
    let port = UserDefaults.standard.integer(forKey: portKey)
    _ = try request(["command": "restore", "backups": backups, "port": port])
    UserDefaults.standard.removeObject(forKey: backupKey)
    UserDefaults.standard.removeObject(forKey: portKey)
  }

  func finishSession() {
    requests.sync {
      if pipe != nil { _ = try? request(["command": "finish"]) }
      closePipe()
    }
  }
}
