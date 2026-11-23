import AVFoundation
import AirLockShared
import CoreImage.CIFilterBuiltins
import CryptoKit
import LocalAuthentication
import SwiftUI

@main
struct AirLockApp: App {
    @Environment(\.scenePhase) private var scenePhase
    private let storage = AppleVault()
    private let crypto = ApplePinCrypto()
    var body: some Scene {
        WindowGroup {
            ZStack {
                ComposeHost(storage: storage, crypto: crypto).ignoresSafeArea()
                if scenePhase != .active {
                    Color.white.ignoresSafeArea().overlay(
                        Text("BJJ-App-Wallet").font(.title).foregroundStyle(.blue))
                }
            }
        }
    }
}

struct ComposeHost: UIViewControllerRepresentable {
    let storage: AppleVault
    let crypto: ApplePinCrypto
    func makeUIViewController(context: Context) -> UIViewController {
        #if DEBUG
            let debug = true
        #else
            let debug = false
        #endif
        return MainViewControllerKt.MainViewController(
            storage: storage, crypto: crypto, debug: debug,
            actions: AppleAddressActions(), biometrics: AppleAppBiometrics(),
            ed25519Verifier: AppleEd25519Verifier())
    }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}

private final class AppleEd25519Verifier: Ed25519Verifier {
    func verify(publicKey: KotlinByteArray, message: KotlinByteArray, signature: KotlinByteArray)
        -> Bool
    {
        func data(_ bytes: KotlinByteArray) -> Data {
            Data((0..<bytes.size).map { UInt8(bitPattern: bytes.get(index: $0)) })
        }
        guard let key = try? Curve25519.Signing.PublicKey(rawRepresentation: data(publicKey)) else {
            return false
        }
        return key.isValidSignature(data(signature), for: data(message))
    }
}

final class AppleAddressActions: AddressActions {
    func openAppSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }

    func scanQrCode(
        prompt: String, cancelLabel: String, completion: @escaping (QrScanResult) -> Void
    ) {
        func presentScanner() {
            guard
                let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }
                ).first(where: { $0.activationState == .foregroundActive }),
                var presenter = scene.windows.first(where: { $0.isKeyWindow })?.rootViewController
            else {
                completion(QrScanResult(text: nil, permissionDenied: false, failed: true))
                return
            }
            while let presented = presenter.presentedViewController { presenter = presented }
            let scanner = RecipientQrController(
                prompt: prompt, cancelLabel: cancelLabel, completion: completion)
            // Keep Compose attached so reading a QR does not lock or discard the draft.
            scanner.modalPresentationStyle = .overFullScreen
            presenter.present(scanner, animated: true)
        }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: presentScanner()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { allowed in
                DispatchQueue.main.async {
                    if allowed {
                        presentScanner()
                    } else {
                        completion(QrScanResult(text: nil, permissionDenied: true, failed: false))
                    }
                }
            }
        default: completion(QrScanResult(text: nil, permissionDenied: true, failed: false))
        }
    }

    func qrCode(text: String) -> KotlinByteArray {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage,
            let cgImage = CIContext().createCGImage(
                output.transformed(by: CGAffineTransform(scaleX: 10, y: 10)),
                from: output.extent.applying(CGAffineTransform(scaleX: 10, y: 10))),
            let data = UIImage(cgImage: cgImage).pngData()
        else { return KotlinByteArray(size: 0) }
        let bytes = KotlinByteArray(size: Int32(data.count))
        for (index, byte) in data.enumerated() {
            bytes.set(index: Int32(index), value: Int8(bitPattern: byte))
        }
        return bytes
    }
    func share(text: String) {
        guard
            let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene })
                .first,
            var presenter = scene.windows.first(where: { $0.isKeyWindow })?.rootViewController
        else { return }
        while let presented = presenter.presentedViewController { presenter = presented }
        let sheet = UIActivityViewController(activityItems: [text], applicationActivities: nil)
        if let popover = sheet.popoverPresentationController {
            popover.sourceView = presenter.view
            popover.sourceRect = CGRect(
                x: presenter.view.bounds.midX, y: presenter.view.bounds.midY, width: 1, height: 1)
        }
        presenter.present(sheet, animated: true)
    }
}

final class AppleAppBiometrics: AppBiometrics {
    func available() -> Bool {
        LAContext().canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
    }
    func authenticate(
        reason: String, pinLabel: String, completion: @escaping (KotlinBoolean) -> Void
    ) {
        let context = LAContext()
        context.localizedFallbackTitle = pinLabel
        guard context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil) else {
            completion(KotlinBoolean(bool: false))
            return
        }
        // LocalAuthentication selects Face ID first on devices that support it, otherwise Touch ID.
        context.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: reason) {
            accepted, _ in
            DispatchQueue.main.async { completion(KotlinBoolean(bool: accepted)) }
        }
    }
}

