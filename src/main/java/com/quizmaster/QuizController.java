package com.quizmaster;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class QuizController {

    private final QuizService service;

    public QuizController(QuizService service) {
        this.service = service;
    }

    public record QuizRequest(String topic, String difficulty, int n, int sections) {}

    @PostMapping("/quiz")
    public ResponseEntity<?> generate(@RequestBody QuizRequest r) {
        String topic = r.topic() == null ? "" : r.topic().trim();
        if (topic.isEmpty() || topic.length() > 120)
            return err(HttpStatus.BAD_REQUEST, "Enter a topic (max 120 characters).");
        if (r.n() < 4 || r.n() > 20 || r.sections() < 2 || r.sections() > 3)
            return err(HttpStatus.BAD_REQUEST, "Questions must be 4-20 and sections 2-3.");
        String diff = switch (String.valueOf(r.difficulty())) {
            case "Easy", "Hard" -> r.difficulty();
            default -> "Medium";
        };
        try {
            return ResponseEntity.ok(service.generate(topic, diff, r.n(), r.sections()));
        } catch (QuizService.QuizException e) {
            return err(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        } catch (Exception e) {
            return err(HttpStatus.BAD_GATEWAY, "Quiz generation failed. Please try again.");
        }
    }

    private ResponseEntity<Map<String, String>> err(HttpStatus s, String m) {
        return ResponseEntity.status(s).body(Map.of("error", m));
    }
}
