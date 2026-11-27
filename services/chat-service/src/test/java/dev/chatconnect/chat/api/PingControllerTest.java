package dev.chatconnect.chat.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.chatconnect.chat.observability.RequestTraceFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

// The slice has no tracing auto-config; RequestTraceFilter is covered by ObservabilityTest (full context).
@WebMvcTest(controllers = PingController.class, excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE, classes = RequestTraceFilter.class))
class PingControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void pingReturnsServiceIdentity() throws Exception {
        mvc.perform(get("/api/chat/v1/ping"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"service\":\"chat-service\",\"status\":\"ok\"}", true));
    }

    @Test
    void pingIsReadOnly() throws Exception {
        mvc.perform(post("/api/chat/v1/ping")).andExpect(status().isMethodNotAllowed());
    }
}
