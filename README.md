# Clock Everywhere

A lightweight Android TV clock overlay that stays visible above every other app.

<img width="800" height="576" alt="WhatsApp Image 2026-10-20 at 21 51 44" src="https://github.com/user-attachments/assets/b6ad1094-e188-4985-a8db-5b934fb455ae" />

## Fork: TCL Clock Accuracy

This fork adds time-accuracy fixes for TCL Android TV (API 28) where
the system clock freezes during TV input and standby.

### Added Features
- **Accessibility-based auto-start** (works without BOOT_COMPLETED)
- **Monotonic clock** (`elapsedRealtime()`) — immune to frozen system clock
- **System clock resync** — auto-corrects when system time recovers
- **NTP sync** (Google) — absolute time correction every 5 min + on boot
- **Pill UI** — semi-transparent rounded background, bottom-right position
- **Separate timezone label** — smaller bold text inside the pill

### TCL-Specific Fixes
- Removed API 34+ manifest attributes that caused silent parse failure on API 28
- Added `INTERNET` + `FOREGROUND_SERVICE_DATA_SYNC` permissions for NTP
- `BootTriggerService` (AccessibilityService) replaces dead BOOT_COMPLETED receiver   
