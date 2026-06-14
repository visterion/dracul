package de.visterion.dracul.agent;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class AgentDefinitionSchemaTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void tablesExist() {
        Integer defs = jdbc.sql(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'agent_definition' AND table_schema = 'public'")
                .query(Integer.class).single();
        Integer bindings = jdbc.sql(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'agent_tool_binding' AND table_schema = 'public'")
                .query(Integer.class).single();
        assertThat(defs).isEqualTo(1);
        assertThat(bindings).isEqualTo(1);
    }
}
