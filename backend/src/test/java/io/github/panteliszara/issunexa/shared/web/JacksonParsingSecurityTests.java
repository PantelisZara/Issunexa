package io.github.panteliszara.issunexa.shared.web;

import org.assertj.core.api.ThrowingConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JacksonParsingSecurityTests {

    private static final int MAX_NAME_LENGTH = 50_000;

    @Test
    void applicationMapperRejectsOversizedNamesBeforeReadingTheEntireField() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .run(context -> assertEarlyNameRejection(context.getBean(ObjectMapper.class)::readTree));
    }

    @Test
    void openApiMapperRejectsOversizedNamesBeforeReadingTheEntireField() throws Exception {
        assertEarlyNameRejection(io.swagger.v3.core.util.Json.mapper()::readTree);
    }

    private static void assertEarlyNameRejection(ThrowingConsumer<Reader> parse) throws Exception {
        // GHSA-649p-m576-vr99: rejecting only after buffering the entire name is too late.
        // Keep input small and measure characters consumed rather than timing or heap usage.
        try (CountingReader reader = new CountingReader("{\"" + "x".repeat(4 * MAX_NAME_LENGTH) + "\":null}")) {
            assertThatThrownBy(() -> parse.accept(reader))
                    .hasMessageContaining("Name length")
                    .hasMessageContaining(Integer.toString(MAX_NAME_LENGTH));
            assertThat(reader.charactersRead).isLessThan(2 * MAX_NAME_LENGTH);
        }
    }

    private static final class CountingReader extends StringReader {

        private int charactersRead;

        private CountingReader(String input) {
            super(input);
        }

        @Override
        public int read(char[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) {
                charactersRead += count;
            }
            return count;
        }
    }
}
