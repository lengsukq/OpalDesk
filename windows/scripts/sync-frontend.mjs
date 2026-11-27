#!/usr/bin/env node
/**
 * Zero-copy build staging script for OpalDesk Windows (Tauri v2).
 * Stages `../site/cast/` and `../site/assets/icon.svg` into `windows/dist/`
 * and synchronizes `versionName` from `../helper/build.gradle.kts` across
 * `package.json`, `src-tauri/tauri.conf.json`, and `src-tauri/Cargo.toml`.
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const WINDOWS_ROOT = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(WINDOWS_ROOT, '..');

export function resolveAppVersion(gradleKtsContent) {
  const match = String(gradleKtsContent || '').match(/versionName\s*=\s*"([^"]+)"/);
  return match && match[1] ? match[1].trim() : '0.0.1';
}

export function rewriteCastHtmlForDesktopDist(html) {
  return String(html || '')
    .replaceAll('../assets/icon.svg', './assets/icon.svg')
    .replace(
      '<a href="../index.html" class="brand-link">',
      '<a href="./index.html" class="brand-link">'
    )
    .replace(
      '<a href="../overview.html" class="pill-btn">2D 详解</a>',
      '<a href="https://lengsukq.github.io/OpalDesk/overview.html" target="_blank" rel="noopener noreferrer" class="pill-btn desktop-ext-link">2D 详解</a>'
    )
    .replace(
      '<a href="../index.html" class="pill-btn">3D 舞台</a>',
      '<a href="https://lengsukq.github.io/OpalDesk/" target="_blank" rel="noopener noreferrer" class="pill-btn desktop-ext-link">3D 舞台</a>'
    );
}

function copyDirectoryFiltered(srcDir, dstDir) {
  fs.mkdirSync(dstDir, { recursive: true });
  for (const entry of fs.readdirSync(srcDir, { withFileTypes: true })) {
    if (entry.name === 'test' || entry.name.startsWith('.')) {
      continue;
    }
    const srcPath = path.join(srcDir, entry.name);
    const dstPath = path.join(dstDir, entry.name);
    if (entry.isDirectory()) {
      copyDirectoryFiltered(srcPath, dstPath);
    } else if (entry.isFile()) {
      fs.copyFileSync(srcPath, dstPath);
    }
  }
}

function writeFileIfChanged(filePath, nextContent) {
  const prev = fs.existsSync(filePath) ? fs.readFileSync(filePath, 'utf8') : null;
  if (prev !== nextContent) {
    fs.writeFileSync(filePath, nextContent, 'utf8');
  }
}

function syncVersionFiles(version) {
  const pkgPath = path.join(WINDOWS_ROOT, 'package.json');
  if (fs.existsSync(pkgPath)) {
    const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8'));
    pkg.version = version;
    writeFileIfChanged(pkgPath, JSON.stringify(pkg, null, 2) + '\n');
  }

  const tauriConfPath = path.join(WINDOWS_ROOT, 'src-tauri', 'tauri.conf.json');
  if (fs.existsSync(tauriConfPath)) {
    const conf = JSON.parse(fs.readFileSync(tauriConfPath, 'utf8'));
    conf.version = version;
    writeFileIfChanged(tauriConfPath, JSON.stringify(conf, null, 2) + '\n');
  }

  const cargoTomlPath = path.join(WINDOWS_ROOT, 'src-tauri', 'Cargo.toml');
  if (fs.existsSync(cargoTomlPath)) {
    const raw = fs.readFileSync(cargoTomlPath, 'utf8');
    const updated = raw.replace(
      /(\[package\][\s\S]*?\nversion\s*=\s*")([^"]+)(")/,
      `$1${version}$3`
    );
    writeFileIfChanged(cargoTomlPath, updated);
  }
}

export function syncDesktopFrontend() {
  const gradlePath = path.join(REPO_ROOT, 'helper', 'build.gradle.kts');
  const gradleContent = fs.existsSync(gradlePath) ? fs.readFileSync(gradlePath, 'utf8') : '';
  const version = resolveAppVersion(gradleContent);

  const castSrc = path.join(REPO_ROOT, 'site', 'cast');
  const iconSrc = path.join(REPO_ROOT, 'site', 'assets', 'icon.svg');
  const distDir = path.join(WINDOWS_ROOT, 'dist');

  if (!fs.existsSync(castSrc)) {
    throw new Error(`Missing frontend source directory: ${castSrc}`);
  }

  fs.rmSync(distDir, { recursive: true, force: true });
  copyDirectoryFiltered(castSrc, distDir);

  if (fs.existsSync(iconSrc)) {
    const assetsOut = path.join(distDir, 'assets');
    fs.mkdirSync(assetsOut, { recursive: true });
    fs.copyFileSync(iconSrc, path.join(assetsOut, 'icon.svg'));
  }

  for (const entry of fs.readdirSync(distDir, { withFileTypes: true })) {
    if (entry.isFile() && entry.name.endsWith('.html')) {
      const htmlPath = path.join(distDir, entry.name);
      const rawHtml = fs.readFileSync(htmlPath, 'utf8');
      fs.writeFileSync(htmlPath, rewriteCastHtmlForDesktopDist(rawHtml), 'utf8');
    }
  }

  syncVersionFiles(version);
  return { version, distDir };
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const { version, distDir } = syncDesktopFrontend();
  console.log(`[opaldesk-windows] Staged site/cast -> ${distDir} (v${version})`);
}
