import AirLockShared
import CommonCrypto
import CryptoKit
import Foundation
import Security

enum VaultError: LocalizedError {
    case invalidData, missingKey
    case keychain(OSStatus)
    case crypto(Int32)
    var errorDescription: String? {
        switch self {
        case .invalidData: return "The encrypted vault contains invalid data."
        case .missingKey: return "The vault encryption key is unavailable on this device."
        case .keychain(let code): return "Keychain access failed (OSStatus \(code))."
        case .crypto(let code): return "PIN derivation failed (status \(code))."
        }
    }
}

final class AppleVault: VaultStorage {
    private let namespace: String
    init() {
        #if DEBUG
            let testName = ProcessInfo.processInfo.environment["AIRLOCK_TEST_VAULT"]
            namespace = testName.flatMap { UUID(uuidString: $0)?.uuidString } ?? "demo"
        #else
            namespace = "demo"
        #endif
    }
    private var service: String { "pub.airlock.kmp.\(namespace).vault.v1" }
    private var fileURL: URL {
        get throws {
            let directory = try FileManager.default.url(
                for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil,
                create: true)
            return directory.appendingPathComponent("airlock-\(namespace)-v1.vault")
        }
    }
    private func key() throws -> SymmetricKey {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
            kSecAttrAccount as String: "encryption-key",
        ]
        var request = query
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(request as CFDictionary, &result)
        if status == errSecSuccess, let data = result as? Data {
            guard data.count == 32 else { throw VaultError.invalidData }
            return SymmetricKey(data: data)
        }
        guard status == errSecItemNotFound else { throw VaultError.keychain(status) }
        guard !FileManager.default.fileExists(atPath: try fileURL.path) else {
            throw VaultError.missingKey
        }
        let key = SymmetricKey(size: .bits256)
        var addition = query
        addition[kSecValueData as String] = key.withUnsafeBytes { Data($0) }
        addition[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let added = SecItemAdd(addition as CFDictionary, nil)
        guard added == errSecSuccess else { throw VaultError.keychain(added) }
        return key
    }
    func read() throws -> VaultRead {
        let url = try fileURL
        guard FileManager.default.fileExists(atPath: url.path) else {
            return VaultRead(contents: nil)
        }
        let data = try Data(contentsOf: url)
        guard data.first == 1 else { throw VaultError.invalidData }
        let box = try AES.GCM.SealedBox(combined: data.dropFirst())
        let plain = try AES.GCM.open(
            box, using: key(), authenticating: Data("airlock-demo-v1".utf8))
        guard let text = String(data: plain, encoding: .utf8) else { throw VaultError.invalidData }
        return VaultRead(contents: text)
    }
    func write(value: String) throws {
        let sealed = try AES.GCM.seal(
            Data(value.utf8), using: key(), authenticating: Data("airlock-demo-v1".utf8))
        guard let combined = sealed.combined else { throw VaultError.invalidData }
        var url = try fileURL
        try (Data([1]) + combined).write(to: url, options: [.atomic, .completeFileProtection])
        var flags = URLResourceValues()
        flags.isExcludedFromBackup = true
        try url.setResourceValues(flags)
    }
}

final class ApplePinCrypto: PinCrypto {
    func randomHex(bytes: Int32) throws -> String {
        guard bytes > 0 && bytes <= 1024 else { throw VaultError.invalidData }
        var buffer = [UInt8](repeating: 0, count: Int(bytes))
        let status = SecRandomCopyBytes(kSecRandomDefault, buffer.count, &buffer)
        guard status == errSecSuccess else { throw VaultError.keychain(status) }
        return buffer.map { String(format: "%02x", $0) }.joined()
    }
    func derive(pin: String, saltHex: String) throws -> String {
        guard saltHex.count.isMultiple(of: 2) else { throw VaultError.invalidData }
        var salt = [UInt8]()
        var cursor = saltHex.startIndex
        while cursor < saltHex.endIndex {
            let end = saltHex.index(cursor, offsetBy: 2)
            guard let byte = UInt8(saltHex[cursor..<end], radix: 16) else {
                throw VaultError.invalidData
            }
            salt.append(byte)
            cursor = end
        }
        var output = [UInt8](repeating: 0, count: 32)
        let status = pin.withCString { password in
            CCKeyDerivationPBKDF(
                CCPBKDFAlgorithm(kCCPBKDF2), password, pin.utf8.count, salt, salt.count,
                CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256), 100_000, &output, output.count)
        }
        guard status == kCCSuccess else { throw VaultError.crypto(status) }
        return output.map { String(format: "%02x", $0) }.joined()
    }
}
