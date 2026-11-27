#!/usr/bin/env swift
import AppKit
import CoreGraphics
import Foundation

// Renders the dedicated OpalCast Receiver launcher icons (Emerald-Teal & Aurora Cyan Liquid Glass)
// across all Android densities (mdpi..xxxhdpi) plus Adaptive Icon background & foreground layers.

let rootDir = URL(fileURLWithPath: CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : FileManager.default.currentDirectoryPath)
let resDir = rootDir.appendingPathComponent("receiver/res")
let fm = FileManager.default

func color(_ hex: UInt32, alpha: CGFloat = 1.0) -> CGColor {
    let r = CGFloat((hex >> 16) & 0xFF) / 255.0
    let g = CGFloat((hex >> 8) & 0xFF) / 255.0
    let b = CGFloat(hex & 0xFF) / 255.0
    return CGColor(colorSpace: CGColorSpaceCreateDeviceRGB(), components: [r, g, b, alpha])!
}

func roundedRectPath(_ rect: CGRect, radius: CGFloat) -> CGPath {
    CGPath(roundedRect: rect, cornerWidth: radius, cornerHeight: radius, transform: nil)
}

func drawBackdrop(ctx: CGContext, size: CGFloat, clipSquircle: Bool) {
    ctx.saveGState()
    let rect: CGRect
    if clipSquircle {
        let inset = size * 0.06
        rect = CGRect(x: inset, y: inset, width: size - inset * 2, height: size - inset * 2)
        let corner = rect.width * 0.23
        ctx.addPath(roundedRectPath(rect, radius: corner))
        ctx.clip()
    } else {
        rect = CGRect(x: 0, y: 0, width: size, height: size)
    }

    // Deep Emerald-Obsidian base gradient
    let cs = CGColorSpaceCreateDeviceRGB()
    let baseGrad = CGGradient(
        colorsSpace: cs,
        colors: [color(0x041F24), color(0x0A3D44), color(0x062832)] as CFArray,
        locations: [0.0, 0.52, 1.0]
    )!
    ctx.drawLinearGradient(
        baseGrad,
        start: CGPoint(x: rect.minX, y: rect.maxY),
        end: CGPoint(x: rect.maxX, y: rect.minY),
        options: [.drawsBeforeStartLocation, .drawsAfterEndLocation]
    )

    // Luminous Mint-Emerald top-right aurora
    let mintGrad = CGGradient(
        colorsSpace: cs,
        colors: [color(0x10E5A5, alpha: 0.62), color(0x06B6D4, alpha: 0.22), color(0x10E5A5, alpha: 0.0)] as CFArray,
        locations: [0.0, 0.48, 1.0]
    )!
    ctx.drawRadialGradient(
        mintGrad,
        startCenter: CGPoint(x: size * 0.78, y: size * 0.78),
        startRadius: 0,
        endCenter: CGPoint(x: size * 0.78, y: size * 0.78),
        endRadius: size * 0.62,
        options: []
    )

    // Electric Cyan bottom-left aurora
    let cyanGrad = CGGradient(
        colorsSpace: cs,
        colors: [color(0x22D3EE, alpha: 0.55), color(0x0EA5E9, alpha: 0.16), color(0x22D3EE, alpha: 0.0)] as CFArray,
        locations: [0.0, 0.5, 1.0]
    )!
    ctx.drawRadialGradient(
        cyanGrad,
        startCenter: CGPoint(x: size * 0.24, y: size * 0.24),
        startRadius: 0,
        endCenter: CGPoint(x: size * 0.24, y: size * 0.24),
        endRadius: size * 0.56,
        options: []
    )

    // Soft jade pearl accent bottom-right
    let jadeGrad = CGGradient(
        colorsSpace: cs,
        colors: [color(0x6EE7B7, alpha: 0.28), color(0x6EE7B7, alpha: 0.0)] as CFArray,
        locations: [0.0, 1.0]
    )!
    ctx.drawRadialGradient(
        jadeGrad,
        startCenter: CGPoint(x: size * 0.72, y: size * 0.22),
        startRadius: 0,
        endCenter: CGPoint(x: size * 0.72, y: size * 0.22),
        endRadius: size * 0.42,
        options: []
    )

    if clipSquircle {
        let corner = rect.width * 0.23
        ctx.addPath(roundedRectPath(rect.insetBy(dx: size * 0.008, dy: size * 0.008), radius: corner))
        ctx.setStrokeColor(color(0xA7F3D0, alpha: 0.45))
        ctx.setLineWidth(max(1.2, size * 0.012))
        ctx.strokePath()
    }
    ctx.restoreGState()
}

