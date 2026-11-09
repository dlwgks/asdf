package com.asdf.board.be;

import com.asdf.board.be.body.entity.BodyAnalysis;
import com.asdf.board.be.body.repository.BodyAnalysisRepository;
import com.asdf.board.be.global.enums.ExperienceLevel;
import com.asdf.board.be.global.security.TokenService;
import com.asdf.board.be.support.TestMailConfig;
import com.asdf.board.be.user.entity.User;
import com.asdf.board.be.user.entity.UserProfile;
import com.asdf.board.be.user.repository.UserProfileRepository;
import com.asdf.board.be.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestMailConfig.class)
@Transactional
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class RoutinePlanningTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired UserProfileRepository profileRepository;
    @Autowired BodyAnalysisRepository analysisRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired TokenService tokenService;

    private record Fixture(String token, Long analysisId) {}

    private Fixture fixture(String email, int weeklyFrequency, String shoulderTilt, String pelvisTilt) {
        User user = userRepository.save(User.builder().email(email)
                .passwordHash(passwordEncoder.encode("test1234")).nickname("plan").role(User.Role.user).build());
        profileRepository.save(UserProfile.builder().user(user).heightCm(new BigDecimal("177")).weightKg(new BigDecimal("73"))
                .age(18).gender(UserProfile.Gender.male).goalType(UserProfile.GoalType.muscle)
                .weeklyFrequency(weeklyFrequency).experienceLevel(ExperienceLevel.under3m).build());
        BodyAnalysis analysis = analysisRepository.save(BodyAnalysis.builder().user(user)
                .source(BodyAnalysis.Source.text).status(BodyAnalysis.Status.done)
                .shoulderTiltDeg(new BigDecimal(shoulderTilt)).pelvisTiltDeg(new BigDecimal(pelvisTilt))
                .textInput("t").textSummary("t").completedAt(LocalDateTime.now()).build());
        return new Fixture(tokenService.issue(user).value(), analysis.getId());
    }

    @Test
    void 주_1회면_루틴_하나만_만들고_어깨_기울기가_크면_어깨를_우선한다() throws Exception {
        Fixture f = fixture("plan-a@vitality.dev", 1, "3.4", "1.0");
        mvc.perform(post("/api/routines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"analysis_id\":" + f.analysisId() + "}").header("Authorization", "Bearer " + f.token()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.routines", hasSize(1)))
                .andExpect(jsonPath("$.routines[0].title").value("어깨 강화 루틴"));
    }

    @Test
    void 골반_기울기가_더_크면_코어를_우선한다() throws Exception {
        Fixture f = fixture("plan-b@vitality.dev", 1, "2.6", "4.8");
        mvc.perform(post("/api/routines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"analysis_id\":" + f.analysisId() + "}").header("Authorization", "Bearer " + f.token()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.routines[0].title").value("코어 강화 루틴"));
    }

    @Test
    void 기울기가_작으면_기본_순서이고_주_3회면_세_개를_만든다() throws Exception {
        Fixture f = fixture("plan-c@vitality.dev", 3, "1.0", "1.0");
        mvc.perform(post("/api/routines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"analysis_id\":" + f.analysisId() + "}").header("Authorization", "Bearer " + f.token()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.routines", hasSize(3)))
                .andExpect(jsonPath("$.routines[0].title").value("가슴 강화 루틴"))
                .andExpect(jsonPath("$.routines[1].title").value("등 강화 루틴"))
                .andExpect(jsonPath("$.routines[2].title").value("하체 강화 루틴"));
    }
}
