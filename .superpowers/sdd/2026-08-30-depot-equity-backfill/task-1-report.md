# Task 1 Report: Schreibregel — gemessen schlägt rekonstruiert

## Summary

Implemented SQL-enforced write priority rules in the DepotEquitySnapshotRepository to ensure measured equity snapshots can never be overwritten by reconstructed ones, and that reconstructed rows get correctly relabeled when later measured for real.

## TDD Evidence

### Step 2: RED — Initial Test Failures (Compilation Errors)

**Command:**
```bash
cd java-server && JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-amd64 ./mvnw verify -Dit.test=DepotEquitySnapshotRepositoryIT -DfailIfNoTests=false
```

**Expected Failure:**
Compilation errors because `upsertReconstructed` and `firstMeasured` methods do not yet exist.

**Observed Output:**
```
[ERROR] COMPILATION ERROR : 
[INFO] -----------------------------------------------------------------
[ERROR] /srv/dev/dracul/.claude/worktrees/depot-equity-backfill/java-server/src/test/java/de/visterion/dracul/depot/DepotEquitySnapshotRepositoryIT.java:[244,13] cannot find symbol
  symbol:   method upsertReconstructed(java.lang.String,java.time.Instant,java.lang.String,java.math.BigDecimal,java.math.BigDecimal,java.lang.String)
  location: variable repo of type de.visterion.dracul.depot.DepotEquitySnapshotRepository
[ERROR] /srv/dev/dracul/.claude/worktrees/depot-equity-backfill/java-server/src/test/java/de/visterion/dracul/depot/DepotEquitySnapshotRepositoryIT.java:[297,24] cannot find symbol
  symbol:   method firstMeasured(java.lang.String,java.lang.String)
  location: variable repo of type de.visterion.dracul.depot.DepotEquitySnapshotRepository
[INFO] 8 errors
```

**Why Expected:** The test methods reference non-existent repository methods that were to be implemented.

### Step 6: GREEN — All Tests Passing

**Command:**
```bash
cd java-server && JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-amd64 ./mvnw verify -Dit.test=DepotEquitySnapshotRepositoryIT -DfailIfNoTests=false
```

**Observed Output:**
```
[INFO] Tests run: 19, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.013 s -- in de.visterion.dracul.depot.DepotEquitySnapshotRepositoryIT
[INFO] BUILD SUCCESS
```

**Test Coverage:**
- 6 new tests added:
  - `reconstructedInsertIsLabelledReconstructed`
  - `reconstructedNeverOverwritesMeasured`
  - `measuredRelabelsAReconstructedRow`
  - `reconstructedReplayWritesNothing`
  - `firstMeasuredSkipsReconstructedRows`
  - `firstMeasuredIgnoresOtherConnections`
- 1 existing test updated to reflect new behavior:
  - `updatePathLeavesExternalFlowUntouchedAndSetsSourceToMeasured` (renamed from `updatePathLeavesExternalFlowAndSourceUntouched`)
- 12 existing tests remained unchanged and passing

**Full Suite:** 370 tests passed with BUILD SUCCESS.

## Implementation Summary

### 1. Modified `upsert()` Method
- Added `source = 'MEASURED'` to the SET clause
- Added `depot_equity_snapshot.source` to the IS DISTINCT FROM comparison tuple
- Updated Javadoc to explain why source is part of the update

This ensures that a row first written as RECONSTRUCTED by the backfill gets relabeled to MEASURED when later measured for real, otherwise the chart would draw it dashed forever.

### 2. Implemented `upsertReconstructed()` Method
- New backfill write path with key differences from `upsert()`:
  - Explicitly writes `source = 'RECONSTRUCTED'`
  - WHERE clause includes `depot_equity_snapshot.source = 'RECONSTRUCTED'` guard
  - This prevents reconstructed rows from overwriting measured rows
  - Returns empty Optional if a measured row already exists (enforced by the database)

### 3. Implemented `firstMeasured()` Method
- Returns the oldest genuinely measured row
- Filters explicitly with `AND source = 'MEASURED'`
- Purpose: backfill anchor to prevent re-runs from drifting further from the broker

## Mutation Check Evidence

All three mutations confirmed the necessity of each code element.

### Mutation 1: Removed `source = 'MEASURED'` from `upsert()` SET clause

**Affected Tests:** 
- `measuredRelabelsAReconstructedRow`
- `updatePathLeavesExternalFlowUntouchedAndSetsSourceToMeasured`

**Failure Output:**
```
[ERROR] de.visterion.dracul.depot.DepotEquitySnapshotRepositoryIT.measuredRelabelsAReconstructedRow -- Time elapsed: 0.007 s <<< FAILURE!
org.opentest4j.AssertionFailedError: 
expected: "MEASURED"
 but was: "RECONSTRUCTED"
	at de.visterion.dracul.depot.DepotEquitySnapshotRepositoryIT.measuredRelabelsAReconstructedRow(DepotEquitySnapshotRepositoryIT.java:277)

[ERROR] de.visterion.dracul.depot.DepotEquitySnapshotRepositoryIT.updatePathLeavesExternalFlowUntouchedAndSetsSourceToMeasured -- Time elapsed: 0.002 s <<< FAILURE!
org.opentest4j.AssertionFailedError: 
expected: "MEASURED"
 but was: "RECONSTRUCTED"
	at de.visterion.dracul.depot.DepotEquitySnapshotRepositoryIT.updatePathLeavesExternalFlowUntouchedAndSetsSourceToMeasured(DepotEquitySnapshotRepositoryIT.java:108)
```

