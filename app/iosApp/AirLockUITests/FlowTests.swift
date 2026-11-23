import XCTest

final class FlowTests: XCTestCase {
    func testFirstRunShowsBluetoothBeforeAnyPinAndKeepsLanguage() throws {
        let app = XCUIApplication()
        app.launchEnvironment["AIRLOCK_TEST_VAULT"] = UUID().uuidString
        app.launchArguments = ["-AppleLanguages", "(pt-BR)", "-AppleLocale", "pt_BR"]
        app.launch()
        XCTAssertTrue(
            app.staticTexts["Inicializar carteira"].waitForExistence(timeout: 30),
            app.debugDescription)
        XCTAssertTrue(app.buttons["Conectar e sincronizar carteira"].exists)
        XCTAssertEqual(app.secureTextFields.count, 0)
        let theme = app.descendants(matching: .any).matching(identifier: "theme-selector")
            .firstMatch
        let language = app.descendants(matching: .any).matching(identifier: "language-selector")
            .firstMatch
        XCTAssertEqual(theme.frame.midY, language.frame.midY, accuracy: 2)
        for (code, syncLabel) in [
            ("es", "Conectar y sincronizar cartera"),
            ("fr", "Connecter et synchroniser"),
            ("de", "Verbinden und synchronisieren"),
            ("it", "Connetti e sincronizza"),
            ("zh", "连接并同步钱包"),
            ("pt", "Conectar e sincronizar carteira"),
            ("en", "Connect & Sync Wallet"),
        ] {
            guard selectLanguage(code, in: app) else { return }
            guard app.buttons[syncLabel].waitForExistence(timeout: 10) else {
                XCTFail("Language \(code) did not update the sync screen. \(app.debugDescription)")
                return
            }
        }
        app.terminate()
        app.launch()
        XCTAssertTrue(
            app.buttons["Connect & Sync Wallet"].waitForExistence(timeout: 30), app.debugDescription
        )
        XCTAssertEqual(app.secureTextFields.count, 0)
    }

    private func selectLanguage(_ code: String, in app: XCUIApplication) -> Bool {
        let selector = app.descendants(matching: .any).matching(identifier: "language-selector")
            .firstMatch
        let privacyCover = app.staticTexts["BJJ-App-Wallet"]
        let ready = XCTNSPredicateExpectation(
            predicate: NSPredicate { _, _ in
                selector.exists && selector.isEnabled && selector.isHittable && !privacyCover.exists
            }, object: nil)
        guard XCTWaiter.wait(for: [ready], timeout: 10) == .completed else {
            XCTFail("Language selector unavailable. \(app.debugDescription)")
            return false
        }
        selector.tap()
        let option = app.descendants(matching: .any).matching(identifier: "language-\(code)")
            .firstMatch
        guard option.waitForExistence(timeout: 10) else {
            XCTFail("Language \(code) unavailable. \(app.debugDescription)")
            return false
        }
        option.tap()
        return true
    }

}
