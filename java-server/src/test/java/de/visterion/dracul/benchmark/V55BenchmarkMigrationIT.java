package de.visterion.dracul.benchmark;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class V55BenchmarkMigrationIT {

    @Autowired JdbcClient jdbc;

    private static String uniqueSymbol() {
        return "SYNM" + Long.toString(System.nanoTime(), 36).toUpperCase(Locale.ROOT);
    }

    @Test
    void screenCandidateIsUniquePerAgentDayAndSymbol() {
        String sym = uniqueSymbol();
        String sql = """
                INSERT INTO screen_candidate (id, agent, fetched_at, as_of_date, symbol, source)
                VALUES (:id, 'strigoi-synthetic', now(), DATE '2001-02-03', :sym, 'LIVE')
                ON CONFLICT (agent, as_of_date, symbol) DO NOTHING""";
        int first = jdbc.sql(sql).param("id", UUID.randomUUID()).param("sym", sym).update();
        int second = jdbc.sql(sql).param("id", UUID.randomUUID()).param("sym", sym).update();
        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
    }

    @Test
    void benchmarkOutcomeRejectsAPickedSignalAndAnUnknownExitKind() {
        assertThatThrownBy(() -> insertOutcome("SIGNAL", true, "NORMAL"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertOutcome("CANDIDATE", true, "SOMETHING_ELSE"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertOutcome("CANDIDATE", null, "NORMAL"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(insertOutcome("CANDIDATE", false, "NO_ENTRY")).isEqualTo(1);
    }

    @Test
    void noDataTableKeysBySymbol() {
        String sym = uniqueSymbol();
        jdbc.sql("INSERT INTO benchmark_symbol_no_data (symbol, nights, last_night) VALUES (:s, 1, DATE '2001-02-03')")
                .param("s", sym).update();
        assertThatThrownBy(() -> jdbc.sql(
                "INSERT INTO benchmark_symbol_no_data (symbol, nights, last_night) VALUES (:s, 1, DATE '2001-02-04')")
                .param("s", sym).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private int insertOutcome(String kind, Boolean picked, String exitKind) {
        return jdbc.sql("""
                INSERT INTO benchmark_outcome (kind, agent, symbol, event_start_date, entry_ref_date,
                                               entry_ref_at, picked, horizon_days, exit_kind)
                VALUES (:kind, 'strigoi-synthetic', :sym, DATE '2001-02-03', DATE '2001-02-03',
                        now(), :picked, 20, :exitKind)""")
                .param("kind", kind)
                .param("sym", uniqueSymbol())
                .param("picked", picked)
                .param("exitKind", exitKind)
                .update();
    }
}
