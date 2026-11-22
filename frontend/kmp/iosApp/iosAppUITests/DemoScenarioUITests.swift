import XCTest

/// Scripted demo for the screen recording: Lausanne -> Genève, results, then scroll through the
/// disruption / recommendation details. Pauses are deliberate so the video is readable.
/// The backend URL comes from TEST_RUNNER_ALT_API_BASE (xcodebuild passes it to the runner as ALT_API_BASE).
final class DemoScenarioUITests: XCTestCase {
    private let app = XCUIApplication()

    override func setUpWithError() throws {
        continueAfterFailure = true
        app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_CH"]
        if let base = ProcessInfo.processInfo.environment["ALT_API_BASE"], !base.isEmpty {
            app.launchEnvironment["ALT_API_BASE"] = base
        }
    }

    func testDemoLausanneToGeneva() throws {
        app.launch()
        pause(6, "welcome animation")
        snapshot("01-planner")

        type("Lausanne", into: 0, pick: "Lausanne")
        type("Geneve", into: 1, pick: "Gen")
        snapshot("02-filled")

        let search = app.staticTexts["Search journeys"]
        XCTAssertTrue(search.waitForExistence(timeout: 20), "search button")
        search.tap()

        // Wait until the loading label disappears (live backend, OJP + realtime can take a while).
        let loading = app.staticTexts["Searching journeys…"]
        let deadline = Date().addingTimeInterval(90)
        while loading.exists && Date() < deadline { pause(1) }
        pause(5, "results")
        snapshot("03-results")

        // Walk through the results: disruption status, recommendation, alternatives, risk badges.
        for step in 1...4 {
            app.swipeUp(velocity: .slow)
            pause(3, "scroll \(step)")
            snapshot(String(format: "04-scroll-%02d", step))
        }
        app.swipeDown(velocity: .fast)
        pause(4, "end")
    }

    // MARK: - helpers

    /// Types into the n-th station field and taps the first suggestion whose label starts with `pick`.
    private func type(_ text: String, into index: Int, pick prefix: String) {
        let field = stationField(index)
        field.tap()
        pause(1)
        app.typeText(text)
        pause(3, "suggestions for \(text)")
        let suggestion = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", prefix))
            .allElementsBoundByIndex
            .filter { $0.isHittable && $0.frame.minY > field.frame.maxY }
            .first
        if let suggestion { suggestion.tap() } else { app.tap() }
        pause(2)
    }

    /// Compose text fields are exposed as text fields; fall back to their placeholder text.
    private func stationField(_ index: Int) -> XCUIElement {
        let fields = app.textFields
        if fields.count > index { return fields.element(boundBy: index) }
        let placeholders = app.staticTexts.matching(NSPredicate(format: "label == %@", "Station or address"))
        return placeholders.count > 0 ? placeholders.element(boundBy: min(index, placeholders.count - 1)) : app
    }

    private func pause(_ seconds: TimeInterval, _ why: String = "") {
        _ = XCTWaiter.wait(for: [XCTestExpectation(description: why)], timeout: seconds)
    }

    private func snapshot(_ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }
}
