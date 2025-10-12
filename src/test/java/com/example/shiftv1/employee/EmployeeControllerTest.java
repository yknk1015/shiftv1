package com.example.shiftv1.employee;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EmployeeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EmployeeRepository employeeRepository;

    @BeforeEach
    void setUp() {
        employeeRepository.deleteAll();
    }

    @Test
    void createEmployee_withAllAttributes_persistsEmployee() throws Exception {
        String payload = """
            {
              \"name\": \"API従業員\",
              \"role\": \"マネージャー\",
              \"skillLevel\": 5,
              \"canWorkWeekends\": false,
              \"canWorkEvenings\": true,
              \"preferredWorkingDays\": 4
            }
            """;

        mockMvc.perform(post("/api/employees")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("API従業員"))
            .andExpect(jsonPath("$.skillLevel").value(5))
            .andExpect(jsonPath("$.canWorkWeekends").value(false))
            .andExpect(jsonPath("$.preferredWorkingDays").value(4));

        Employee saved = employeeRepository.findByName("API従業員").orElseThrow();
        assertThat(saved.getRole()).isEqualTo("マネージャー");
        assertThat(saved.getCanWorkWeekends()).isFalse();
        assertThat(saved.getCanWorkEvenings()).isTrue();
        assertThat(saved.getPreferredWorkingDays()).isEqualTo(4);
    }

    @Test
    void updateEmployee_updatesAllAttributes() throws Exception {
        Employee existing = employeeRepository.save(
            new Employee("既存従業員", "スタッフ", 2, true, false, 3)
        );

        String payload = """
            {
              \"name\": \"更新後従業員\",
              \"role\": \"リーダー\",
              \"skillLevel\": 4,
              \"canWorkWeekends\": true,
              \"canWorkEvenings\": true,
              \"preferredWorkingDays\": 6
            }
            """;

        mockMvc.perform(put("/api/employees/" + existing.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("更新後従業員"))
            .andExpect(jsonPath("$.role").value("リーダー"))
            .andExpect(jsonPath("$.skillLevel").value(4))
            .andExpect(jsonPath("$.preferredWorkingDays").value(6));

        Employee updated = employeeRepository.findById(existing.getId()).orElseThrow();
        assertThat(updated.getName()).isEqualTo("更新後従業員");
        assertThat(updated.getRole()).isEqualTo("リーダー");
        assertThat(updated.getSkillLevel()).isEqualTo(4);
        assertThat(updated.getCanWorkEvenings()).isTrue();
        assertThat(updated.getPreferredWorkingDays()).isEqualTo(6);
    }

    @Test
    void createEmployee_withInvalidAttributes_returnsBadRequest() throws Exception {
        String payload = """
            {
              \"name\": \"無効従業員\",
              \"role\": \"スタッフ\",
              \"skillLevel\": 6,
              \"canWorkWeekends\": true,
              \"canWorkEvenings\": true,
              \"preferredWorkingDays\": 0
            }
            """;

        mockMvc.perform(post("/api/employees")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("バリデーションエラー"))
            .andExpect(jsonPath("$.details.skillLevel").value("スキルレベルは5以下である必要があります"))
            .andExpect(jsonPath("$.details.preferredWorkingDays").value("希望勤務日数は1以上である必要があります"));
    }
}

