/*
 * Copyright 2025 Ukrcom
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations
 * under the License.
 */
package net.ukrcom.noczvit.claude;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.noczvit.Config;
import net.ukrcom.noczvit.NOCZvit;
import net.ukrcom.noczvit.model.Incident;

/**
 * Generates a human-readable Ukrainian shift summary using the Claude API.
 */
@Slf4j
public class SummaryClient {

    // Fallback converters for residual Markdown the model may produce despite instructions
    private static final Pattern MD_HEADING = Pattern.compile("(?m)^#{1,6}\\s+");
    private static final Pattern MD_BOLD    = Pattern.compile("\\*\\*(.+?)\\*\\*");

    private final AnthropicClient client;
    private final String model;

    public SummaryClient(Config config) {
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(config.getClaudeApiKey())
                .build();
        this.model = config.getClaudeModel();
    }

    /**
     * Calls Claude API to produce a concise Ukrainian summary of the duty period incidents.
     * Filters {@code allIncidents} by {@code from}/{@code to} message timestamps — same
     * window as {@link net.ukrcom.noczvit.report.IncidentSectionBuilder}.
     *
     * @param allIncidents all parsed incidents (may span multiple duty periods)
     * @param from         start of the duty period
     * @param to           end of the duty period
     * @return HTML fragment (never null); empty string on failure or no incidents in window
     */
    public String generateSummary(List<Incident> allIncidents, LocalDateTime from, LocalDateTime to) {
        long ctFrom = from.atZone(ZoneId.systemDefault()).toEpochSecond();
        long ctTo   = to.atZone(ZoneId.systemDefault()).toEpochSecond();

        List<Incident> incidents = allIncidents.stream()
                .filter(i -> i.messageTs() >= ctFrom && i.messageTs() <= ctTo)
                .sorted(Comparator.comparingLong(Incident::messageTs))
                .toList();

        if (incidents.isEmpty()) {
            return "";
        }

        try {
            log.debug("Calling Claude API ({}) for shift summary ({} incidents)", model, incidents.size());

            MessageCreateParams params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(1024L)
                    .addUserMessage(buildPrompt(incidents, from, to))
                    .build();

            Message response = client.messages().create(params);
            String summary = response.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(t -> t.text())
                    .collect(Collectors.joining());

            if (summary.isBlank()) {
                log.warn("Claude returned empty summary");
                return "";
            }

            log.debug("Claude summary generated ({} chars)", summary.length());
            return buildHtml(summary);

        } catch (AnthropicServiceException e) {
            log.warn("Claude API error (HTTP {}): {}", e.statusCode(), e.getMessage());
            return "";
        } catch (Exception e) {
            log.warn("Claude summary failed: {}", e.getMessage());
            return "";
        }
    }

    private String buildPrompt(List<Incident> incidents, LocalDateTime from, LocalDateTime to) {
        StringBuilder sb = new StringBuilder();
        sb.append("Звітний період: з ")
          .append(from.format(NOCZvit.DATE_TIME_FORMATTER))
          .append(" по ")
          .append(to.format(NOCZvit.DATE_TIME_FORMATTER))
          .append("\n\nІнциденти (").append(incidents.size()).append(" шт.):\n");

        int n = 0;
        for (Incident inc : incidents) {
            sb.append(++n).append(". ");
            sb.append("[").append(inc.messageDateStr()).append("] ");
            sb.append(inc.location());
            if (!inc.device().isEmpty()) {
                sb.append(" / ").append(inc.device());
            }
            sb.append(" — ").append(inc.description());
            sb.append(" [").append(inc.source()).append(", ").append(inc.status()).append("]\n");
        }

        // Pre-computed fact — do NOT ask Claude to infer this from START/END pairs
        sb.append("\nНезакриті інциденти на кінець зміни: ")
          .append(computeUnclosed(incidents))
          .append("\n");

        return """
                Ти — досвідчений інженер NOC (Network Operations Center). Нижче наведено технічний список інцидентів мережі за зміну.

                Твоє завдання: написати КОРОТКЕ (3-5 речень) резюме зміни звичайним текстом українською мовою, призначене для керівництва або чергової зміни, що приходить. Резюме має:
                - Вказати загальну кількість інцидентів та їх характер (пінг-падіння, обриви оптики, OSPF, живлення тощо)
                - Виділити найбільш значущі або повторювані проблеми по локаціях
                - Використати готовий факт "Незакриті інциденти на кінець зміни" — не аналізуй пари START/END самостійно
                - Бути написане стисло, в офіційному стилі, без технічного жаргону

                КРИТИЧНО ВАЖЛИВО: відповідь — лише звичайний текст.
                ЗАБОРОНЕНО будь-яке Markdown-форматування: жодних **, __, #, -, *, _ та подібних символів.
                НЕ перелічуй всі інциденти по одному. Дай загальну картину зміни.

                %s""".formatted(sb.toString());
    }

    /**
     * Groups incidents by (location, device) and counts STARTs vs ENDs.
     * Returns a human-readable Ukrainian string describing open incidents,
     * or "немає" if all are closed.
     */
    private String computeUnclosed(List<Incident> incidents) {
        record GroupKey(String location, String device) {}

        Map<GroupKey, Long> starts = incidents.stream()
                .filter(i -> i.status() == Incident.Status.START)
                .collect(Collectors.groupingBy(
                        i -> new GroupKey(i.location(), i.device()),
                        Collectors.counting()));

        Map<GroupKey, Long> ends = incidents.stream()
                .filter(i -> i.status() == Incident.Status.END)
                .collect(Collectors.groupingBy(
                        i -> new GroupKey(i.location(), i.device()),
                        Collectors.counting()));

        List<String> open = new ArrayList<>();
        starts.forEach((key, startCount) -> {
            long endCount = ends.getOrDefault(key, 0L);
            if (startCount > endCount) {
                long diff = startCount - endCount;
                String label = key.location();
                if (!key.device().isEmpty()) {
                    label += " (" + key.device() + ")";
                }
                open.add(label + " — " + diff + " шт.");
            }
        });

        return open.isEmpty() ? "немає" : String.join("; ", open);
    }

    private String buildHtml(String summary) {
        // 1. Strip markdown headings (# Заголовок → Заголовок) — safety net
        String clean = MD_HEADING.matcher(summary).replaceAll("");

        // 2. Escape HTML — BEFORE inserting any tags
        String escaped = clean
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");

        // 3. Convert **bold** → <b>bold</b> — safe after HTML escaping since ** is not HTML
        escaped = MD_BOLD.matcher(escaped).replaceAll("<b>$1</b>");

        // 4. Paragraph and line breaks
        escaped = escaped.replace("\n\n", "</p><p>").replace("\n", "<br>");

        return "<div class=\"section\" style=\"background:#fff;padding:12px 16px;"
                + "border-left:4px solid #1976d2;margin-bottom:20px;"
                + "box-shadow:2px 2px 6px rgba(0,0,0,.1)\">\n"
                + "<h2 style=\"color:#1976d2;margin-top:0\">Резюме зміни</h2>\n"
                + "<p>" + escaped + "</p>\n"
                + "</div>\n";
    }
}
