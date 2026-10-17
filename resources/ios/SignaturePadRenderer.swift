import SwiftUI
import UIKit

/// Signature pad: the user signs with a finger, and after every stroke the whole drawing goes
/// to PHP as a PNG data URL through `on_change` (an empty string once cleared).
///
/// The drawing stays here, in view state; PHP only ever receives it. While the pad has no
/// strokes of its own it shows the `value` PHP holds, so a signature survives the screen being
/// rebuilt. When the pad is turned or resized, what was drawn becomes a backdrop, shown at its
/// own size (scaled down only where it no longer fits). Strokes drawn on a shown signature add to
/// it; only the clear button starts over.
///
/// The exported PNG is cropped to what was drawn, transparent, at most 1200 px wide, and drawn in
/// `export_color` (black by default) whatever the stroke looks like on screen, so a signature
/// given in dark mode still prints.
///
/// With `allow_rotation`, the screen may turn to landscape while the pad is on it, even in an app
/// that is otherwise portrait only (see NativeSignOrientation).
struct SignaturePadRenderer: View {
    let node: NativeUINode

    @State private var strokes: [[CGPoint]] = []
    @State private var currentStroke: [CGPoint] = []
    @State private var dismissedValue = false
    /// What was drawn before the pad was turned or rebuilt, kept under the new strokes.
    @State private var backdrop: UIImage? = nil

    /// Decoded `value` images, so the same data URL is decoded once rather than on every render.
    private static let decodedValues = NSCache<NSString, UIImage>()

    /// The widest exported PNG, in pixels.
    private static let maxExportWidth: CGFloat = 1200

    var body: some View {
        let p = node.props
        let strokeColor = Color(argb: p.getColor("stroke_color", default: 0xFF1C1C1E))
        let lineColor = Color(argb: p.getColor("line_color", default: 0x668E8E93))
        let exportColor = UIColor(argb: p.getColor("export_color", default: 0xFF000000))
        let strokeWidth = CGFloat(p.getFloat("stroke_width", default: 3))
        let placeholder = p.getString("placeholder")
        let clearLabel = p.getString("clear_label", default: "Clear")
        let accentColor = p.has("accent_color") ? Color(argb: p.getColor("accent_color")) : Color.accentColor
        let disabled = p.getBool("disabled")
        let allowsRotation = p.getBool("allow_rotation")
        let onChange = p.getCallbackId("on_change")
        let a11yLabel = p.getString("a11y_label")
        let a11yHint = p.getString("a11y_hint")
        let valueImage = (dismissedValue || backdrop != nil || !strokes.isEmpty || !currentStroke.isEmpty)
            ? nil
            : Self.decode(p.getString("value"))
        let shownBackdrop = backdrop ?? valueImage
        let hasDrawing = !strokes.isEmpty || !currentStroke.isEmpty || shownBackdrop != nil

        GeometryReader { geo in
            ZStack {
                // The signature line
                Path { path in
                    let y = geo.size.height * 0.75
                    path.move(to: CGPoint(x: 20, y: y))
                    path.addLine(to: CGPoint(x: max(20, geo.size.width - 20), y: y))
                }
                .stroke(lineColor, lineWidth: 1)

                if !hasDrawing && !placeholder.isEmpty {
                    Text(placeholder)
                        .font(.subheadline)
                        .foregroundStyle(lineColor)
                }

                if let shownBackdrop {
                    let rect = Self.backdropRect(shownBackdrop, in: geo.size)
                    Image(uiImage: shownBackdrop)
                        .renderingMode(.template)
                        .resizable()
                        .foregroundStyle(strokeColor)
                        .frame(width: rect.width, height: rect.height)
                        .position(x: rect.midX, y: rect.midY)
                }

                // The drawing surface alone takes the strokes, so the clear button above it keeps its taps
                Canvas { context, _ in
                    for stroke in strokes + [currentStroke] where !stroke.isEmpty {
                        context.stroke(
                            Self.path(stroke),
                            with: .color(strokeColor),
                            style: StrokeStyle(lineWidth: strokeWidth, lineCap: .round, lineJoin: .round)
                        )
                    }
                }
                .contentShape(Rectangle())
                // High priority, so signing inside a scroll view draws instead of scrolling
                .highPriorityGesture(
                    DragGesture(minimumDistance: 0, coordinateSpace: .local)
                        .onChanged { value in
                            if strokes.isEmpty && currentStroke.isEmpty && !dismissedValue {
                                // Strokes add to the signature shown from the value
                                backdrop = backdrop ?? valueImage
                                dismissedValue = true
                            }
                            currentStroke.append(Self.clamp(value.location, to: geo.size))
                        }
                        .onEnded { _ in
                            guard !currentStroke.isEmpty else { return }
                            var finished = currentStroke
                            if finished.count == 1 {
                                // A tap leaves a dot
                                finished.append(CGPoint(x: finished[0].x + 0.5, y: finished[0].y + 0.5))
                            }
                            strokes.append(finished)
                            currentStroke = []
                            send(onChange, Self.export(backdrop: backdrop, strokes: strokes, size: geo.size, color: exportColor, width: strokeWidth))
                        },
                    including: disabled ? .none : .all
                )

                if hasDrawing && !disabled {
                    VStack {
                        HStack {
                            Spacer()
                            Button(clearLabel) {
                                strokes = []
                                currentStroke = []
                                backdrop = nil
                                dismissedValue = true
                                send(onChange, "")
                            }
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(accentColor)
                            .padding(10)
                        }
                        Spacer()
                    }
                }
            }
            .onChange(of: geo.size) { oldSize, newSize in
                // Turned or resized: new strokes no longer fit, so they join the backdrop, which is
                // shown at its own size — a drawing without new strokes is left as it is, so going
                // back and forth never shrinks it
                if !strokes.isEmpty && oldSize != newSize && oldSize.width > 0 {
                    backdrop = Self.render(backdrop: backdrop, strokes: strokes, size: oldSize, color: exportColor, width: strokeWidth)
                    strokes = []
                    currentStroke = []
                }
            }
        }
        .frame(maxWidth: .infinity, minHeight: 120)
        .onAppear {
            if allowsRotation {
                NativeSignOrientation.allowRotation(for: node.id)
            }
        }
        .onDisappear {
            NativeSignOrientation.endRotation(for: node.id)
        }
        .onChange(of: allowsRotation) { _, allows in
            if allows {
                NativeSignOrientation.allowRotation(for: node.id)
            } else {
                NativeSignOrientation.endRotation(for: node.id)
            }
        }
        // Contain, not combine: the clear button has to stay reachable for VoiceOver
        .accessibilityElement(children: .contain)
        .accessibilityLabel(a11yLabel.isEmpty ? placeholder : a11yLabel)
        .accessibilityHint(a11yHint)
    }

