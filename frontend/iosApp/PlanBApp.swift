import PlanBKit
import SwiftUI
import UIKit

@main
struct PlanBApp: App {
    private var apiOrigin: String {
        let origin = ProcessInfo.processInfo.environment["API_BASE_URL"]
            ?? Bundle.main.object(forInfoDictionaryKey: "PlanBApiBaseURL") as? String
            ?? ""
        return origin.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var body: some Scene {
        WindowGroup {
            ComposeHost(apiOrigin: apiOrigin)
        }
    }
}

private struct ComposeHost: UIViewControllerRepresentable {
    let apiOrigin: String

    func makeUIViewController(context: Context) -> UIViewController {
        PlanBIos.shared.makeViewController(apiBaseUrl: apiOrigin)
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}
