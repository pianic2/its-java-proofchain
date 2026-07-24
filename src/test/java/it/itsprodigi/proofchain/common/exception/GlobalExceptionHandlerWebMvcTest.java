package it.itsprodigi.proofchain.common.exception;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ExceptionFixtureController.class)
@AutoConfigureMockMvc(addFilters = false)
class GlobalExceptionHandlerWebMvcTest {

    private static final String UTC_TIMESTAMP_PATTERN = "\\d{4}-\\d{2}-\\d{2}T.*Z";

    @Autowired
    private MockMvc mockMvc;

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
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("database password"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("IllegalStateException"))));
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
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("do-not-return-this"))));
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
