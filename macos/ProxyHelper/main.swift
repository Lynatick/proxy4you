import Foundation
import SystemConfiguration

typealias ProxyConfigurations = [String: [String: Any]]

protocol ProxyStore {
  func transaction(_ change: (inout ProxyConfigurations) throws -> Void) throws
}

enum ProxyFailure: Error { case invalidRequest, unavailable, commitFailed }

// A successful preference commit followed by a failed apply must be retried
// even when the next transaction sees the already-restored stored values.
final class ProxyCommitController {
  private(set) var needsApply = true
  func finish(changed: Bool, commit: () -> Bool, apply: () -> Bool) throws {
    if changed {
      needsApply = true
      guard commit() else { throw ProxyFailure.commitFailed }
    }
    if needsApply {
      guard apply() else { throw ProxyFailure.commitFailed }
      needsApply = false
    }
  }
}

final class NetworkProxyStore: ProxyStore {
  private let commits = ProxyCommitController()
  func transaction(_ change: (inout ProxyConfigurations) throws -> Void) throws {
    guard let prefs = SCPreferencesCreate(nil, "proxy4you session helper" as CFString, nil)
    else { throw ProxyFailure.unavailable }
    // Never wait indefinitely for another process to release network preferences.
    let deadline = ProcessInfo.processInfo.systemUptime + 3
    while !SCPreferencesLock(prefs, false) {
      guard ProcessInfo.processInfo.systemUptime < deadline else { throw ProxyFailure.unavailable }
      Thread.sleep(forTimeInterval: 0.05)
    }
    defer { SCPreferencesUnlock(prefs) }
    guard let services = SCNetworkServiceCopyAll(prefs) as? [SCNetworkService] else {
      throw ProxyFailure.unavailable
    }
    var protocols: [String: SCNetworkProtocol] = [:]
    var configs: ProxyConfigurations = [:]
    for service in services where SCNetworkServiceGetEnabled(service) {
      guard let id = SCNetworkServiceGetServiceID(service),
        let proto = SCNetworkServiceCopyProtocol(service, kSCNetworkProtocolTypeProxies)
      else { continue }
      protocols[id as String] = proto
      configs[id as String] = SCNetworkProtocolGetConfiguration(proto) as? [String: Any] ?? [:]
    }
    let original = configs
    try change(&configs)
    var changed = false
    for (id, config) in configs where !(config as NSDictionary).isEqual(to: original[id] ?? [:]) {
      guard let proto = protocols[id],
        SCNetworkProtocolSetConfiguration(proto, config as CFDictionary)
      else { throw ProxyFailure.commitFailed }
      changed = true
    }
    try commits.finish(changed: changed,
      commit: { SCPreferencesCommitChanges(prefs) }, apply: { SCPreferencesApplyChanges(prefs) })
  }
}

// The child has only three commands. It cannot execute programs or access caller-selected files.
final class ProxySession {
  let store: ProxyStore
  var backups: ProxyConfigurations = [:]
  var port: Int?
  init(store: ProxyStore) { self.store = store }

  func matches(_ config: [String: Any], port: Int) -> Bool {
    ["HTTP", "HTTPS", "SOCKS"].allSatisfy {
      config[$0 + "Proxy"] as? String == "127.0.0.1" && config[$0 + "Port"] as? Int == port
    }
  }

  func enable(port newPort: Int) throws -> ProxyConfigurations {
    guard (1...65535).contains(newPort) else { throw ProxyFailure.invalidRequest }
    // A failed commit can still have written preferences: retain recovery state before committing.
    try store.transaction { configs in
      guard !configs.isEmpty else { throw ProxyFailure.unavailable }
      for (id, var config) in configs {
        if backups[id] == nil { backups[id] = config }
        for prefix in ["HTTP", "HTTPS", "SOCKS"] {
          config[prefix + "Enable"] = 1
          config[prefix + "Proxy"] = "127.0.0.1"
          config[prefix + "Port"] = newPort
        }
        config["ProxyAutoConfigEnable"] = 0
        config["ProxyAutoDiscoveryEnable"] = 0
        configs[id] = config
      }
      port = newPort
    }
    return backups
  }

  func restore(previous: ProxyConfigurations, previousPort: Int) throws {
    guard (1...65535).contains(previousPort),
      PropertyListSerialization.propertyList(previous, isValidFor: .binary)
    else { throw ProxyFailure.invalidRequest }
    try store.transaction { configs in
      for (id, old) in previous {
        if let config = configs[id], matches(config, port: previousPort) { configs[id] = old }
      }
    }
  }

  func cleanup() throws {
    if let port = port, !backups.isEmpty {
      try restore(previous: backups, previousPort: port)
    }
    backups = [:]
    port = nil
  }

  func handle(_ request: [String: Any]) throws -> [String: Any] {
    switch request["command"] as? String {
    case "enable":
      guard let port = request["port"] as? Int else { throw ProxyFailure.invalidRequest }
      return ["backups": try enable(port: port)]
    case "restore":
      guard let previous = request["backups"] as? ProxyConfigurations,
        let previousPort = request["port"] as? Int
      else { throw ProxyFailure.invalidRequest }
      try restore(previous: previous, previousPort: previousPort)
      try cleanup()
      return [:]
    case "finish":
      try cleanup()
      return [:]
    default: throw ProxyFailure.invalidRequest
    }
  }
}

