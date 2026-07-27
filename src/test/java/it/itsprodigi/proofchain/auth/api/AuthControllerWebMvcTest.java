package it.itsprodigi.proofchain.auth.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.itsprodigi.proofchain.auth.application.AuthenticationService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthControllerWebMvcTest {
    private final AuthenticationService service = mock(AuthenticationService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AuthController(service))
            .setMessageConverters(new MappingJackson2HttpMessageConverter())
            .build();

    @Test
    void validLoginReturnsJson() throws Exception {
        when(service.login(new LoginRequest("admin", "secret")))
                .thenReturn(new LoginResponse("redacted", "Bearer", Instant.parse("2026-01-01T00:30:00Z"), 1800));
        mvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"secret\"}"))
                .andExpect(status().isOk());
    }
}
