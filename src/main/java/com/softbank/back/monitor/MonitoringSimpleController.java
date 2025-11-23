package com.softbank.back.monitor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 단일 파일 모니터링 API (Mock)
 * - GET /monitoring
 * - 5초 간격으로 고정된 5가지 시나리오 값을 순차 적용
 * - 외부 의존성 없이 한 파일로 동작
 */
@RestController
@RequestMapping("/monitoring")
@CrossOrigin(origins = "*")
public class MonitoringSimpleController {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);

    // 전역 상태 (간단 구현)
    private static final State STATE = new State();

    @GetMapping
    public ResponseEntity<ResponsePayload> getMonitoring() {
        // 5초마다 다음 시나리오로 전환 (요청 시 Lazy Update)
        STATE.maybeUpdate();

        // 응답 조립
        Metrics metrics = new Metrics(
                round1(STATE.cpuUsage),
                Math.round(STATE.latency),
                round1(STATE.errorRate),
                ISO.format(Instant.now())
        );

        AnomalyScore anomaly = new AnomalyScore(
                STATE.healthScore,
                STATE.healthState,
                STATE.penguinAnimation,
                STATE.coachMessage
        );

        // alerts는 최근 알림 5개까지
        List<Alert> alerts = new ArrayList<>(STATE.alerts);

        return ResponseEntity.ok(new ResponsePayload(metrics, anomaly, alerts));
    }

    // ===================== 내부 구현 (상태/로직) =====================

    private static class State {
        final Random random = new Random();

        // 현재 적용된 값
        volatile double cpuUsage = 60.0;   // %
        volatile double latency = 850.0;   // ms
        volatile double errorRate = 2.0;   // %

        // 점수(높을수록 나쁨, 0~100)
        volatile int healthScore = 0;
        volatile String healthState = "healthy"; // healthy | warning | danger
        volatile String penguinAnimation = "happy"; // happy | worried | crying
        volatile String coachMessage = "✨ 안정적이에요! 계속 유지해주세요.";

        // 시나리오 전개
        final long startedAtMs = System.currentTimeMillis();
        volatile int lastScenarioIndex = -1; // 0~4

        final Deque<Alert> alerts = new ArrayDeque<>();

        synchronized void maybeUpdate() {
            int scenarioIdx = currentScenarioIndex(); // 0..4
            Scenario s = scenario(scenarioIdx);

            // 시나리오가 바뀐 경우에만 값 갱신 및 재계산
            if (scenarioIdx != lastScenarioIndex) {
                String prevState = healthState;

                // 고정값 적용
                this.cpuUsage = s.cpu;
                this.latency = s.latency;
                this.errorRate = s.errorRate;

                // 점수 재계산 (밴드 우선, 없으면 임계값)
                double sevError = severity(errorRate, s.errorBandLower, s.errorBandUpper, 1.0, 5.0, 10.0);
                double sevLatency = severity(latency, s.latencyBandLower, s.latencyBandUpper, 300, 700, 1500);
                double sevCpu = severity(cpuUsage, null, null, 50, 80, 95);

                double score = sevError * 0.50 * 100
                             + sevLatency * 0.35 * 100
                             + sevCpu * 0.15 * 100;
                this.healthScore = (int) clamp(Math.round(score), 0, 100);

                // 상태 분류 (HealthScoreEngine 규칙과 동일)
                String state;
                if (healthScore <= 30) state = "healthy";
                else if (healthScore <= 70) state = "warning";
                else state = "danger";
                this.healthState = state;
                this.penguinAnimation = switch (state) {
                    case "healthy" -> "happy";
                    case "warning" -> "worried";
                    default -> "crying";
                };
                this.coachMessage = switch (state) {
                    case "healthy" -> pick(
                            "🎉 완벽해요! 모든 지표가 정상이에요!",
                            "👍 아주 안정적이에요! 이대로 가요!",
                            "✨ 훌륭해요! 펭귄이 춤추고 있어요!");
                    case "warning" -> pick(
                            "⚠️ 응답 속도가 약간 느려지고 있어요.",
                            "⚠️ 에러가 조금 보이네요. 로그 점검을 권장해요.",
                            "⚠️ 리소스 사용률 상승 조짐이 있어요.");
                    default -> pick(
                            "🚨 서비스가 위험해요! 즉시 점검이 필요해요!",
                            "🚨 에러/지연이 심각해요! 원인 분석이 필요해요!",
                            "🚨 리소스가 포화 상태에요!");
                };

                if (prevState != null && !prevState.equals(this.healthState)) {
                    switch (this.healthState) {
                        case "warning" -> pushAlert("warning", "System health requires attention");
                        case "danger" -> pushAlert("critical", "Overall health is in danger state");
                        case "healthy" -> pushAlert("info", "System recovered to healthy state");
                    }
                }

                lastScenarioIndex = scenarioIdx;
            }
        }

        private int currentScenarioIndex() {
            long now = System.currentTimeMillis();
            long elapsed = now - startedAtMs;
            long slot = (elapsed / 5000L); // 5초 단위
            return (int) (slot % 5); // 0..4 반복
        }

        private Scenario scenario(int idx) {
            // 파이썬 스니펫과 동일한 5개 시나리오
            return switch (idx) {
                case 0 -> new Scenario(60, 850, 2.0, null, null, null, null); // Healthy
                case 1 -> new Scenario(82, 1200, 5.0, null, null, null, null); // Warning
                case 2 -> new Scenario(95, 2500, 10.0, null, null, null, null); // Danger
                case 3 -> new Scenario(65, 900, 2.5, 0.5, 3.0, 200.0, 600.0); // Anomaly band (latency 초과)
                default -> new Scenario(40, 250, 8.0, null, null, null, null); // Error only
            };
        }

        private double severity(double value, Double bandLower, Double bandUpper,
                                 double normal, double warning, double danger) {
            // 1) Band 우선
            if (bandLower != null && bandUpper != null) {
                if (value >= bandLower && value <= bandUpper) return 0.0;
                double deviation;
                if (value > bandUpper) deviation = (value - bandUpper) / bandUpper;
                else deviation = (bandLower - value) / bandLower;
                return Math.min(1.0, deviation * 2.0);
            }
            // 2) 임계값 기반
            if (value <= normal) return 0.0;
            else if (value <= warning) {
                double r = (value - normal) / (warning - normal);
                return r * 0.5; // 0.0 → 0.5
            } else if (value <= danger) {
                double r = (value - warning) / (danger - warning);
                return 0.5 + r * 0.3; // 0.5 → 0.8
            } else {
                double r = Math.min(1.0, (value - danger) / danger);
                return 0.8 + r * 0.2; // 0.8 → 1.0
            }
        }

        private void pushAlert(String level, String message) {
            Alert a = new Alert(java.util.UUID.randomUUID().toString(), level, message, ISO.format(Instant.now()), false);
            alerts.addFirst(a);
            while (alerts.size() > 5) alerts.removeLast();
        }

        private String pick(String... options) {
            return options[random.nextInt(options.length)];
        }

        private double clamp(double v, double lo, double hi) {
            return Math.max(lo, Math.min(hi, v));
        }

        private record Scenario(
                double cpu, double latency, double errorRate,
                Double errorBandLower, Double errorBandUpper,
                Double latencyBandLower, Double latencyBandUpper
        ) {}
    }

    // ===================== DTO =====================

    public static class ResponsePayload {
        public Metrics metrics;
        public AnomalyScore anomaly;
        public List<Alert> alerts;

        public ResponsePayload(Metrics metrics, AnomalyScore anomaly, List<Alert> alerts) {
            this.metrics = metrics;
            this.anomaly = anomaly;
            this.alerts = alerts;
        }
    }

    public static class Metrics {
        public double cpuUsage;   // 0-100
        public long latency;      // ms
        public double errorRate;  // 0-100 (%)
        public String timestamp;  // ISO8601

        public Metrics(double cpuUsage, long latency, double errorRate, String timestamp) {
            this.cpuUsage = cpuUsage;
            this.latency = latency;
            this.errorRate = errorRate;
            this.timestamp = timestamp;
        }
    }

    public static class AnomalyScore {
        public int healthScore;          // 0-100 (높을수록 나쁨)
        public String healthState;       // 'healthy' | 'warning' | 'danger'
        public String penguinAnimation;  // 'happy' | 'worried' | 'crying'
        public String coachMessage;

        public AnomalyScore(int healthScore, String healthState, String penguinAnimation, String coachMessage) {
            this.healthScore = healthScore;
            this.healthState = healthState;
            this.penguinAnimation = penguinAnimation;
            this.coachMessage = coachMessage;
        }
    }

    public static class Alert {
        public String id;
        public String level;       // 'info' | 'warning' | 'critical'
        public String message;
        public String timestamp;   // ISO8601
        public boolean acknowledged;

        public Alert(String id, String level, String message, String timestamp, boolean acknowledged) {
            this.id = id;
            this.level = level;
            this.message = message;
            this.timestamp = timestamp;
            this.acknowledged = acknowledged;
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
