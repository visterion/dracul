package de.visterion.dracul;

import de.visterion.dracul.strigoi.spin.SpinCandidate;
import de.visterion.dracul.strigoi.spin.SpinCandidateRepository;
import de.visterion.dracul.strigoi.spin.SpinCandidateRow;
import de.visterion.dracul.strigoi.spin.SpinStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class SpinCandidateRepositoryIT {

    @Autowired SpinCandidateRepository repo;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM spin_candidate").update();
    }

    private static SpinCandidate candidate(String cik, String symbol, String name) {
        return new SpinCandidate(symbol, name, "10-12B", "2026-05-20", "https://sec/" + name, cik);
    }

    private long idByCompany(String name) {
        return jdbc.sql("SELECT id FROM spin_candidate WHERE company_name = :n")
                .param("n", name).query(Long.class).single();
    }

    @Test
    void upsertRegisteredInsertsAndMapsColumns() {
        boolean inserted = repo.upsertRegistered(candidate("0000000101", "SPN", "Repo Alpha Spinco"));
        assertThat(inserted).isTrue();

        SpinCandidateRow row = repo.findById(idByCompany("Repo Alpha Spinco")).orElseThrow();
        assertThat(row.cik()).isEqualTo("0000000101");
        assertThat(row.symbol()).isEqualTo("SPN");
        assertThat(row.companyName()).isEqualTo("Repo Alpha Spinco");
        assertThat(row.formType()).isEqualTo("10-12B");
        assertThat(row.filingDate().toString()).isEqualTo("2026-05-20");
        assertThat(row.status()).isEqualTo(SpinStatus.REGISTERED);
        assertThat(row.termSheetAvailable()).isFalse();
        assertThat(row.discoveredAt()).isNotNull();
        assertThat(row.lastCheckedAt()).isNotNull();
    }

    @Test
    void emptySymbolNormalisedToNull() {
        repo.upsertRegistered(candidate("0000000102", "", "Repo NoTicker Spinco"));
        assertThat(repo.findById(idByCompany("Repo NoTicker Spinco")).orElseThrow().symbol()).isNull();
    }

    @Test
    void upsertOnConflictSameCikIsNoOp() {
        assertThat(repo.upsertRegistered(candidate("0000000103", "AAA", "Repo Conflict One"))).isTrue();
        // same CIK, different company string (an amendment) -> DO NOTHING, no second row
        assertThat(repo.upsertRegistered(candidate("0000000103", "AAA", "Repo Conflict One Amended"))).isFalse();

        Integer count = jdbc.sql("SELECT count(*) FROM spin_candidate WHERE cik = :c")
                .param("c", "0000000103").query(Integer.class).single();
        assertThat(count).isEqualTo(1);
        // original row preserved (not overwritten)
        assertThat(jdbc.sql("SELECT company_name FROM spin_candidate WHERE cik = :c")
                .param("c", "0000000103").query(String.class).single())
                .isEqualTo("Repo Conflict One");
    }

    @Test
    void upsertOnConflictNullCikCollapsesOnLowerCompanyName() {
        assertThat(repo.upsertRegistered(candidate(null, "BBB", "Repo Namekey Co"))).isTrue();
        // null CIK -> natural key degrades to lower(company_name); differing case still collides
        assertThat(repo.upsertRegistered(candidate(null, "BBB", "REPO NAMEKEY CO"))).isFalse();

        Integer count = jdbc.sql("SELECT count(*) FROM spin_candidate WHERE lower(company_name) = :n")
                .param("n", "repo namekey co").query(Integer.class).single();
        assertThat(count).isEqualTo(1);
    }

    // --- Ticker backfill on conflict (2026-08-08 spin-ticker-backfill) ---

    @Test
    void upsertBackfillsMissingSymbolOnConflictWithoutTouchingStatusOrSnapshots() {
        assertThat(repo.upsertRegistered(candidate("0000000910", null, "Repo Backfill Co"))).isTrue();
        long id = idByCompany("Repo Backfill Co");
        assertThat(repo.findById(id).orElseThrow().symbol()).isNull();

        // re-reading the same filing, the ticker now resolves -> ON CONFLICT DO UPDATE backfills it
        boolean insertedAgain = repo.upsertRegistered(candidate("0000000910", "ADIG", "Repo Backfill Co"));
        assertThat(insertedAgain).as("a backfilling upsert is not a fresh insert").isFalse();

        SpinCandidateRow after = repo.findById(id).orElseThrow();
        assertThat(after.symbol()).isEqualTo("ADIG");
        assertThat(after.status()).isEqualTo(SpinStatus.REGISTERED);
        assertThat(after.registeredSnapshot()).isNull();
        assertThat(after.distributedSnapshot()).isNull();
    }

    @Test
    void upsertNeverOverwritesAnAlreadyKnownSymbol() {
        assertThat(repo.upsertRegistered(candidate("0000000911", "ADIG", "Repo Keep Co"))).isTrue();
        long id = idByCompany("Repo Keep Co");

        assertThat(repo.upsertRegistered(candidate("0000000911", "WRONG", "Repo Keep Co"))).isFalse();

        assertThat(repo.findById(id).orElseThrow().symbol()).isEqualTo("ADIG");
    }

    @Test
    void upsertWithBlankSymbolLeavesAnUnresolvedSymbolNullNeverEmptyString() {
        assertThat(repo.upsertRegistered(candidate("0000000912", null, "Repo StaysNull Co"))).isTrue();
        long id = idByCompany("Repo StaysNull Co");

        assertThat(repo.upsertRegistered(candidate("0000000912", "", "Repo StaysNull Co"))).isFalse();

        assertThat(repo.findById(id).orElseThrow().symbol()).isNull();
    }

    @Test
    void upsertReturnValueIsFalseForBothAnIdenticalReplayAndABackfill() {
        assertThat(repo.upsertRegistered(candidate("0000000913", "MFP", "Repo ReturnValue Co"))).isTrue();
        // identical replay: DO UPDATE fires (same conflict key) but COALESCE keeps the existing
        // symbol -> xmax != 0 -> not counted as an insert
        assertThat(repo.upsertRegistered(candidate("0000000913", "MFP", "Repo ReturnValue Co"))).isFalse();
        // a genuinely backfilling upsert -> still not an insert, the row already existed
        assertThat(repo.upsertRegistered(candidate("0000000913", "MFP", "Repo ReturnValue Co"))).isFalse();
    }

    @Test
    void upsertBackfillOnANameKeyedRowNeverMutatesTheCompanyNameConflictKey() {
        // cik null -> the conflict key IS lower(company_name); a DO UPDATE must not touch that column
        assertThat(repo.upsertRegistered(candidate(null, null, "Repo Namekey Backfill Co"))).isTrue();
        long id = idByCompany("Repo Namekey Backfill Co");

        // same spin-co arrives again in different capitalisation, this time with a resolved ticker
        assertThat(repo.upsertRegistered(candidate(null, "MFP", "REPO NAMEKEY BACKFILL CO"))).isFalse();

        SpinCandidateRow row = repo.findById(id).orElseThrow();
        assertThat(row.symbol()).isEqualTo("MFP");
        assertThat(row.companyName()).as("company_name is the conflict key itself; must stay untouched")
                .isEqualTo("Repo Namekey Backfill Co");
    }

    @Test
    void advanceStatusIsGuardedCompareAndSet() {
        repo.upsertRegistered(candidate("0000000104", "CAS", "Repo CAS Spinco"));
        long id = idByCompany("Repo CAS Spinco");

        // wrong "from" -> no-op, row unchanged
        assertThat(repo.advanceStatus(id, SpinStatus.WHEN_ISSUED, SpinStatus.DISTRIBUTED)).isFalse();
        assertThat(repo.findById(id).orElseThrow().status()).isEqualTo(SpinStatus.REGISTERED);

        // matching "from" -> moves, stamps distributed_at
        assertThat(repo.advanceStatus(id, SpinStatus.REGISTERED, SpinStatus.DISTRIBUTED)).isTrue();
        SpinCandidateRow row = repo.findById(id).orElseThrow();
        assertThat(row.status()).isEqualTo(SpinStatus.DISTRIBUTED);
        assertThat(row.distributedAt()).isNotNull();

        // replaying the same transition is now a no-op (forward-only)
        assertThat(repo.advanceStatus(id, SpinStatus.REGISTERED, SpinStatus.DISTRIBUTED)).isFalse();
    }

    @Test
    void findNonTerminalExcludesSettledAndAbandoned() {
        repo.upsertRegistered(candidate("0000000201", "R1", "Repo Nonterm Registered"));
        repo.upsertRegistered(candidate("0000000202", "S1", "Repo Nonterm Settled"));
        repo.upsertRegistered(candidate("0000000203", "A1", "Repo Nonterm Abandoned"));

        long settledId = idByCompany("Repo Nonterm Settled");
        long abandonedId = idByCompany("Repo Nonterm Abandoned");
        repo.advanceStatus(settledId, SpinStatus.REGISTERED, SpinStatus.SETTLED);
        repo.advanceStatus(abandonedId, SpinStatus.REGISTERED, SpinStatus.ABANDONED);

        List<SpinCandidateRow> nonTerminal = repo.findNonTerminalOldestCheckedFirst(50);
        assertThat(nonTerminal).extracting(SpinCandidateRow::companyName)
                .contains("Repo Nonterm Registered")
                .doesNotContain("Repo Nonterm Settled", "Repo Nonterm Abandoned");
    }

    @Test
    void findDistributedUnpromotedRespectsStatusAndPromotedAt() {
        repo.upsertRegistered(candidate("0000000301", "D1", "Repo Dist Unpromoted"));
        repo.upsertRegistered(candidate("0000000302", "D2", "Repo Dist Promoted"));
        repo.upsertRegistered(candidate("0000000303", "R2", "Repo Still Registered"));

        long unpromoted = idByCompany("Repo Dist Unpromoted");
        long promoted = idByCompany("Repo Dist Promoted");
        repo.advanceStatus(unpromoted, SpinStatus.REGISTERED, SpinStatus.DISTRIBUTED);
        repo.advanceStatus(promoted, SpinStatus.REGISTERED, SpinStatus.DISTRIBUTED);
        jdbc.sql("UPDATE spin_candidate SET promoted_at = now(), promoted_prey_id = 'p1' WHERE id = :id")
                .param("id", promoted).update();

        List<SpinCandidateRow> rows = repo.findDistributedUnpromoted(50);
        assertThat(rows).extracting(SpinCandidateRow::companyName)
                .containsExactly("Repo Dist Unpromoted");
    }

    @Test
    void markPromotedIsGuardedCompareAndSet() {
        repo.upsertRegistered(candidate("0000000601", "PRM", "Repo MarkPromoted Co"));
        long id = idByCompany("Repo MarkPromoted Co");
        repo.advanceStatus(id, SpinStatus.REGISTERED, SpinStatus.DISTRIBUTED);

        // first stamp moves the row and links the prey
        assertThat(repo.markPromoted(id, "prey-1")).isTrue();
        SpinCandidateRow afterFirst = repo.findById(id).orElseThrow();
        assertThat(afterFirst.promotedAt()).isNotNull();
        assertThat(afterFirst.promotedPreyId()).isEqualTo("prey-1");

        // second call (different prey id) is a no-op: promoted_at IS NULL guard fails
        assertThat(repo.markPromoted(id, "prey-2")).isFalse();
        SpinCandidateRow afterSecond = repo.findById(id).orElseThrow();
        assertThat(afterSecond.promotedPreyId()).as("prey id not overwritten").isEqualTo("prey-1");
        assertThat(afterSecond.promotedAt()).as("promoted_at unchanged").isEqualTo(afterFirst.promotedAt());
    }

    @Test
    void findPromotableBySymbolBlankSymbolIsEmpty() {
        assertThat(repo.findPromotableBySymbol("")).isEmpty();
        assertThat(repo.findPromotableBySymbol("   ")).isEmpty();
        assertThat(repo.findPromotableBySymbol(null)).isEmpty();
    }

    @Test
    void findPromotableBySymbolExcludesNonDistributedRows() {
        repo.upsertRegistered(candidate("0000000701", "REGSYM", "Repo Promotable Registered"));
        // still REGISTERED -> not promotable even though the symbol matches
        assertThat(repo.findPromotableBySymbol("REGSYM")).isEmpty();
    }

    @Test
    void findPromotableBySymbolExcludesAlreadyPromotedRows() {
        repo.upsertRegistered(candidate("0000000702", "PROMSYM", "Repo Promotable Promoted"));
        long id = idByCompany("Repo Promotable Promoted");
        repo.advanceStatus(id, SpinStatus.REGISTERED, SpinStatus.DISTRIBUTED);
        repo.markPromoted(id, "prey-x");

        assertThat(repo.findPromotableBySymbol("PROMSYM")).isEmpty();
    }

    @Test
    void findPromotableBySymbolFindsDistributedUnpromotedMatch() {
        repo.upsertRegistered(candidate("0000000703", "OKSYM", "Repo Promotable Ok"));
        long id = idByCompany("Repo Promotable Ok");
        repo.advanceStatus(id, SpinStatus.REGISTERED, SpinStatus.DISTRIBUTED);

        SpinCandidateRow found = repo.findPromotableBySymbol("OKSYM").orElseThrow();
        assertThat(found.id()).isEqualTo(id);
        assertThat(found.status()).isEqualTo(SpinStatus.DISTRIBUTED);
    }

    @Test
    void findPromotableBySymbolPrefersNewestDistributedAtOnSymbolCollision() {
        repo.upsertRegistered(candidate("0000000801", "DUP", "Repo Dup Older"));
        repo.upsertRegistered(candidate("0000000802", "DUP", "Repo Dup Newer"));
        long older = idByCompany("Repo Dup Older");
        long newer = idByCompany("Repo Dup Newer");
        repo.advanceStatus(older, SpinStatus.REGISTERED, SpinStatus.DISTRIBUTED);
        repo.advanceStatus(newer, SpinStatus.REGISTERED, SpinStatus.DISTRIBUTED);
        // make the distribution timestamps distinct and deterministic
        jdbc.sql("UPDATE spin_candidate SET distributed_at = now() - interval '10 days' WHERE id = :id")
                .param("id", older).update();
        jdbc.sql("UPDATE spin_candidate SET distributed_at = now() WHERE id = :id")
                .param("id", newer).update();

        // ORDER BY distributed_at DESC NULLS LAST LIMIT 1 -> the newest
        SpinCandidateRow found = repo.findPromotableBySymbol("DUP").orElseThrow();
        assertThat(found.id()).isEqualTo(newer);
        assertThat(found.companyName()).isEqualTo("Repo Dup Newer");
    }

    @Test
    void storeSnapshotWritesJsonbAndReadsBack() {
        repo.upsertRegistered(candidate("0000000401", "SNP", "Repo Snapshot Co"));
        long id = idByCompany("Repo Snapshot Co");

        var node = tools.jackson.databind.json.JsonMapper.builder().build()
                .createObjectNode().put("totalAssets", 1234);
        assertThat(repo.storeSnapshot(id, SpinStatus.REGISTERED, node)).isTrue();

        SpinCandidateRow row = repo.findById(id).orElseThrow();
        assertThat(row.registeredSnapshot()).isNotNull();
        assertThat(row.registeredSnapshot().path("totalAssets").asInt()).isEqualTo(1234);
        assertThat(row.distributedSnapshot()).isNull();
    }
}