func readRequest() -> Data? {
  var data = Data()
  var byte: UInt8 = 0
  while true {
    let received = read(STDIN_FILENO, &byte, 1)
    if received < 0 && errno == EINTR { continue }
    guard received == 1 else { return nil }
    if byte == 10 { return data }
    data.append(byte)
    if data.count > 262144 { return nil }
  }
  return nil
}

func send(_ response: [String: Any]) {
  guard var data = try? JSONSerialization.data(withJSONObject: response, options: [.sortedKeys])
  else { return }
  data.append(10)
  data.withUnsafeBytes { buffer in
    var offset = 0
    while offset < buffer.count {
      let count = write(
        STDOUT_FILENO, buffer.baseAddress!.advanced(by: offset), buffer.count - offset)
      if count <= 0 { break }
      offset += count
    }
  }
}

// Tests use an in-memory network store, never system preferences or elevated privileges.
final class MemoryProxyStore: ProxyStore {
  var configs: ProxyConfigurations = [
    "wifi": ["HTTPEnable": 1, "HTTPProxy": "existing-proxy", "HTTPPort": 8080],
    "ethernet": [:],
  ]
  var failNext = false
  func transaction(_ change: (inout ProxyConfigurations) throws -> Void) throws {
    if failNext { failNext = false; throw ProxyFailure.unavailable }
    try change(&configs)
  }
}

func selfTest() throws {
  let commits = ProxyCommitController()
  var applyCalls = 0
  try commits.finish(changed: false, commit: { preconditionFailure("Unnecessary commit") },
    apply: { applyCalls += 1; return true })
  do {
    try commits.finish(changed: true, commit: { true }, apply: { false })
    preconditionFailure("Failed apply accepted")
  } catch ProxyFailure.commitFailed {}
  precondition(commits.needsApply)
  try commits.finish(changed: false, commit: { preconditionFailure("Repeated commit") },
    apply: { applyCalls += 1; return true })
  precondition(!commits.needsApply && applyCalls == 2)
  try commits.finish(changed: false, commit: { preconditionFailure("Repeated commit") },
    apply: { preconditionFailure("Unnecessary apply") })
  let store = MemoryProxyStore()
  let original = store.configs
  let session = ProxySession(store: store)
  let backup = try session.enable(port: 12334)
  precondition((backup as NSDictionary).isEqual(to: original))
  precondition(session.matches(store.configs["wifi"]!, port: 12334))
  _ = try session.enable(port: 12335)
  precondition((session.backups as NSDictionary).isEqual(to: original))
  try session.cleanup()
  precondition((store.configs as NSDictionary).isEqual(to: original))
  _ = try session.enable(port: 12334)
  store.configs["wifi"] = ["HTTPProxy": "other-app", "HTTPPort": 8080]
  try session.cleanup()
  precondition(store.configs["wifi"]?["HTTPProxy"] as? String == "other-app")
  precondition(store.configs["ethernet"]!.isEmpty)
  do {
    _ = try session.enable(port: 0)
    preconditionFailure("Invalid port accepted")
  } catch ProxyFailure.invalidRequest {}
  do {
    _ = try session.handle(["command": "execute", "path": "/bin/sh"])
    preconditionFailure("Unknown command accepted")
  } catch ProxyFailure.invalidRequest {}
  _ = try session.enable(port: 12334)
  let retryBackup = session.backups
  store.failNext = true
  do {
    _ = try session.handle(["command": "restore", "backups": retryBackup, "port": 12334])
    preconditionFailure("Simulated busy network store accepted")
  } catch ProxyFailure.unavailable {}
  precondition(!session.backups.isEmpty)
  _ = try session.handle(["command": "restore", "backups": retryBackup, "port": 12334])
  precondition(session.backups.isEmpty)
  // The helper's lifetime, rather than a 30-second authorization credential, controls access.
  for _ in 0..<20 {
    _ = try session.handle(["command": "enable", "port": 12334])
    _ = try session.handle(["command": "restore", "backups": session.backups, "port": 12334])
  }
  print(
    "PASS: enable, repeated port changes, restore, external changes, invalid commands, restore retry after busy store, apply retry after committed restore, 20 reconnects"
  )
}

if CommandLine.arguments == [CommandLine.arguments[0], "--self-test"] {
  try selfTest()
} else {
  let testTransport = CommandLine.arguments == [CommandLine.arguments[0], "--test-transport"]
  guard testTransport || (geteuid() == 0 && CommandLine.arguments.count == 1) else { exit(77) }
  signal(SIGPIPE, SIG_IGN)
  let store: ProxyStore = testTransport ? MemoryProxyStore() : NetworkProxyStore()
  let session = ProxySession(store: store)
  send(["ok": true, "ready": true])
  while let data = readRequest() {
    do {
      guard let request = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
        throw ProxyFailure.invalidRequest
      }
      let response = try session.handle(request)
      send(response.merging(["ok": true]) { _, value in value })
      if request["command"] as? String == "finish" { break }
    } catch {
      send([
        "ok": false, "error": "Не удалось применить системный прокси macOS.",
        "backups": session.backups, "port": session.port ?? 0,
      ])
    }
  }
  // EOF also runs after an app crash: restore and leave no permanent privileged service.
  try? session.cleanup()
}
