# 📱 SmartAttendance
**An AI-Powered, 3-Layer Security Classroom Attendance Management System Built with Jetpack Compose**

SmartAttendance is an enterprise-concept native Android application built to eliminate proxy attendance. By leveraging structural hardware constraints, asynchronous edge-based computer vision frameworks, and encrypted cloud synchronization, it completely automates classroom management securely.
---

## 🛠️ Advanced Production Tech Stack
* **UI Framework:** Jetpack Compose (Modern Declarative UI State Mapping)
* **Architectural Runtime:** Kotlin Coroutines & Flow (For fluid, non-blocking asynchronous threads)
* **On-Device Artificial Intelligence:** Google ML Kit Face Detection Engine
* **Networking Infrastructure:** Asynchronous Ktor Clients + Retrofit HTTP Clients
* **Local Crypto Sandbox:** AndroidX Jetpack Crypto Security (Encrypted Context Mapping)
* **Persistent Deferred Syncing:** Jetpack WorkManager (Enables background retry queues for unstable networks)
* **Database Backend Module:** Supabase Engine (PostgreSQL Layered Cloud Architecture)

---

## 💎 Deep-Dive Feature Architecture

### 1. Hardened Hardware-Secret Infrastructure (`strictConfig`)
* To ensure data safety, backend credentials (`SUPABASE_URL` & `SUPABASE_ANON_KEY`) are never hardcoded into the source code.
* Built a rigorous compilation check mechanism within Gradle that fetches production keys cleanly from local environmental parameters, automatically dropping them into an immutable `BuildConfig` sandbox during runtime compilations.

### 2. Edge-Based AI Facial Core (Google ML Kit)
* Embedded Google's **ML Kit Face Detection API** inside the registration pipeline. 
* Rather than simply checking static images, the device parses active camera frames natively using embedded computer vision algorithms to evaluate real face dimensions and geometric contours before registering coordinates to the database.

### 3. Dynamic 30-Second Token Lifecycle
* Instructors spin up live attendance sessions that request dynamic, single-use server OTP sequences.
* An active coroutine monitoring routine sets a 30-second structural delay (`delay(30000)`). Once the window passes, the lifecycle worker automatically clears state arrays (`code = ""`), instantly rendering late entries or remote code-sharing completely useless.

### 4. Proximity Hardware Constraints (BLE Scanning)
* Students cannot log attendance outside the classroom perimeter. 
* The system probes the active hardware wrapper (`BluetoothAdapter`) to search through `bondedDevices`. If a matching `"Teacher"` beacon matrix isn't tracked in close local physical range, the enrollment interface locks out.

### 5. High-Efficiency Networking Architecture
* Utilizes a highly optimized dual network layer: **Retrofit** processes structured data streams smoothly via Gson parsing layers, while **Ktor Core engines** ensure rapid API request responses.

---

## 📦 Local Compilation & Setup Guide

### System Prerequisites
* An Android device running **Android SDK 26 (Android 8.0 Oreo)** up to **Android SDK 36**.
* Hardware Requirements: Front-facing Camera module and Bluetooth Low Energy transceiver.

### Installation Walkthrough
1. Clone the project locally:
   ```bash
   git clone [https://github.com/thanmai1019/SmartAttendance.git](https://github.com/thanmai1019/SmartAttendance.git)
