# CalDAV-D: Native Android CalDAV & HTTP Web Server

![Build Status](https://github.com/hugodraker/caldavd/actions/workflows/android.yml/badge.svg)
![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android)
![Language](https://img.shields.io/badge/Language-C%20%7C%20Java-blue)
![License](https://img.shields.io/badge/License-Public%20Domain-lightgrey)

**CalDAV-D** is a lightweight, high-performance CalDAV and HTTP Web Server designed to run entirely on-device for Android. 

Originally written as a Win32 C daemon, this project has been fully ported to Android using the **Android NDK (JNI)**, **OpenSSL**, and **Java**. It runs as an Android Foreground Service, allowing you to host a secure, multi-user calendar and scheduling server directly from your smartphone or tablet.

## 🚀 Key Features

* **Embedded CalDAV Server:** Fully functional CalDAV protocol implementation (`PROPFIND`, `REPORT`, `PUT`, `DELETE`, etc.) for seamless synchronization with standard calendar clients (Apple Calendar, Thunderbird, Android sync adapters).
* **Integrated HTTP Web UI:** Serves a built-in HTML/JS web client offering Day, Week, Month, and People views, plus an advanced Automated Weekly Scheduling engine.
* **Native TLS/SSL Security:** Powered by OpenSSL (`libssl`/`libcrypto`). Auto-generates its own `CalDAV Local Root CA` and `localhost` leaf certificates for secure TLS 1.2/1.3 connections.
* **Certificate Management:** Export the auto-generated certificate chain as a PKCS#12 (`.pfx`) bundle to install on client devices for trusted HTTPS connections.
* **Multi-User & Permissions:** Built-in user management with hashed passwords (SHA-256) and granular permissions (e.g., restricted vs. "can edit all events").
* **CardDAV / Contacts:** Basic contact management and `vCard` upload/download support.
* **ICS Support:** Import and export standard iCalendar (`.ics`) files directly from the app or web UI.

## 🛠 Technical Architecture

* **Core Engine:** Written in pure C for maximum performance and low memory overhead.
* **JNI Bridge:** Connects the native C server socket loop to the Android OS.
* **Android Service:** Operates as a sticky Foreground Service to ensure the server remains alive in the background without being killed by Android's battery optimizer.
* **Automated CI/CD:** Fully configured GitHub Actions workflow (`android.yml`) to automatically compile the C code via CMake/Ninja and build the debug APK on every push.

## ⚙️ Default Configuration

When running CalDAV-D for the first time, it uses the following default settings (which can be changed in the Android app UI):

* **CalDAV (HTTPS) Port:** `8443`
* **HTTP Web UI Port:** `8080`
* **Default Admin User:** `admin` (Password is generated or set via the app)
* **PFX Certificate Export Password:** `caldav`
* **Timeout:** `2000` ms

*(Note: All ports must be configured above `1000` as Android restricts binding to lower privileged ports without root access).*

## 📥 Installation & Build Instructions

### Option 1: Download the APK
You can download the latest automatically compiled APK from the [GitHub Actions Actions tab](../../actions). Look for the `caldavd-android-apk` artifact on the latest successful workflow run.

### Option 2: Build from Source
This project uses Gradle and CMake. You can build it locally using Android Studio or via the command line:

```bash
# Clone the repository
git clone [https://github.com/hugodraker/caldavd.git](https://github.com/hugodraker/caldavd.git)
cd caldavd

# Build the debug APK using the Gradle wrapper
./gradlew assembleDebug

🔒 Trusting the Self-Signed Certificate
Because CalDAV-D generates its own SSL certificates on your device, web browsers and CalDAV clients will initially show a security warning. To fix this:

Open the CalDAV-D Android app.

Tap Export Cert to save the CalDAV_CertBundle.pfx file.

Transfer this file to your client device (PC, Mac, or another phone).

Import the .pfx file into your device's Trusted Root Certificate Authorities store.

Password: caldav

🤝 Contributing & License
This work is released into the Public Domain. It is provided "as-is", without warranty of any kind. Contributions, forks, and modifications are highly encouraged!

Keywords: Android CalDAV Server, C CalDAV Server, Android HTTP Server, OpenSSL NDK, Android JNI Server, Self-hosted Calendar, CalDAV-D, CardDAV Android, Employee Scheduling Server, Android Foreground Service Daemon.
