# AOSP DeskClock reference snapshot

- **Status:** pending import.
- **Official upstream:** https://android.googlesource.com/platform/packages/apps/DeskClock/
- **Selected branch:** `main`
- **Destination:** `/reference` in this repository.

The workflow at [vendor-aosp-deskclock.yml](../../.github/workflows/vendor-aosp-deskclock.yml) clones the official upstream repository and copies the complete checked-out project tree to `/reference`. It retains source files, resources, tests, hidden project files, build metadata, and any upstream license/notice files. The only excluded content is Git's internal `.git` directory and its history. Once the import finishes, this document records the exact upstream commit and UTC import time.

The reference directory is intended to remain an untouched upstream baseline. DP_Clock-specific changes should be implemented outside it.
