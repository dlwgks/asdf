package com.asdf.board.be;

import com.asdf.board.be.global.security.TokenService;
import com.asdf.board.be.support.TestMailConfig;
import com.asdf.board.be.user.entity.User;
import com.asdf.board.be.user.repository.UserRepository;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestMailConfig.class)
@Transactional
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class AnalysisHonestyTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired TokenService tokenService;

    private String token;

    @BeforeEach
    void setUp() {
        User user = User.builder()
                .email("honesty@vitality.dev")
                .passwordHash(passwordEncoder.encode("test1234"))
                .nickname("HonestyUser")
                .role(User.Role.user)
                .build();
        token = tokenService.issue(userRepository.save(user)).value();
    }

    @Test
    void 사용량은_하루_3회와_다음날_자정_오프셋_시각() throws Exception {
        mvc.perform(get("/api/body-analyses/usage").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(3))
                .andExpect(jsonPath("$.used").value(0))
                .andExpect(jsonPath("$.remaining").value(3))
                .andExpect(jsonPath("$.reset_at").value(org.hamcrest.Matchers.matchesPattern(
                        "\\d{4}-\\d{2}-\\d{2}T00:00:00\\+09:00")));
    }

    @Test
    void 글_분석은_점수와_체형을_지어내지_않는다() throws Exception {
        String res = mvc.perform(post("/api/body-analyses/text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"shoulder is tight\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long id = com.jayway.jsonpath.JsonPath.parse(res).read("$.analysis_id", Long.class);

        mvc.perform(get("/api/body-analyses/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance_score").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.body_type").value(org.hamcrest.Matchers.nullValue()));

        mvc.perform(get("/api/body-analyses/usage").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.used").value(1))
                .andExpect(jsonPath("$.remaining").value(2));
    }

    @Test
    void AI_서버_없는_사진_분석은_점수와_요약을_지어내지_않는다() throws Exception {
        MockMultipartFile image = new MockMultipartFile("image", "front.jpg", "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 1, 2, 3});
        String photo = mvc.perform(multipart("/api/photos").file(image)
                        .param("type", "current").param("side", "front")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long photoId = com.jayway.jsonpath.JsonPath.parse(photo).read("$.photo_id", Long.class);

        String res = mvc.perform(post("/api/body-analyses/photo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"photo_id\":" + photoId + "}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long id = com.jayway.jsonpath.JsonPath.parse(res).read("$.analysis_id", Long.class);

        mvc.perform(get("/api/body-analyses/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance_score").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.body_type").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.text_summary").value(org.hamcrest.Matchers.nullValue()));
    }
}
