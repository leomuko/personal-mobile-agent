# Phase 0 foundation

The approved product remains the floating conversational companion. This first implementation proves the project, Android hosts, capture lifecycle and encrypted SQLDelight path. It does not imitate model responses.

- Android application module with built-in AGP Kotlin; shared core and UI use the modern Android KMP library plugin plus JVM targets for shared tests. iOS targets are intentionally added at the iOS milestone.
- Kotlin 2.4.20, AGP 9.2.1, Gradle 9.4.1 and Compose 1.12.1 are pinned. Compose requires compile API 37. The app targets API 36 and supports API 31+, subject to physical-device validation.
- SQLDelight 2.1.0 with SQLCipher 4.10.0 through SupportOpenHelperFactory. AES-GCM in Android Keystore wraps a random database key in no-backup storage. Key loss is surfaced without resetting the database.
- SQL schema v1 contains messages, draft and FTS; v2 adds the creation-time index. Migration scripts and tests retain earlier records.
- The same application session backs MainActivity and DrawerActivity. A translucent foreground Activity hosts the editable drawer. This provides normal keyboard and foreground capture behavior; an editable WindowManager-only drawer remains a separate feasibility experiment.
- A user-started special-use foreground service hosts only the draggable mascot. It offers Stop in its notification and supports long-press dismissal, lock-screen hiding, and permission revocation. Play approval is not established by this prototype.
- Capture is foreground-only. Microphone samples drive a real waveform and are discarded; camera images exist only in memory. No transcript, OCR result or saved document is fabricated.
- No INTERNET, calendar, accessibility, broad storage or settings-write permission is declared. Cloud-model fallback does not exist. Messages are saved locally but never marked answered.
- Release builds protect their windows from screenshots; debug builds permit visual QA. Do not use private data in debug screenshots.

Outstanding P0 evidence: S24 Ultra overlay/IME/rotation/capture checks, model runtime integration and comparative benchmarks, resource/thermal measurements, and physical-device encrypted-store tests. SDK/emulator success is not evidence of Samsung behavior or inference speed.

