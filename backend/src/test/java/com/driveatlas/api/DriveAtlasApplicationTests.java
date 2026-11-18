package com.driveatlas.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.http.MediaType;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DriveAtlasApplicationTests {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void applicationContextLoads() {
    }

    @Test
    void unauthenticatedApiRequestReturnsStructuredUnauthorizedResponse() throws Exception {
        mockMvc.perform(get("/api/v1/session"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void landingPageAndPrivacyNoticeArePublic() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/privacy.html"))
                .andExpect(status().isOk());
    }

    @Test
    void blankManualTagIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/resources/file-id/tags")
                        .with(user("test-user"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void folderScanRequiresAnAuthenticatedSession() throws Exception {
        mockMvc.perform(post("/api/v1/resources/folder-id/scan")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resources\":[]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void contentEndpointsRequireAnAuthenticatedSession() throws Exception {
        mockMvc.perform(get("/api/v1/resources/file-id/content"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/resources/file-id/tag-suggestions")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"a sufficiently long document excerpt\"}"))
                .andExpect(status().isUnauthorized());
    }
}
