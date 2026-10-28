package com.wifibilling.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    @RestController
    static class ThrowingController {
        record Body(@NotBlank String name) {}

        @GetMapping("/t/reason") void reason() { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Router not found"); }
        @GetMapping("/t/no-reason") void noReason() { throw new ResponseStatusException(HttpStatus.CONFLICT); }
        @GetMapping("/t/server-reason") void serverReason() { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Portal unavailable"); }
        @GetMapping("/t/crash") void crash() { throw new IllegalStateException("provider said: secret-token-123"); }
        @GetMapping("/t/denied") void denied() { throw new AccessDeniedException("nope"); }
        @GetMapping("/t/param") void param(@RequestParam("token") String token) {}
        @PostMapping("/t/valid") void valid(@Valid @RequestBody Body body) {}
    }

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ThrowingController()).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void reasonFromResponseStatusExceptionReachesTheClient() throws Exception {
        mvc.perform(get("/t/reason"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Router not found"))
                .andExpect(jsonPath("$.path").value("/t/reason"))
                .andExpect(jsonPath("$.reference").doesNotExist());
    }

    @Test
    void missingReasonFallsBackToStatusPhrase() throws Exception {
        mvc.perform(get("/t/no-reason"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Conflict"));
    }

    @Test
    void deliberateServerErrorKeepsItsReasonAndGetsAReference() throws Exception {
        mvc.perform(get("/t/server-reason"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Portal unavailable"))
                .andExpect(jsonPath("$.reference").value(matchesPattern("[0-9a-f]{8}")));
    }

    @Test
    void unexpectedExceptionIsGenericAndNeverLeaksItsText() throws Exception {
        mvc.perform(get("/t/crash"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value(containsString("Something went wrong")))
                .andExpect(jsonPath("$.reference").value(matchesPattern("[0-9a-f]{8}")))
                .andExpect(content().string(not(containsString("secret-token-123"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    @Test
    void methodSecurityDenialStaysForbidden() throws Exception {
        mvc.perform(get("/t/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You don't have permission to do that."));
    }

    @Test
    void springMvcErrorsKeepTheirStatus() throws Exception {
        mvc.perform(get("/t/param")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());
        mvc.perform(post("/t/reason")).andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/t/valid").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validationErrorNamesTheField() throws Exception {
        mvc.perform(post("/t/valid").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("name")));
    }
}