**Rationale:** Without this SET clause, the source column is never updated when a measured value overwrites a reconstructed one, breaking the relabeling requirement. Both tests that verify measured data correctly updates RECONSTRUCTED rows fail.

### Mutation 2: Removed `AND depot_equity_snapshot.source = 'RECONSTRUCTED'` from `upsertReconstructed()` WHERE clause

**Affected Test:** `reconstructedNeverOverwritesMeasured`

**Failure Output:**
```
[ERROR] de.visterion.dracul.depot.DepotEquitySnapshotRepositoryIT.reconstructedNeverOverwritesMeasured -- Time elapsed: 0.012 s <<< FAILURE!
java.lang.AssertionError: 
Expecting an empty Optional but was containing value: SnapshotWrite[id=6, inserted=false]
	at de.visterion.dracul.depot.DepotEquitySnapshotRepositoryIT.reconstructedNeverOverwritesMeasured(DepotEquitySnapshotRepositoryIT.java:259)
```

**Rationale:** Without this WHERE clause guard, the upsert logic would update a MEASURED row even though the test explicitly expects the operation to fail (return empty). This allows measured data to be overwritten by reconstructions, violating the core rule.

### Mutation 3: Removed `AND source = 'MEASURED'` from `firstMeasured()` WHERE clause

**Affected Test:** `firstMeasuredSkipsReconstructedRows`

**Failure Output:**
```
[ERROR] de.visterion.dracul.depot.DepotEquitySnapshotRepositoryIT.firstMeasuredSkipsReconstructedRows -- Time elapsed: 0.008 s <<< FAILURE!
org.opentest4j.AssertionFailedError: 
expected: 2026-01-05T00:00:00Z
 but was: 2026-01-02T00:00:00Z
	at de.visterion.dracul.depot.DepotEquitySnapshotRepositoryIT.firstMeasuredSkipsReconstructedRows(DepotEquitySnapshotRepositoryIT.java:301)
```

**Rationale:** Without this filter, firstMeasured() returns the oldest row regardless of source. In the test, a RECONSTRUCTED row from 2026-01-02 exists alongside a MEASURED row from 2026-01-05. The function must skip the reconstructed row and return the measured one, otherwise the backfill anchor would drift further from the broker on each re-run.

## Commits

- **7f90a90a**: `feat(depot): measured snapshots outrank reconstructed ones in SQL`
  - Modified: `DepotEquitySnapshotRepository.java` (added upsertReconstructed, firstMeasured; enhanced upsert)
  - Modified: `DepotEquitySnapshotRepositoryIT.java` (added 6 new tests; updated 1 existing test)

## Test Summary

- **19 DepotEquitySnapshotRepositoryIT tests**: All passing ✓
- **370 total integration/unit tests**: All passing ✓
- **Build**: Success

## Notes

- No migration was added (table schema requires no changes; V47 already has the required source column with correct constraints)
- The updated test name `updatePathLeavesExternalFlowUntouchedAndSetsSourceToMeasured` better reflects the new behavior where source IS changed to MEASURED
- All code follows established patterns from the codebase (e.g., SpinCandidateRepository for xmax usage pattern)
- The constraint enforcement is 100% database-side; no caller-level checks that could be accidentally skipped in refactoring

---

## Fix Round 1: Test Coverage for Backfill Re-run Path

### Finding

The review identified a gap: the correcting path of `upsertReconstructed` (the DO UPDATE branch allowing re-runs to correct previously backfilled values) had no test coverage. The mutation check proved that deleting the entire SET body left all original tests green.

### Resolution

**Test Added:** `reconstructedRerunWithNewNumbersCorrectsTheRow`

This test verifies the critical backfill re-run scenario:
1. Initial backfill writes a RECONSTRUCTED row with equity 100.00, cash 40.00
2. Book improves; backfill re-runs with equity 120.00, cash 50.00
3. The row is corrected (not skipped)
4. Source remains RECONSTRUCTED

**Coverage Changes:**
- Initial: 19 tests (6 new for the feature)
- After fix: 20 tests (7 new, including this re-run scenario)

### Mutation Check for New Test

**Mutation:** Removed the SET clause from `upsertReconstructed`'s DO UPDATE:

```sql
ON CONFLICT (connection, granularity, as_of) DO NOTHING  -- Instead of DO UPDATE SET ...
```

**Test Result:** FAILED as expected

**Failure Output:**
```
[ERROR] de.visterion.dracul.depot.DepotEquitySnapshotRepositoryIT.reconstructedRerunWithNewNumbersCorrectsTheRow -- Time elapsed: 0.003 s <<< FAILURE!
java.lang.AssertionError: 

Expecting Optional to contain a value but it was empty.
	at de.visterion.dracul.depot.DepotEquitySnapshotRepositoryIT.reconstructedRerunWithNewNumbersCorrectsTheRow(DepotEquitySnapshotRepositoryIT.java:322)
```

**Why Expected:** Without the SET clause (DO NOTHING on conflict), the second upsertReconstructed call returns empty instead of a SnapshotWrite. The test expects `.isPresent()` to be true, confirming the database accepted the update.

### Verification

**Command:**
```bash
cd java-server && JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-amd64 ./mvnw verify -Dit.test=DepotEquitySnapshotRepositoryIT -DfailIfNoTests=false
```

**Final Status:** 20 tests passing, BUILD SUCCESS

### Commit

- **Fix commit:** Updates appended to the same branch; test added, mutation checked, report updated.
