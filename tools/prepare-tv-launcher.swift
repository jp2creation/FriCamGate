import AppKit

// Export the original shield with equal safe margins for Google TV's circular mask.
let source = NSBitmapImageRep(data: try Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1])))!
var left = 435, top = 520, right = 110, bottom = 190
for y in 190..<520 {
    for x in 110..<435 {
        let color = source.colorAt(x: x, y: y)!.usingColorSpace(.deviceRGB)!
        if max(color.redComponent, color.greenComponent, color.blueComponent) > 0.24 {
            left = min(left, x); right = max(right, x)
            top = min(top, y); bottom = max(bottom, y)
        }
    }
}
let crop = source.cgImage!.cropping(to: CGRect(x: left, y: top, width: right - left + 1, height: bottom - top + 1))!
let colorSpace = CGColorSpaceCreateDeviceRGB()
let canvas = CGContext(data: nil, width: 320, height: 320, bitsPerComponent: 8, bytesPerRow: 1280,
    space: colorSpace, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
canvas.setFillColor(CGColor(gray: 0, alpha: 1)); canvas.fill(CGRect(x: 0, y: 0, width: 320, height: 320))
let scale = 236.0 / Double(max(crop.width, crop.height))
let width = Double(crop.width) * scale, height = Double(crop.height) * scale
canvas.interpolationQuality = .high
canvas.draw(crop, in: CGRect(x: (320 - width) / 2, y: (320 - height) / 2, width: width, height: height))
let output = NSBitmapImageRep(cgImage: canvas.makeImage()!)
try output.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: CommandLine.arguments[2]))
print("Shield bounds: \(left),\(top) to \(right),\(bottom); centered in 320x320")
