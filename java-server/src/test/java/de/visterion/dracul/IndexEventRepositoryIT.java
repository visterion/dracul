package de.visterion.dracul;

import de.visterion.dracul.hunting.agora.IndexChangeEvent;
import de.visterion.dracul.strigoi.index.IndexEventRepository;
import de.visterion.dracul.strigoi.index.IndexEventRow;
import de.visterion.dracul.strigoi.index.IndexEventStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class IndexEventRepositoryIT {

    @Autowired IndexEventRepository repo;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM index_event").update();
    }

    private static IndexChangeEvent change(String index, String symbol, String action, String effective) {
        return new IndexChangeEvent(symbol, "", index, action,
                LocalDate.parse("2026-06-18"), LocalDate.parse(effective), "sp_press");
    }

    private long idBySymbol(String symbol) {
        return jdbc.sql("SELECT id FROM index_event WHERE symbol = :s")
                .param("s", symbol).query(Long.class).single();
    }

    @Test
    void upsertAnnouncedInsertsAndMapsColumns() {
        assertThat(repo.upsertAnnounced(change("sp500", "AAA", "add", "2026-06-24"))).isTrue();

        IndexEventRow row = repo.findById(idBySymbol("AAA")).orElseThrow();
        assertThat(row.symbol()).isEqualTo("AAA");
        assertThat(row.indexName()).isEqualTo("sp500");
        assertThat(row.action()).isEqualTo("add");
        assertThat(row.source()).isEqualTo("sp_press");
        assertThat(row.announcementDate()).isEqualTo(LocalDate.parse("2026-06-18"));
        assertThat(row.effectiveDate()).isEqualTo(LocalDate.parse("2026-06-24"));
        assertThat(row.status()).isEqualTo(IndexEventStatus.ANNOUNCED);
        assertThat(row.companyName()).isNull();       // blank normalised to null
        assertThat(row.discoveredAt()).isNotNull();
        assertThat(row.lastCheckedAt()).isNotNull();
    }

    @Test
    void upsertOnConflictSameNaturalKeyIsNoOp() {
        assertThat(repo.upsertAnnounced(change("sp500", "BBB", "add", "2026-06-24"))).isTrue();
        // same (index, upper(symbol), action, effective_date), differing symbol case -> DO NOTHING
        assertThat(repo.upsertAnnounced(change("sp500", "bbb", "add", "2026-06-24"))).isFalse();

        Integer count = jdbc.sql("SELECT count(*) FROM index_event WHERE upper(symbol) = 'BBB'")
                .query(Integer.class).single();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void upsertDistinguishesActionAndEffectiveDate() {
        // Same symbol/index but different action AND different effective date are distinct rows.
        assertThat(repo.upsertAnnounced(change("sp500", "CCC", "add", "2026-06-24"))).isTrue();
        assertThat(repo.upsertAnnounced(change("sp500", "CCC", "remove", "2026-07-24"))).isTrue();

        Integer count = jdbc.sql("SELECT count(*) FROM index_event WHERE symbol = 'CCC'")
                .query(Integer.class).single();
        assertThat(count).isEqualTo(2);
    }

    @Test
    void advanceStatusIsGuardedCompareAndSet() {
        repo.upsertAnnounced(change("sp500", "CAS", "add", "2026-06-24"));
        long id = idBySymbol("CAS");

        // wrong "from" -> no-op
        assertThat(repo.advanceStatus(id, IndexEventStatus.POST, IndexEventStatus.CLOSED)).isFalse();
        assertThat(repo.findById(id).orElseThrow().status()).isEqualTo(IndexEventStatus.ANNOUNCED);

        // matching "from" -> moves, stamps effective_at
        assertThat(repo.advanceStatus(id, IndexEventStatus.ANNOUNCED, IndexEventStatus.EFFECTIVE)).isTrue();
        IndexEventRow row = repo.findById(id).orElseThrow();
        assertThat(row.status()).isEqualTo(IndexEventStatus.EFFECTIVE);
        assertThat(row.effectiveAt()).isNotNull();

        // replay is a no-op (forward-only)
        assertThat(repo.advanceStatus(id, IndexEventStatus.ANNOUNCED, IndexEventStatus.EFFECTIVE)).isFalse();

        // EFFECTIVE -> POST has no timestamp column; CLOSED stamps closed_at
        assertThat(repo.advanceStatus(id, IndexEventStatus.EFFECTIVE, IndexEventStatus.POST)).isTrue();
        assertThat(repo.advanceStatus(id, IndexEventStatus.POST, IndexEventStatus.CLOSED)).isTrue();
        assertThat(repo.findById(id).orElseThrow().closedAt()).isNotNull();
    }

    @Test
    void findNonTerminalExcludesClosedAndAbandoned() {
        repo.upsertAnnounced(change("sp500", "N1", "add", "2026-06-24"));
        repo.upsertAnnounced(change("sp500", "C1", "add", "2026-06-25"));
        repo.upsertAnnounced(change("sp500", "A1", "add", "2026-06-26"));

        long closed = idBySymbol("C1");
        long abandoned = idBySymbol("A1");
        repo.advanceStatus(closed, IndexEventStatus.ANNOUNCED, IndexEventStatus.CLOSED);
        repo.advanceStatus(abandoned, IndexEventStatus.ANNOUNCED, IndexEventStatus.ABANDONED);

        List<IndexEventRow> nonTerminal = repo.findNonTerminalOldestCheckedFirst(50);
        assertThat(nonTerminal).extracting(IndexEventRow::symbol)
                .contains("N1").doesNotContain("C1", "A1");
    }

    @Test
    void findActiveUnpromotedRespectsStatusAndPromotedAt() {
        repo.upsertAnnounced(change("sp500", "U1", "add", "2026-06-24"));   // active, unpromoted
        repo.upsertAnnounced(change("sp500", "P1", "add", "2026-06-25"));   // active, promoted
        repo.upsertAnnounced(change("sp500", "T1", "add", "2026-06-26"));   // terminal (closed)

        long promoted = idBySymbol("P1");
        long terminal = idBySymbol("T1");
        jdbc.sql("UPDATE index_event SET promoted_at = now(), promoted_prey_id = 'p1' WHERE id = :id")
                .param("id", promoted).update();
        repo.advanceStatus(terminal, IndexEventStatus.ANNOUNCED, IndexEventStatus.CLOSED);

        List<IndexEventRow> rows = repo.findActiveUnpromoted(50);
        assertThat(rows).extracting(IndexEventRow::symbol).containsExactly("U1");
    }

    @Test
    void findPromotableBySymbolBlankIsEmpty() {
        assertThat(repo.findPromotableBySymbol("")).isEmpty();
        assertThat(repo.findPromotableBySymbol("   ")).isEmpty();
        assertThat(repo.findPromotableBySymbol(null)).isEmpty();
    }

    @Test
    void findPromotableBySymbolFindsAnnouncedUnpromotedMatchCaseInsensitively() {
        repo.upsertAnnounced(change("sp500", "OKS", "add", "2026-06-24"));
        IndexEventRow found = repo.findPromotableBySymbol("oks").orElseThrow();
        assertThat(found.symbol()).isEqualTo("OKS");
        assertThat(found.status()).isEqualTo(IndexEventStatus.ANNOUNCED);
    }

    @Test
    void findPromotableBySymbolExcludesNonAnnouncedAndPromoted() {
        repo.upsertAnnounced(change("sp500", "EFF", "add", "2026-06-24"));
        long eff = idBySymbol("EFF");
        repo.advanceStatus(eff, IndexEventStatus.ANNOUNCED, IndexEventStatus.EFFECTIVE);
        assertThat(repo.findPromotableBySymbol("EFF")).as("EFFECTIVE not promotable").isEmpty();

        repo.upsertAnnounced(change("sp500", "PRM", "add", "2026-06-25"));
        long prm = idBySymbol("PRM");
        repo.markPromoted(prm, "prey-x");
        assertThat(repo.findPromotableBySymbol("PRM")).as("already promoted not promotable").isEmpty();
    }

    @Test
    void markPromotedIsGuardedCompareAndSet() {
        repo.upsertAnnounced(change("sp500", "MRK", "add", "2026-06-24"));
        long id = idBySymbol("MRK");

        assertThat(repo.markPromoted(id, "prey-1")).isTrue();
        IndexEventRow afterFirst = repo.findById(id).orElseThrow();
        assertThat(afterFirst.promotedAt()).isNotNull();
        assertThat(afterFirst.promotedPreyId()).isEqualTo("prey-1");

        // second call (different prey) is a no-op: promoted_at IS NULL guard fails
        assertThat(repo.markPromoted(id, "prey-2")).isFalse();
        IndexEventRow afterSecond = repo.findById(id).orElseThrow();
        assertThat(afterSecond.promotedPreyId()).isEqualTo("prey-1");
        assertThat(afterSecond.promotedAt()).isEqualTo(afterFirst.promotedAt());
    }

    @Test
    void storeSnapshotWritesJsonbAndReadsBack() {
        repo.upsertAnnounced(change("sp500", "SNP", "add", "2026-06-24"));
        long id = idBySymbol("SNP");

        var node = tools.jackson.databind.json.JsonMapper.builder().build()
                .createObjectNode().put("adv", 1234);
        assertThat(repo.storeSnapshot(id, IndexEventStatus.ANNOUNCED, node)).isTrue();

        IndexEventRow row = repo.findById(id).orElseThrow();
        assertThat(row.announcedSnapshot()).isNotNull();
        assertThat(row.announcedSnapshot().path("adv").asInt()).isEqualTo(1234);
        assertThat(row.postSnapshot()).isNull();
    }

    @Test
    void touchLastCheckedBumpsWithoutStateChange() {
        repo.upsertAnnounced(change("sp500", "TCH", "add", "2026-06-24"));
        long id = idBySymbol("TCH");
        assertThat(repo.touchLastChecked(id)).isTrue();
        assertThat(repo.findById(id).orElseThrow().status()).isEqualTo(IndexEventStatus.ANNOUNCED);
    }
}