func drawForegroundGlyph(ctx: CGContext, size: CGFloat, adaptiveSafeScale: Bool) {
    // Adaptive icons require artwork within the inner 66/108 (~61%) safe circle.
    let s: CGFloat = adaptiveSafeScale ? (size * 0.64) : (size * 0.78)
    let ox = (size - s) * 0.5
    let oy = (size - s) * 0.5
    let cs = CGColorSpaceCreateDeviceRGB()

    ctx.saveGState()

    // 1. Sleek Frosted Glass Receiving Display Frame
    let monW = s * 0.86
    let monH = s * 0.60
    let monX = ox + (s - monW) * 0.5
    let monY = oy + s * 0.28
    let monRect = CGRect(x: monX, y: monY, width: monW, height: monH)
    let monRadius = s * 0.12

    // Drop shadow under display chassis
    ctx.saveGState()
    ctx.setShadow(offset: CGSize(width: 0, height: -s * 0.03), blur: s * 0.07, color: color(0x021216, alpha: 0.55))
    ctx.addPath(roundedRectPath(monRect, radius: monRadius))
    ctx.setFillColor(color(0xECFEFF, alpha: 0.94))
    ctx.fillPath()
    ctx.restoreGState()

    // Glass screen surface fill
    ctx.saveGState()
    ctx.addPath(roundedRectPath(monRect, radius: monRadius))
    ctx.clip()
    let glassGrad = CGGradient(
        colorsSpace: cs,
        colors: [color(0xF0FDFA, alpha: 0.96), color(0xCCFBF1, alpha: 0.86)] as CFArray,
        locations: [0.0, 1.0]
    )!
    ctx.drawLinearGradient(
        glassGrad,
        start: CGPoint(x: monRect.minX, y: monRect.maxY),
        end: CGPoint(x: monRect.maxX, y: monRect.minY),
        options: []
    )

    // Inner luminous dark-teal live display canvas inside bezel
    let bezel = s * 0.055
    let innerRect = monRect.insetBy(dx: bezel, dy: bezel)
    let innerRadius = s * 0.075
    ctx.addPath(roundedRectPath(innerRect, radius: innerRadius))
    ctx.clip()
    let screenGrad = CGGradient(
        colorsSpace: cs,
        colors: [color(0x083344), color(0x0F766E), color(0x059669)] as CFArray,
        locations: [0.0, 0.55, 1.0]
    )!
    ctx.drawLinearGradient(
        screenGrad,
        start: CGPoint(x: innerRect.minX, y: innerRect.minY),
        end: CGPoint(x: innerRect.maxX, y: innerRect.maxY),
        options: []
    )

    // Subtle top specular sheen across glass screen
    let sheenRect = CGRect(x: innerRect.minX, y: innerRect.midY, width: innerRect.width, height: innerRect.height * 0.52)
    let sheenGrad = CGGradient(
        colorsSpace: cs,
        colors: [color(0xFFFFFF, alpha: 0.30), color(0xFFFFFF, alpha: 0.0)] as CFArray,
        locations: [0.0, 1.0]
    )!
    ctx.drawLinearGradient(
        sheenGrad,
        start: CGPoint(x: sheenRect.midX, y: sheenRect.maxY),
        end: CGPoint(x: sheenRect.midX, y: sheenRect.minY),
        options: []
    )

    // Incoming Cast Wireless Signal Arcs radiating from bottom-left corner of inner display
    let waveOrigin = CGPoint(x: innerRect.minX + innerRect.width * 0.16, y: innerRect.minY + innerRect.height * 0.18)
    let waveWidths: [(CGFloat, CGFloat, CGFloat)] = [
        (innerRect.height * 0.24, s * 0.036, 0.96),
        (innerRect.height * 0.44, s * 0.034, 0.85),
        (innerRect.height * 0.64, s * 0.032, 0.72),
    ]
    ctx.setLineCap(.round)
    // Solid signal emitter dot
    let dotR = s * 0.034
    ctx.setFillColor(color(0x6EE7B7, alpha: 0.98))
    ctx.fillEllipse(in: CGRect(x: waveOrigin.x - dotR, y: waveOrigin.y - dotR, width: dotR * 2, height: dotR * 2))

    for (rad, lw, alpha) in waveWidths {
        ctx.setStrokeColor(color(0xA7F3D0, alpha: alpha))
        ctx.setLineWidth(lw)
        ctx.addArc(center: waveOrigin, radius: rad, startAngle: 0.06, endAngle: .pi / 2.0 - 0.06, clockwise: false)
        ctx.strokePath()
    }

    // Crisp incoming stream play chevron badge in top-right of inner screen
    let pillW = innerRect.width * 0.30
    let pillH = innerRect.height * 0.34
    let pillRect = CGRect(
        x: innerRect.maxX - pillW - innerRect.width * 0.10,
        y: innerRect.maxY - pillH - innerRect.height * 0.16,
        width: pillW,
        height: pillH
    )
    ctx.addPath(roundedRectPath(pillRect, radius: pillH * 0.38))
    ctx.setFillColor(color(0xFFFFFF, alpha: 0.24))
    ctx.fillPath()
    ctx.addPath(roundedRectPath(pillRect, radius: pillH * 0.38))
    ctx.setStrokeColor(color(0xFFFFFF, alpha: 0.62))
    ctx.setLineWidth(max(1.0, s * 0.012))
    ctx.strokePath()

    // Crisp miniature play/receive triangle inside badge
    let triCx = pillRect.midX + pillRect.width * 0.03
    let triCy = pillRect.midY
    let triR = pillH * 0.26
    ctx.beginPath()
    ctx.move(to: CGPoint(x: triCx - triR * 0.75, y: triCy - triR))
    ctx.addLine(to: CGPoint(x: triCx + triR * 0.95, y: triCy))
    ctx.addLine(to: CGPoint(x: triCx - triR * 0.75, y: triCy + triR))
    ctx.closePath()
    ctx.setFillColor(color(0xFFFFFF, alpha: 0.96))
    ctx.fillPath()

    ctx.restoreGState()

    // Crisp white specular rim around monitor chassis
    ctx.addPath(roundedRectPath(monRect, radius: monRadius))
    ctx.setStrokeColor(color(0xFFFFFF, alpha: 0.92))
    ctx.setLineWidth(max(1.2, s * 0.016))
    ctx.strokePath()

    // 2. Minimalist Frosted Display Stand Base (clearly reads as Receiver Screen)
    let neckW = s * 0.14
    let neckH = s * 0.075
    let neckRect = CGRect(x: ox + (s - neckW) * 0.5, y: monY - neckH + s * 0.01, width: neckW, height: neckH)
    ctx.setFillColor(color(0xCCFBF1, alpha: 0.85))
    ctx.fill(neckRect)

    let baseW = s * 0.42
    let baseH = s * 0.068
    let baseRect = CGRect(x: ox + (s - baseW) * 0.5, y: monY - neckH - baseH * 0.65, width: baseW, height: baseH)
    ctx.saveGState()
    ctx.setShadow(offset: CGSize(width: 0, height: -s * 0.018), blur: s * 0.04, color: color(0x021216, alpha: 0.42))
    ctx.addPath(roundedRectPath(baseRect, radius: baseH * 0.5))
    ctx.setFillColor(color(0xECFEFF, alpha: 0.92))
    ctx.fillPath()
    ctx.restoreGState()

    ctx.restoreGState()
}

