import AppKit
import CoreText
import Foundation

let root = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
let font = NSFont.systemFont(ofSize: 52, weight: .heavy)
let line = CTLineCreateWithAttributedString(NSAttributedString(string: "p4y", attributes: [.font: font]))
var glyphPaths: [CGPath] = []
for run in CTLineGetGlyphRuns(line) as NSArray {
  let run = run as! CTRun
  let attributes = CTRunGetAttributes(run) as NSDictionary
  let runFont = attributes[kCTFontAttributeName] as! CTFont
  for index in 0..<CTRunGetGlyphCount(run) {
    var glyph = CGGlyph()
    var position = CGPoint.zero
    CTRunGetGlyphs(run, CFRangeMake(index, 1), &glyph)
    CTRunGetPositions(run, CFRangeMake(index, 1), &position)
    guard let path = CTFontCreatePathForGlyph(runFont, glyph, nil) else { continue }
    var move = CGAffineTransform(translationX: position.x, y: position.y)
    glyphPaths.append(path.copy(using: &move)!)
  }
}
precondition(glyphPaths.count == 3)

let combined = CGMutablePath()
glyphPaths.forEach { combined.addPath($0) }
let bounds = combined.boundingBoxOfPath
let scale = min(54 / bounds.width, 47 / bounds.height)
let transform = CGAffineTransform(
  a: scale, b: 0, c: 0, d: scale,
  tx: (64 - bounds.width * scale) / 2 - bounds.minX * scale,
  ty: (64 - bounds.height * scale) / 2 - bounds.minY * scale
)
let letters = glyphPaths.map { path -> CGPath in
  let result = CGMutablePath()
  result.addPath(path, transform: transform)
  return result
}
let mark = CGMutablePath()
letters.forEach { mark.addPath($0) }

func number(_ value: CGFloat) -> String {
  String(format: "%.3f", locale: Locale(identifier: "en_US_POSIX"), Double(value))
}

func svgCommands(_ path: CGPath, flipY: Bool) -> String {
  var commands: [String] = []
  func point(_ p: CGPoint) -> String { "\(number(p.x)) \(number(flipY ? 64 - p.y : p.y))" }
  path.applyWithBlock { raw in
    let element = raw.pointee
    switch element.type {
    case .moveToPoint: commands.append("M\(point(element.points[0]))")
    case .addLineToPoint: commands.append("L\(point(element.points[0]))")
    case .addQuadCurveToPoint:
      commands.append("Q\(point(element.points[0])) \(point(element.points[1]))")
    case .addCurveToPoint:
      commands.append("C\(point(element.points[0])) \(point(element.points[1])) \(point(element.points[2]))")
    case .closeSubpath: commands.append("Z")
    @unknown default: break
    }
  }
  return commands.joined(separator: "")
}

let svgPath = svgCommands(mark, flipY: true)
let svg = """
<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64" viewBox="0 0 64 64">
  <path d="\(svgPath)" fill="#28C7DB" fill-rule="evenodd"/>
</svg>
"""
try svg.write(to: root.appendingPathComponent("assets/images/logo.svg"), atomically: true, encoding: .utf8)

let dartPaths = letters.map { "  '\(svgCommands($0, flipY: true))'," }.joined(separator: "\n")
let dart = """
// Generated locally from the macOS system font by draw-proxy4you-icon.swift.
const p4yLogoPaths = <String>[
\(dartPaths)
];
"""
try (dart + "\n").write(
  to: root.appendingPathComponent("lib/features/home/widget/p4y_logo_paths.dart"),
  atomically: true, encoding: .utf8
)

let androidVector = """
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <group android:translateX="22" android:translateY="22">
        <path android:pathData="\(svgPath)" android:fillColor="#FFFFFF" android:fillType="evenOdd" />
    </group>
</vector>
"""
try androidVector.write(
  to: root.appendingPathComponent("android/app/src/main/res/drawable/ic_p4y_foreground.xml"),
  atomically: true, encoding: .utf8
)

