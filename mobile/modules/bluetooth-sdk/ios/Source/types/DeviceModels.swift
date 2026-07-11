import Foundation

public struct MentraBluetoothSDKConfiguration {
    public static let `default` = MentraBluetoothSDKConfiguration()

    public let analytics: BluetoothSdkAnalyticsConfiguration

    /// When non-nil, central managers are created with
    /// `CBCentralManagerOptionRestoreIdentifierKey` set to this value, opting
    /// into CoreBluetooth state preservation & restoration so the system can
    /// relaunch the app in the background to resume BLE work. Nil (default)
    /// disables restoration — the current behavior. Opt-in and purpose-agnostic.
    public let bluetoothRestoreIdentifier: String?

    public init(
        analytics: BluetoothSdkAnalyticsConfiguration = BluetoothSdkAnalyticsConfiguration(),
        bluetoothRestoreIdentifier: String? = nil
    ) {
        self.analytics = analytics
        self.bluetoothRestoreIdentifier = bluetoothRestoreIdentifier
    }
}

public enum DeviceModel: String {
    case g1
    case g2
    case mentraLive
    case mentraNex
    case mach1
    case z100
    case frame
    case simulated
    case r1

    public var deviceType: String {
        switch self {
        case .g1:
            DeviceTypes.G1
        case .g2:
            DeviceTypes.G2
        case .mentraLive:
            DeviceTypes.LIVE
        case .mentraNex:
            DeviceTypes.NEX
        case .mach1:
            DeviceTypes.MACH1
        case .z100:
            DeviceTypes.Z100
        case .frame:
            DeviceTypes.FRAME
        case .simulated:
            DeviceTypes.SIMULATED
        case .r1:
            ControllerTypes.R1
        }
    }

    public static func fromDeviceType(_ deviceType: String?) -> DeviceModel {
        switch deviceType {
        case DeviceTypes.G1:
            .g1
        case DeviceTypes.G2:
            .g2
        case DeviceTypes.LIVE:
            .mentraLive
        case DeviceTypes.NEX:
            .mentraNex
        case DeviceTypes.MACH1:
            .mach1
        case DeviceTypes.Z100:
            .z100
        case DeviceTypes.FRAME:
            .frame
        case DeviceTypes.SIMULATED:
            .simulated
        case ControllerTypes.R1:
            .r1
        default:
            .mentraLive
        }
    }
}

public struct Device: Identifiable, Equatable, CustomStringConvertible {
    public let model: DeviceModel
    public let name: String
    /// CoreBluetooth identifier when available.
    public let identifier: String?
    public let rssi: Int?
    /// Stable app-facing scan-result key. Do not parse; use typed fields instead.
    public let id: String

    public init(
        model: DeviceModel,
        name: String,
        identifier: String? = nil,
        rssi: Int? = nil,
        id: String? = nil
    ) {
        self.model = model
        self.name = name
        self.identifier = identifier
        self.rssi = rssi
        self.id = id ?? identifier.flatMap { $0.isEmpty ? nil : $0 } ?? "\(model.deviceType):\(name)"
    }

    public var description: String {
        "Device(model: \(model), name: \(name))"
    }

    var dictionary: [String: Any] {
        var values: [String: Any] = [
            "id": id,
            "model": model.deviceType,
            "name": name,
        ]
        if let identifier, !identifier.isEmpty {
            values["address"] = identifier
        }
        if let rssi {
            values["rssi"] = rssi
        }
        return values
    }

    init?(values: [String: Any]) {
        guard let model = stringValue(values, "model") else { return nil }
        guard let name = stringValue(values, "name") else { return nil }
        let identifier = stringValue(values, "address").flatMap { $0.isEmpty ? nil : $0 }
        let rssi = intValue(values["rssi"])
        self.init(
            model: DeviceModel.fromDeviceType(model),
            name: name,
            identifier: identifier,
            rssi: rssi,
            id: stringValue(values, "id")
        )
    }
}

/// Process-wide iOS background-reconnect switches, set by the SDK entry points
/// and read by the CoreBluetooth controllers at manager-creation / connect time.
/// Kept as static state — consistent with `DeviceManager.shared` /
/// `DeviceStore.shared` — so the opt-in flags reach the controllers without
/// threading a parameter through `connectByName` / `connectById`.
enum BluetoothBackgroundConfig {
    /// Non-nil enables CoreBluetooth state preservation & restoration; used as
    /// the `CBCentralManagerOptionRestoreIdentifierKey`. Set once at SDK init,
    /// so it is in place before any central manager is created (including on a
    /// background relaunch, where the SDK is re-initialized early).
    static var restoreIdentifier: String?

    /// When true, connects are left pending (no app-level connection timeout) so
    /// CoreBluetooth reconnects the peripheral on its own when it reappears,
    /// including while the app is backgrounded. Set per `connect()` call.
    static var backgroundReconnect: Bool = false
}

public struct ConnectOptions {
    public let saveAsDefault: Bool
    public let cancelExistingConnectionAttempt: Bool

    /// When true, the connection request is left pending so CoreBluetooth
    /// reconnects the peripheral on its own when it reappears — including while
    /// the app is backgrounded — instead of giving up after the app-level
    /// connection timeout. Default false preserves the timeout-bounded connect.
    public let backgroundReconnect: Bool

    public init(
        saveAsDefault: Bool = true,
        cancelExistingConnectionAttempt: Bool = true,
        backgroundReconnect: Bool = false
    ) {
        self.saveAsDefault = saveAsDefault
        self.cancelExistingConnectionAttempt = cancelExistingConnectionAttempt
        self.backgroundReconnect = backgroundReconnect
    }
}