    // MARK: - Events

    private func send(_ callbackId: Int, _ text: String) {
        if callbackId != 0 {
            NativeElementBridge.sendTextChangeEvent(callbackId, nodeId: node.id, text: text)
        }
    }

    // MARK: - Drawing

    private static func clamp(_ point: CGPoint, to size: CGSize) -> CGPoint {
        CGPoint(x: min(max(point.x, 0), size.width), y: min(max(point.y, 0), size.height))
    }

    /// Where a backdrop sits in the pad: centred, at its own size, scaled down only to fit.
    private static func backdropRect(_ image: UIImage, in size: CGSize) -> CGRect {
        guard image.size.width > 0, image.size.height > 0 else { return .zero }

        let scale = min(1, size.width / image.size.width, size.height / image.size.height)
        let fitted = CGSize(width: image.size.width * scale, height: image.size.height * scale)

        return CGRect(
            origin: CGPoint(x: (size.width - fitted.width) / 2, y: (size.height - fitted.height) / 2),
            size: fitted
        )
    }

    /// A smooth line through the points: quadratic curves between their midpoints.
    private static func path(_ points: [CGPoint]) -> Path {
        var path = Path()
        guard let first = points.first else { return path }
        path.move(to: first)

        if points.count < 3 {
            points.dropFirst().forEach { path.addLine(to: $0) }
            return path
        }

        for index in 1..<points.count {
            let previous = points[index - 1]
            let current = points[index]
            path.addQuadCurve(
                to: CGPoint(x: (previous.x + current.x) / 2, y: (previous.y + current.y) / 2),
                control: previous
            )
        }
        path.addLine(to: points[points.count - 1])

        return path
    }

    /// The backdrop with the strokes on top, cropped to what was drawn: transparent, at twice the
    /// pad's scale (less where that would be wider than maxExportWidth).
    private static func render(backdrop: UIImage?, strokes: [[CGPoint]], size: CGSize, color: UIColor, width: CGFloat) -> UIImage? {
        guard size.width > 0, size.height > 0 else { return nil }

        let backdropFrame = backdrop.map { backdropRect($0, in: size) }
        var bounds = backdropFrame

        for point in strokes.joined() {
            let dot = CGRect(origin: point, size: .zero)
            bounds = bounds.map { $0.union(dot) } ?? dot
        }

        guard var drawn = bounds else { return nil }

        drawn = drawn.insetBy(dx: -(width + 4), dy: -(width + 4)).intersection(CGRect(origin: .zero, size: size))

        guard !drawn.isNull, drawn.width >= 1, drawn.height >= 1 else { return nil }

        let format = UIGraphicsImageRendererFormat()
        format.scale = min(2, maxExportWidth / drawn.width)
        format.opaque = false

        return UIGraphicsImageRenderer(size: drawn.size, format: format).image { rendererContext in
            let context = rendererContext.cgContext
            context.translateBy(x: -drawn.minX, y: -drawn.minY)

            if let backdrop, let backdropFrame {
                backdrop.draw(in: backdropFrame)
            }

            context.setStrokeColor(color.cgColor)
            context.setLineWidth(width)
            context.setLineCap(.round)
            context.setLineJoin(.round)

            for stroke in strokes {
                context.addPath(path(stroke).cgPath)
                context.strokePath()
            }
        }
    }

    /// The drawing as a transparent PNG data URL.
    private static func export(backdrop: UIImage?, strokes: [[CGPoint]], size: CGSize, color: UIColor, width: CGFloat) -> String {
        guard let data = render(backdrop: backdrop, strokes: strokes, size: size, color: color, width: width)?.pngData() else { return "" }

        return "data:image/png;base64," + data.base64EncodedString()
    }

    private static func decode(_ dataUrl: String) -> UIImage? {
        guard dataUrl.hasPrefix("data:image/png;base64,"), let comma = dataUrl.firstIndex(of: ",") else { return nil }

        if let cached = decodedValues.object(forKey: dataUrl as NSString) {
            return cached
        }

        guard let data = Data(base64Encoded: String(dataUrl[dataUrl.index(after: comma)...])),
              let image = UIImage(data: data, scale: 2) else { return nil }

        // A tiny file can still decode to an enormous canvas: refuse what no signature would be
        guard image.size.width * image.size.height * 4 <= 16_000_000 else { return nil }

        decodedValues.setObject(image, forKey: dataUrl as NSString)

        return image
    }
}