/// Decodes locally without recording photos/video or opening a payment link.
private final class RecipientQrController: UIViewController, AVCaptureMetadataOutputObjectsDelegate
{
    private let session = AVCaptureSession()
    private let captureQueue = DispatchQueue(label: "pub.airlock.recipient-qr")
    private var previewLayer: AVCaptureVideoPreviewLayer!
    private let prompt: String
    private let cancelLabel: String
    private var completion: ((QrScanResult) -> Void)?
    private var observers: [NSObjectProtocol] = []

    init(prompt: String, cancelLabel: String, completion: @escaping (QrScanResult) -> Void) {
        self.prompt = prompt
        self.cancelLabel = cancelLabel
        self.completion = completion
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    deinit { observers.forEach { NotificationCenter.default.removeObserver($0) } }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        previewLayer = AVCaptureVideoPreviewLayer(session: session)
        previewLayer.videoGravity = .resizeAspectFill
        view.layer.addSublayer(previewLayer)
        let label = UILabel()
        label.text = prompt
        label.textColor = .white
        label.backgroundColor = .black
        label.numberOfLines = 0
        label.font = .preferredFont(forTextStyle: .body)
        label.adjustsFontForContentSizeCategory = true
        let cancel = UIButton(type: .system)
        cancel.setTitle(cancelLabel, for: .normal)
        cancel.titleLabel?.font = .preferredFont(forTextStyle: .headline)
        cancel.titleLabel?.adjustsFontForContentSizeCategory = true
        cancel.backgroundColor = .black
        cancel.addTarget(self, action: #selector(cancelScan), for: .touchUpInside)
        for item in [label, cancel] {
            item.translatesAutoresizingMaskIntoConstraints = false
            view.addSubview(item)
        }
        NSLayoutConstraint.activate([
            label.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 16),
            label.leadingAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 16),
            label.trailingAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -16),
            cancel.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            cancel.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor),
            cancel.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor),
            cancel.heightAnchor.constraint(greaterThanOrEqualToConstant: 52),
        ])
        observers.append(
            NotificationCenter.default.addObserver(
                forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main
            ) { [weak self] _ in
                self?.finish(QrScanResult(text: nil, permissionDenied: false, failed: false))
            })
        observers.append(
            NotificationCenter.default.addObserver(
                forName: .AVCaptureSessionRuntimeError, object: session, queue: .main
            ) { [weak self] _ in
                self?.finish(QrScanResult(text: nil, permissionDenied: false, failed: true))
            })
        captureQueue.async { [weak self] in self?.configureCamera() }
    }

    private func configureCamera() {
        do {
            guard let device = AVCaptureDevice.default(for: .video) else {
                throw CameraError.unavailable
            }
            let input = try AVCaptureDeviceInput(device: device)
            let output = AVCaptureMetadataOutput()
            session.beginConfiguration()
            guard session.canAddInput(input), session.canAddOutput(output) else {
                session.commitConfiguration()
                throw CameraError.unavailable
            }
            session.addInput(input)
            session.addOutput(output)
            guard output.availableMetadataObjectTypes.contains(.qr) else {
                session.commitConfiguration()
                throw CameraError.unavailable
            }
            output.setMetadataObjectsDelegate(self, queue: .main)
            output.metadataObjectTypes = [.qr]
            session.commitConfiguration()
            session.startRunning()
        } catch {
            DispatchQueue.main.async { [weak self] in
                self?.finish(
                    QrScanResult(
                        text: nil,
                        permissionDenied: AVCaptureDevice.authorizationStatus(for: .video)
                            != .authorized, failed: true))
            }
        }
    }
    private enum CameraError: Error { case unavailable }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer.frame = view.bounds
        if let connection = previewLayer.connection, connection.isVideoOrientationSupported,
            let orientation = view.window?.windowScene?.interfaceOrientation
        {
            switch orientation {
            case .landscapeLeft: connection.videoOrientation = .landscapeLeft
            case .landscapeRight: connection.videoOrientation = .landscapeRight
            case .portraitUpsideDown: connection.videoOrientation = .portraitUpsideDown
            default: connection.videoOrientation = .portrait
            }
        }
    }
    func metadataOutput(
        _ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard
            let code = metadataObjects.compactMap({ $0 as? AVMetadataMachineReadableCodeObject })
                .first(where: { $0.type == .qr }),
            let text = code.stringValue
        else { return }
        finish(QrScanResult(text: text, permissionDenied: false, failed: false))
    }
    @objc private func cancelScan() {
        finish(QrScanResult(text: nil, permissionDenied: false, failed: false))
    }
    private func finish(_ result: QrScanResult) {
        guard let callback = completion else { return }
        completion = nil
        let session = session
        captureQueue.async { if session.isRunning { session.stopRunning() } }
        dismiss(animated: true) { callback(result) }
    }
}