func writePng(path: String, size: Int, foreground: NSColor, background: NSColor? = nil) throws {
  let bitmap = NSBitmapImageRep(
    bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size,
    bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true,
    isPlanar: false, colorSpaceName: .deviceRGB,
    bytesPerRow: 0, bitsPerPixel: 0
  )!
  NSGraphicsContext.saveGraphicsState()
  NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)
  let context = NSGraphicsContext.current!.cgContext
  context.setAllowsAntialiasing(true)
  context.scaleBy(x: CGFloat(size) / 64, y: CGFloat(size) / 64)
  if let background {
    context.addPath(CGPath(roundedRect: CGRect(x: 1, y: 1, width: 62, height: 62),
                           cornerWidth: 14, cornerHeight: 14, transform: nil))
    context.setFillColor(background.cgColor)
    context.fillPath()
    context.addPath(CGPath(roundedRect: CGRect(x: 2.5, y: 2.5, width: 59, height: 59),
                           cornerWidth: 13, cornerHeight: 13, transform: nil))
    context.setStrokeColor(NSColor(srgbRed: 0.16, green: 0.81, blue: 0.9, alpha: 1).cgColor)
    context.setLineWidth(2.5)
    context.strokePath()
  }
  context.addPath(mark)
  context.setFillColor(foreground.cgColor)
  context.fillPath(using: .evenOdd)
  NSGraphicsContext.restoreGraphicsState()
  let destination = root.appendingPathComponent(path)
  try bitmap.representation(using: .png, properties: [:])!.write(to: destination)
}

try writePng(
  path: "macos/Runner/AppIcon.icon/Assets/logo.png", size: 512,
  foreground: .white,
  background: NSColor(srgbRed: 0.055, green: 0.11, blue: 0.21, alpha: 1)
)
let cyan = NSColor(srgbRed: 0.16, green: 0.78, blue: 0.86, alpha: 1)
let red = NSColor(srgbRed: 0.91, green: 0.26, blue: 0.30, alpha: 1)
for (name, color) in [
  ("tray_icon.png", NSColor.black),
  ("tray_icon_dark.png", NSColor.white),
  ("tray_icon_connected.png", cyan),
  ("tray_icon_connected_light.png", cyan),
  ("tray_icon_connected_dark.png", cyan),
  ("tray_icon_disconnected.png", red),
] {
  try writePng(path: "assets/images/\(name)", size: 64, foreground: color)
}

let androidBackground = NSColor(srgbRed: 0.055, green: 0.11, blue: 0.21, alpha: 1)
for (density, size) in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)] {
  try FileManager.default.createDirectory(
    at: root.appendingPathComponent("android/app/src/main/res/mipmap-\(density)"),
    withIntermediateDirectories: true
  )
  try writePng(
    path: "android/app/src/main/res/mipmap-\(density)/ic_p4y_launcher.png",
    size: size, foreground: .white, background: androidBackground
  )
  try writePng(
    path: "android/app/src/main/res/mipmap-\(density)/ic_p4y_launcher_round.png",
    size: size, foreground: .white, background: androidBackground
  )
}
for (density, size) in [("mdpi", 24), ("hdpi", 36), ("xhdpi", 48), ("xxhdpi", 72), ("xxxhdpi", 96)] {
  try FileManager.default.createDirectory(
    at: root.appendingPathComponent("android/app/src/main/res/drawable-\(density)"),
    withIntermediateDirectories: true
  )
  try writePng(
    path: "android/app/src/main/res/drawable-\(density)/ic_stat_p4y.png",
    size: size, foreground: .white
  )
}

func writeAndroidBanner() throws {
  let width = 320
  let height = 180
  let bitmap = NSBitmapImageRep(
    bitmapDataPlanes: nil, pixelsWide: width, pixelsHigh: height,
    bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true,
    isPlanar: false, colorSpaceName: .deviceRGB,
    bytesPerRow: 0, bitsPerPixel: 0
  )!
  NSGraphicsContext.saveGraphicsState()
  NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)
  let context = NSGraphicsContext.current!.cgContext
  context.setFillColor(androidBackground.cgColor)
  context.fill(CGRect(x: 0, y: 0, width: width, height: height))
  context.saveGState()
  context.translateBy(x: 70, y: 28)
  context.scaleBy(x: 2, y: 2)
  context.addPath(mark)
  context.setFillColor(NSColor.white.cgColor)
  context.fillPath(using: .evenOdd)
  context.restoreGState()
  NSGraphicsContext.restoreGraphicsState()
  let destination = root.appendingPathComponent("android/app/src/main/res/mipmap-xhdpi/ic_p4y_banner.png")
  try bitmap.representation(using: .png, properties: [:])!.write(to: destination)
}
try writeAndroidBanner()
