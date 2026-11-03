package com.asdf.board.be;

import com.asdf.board.be.body.repository.GoalBodyRepository;
import com.asdf.board.be.body.repository.PhotoRepository;
import com.asdf.board.be.global.security.TokenService;
import com.asdf.board.be.user.entity.User;
import com.asdf.board.be.user.repository.UserRepository;
import com.asdf.board.be.support.TestMailConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestMailConfig.class)
@Transactional
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class GoalBodyFlowTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired TokenService tokenService;
    @Autowired PhotoRepository photoRepository;
    @Autowired GoalBodyRepository goalBodyRepository;

    private String token;
    private Long userId;

    @BeforeEach
    void setUp() {
        User user = User.builder()
                .email("flowtest@vitality.dev")
                .passwordHash(passwordEncoder.encode("test1234"))
                .nickname("FlowTestUser")
                .role(User.Role.user)
                .build();
        user = userRepository.save(user);
        userId = user.getId();
        token = tokenService.issue(user).value();
    }

    @Test
    void 사진_업로드_후_목표체형_저장_성공() throws Exception {
        // 1. 사진 업로드
        MockMultipartFile image = new MockMultipartFile(
                "image", "test.jpg", "image/jpeg",
                new byte[]{(byte)0xFF, (byte)0xD8, (byte)0xFF, (byte)0xE0, 0, 1, 2, 3}
        );

        MvcResult photoResult = mvc.perform(multipart("/api/photos")
                        .file(image)
                        .param("type", "goal")
                        .param("side", "front")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.photo_id").exists())
                .andExpect(jsonPath("$.url").exists())
                .andExpect(jsonPath("$.url_expires_at").value("2099-12-31T23:59:59"))
                .andReturn();

        String photoBody = photoResult.getResponse().getContentAsString();
        Long photoId = com.jayway.jsonpath.JsonPath.parse(photoBody).read("$.photo_id", Long.class);
        assertThat(photoId).isNotNull();

        // 2. 목표 체형 저장 (사진 포함)
        String goalBody = String.format("{\"photo_id\":%d,\"description\":\"전완근과 복근이 선명하게\"}", photoId);

        MvcResult goalResult = mvc.perform(post("/api/goal-bodies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(goalBody)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.goal_body_id").exists())
                .andExpect(jsonPath("$.status").value("done"))
                .andReturn();

        String goalResponse = goalResult.getResponse().getContentAsString();
        Long goalId = com.jayway.jsonpath.JsonPath.parse(goalResponse).read("$.goal_body_id", Long.class);

        // DB 검증: sourcePhoto가 실제로 연결됐는지
        var savedGoal = goalBodyRepository.findById(goalId).orElseThrow();
        assertThat(savedGoal.getSourcePhoto()).isNotNull();
        assertThat(savedGoal.getSourcePhoto().getId()).isEqualTo(photoId);
        assertThat(savedGoal.getDescription()).isEqualTo("전완근과 복근이 선명하게");
        assertThat(savedGoal.getSummary()).contains("전완근과 복근이 선명하게");
    }

    @Test
    void 사진_없이_목표체형_저장_성공() throws Exception {
        String goalBody = "{\"description\":\"날씬한 몸매\"}";

        mvc.perform(post("/api/goal-bodies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(goalBody)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.goal_body_id").exists())
                .andExpect(jsonPath("$.status").value("done"))
                .andExpect(jsonPath("$.description").value("날씬한 몸매"));
    }

    @Test
    void 잘못된_photo_id_사용시_404() throws Exception {
        String goalBody = "{\"photo_id\":99999,\"description\":\"테스트\"}";

        mvc.perform(post("/api/goal-bodies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(goalBody)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void 목표가_없는_회원은_GOAL_NOT_FOUND() throws Exception {
        mvc.perform(get("/api/goal-bodies").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GOAL_NOT_FOUND"));
    }
}
