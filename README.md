# Hermes Agent for Android (Standalone 24/7 Gateway & Client)

[![Platform](https://img.shields.io/badge/Platform-Android%2010%2B%20(API%2029%2B)-3DDC84?logo=android&logoColor=white)](#)
[![Architecture](https://img.shields.io/badge/Architecture-ARM64--v8a-blue)](#)
[![Runtime](https://img.shields.io/badge/Runtime-CPython%203.14.8-3776AB?logo=python&logoColor=white)](#)
[![Node.js](https://img.shields.io/badge/Node.js-v24.22.1-339933?logo=node.js&logoColor=white)](#)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](#)

A fully self-contained Android APK that packages the complete **Hermes Agent** environment cloned from macOS, including the embedded CPython 3.14 ARM64 runtime, Bionic native shared libraries, TLS certificate store, messaging channels (WhatsApp, Discord, Telegram), tools, cron scheduler, and the built React Web Dashboard.

---

## 🌟 Key Features

### 1. 100% Standalone Execution
- **Zero Host Dependencies**: No Termux, PRoot, or external Linux environments required on the target device.
- **Embedded CPython 3.14.8 ARM64**: Full Python runtime with SQLite3 (WAL mode), OpenSSL 3.x, zlib, and ffi.
- **Pre-packaged Python Packages**: FastAPI, Uvicorn, Pydantic, HTTPX, Requests, WebSockets, Rich, Tenacity, Prompt Toolkit, Croniter, and Ruamel.yaml.
- **Node.js v24 & NPM/NPX Support**: Full support for running Model Context Protocol (MCP) servers (e.g., Shopify, Google Workspace).
- **Self-Extracting Asset Loader**: Automatically extracts and initializes the internal bundle in under 5 seconds on first boot.

### 2. 24/7 Background Persistence
- **Foreground Service (`HermesService`)**: Runs with `START_STICKY` and a persistent system notification to prevent OS low-memory termination.
- **Network Resilience (`NetworkMonitor`)**: Automatically detects connectivity drops (Wi-Fi/Mobile) and gracefully reschedules outbound requests without socket panics.
- **CPU & Wi-Fi Locks (`WakeLock` + `WifiLock`)**: Keeps the background daemon and WebSocket bridges alive when the screen is turned off.
- **Auto-Boot Watchdog (`BootReceiver`)**: Automatically revives Hermes on device reboot.

### 3. Integrated Figma-Grade Dashboard UI
- **Embedded WebView**: Direct connection to `http://127.0.0.1:9119/` for chatting, session history, memory review, and skill management.
- **Real-Time Gateway Controls**: Live PID, platform statuses, memory consumption, and one-tap process restart.
- **Mono Terminal Log Viewer**: Real-time stdout/stderr stream with search, scroll lock, and copy-to-clipboard.

---

## 🏗️ Architecture Overview

```
                      ┌──────────────────────────────────────┐
                      │        Hermes Android APK            │
                      │                                      │
                      │  ┌────────────────────────────────┐  │
                      │  │   MainActivity / WebView UI    │  │
                      │  │     (http://127.0.0.1:9119)    │  │
                      │  └───────────────▲────────────────┘  │
                      │                  │ IPC / HTTP        │
                      │  ┌───────────────▼────────────────┐  │
                      │  │   HermesService (Foreground)   │  │
                      │  │     ├─ WakeLock + WifiLock     │  │
                      │  │     └─ NetworkMonitor          │  │
                      │  └───────────────▲────────────────┘  │
                      │                  │ ProcessManager    │
                      │  ┌───────────────▼────────────────┐  │
                      │  │     Background Daemons         │  │
                      │  │  ├─ Python 3.14 (Gateway/API)  │  │
                      │  │  ├─ Node.js (WhatsApp Bridge)  │  │
                      │  │  └─ MCP Servers (Shopify, etc) │  │
                      │  └────────────────────────────────┘  │
                      └──────────────────────────────────────┘
```

---

## 🛠️ Building from Source

### Prerequisites
- macOS or Linux with **Android SDK** (API 35, Build Tools 35.0.0+)
- **JDK 17** or higher
- **Gradle 9.x**

### Step 1: Clone Repository
```bash
git clone https://github.com/humzasohail25-tech/hermes-agent-android.git
cd hermes-agent-android
```

### Step 2: Prepare the Asset Bundle
Place your compressed `hermes_bundle.tar.gz` (containing `usr/` and `hermes_home/`) into `app/src/main/assets/`:
```bash
mkdir -p app/src/main/assets
cp /path/to/hermes_bundle.tar.gz app/src/main/assets/hermes_bundle.tar.gz
```

### Step 3: Build the APK
```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease
```

---

## 📲 Installation & Device Setup

### Via ADB
```bash
adb install -r app/build/outputs/apk/release/app-release-unsigned.apk
```

### Post-Install Permissions
To ensure Android doesn't suspend the process in deep sleep:
1. Open **Settings** → **Apps** → **Hermes Agent**.
2. Select **Battery** → Set to **Unrestricted** / **No restrictions**.
3. Enable **Autostart** / **Allow background activity** (required on MIUI, ColorOS, OneUI).

---

## ⚙️ Configuration & Environment

Configuration files are located in `/data/data/ai.hermes.agent/files/hermes_home/`:

- **`config.yaml`**: Primary agent configuration (model, provider, platforms, MCP servers).
- **`.env`**: API credentials (OpenRouter, Nous Research, Groq, WhatsApp tokens).
- **`SOUL.md`**: Persona instructions and conversational behavior.
- **`memories/MEMORY.md`**: Persistent long-term memory store.

---

## 🛡️ Security & Privacy Notice

This repository contains clean, modular open-source code and build scaffolding. **No private API keys, authentication tokens, credentials, keystores, or user memories are stored or committed into this codebase.**

---

## 📄 License

Licensed under the [MIT License](LICENSE).
