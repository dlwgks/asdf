package com.asdf.board.be;

import com.asdf.board.be.global.security.TokenService;
import com.asdf.board.be.support.TestMailConfig;
import com.asdf.board.be.user.entity.User;
import com.asdf.board.be.user.repository.UserRepository;
import com.asdf.board.be.workout.repository.ExerciseRepository;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestMailConfig.class)
@Transactional
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class DefectFixesTest {

    private static final HttpServer AI = startAi();

    private static HttpServer startAi() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/analyze", exchange -> {
                exchange.getRequestBody().readAllBytes();
                byte[] body = "{\"detail\":{\"code\":\"IMAGE_BLURRY\",\"message\":\"사진이 흐리거나 단색이에요. 다시 촬영해 주세요.\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(422, body.length);
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
    static void aiUrl(DynamicPropertyRegistry registry) {
        registry.add("vitality.ai.server-url", () -> "http://127.0.0.1:" + AI.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        AI.stop(0);
    }

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired TokenService tokenService;
    @Autowired ExerciseRepository exerciseRepository;

    private String token;
    private String otherToken;

    private String tokenFor(String email, String nickname) {
        User user = userRepository.save(User.builder().email(email)
                .passwordHash(passwordEncoder.encode("test1234")).nickname(nickname).role(User.Role.user).build());
        return tokenService.issue(user).value();
    }

    @BeforeEach
    void setUp() {
        token = tokenFor("defect-a@vitality.dev", "DefectA");
        otherToken = tokenFor("defect-b@vitality.dev", "DefectB");
    }

    private String auth(String t) {
        return "Bearer " + t;
    }

    private byte[] jpegBytes() {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 1, 2, 3, 4, 5};
    }

    @Test
    void 기기_토큰을_등록하고_다시_등록해도_성공한다() throws Exception {
        mvc.perform(post("/api/notifications/device-tokens").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fcm_token\":\"token-1\",\"platform\":\"ios\"}").header("Authorization", auth(token)))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/notifications/device-tokens").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fcm_token\":\"token-2\",\"platform\":\"ios\"}").header("Authorization", auth(token)))
                .andExpect(status().isCreated());
    }

    @Test
    void 이미지가_아닌_파일_업로드는_400() throws Exception {
        MockMultipartFile text = new MockMultipartFile("image", "a.jpg", "image/jpeg", "not an image".getBytes());
        mvc.perform(multipart("/api/photos").file(text).param("type", "current").param("side", "front")
                        .header("Authorization", auth(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IMAGE"));
    }

    @Test
    void 사진_type_값이_잘못되면_500이_아니라_400() throws Exception {
        MockMultipartFile image = new MockMultipartFile("image", "a.jpg", "image/jpeg", jpegBytes());
        mvc.perform(multipart("/api/photos").file(image).file(new MockMultipartFile("type", "", "text/plain", "nonsense".getBytes()))
                        .header("Authorization", auth(token)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void AI_실패_문구는_AI가_준_메시지이고_시각은_오프셋을_가진다() throws Exception {
        MockMultipartFile image = new MockMultipartFile("image", "front.jpg", "image/jpeg", jpegBytes());
        String photo = mvc.perform(multipart("/api/photos").file(image).param("type", "current").param("side", "front")
                        .header("Authorization", auth(token)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Long photoId = com.jayway.jsonpath.JsonPath.parse(photo).read("$.photo_id", Long.class);

        String created = mvc.perform(post("/api/body-analyses/photo").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"photo_id\":" + photoId + "}").header("Authorization", auth(token)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Long id = com.jayway.jsonpath.JsonPath.parse(created).read("$.analysis_id", Long.class);

        mvc.perform(get("/api/body-analyses/" + id).header("Authorization", auth(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.error_code").value("IMAGE_BLURRY"))
                .andExpect(jsonPath("$.error_message").value("사진이 흐리거나 단색이에요. 다시 촬영해 주세요."))
                .andExpect(jsonPath("$.created_at").value(matchesPattern(".*\\+09:00")));
    }

    private Long startWorkout() throws Exception {
        Long exerciseId = exerciseRepository.findAll().get(0).getId();
        String res = mvc.perform(post("/api/workout-logs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"exercise_id\":" + exerciseId + "}").header("Authorization", auth(token)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.parse(res).read("$.log_id", Long.class);
    }

    @Test
    void 운동_세트의_음수_무게와_0회는_400() throws Exception {
        Long id = startWorkout();
        mvc.perform(post("/api/workout-logs/" + id + "/sets").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weight_kg\":-5,\"reps\":10}").header("Authorization", auth(token)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/workout-logs/" + id + "/sets").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weight_kg\":20,\"reps\":0}").header("Authorization", auth(token)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/workout-logs/" + id + "/sets").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weight_kg\":\"abc\",\"reps\":5}").header("Authorization", auth(token)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/workout-logs/" + id + "/sets").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weight_kg\":20,\"reps\":10}").header("Authorization", auth(token)))
                .andExpect(status().isCreated());
    }

    @Test
    void 완료된_운동_기록에는_세트를_추가할_수_없다() throws Exception {
        Long id = startWorkout();
        mvc.perform(patch("/api/workout-logs/" + id + "/complete").contentType(MediaType.APPLICATION_JSON)
                        .content("{}").header("Authorization", auth(token)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/workout-logs/" + id + "/sets").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weight_kg\":20,\"reps\":10}").header("Authorization", auth(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKOUT_ALREADY_COMPLETED"));
    }

    @Test
    void 운동_종료_응답은_앱이_쓰는_필드를_모두_담는다() throws Exception {
        Long id = startWorkout();
        mvc.perform(post("/api/workout-logs/" + id + "/sets").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weight_kg\":123,\"reps\":10}").header("Authorization", auth(token)))
                .andExpect(status().isCreated());
        mvc.perform(patch("/api/workout-logs/" + id + "/complete").contentType(MediaType.APPLICATION_JSON)
                        .content("{}").header("Authorization", auth(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed_at").value(matchesPattern(".*\\+09:00")))
                .andExpect(jsonPath("$.set_count").value(1))
                .andExpect(jsonPath("$.total_volume_kg").value(1230.0));
        mvc.perform(get("/api/workout-logs").param("start_date", "2000-01-01").param("end_date", "2999-12-31").header("Authorization", auth(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].set_count").value(1))
                .andExpect(jsonPath("$.items[0].total_volume_kg").value(1230.0));
    }

    @Test
    void 댓글_작성은_생성된_댓글을_돌려주고_본인_글_신고는_거절된다() throws Exception {
        String post = mvc.perform(multipart("/api/posts")
                        .file(new MockMultipartFile("category", "", "text/plain", "info".getBytes()))
                        .file(new MockMultipartFile("title", "", "text/plain", "제목".getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("content", "", "text/plain", "내용".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", auth(token)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Long postId = com.jayway.jsonpath.JsonPath.parse(post).read("$.post_id", Long.class);

        mvc.perform(post("/api/posts/" + postId + "/comments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"댓글\"}").header("Authorization", auth(otherToken)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.comment_id").exists())
                .andExpect(jsonPath("$.content").value("댓글"));

        mvc.perform(post("/api/reports").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target_type\":\"post\",\"target_id\":" + postId + ",\"reason\":\"spam\"}")
                        .header("Authorization", auth(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CANNOT_REPORT_SELF"));
        mvc.perform(post("/api/reports").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target_type\":\"post\",\"target_id\":" + postId + ",\"reason\":\"spam\"}")
                        .header("Authorization", auth(otherToken)))
                .andExpect(status().isCreated());
    }

    @Test
    void 지원하지_않는_요청_형식은_통일된_오류_형식() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void 로그아웃은_204() throws Exception {
        mvc.perform(post("/api/auth/logout").header("Authorization", auth(token)))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/ai-coach/messages").header("Authorization", auth(token)))
                .andExpect(status().isOk());
    }
}
