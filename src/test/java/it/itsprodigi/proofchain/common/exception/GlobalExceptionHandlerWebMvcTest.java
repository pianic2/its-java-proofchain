package it.itsprodigi.proofchain.common.exception;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.itsprodigi.proofchain.auth.logging.AuthEventLogger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ExceptionFixtureController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ProblemDetailFactory.class)
class GlobalExceptionHandlerWebMvcTest {

    private static final String UTC_TIMESTAMP_PATTERN = "\\d{4}-\\d{2}-\\d{2}T.*Z";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthEventLogger authEventLogger;

    @Test
    void mapsResourceNotFoundToAProblemDetail() throws Exception {
        mockMvc.perform(post("/api/test/resource"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/resource-not-found"))
                .andExpect(jsonPath("$.title").value("Resource not found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("The requested resource was not found."))
                .andExpect(jsonPath("$.instance").value("/api/test/resource"))
                .andExpect(jsonPath("$.timestamp").value(matchesPattern(UTC_TIMESTAMP_PATTERN)));
    }

    @Test
    void sanitizesUnexpectedExceptions() throws Exception {
        mockMvc.perform(post("/api/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/internal-server-error"))
                .andExpect(jsonPath("$.title").value("Internal server error"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.instance").value("/api/test/unexpected"))
                .andExpect(jsonPath("$.timestamp").value(matchesPattern(UTC_TIMESTAMP_PATTERN)))
                .andExpect(content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("database password"))))
                .andExpect(content()
                        .string(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("IllegalStateException"))));
    }

    @Test
    void returnsSortedValidationErrorsWithoutRejectedValues() throws Exception {
        mockMvc.perform(post("/api/test/validation")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name":"","description":"x","secret":"do-not-return-this"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/validation-error"))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("One or more request fields are invalid."))
                .andExpect(jsonPath("$.instance").value("/api/test/validation"))
                .andExpect(jsonPath("$.timestamp").value(matchesPattern(UTC_TIMESTAMP_PATTERN)))
                .andExpect(jsonPath("$.errors[0].field").value("description"))
                .andExpect(jsonPath("$.errors[0].code").value("Size"))
                .andExpect(jsonPath("$.errors[1].field").value("name"))
                .andExpect(jsonPath("$.errors[1].code").value("NotBlank"))
                .andExpect(jsonPath("$.errors[2]").doesNotExist())
                .andExpect(content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("do-not-return-this"))));
    }

    @Test
    void mapsInvalidEvidenceStateToAStableConflict() throws Exception {
        mockMvc.perform(post("/api/test/evidence-state"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/invalid-evidence-state"))
                .andExpect(jsonPath("$.title").value("Invalid evidence state"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("Released evidence is terminal and cannot be modified."));
    }

    @Test
    void mapsCustodyEventConcurrencyConflictWithoutLeakingPersistenceDetail() throws Exception {
        mockMvc.perform(post("/api/test/custody-event-conflict"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
                .andExpect(
                        jsonPath("$.type").value("https://proofchain.dev/problems/custody-event-concurrency-conflict"))
                .andExpect(jsonPath("$.title").value("Custody event concurrency conflict"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail")
                        .value("The evidence was modified by another transaction. Retry using current data."))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("40P01"))))
                .andExpect(content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("deadlock detected"))));
    }

    @Test
    void mapsCustodyEventPersistenceFailureWithoutLeakingStorageDetail() throws Exception {
        mockMvc.perform(post("/api/test/custody-event-persistence"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
                .andExpect(
                        jsonPath("$.type").value("https://proofchain.dev/problems/custody-event-persistence-failure"))
                .andExpect(jsonPath("$.title").value("Custody event persistence failure"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("The custody event could not be persisted."))
                .andExpect(content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("content.bin"))));
    }

    @Test
    void returnsValidationErrorForAnInvalidSize() throws Exception {
        mockMvc.perform(post("/api/test/validation")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name":"valid","description":"x"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("description"))
                .andExpect(jsonPath("$.errors[0].code").value("Size"));
    }
}
