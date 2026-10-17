import ObjectiveC
import UIKit

/// Lets the screen turn while a signature pad that allows it is on screen, and keeps it in
/// portrait otherwise — in apps that ask for it.
///
/// Opt-in, so installing the plugin never changes an app's orientations: an app that wants only
/// its signature screens to turn declares landscape as supported (`nativephp.orientation`) and sets
/// the Info.plist key `NativeSignPortraitOutsidePads` to true (`nativephp.permissions`). Then, on
/// iPhone, every view controller is narrowed to what this says: portrait, or — while a pad with
/// `allow_rotation` is shown — anything but upside down. When the last such pad goes, the screen
/// is turned back to portrait. Without the key (and always on iPad) nothing is narrowed, and the
/// app's own orientations apply everywhere.
enum NativeSignOrientation {
    /// Whether the app asked for portrait outside signature pads.
    static let locksPortraitOutsidePads: Bool = {
        guard UIDevice.current.userInterfaceIdiom == .phone else { return false }

        switch Bundle.main.object(forInfoDictionaryKey: "NativeSignPortraitOutsidePads") {
        case let flag as Bool: return flag
        case let flag as NSNumber: return flag.boolValue
        case let flag as String: return ["1", "true", "yes"].contains(flag.lowercased())
        default: return false
        }
    }()

    /// How often each pad that allows turning is on screen, by node id. Counted rather than
    /// flagged: when SwiftUI rebuilds a pad, the new one appears before the old one disappears.
    private static var rotatingPads: [Int: Int] = [:]

    /// The view controller classes already narrowed, by name.
    private static var narrowedClasses = Set<String>()

    /// What every screen supports right now.
    static var mask: UIInterfaceOrientationMask {
        rotatingPads.isEmpty ? .portrait : .allButUpsideDown
    }

    static func allowRotation(for nodeId: Int) {
        guard locksPortraitOutsidePads else { return }

        rotatingPads[nodeId, default: 0] += 1
        update(turnBackToPortrait: false)
    }

    static func endRotation(for nodeId: Int) {
        guard locksPortraitOutsidePads, let count = rotatingPads[nodeId] else { return }

        rotatingPads[nodeId] = count > 1 ? count - 1 : nil
        update(turnBackToPortrait: rotatingPads.isEmpty)
    }

    /// Narrow a view controller class to `mask` — every class that answers for itself, since a
    /// subclass overriding the property would otherwise keep the app's full set.
    static func narrow(_ viewControllerClass: AnyClass) {
        let name = NSStringFromClass(viewControllerClass)
        let selector = #selector(getter: UIViewController.supportedInterfaceOrientations)

        guard !narrowedClasses.contains(name),
              let method = class_getInstanceMethod(viewControllerClass, selector) else { return }

        narrowedClasses.insert(name)

        let implementation: @convention(block) (AnyObject) -> UInt = { _ in
            NativeSignOrientation.mask.rawValue
        }
        class_replaceMethod(viewControllerClass, selector, imp_implementationWithBlock(implementation), method_getTypeEncoding(method))
    }

    private static func update(turnBackToPortrait: Bool) {
        for case let windowScene as UIWindowScene in UIApplication.shared.connectedScenes {
            for window in windowScene.windows {
                var controller = window.rootViewController

                while let current = controller {
                    narrow(type(of: current))
                    current.setNeedsUpdateOfSupportedInterfaceOrientations()
                    controller = current.presentedViewController
                }
            }

            if turnBackToPortrait {
                windowScene.requestGeometryUpdate(.iOS(interfaceOrientations: .portrait)) { _ in }
            }
        }
    }
}

/// Init function (nativephp.json): in an app that asked for it, screens stay in portrait from the
/// start unless a signature pad allows turning.
func registerNativeSignOrientation() {
    guard NativeSignOrientation.locksPortraitOutsidePads else { return }

    NativeSignOrientation.narrow(UIViewController.self)
}
