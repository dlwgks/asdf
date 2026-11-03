package com.asdf.board.be;

import com.asdf.board.be.global.security.TokenService;
import com.asdf.board.be.support.TestMailConfig;
import com.asdf.board.be.user.entity.User;
import com.asdf.board.be.user.repository.UserRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestMailConfig.class)
@Transactional
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class AiCoachServerTest {

    private static final HttpServer COACH = startCoach();
    private static volatile int coachStatus = 200;
    private static volatile String lastRequest = "";

    private static HttpServer startCoach() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat", exchange -> {
                lastRequest = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                byte[] body = (coachStatus == 200 ? "{\"reply\":\"테스트 코치 답변\"}" : "{}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(coachStatus, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void coachUrl(DynamicPropertyRegistry registry) {
        registry.add("vitality.coach.server-url", () -> "http://127.0.0.1:" + COACH.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        COACH.stop(0);
    }

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired TokenService tokenService;

    private String token;

    @BeforeEach
    void setUp() {
        coachStatus = 200;
        lastRequest = "";
        User user = userRepository.save(User.builder().email("coach@vitality.dev")
                .passwordHash(passwordEncoder.encode("test1234")).nickname("CoachUser").role(User.Role.user).build());
        token = tokenService.issue(user).value();
    }

    @Test
    void 코치_서버_답변을_저장하고_회원정보를_함께_보낸다() throws Exception {
        mvc.perform(post("/api/ai-coach/messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"운동 추천해줘\"}").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_message.content").value("운동 추천해줘"))
                .andExpect(jsonPath("$.assistant_message.content").value("테스트 코치 답변"));

        assertThat(lastRequest).contains("[회원 정보]").contains("운동 추천해줘").contains("\"role\":\"user\"");

        mvc.perform(get("/api/ai-coach/messages").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.messages.length()").value(2));
    }

    @Test
    void 코치_서버가_실패하면_502이고_질문을_저장하지_않는다() throws Exception {
        coachStatus = 500;

        mvc.perform(post("/api/ai-coach/messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"운동 추천해줘\"}").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"));

        mvc.perform(get("/api/ai-coach/messages").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.messages.length()").value(0));
    }
}
