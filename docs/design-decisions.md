## 2026-10-13

**Decision:** Phase 1 uses Android Host Card Emulation with AID `F01234567821`, category `other`.
**Reason:** The Android mCard must be selected exactly like the Java Card applet.
**Status:** implemented.

## 2026-10-21

**Decision:** Phase 2 persists Basic Info and opaque record blobs in Room.
**Reason:** HCE record operations need transactional insert, duplicate detection, paging, and oldest-first ring eviction across process restarts.
**Status:** implemented. Existing Phase 1 Basic Info migrates lazily from the previous private preferences store.

**Decision:** Phase 2 remains unauthenticated.
**Reason:** The protocol reserves mutual authentication and session enforcement for Phase 3.
**Status:** implemented.

**Decision:** The Android mCard reports a maximum record count of 20 and a ring size of 4,096 bytes.
**Reason:** The app specification sets these Android-specific limits.
**Status:** implemented for `GET_INFO`.

**Decision:** Phase 1 stores the basic info block in app-private `SharedPreferences`.
**Reason:** Phase 1 needs a local store for HCE reads before Phase 2 introduces the record database.
**Status:** implemented.

**Decision:** Phase 1 is unauthenticated.
**Reason:** Mutual authentication starts in Phase 3.
**Status:** implemented.
