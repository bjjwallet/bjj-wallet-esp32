// Run from the repository root: swift scripts/generate-app-icons.swift
// Reuse the setup artwork; only resize and add platform-required padding.
import AppKit

let root = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
func render(_ source: String, _ destination: String, size: Int, artwork: Int, opaque: Bool = false)
    throws
{
    let image = NSImage(contentsOf: root.appendingPathComponent(source))!
    let context = CGContext(
        data: nil, width: size, height: size, bitsPerComponent: 8,
        bytesPerRow: size * 4, space: CGColorSpaceCreateDeviceRGB(),
        bitmapInfo: opaque
            ? CGImageAlphaInfo.noneSkipLast.rawValue : CGImageAlphaInfo.premultipliedLast.rawValue)!
    context.interpolationQuality = .high
    if opaque {
        context.setFillColor(CGColor(gray: 245.0 / 255, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: size, height: size))
    }
    let inset = (size - artwork) / 2
    context.draw(
        image.cgImage(forProposedRect: nil, context: nil, hints: nil)!,
        in: CGRect(x: inset, y: inset, width: artwork, height: artwork))
    let bitmap = NSBitmapImageRep(cgImage: context.makeImage()!)
    let url = root.appendingPathComponent(destination)
    try FileManager.default.createDirectory(
        at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    try bitmap.representation(using: .png, properties: [:])!.write(to: url)
}
let source = "shared/src/commonMain/composeResources/drawable/airlock_logo_"
for (density, size) in [
    ("ldpi", 36), ("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192),
] {
    try render(
        source + "light.png", "androidApp/src/main/res/mipmap-\(density)/ic_launcher.png",
        size: size, artwork: size * 4 / 5, opaque: true)
}
try render(
    source + "light.png", "androidApp/src/main/res/drawable-nodpi/ic_launcher_foreground.png",
    size: 432, artwork: 240)
for theme in ["light", "dark"] {
    let qualifier = theme == "dark" ? "drawable-night-nodpi" : "drawable-nodpi"
    try render(
        source + theme + ".png", "androidApp/src/main/res/\(qualifier)/splash_logo.png", size: 864,
        artwork: 576)
    try render(
        source + theme + ".png", "iosApp/AirLock/Assets.xcassets/LaunchLogo.imageset/\(theme).png",
        size: 288, artwork: 288)
}
try render(
    source + "light.png", "iosApp/AirLock/Assets.xcassets/AppIcon.appiconset/icon.png", size: 1024,
    artwork: 820, opaque: true)