func renderPng(size: Int, outputPath: URL, draw: (CGContext, CGFloat) -> Void) throws {
    let cs = CGColorSpaceCreateDeviceRGB()
    guard let ctx = CGContext(
        data: nil,
        width: size,
        height: size,
        bitsPerComponent: 8,
        bytesPerRow: size * 4,
        space: cs,
        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
    ) else {
        fatalError("Failed to create CGContext")
    }
    ctx.interpolationQuality = .high
    ctx.setAllowsAntialiasing(true)
    ctx.setShouldAntialias(true)
    draw(ctx, CGFloat(size))

    guard let cgImg = ctx.makeImage() else { fatalError("Failed to create CGImage") }
    let rep = NSBitmapImageRep(cgImage: cgImg)
    guard let data = rep.representation(using: .png, properties: [:]) else {
        fatalError("Failed to encode PNG")
    }
    try fm.createDirectory(at: outputPath.deletingLastPathComponent(), withIntermediateDirectories: true)
    try data.write(to: outputPath)
}

// Legacy launcher icon sizes (Android 5–7) & Adaptive icon layer sizes (Android 8–17)
let buckets: [(String, Int, Int)] = [
    ("mipmap-mdpi", 48, 108),
    ("mipmap-hdpi", 72, 162),
    ("mipmap-xhdpi", 96, 216),
    ("mipmap-xxhdpi", 144, 324),
    ("mipmap-xxxhdpi", 192, 432),
]

for (dir, legacyPx, adaptivePx) in buckets {
    let bucketUrl = resDir.appendingPathComponent(dir)
    try renderPng(size: legacyPx, outputPath: bucketUrl.appendingPathComponent("ic_launcher.png")) { ctx, sz in
        drawBackdrop(ctx: ctx, size: sz, clipSquircle: true)
        drawForegroundGlyph(ctx: ctx, size: sz, adaptiveSafeScale: false)
    }
    try renderPng(size: adaptivePx, outputPath: bucketUrl.appendingPathComponent("ic_launcher_background.png")) { ctx, sz in
        drawBackdrop(ctx: ctx, size: sz, clipSquircle: false)
    }
    try renderPng(size: adaptivePx, outputPath: bucketUrl.appendingPathComponent("ic_launcher_foreground.png")) { ctx, sz in
        drawForegroundGlyph(ctx: ctx, size: sz, adaptiveSafeScale: true)
    }
}

// High-res 512x512 preview asset
try renderPng(size: 512, outputPath: rootDir.appendingPathComponent("receiver/assets/opalcast_icon_512.png")) { ctx, sz in
    drawBackdrop(ctx: ctx, size: sz, clipSquircle: true)
    drawForegroundGlyph(ctx: ctx, size: sz, adaptiveSafeScale: false)
}

print("Generated OpalCast Receiver icons under \(resDir.path)")
