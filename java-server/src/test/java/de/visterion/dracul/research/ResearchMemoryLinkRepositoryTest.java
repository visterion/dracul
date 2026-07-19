package de.visterion.dracul.research;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class ResearchMemoryLinkRepositoryTest {

    @Autowired ResearchMemoryLinkRepository repo;

    @Test
    void insertAndFindByPreyRefIdRoundTrips() {
        String refId = UUID.randomUUID().toString();
        long id = repo.insert("prey", refId, "ACME", "cell-123");

        var found = repo.findByPreyRefId(refId);

        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(id);
        assertThat(found.get().kind()).isEqualTo("prey");
        assertThat(found.get().refId()).isEqualTo(refId);
        assertThat(found.get().symbol()).isEqualTo("ACME");
        assertThat(found.get().cellId()).isEqualTo("cell-123");
        assertThat(found.get().createdAt()).isNotNull();
        assertThat(found.get().outcomeWritten()).isFalse();
    }

    @Test
    void duplicateKindAndRefIdViolatesUniqueConstraint() {
        String refId = UUID.randomUUID().toString();
        repo.insert("prey", refId, "ACME", "cell-123");

        assertThatThrownBy(() -> repo.insert("prey", refId, "ACME", "cell-456"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findUnwrittenPreyLinksExcludesOutcomeWrittenRows() {
        String refIdUnwritten = UUID.randomUUID().toString();
        String refIdWritten = UUID.randomUUID().toString();
        repo.insert("prey", refIdUnwritten, "UNWCO", "cell-unw");
        long writtenId = repo.insert("prey", refIdWritten, "WRICO", "cell-wri");
        repo.markOutcomeWritten(writtenId);

        var unwritten = repo.findUnwrittenPreyLinks(1000);

        assertThat(unwritten).anySatisfy(l -> assertThat(l.refId()).isEqualTo(refIdUnwritten));
        assertThat(unwritten).noneSatisfy(l -> assertThat(l.refId()).isEqualTo(refIdWritten));
    }

    @Test
    void markOutcomeWrittenFlipsFlagAndIsIdempotent() {
        String refId = UUID.randomUUID().toString();
        long id = repo.insert("prey", refId, "FLIPCO", "cell-flip");

        repo.markOutcomeWritten(id);
        repo.markOutcomeWritten(id); // second call must not error

        assertThat(repo.findByPreyRefId(refId)).isPresent();
        assertThat(repo.findByPreyRefId(refId).get().outcomeWritten()).isTrue();
    }

    @Test
    void markExcludedRemovesRowFromUnwrittenScan() {
        String refId = UUID.randomUUID().toString();
        long id = repo.insert("prey", refId, "EXCLCO", "cell-excl");

        repo.markExcluded(id);

        var unwritten = repo.findUnwrittenPreyLinks(1000);
        assertThat(unwritten).noneSatisfy(l -> assertThat(l.refId()).isEqualTo(refId));
    }
}
