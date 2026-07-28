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
import java.util.List;
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

    private final AnthropicClient client;
    private final String model;

    public SummaryClient(Config config) {
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(config.getClaudeApiKey())
                .build();
        this.model = config.getClaudeModel();
    }

    /**
     * Calls Claude API to produce a concise Ukrainian summary of the incident list.
     *
     * @param incidents filtered incidents for the duty period (may be empty)
     * @param from      start of the duty period
     * @param to        end of the duty period
     * @return HTML fragment with the summary, or empty string on failure/no incidents
     */
    public String generateSummary(List<Incident> incidents, LocalDateTime from, LocalDateTime to) {
        if (incidents.isEmpty()) {
            return "";
        }

        String incidentText = buildIncidentText(incidents, from, to);

        try {
            log.debug("Calling Claude API ({}) for shift summary ({} incidents)", model, incidents.size());

            MessageCreateParams params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(1024L)
                    .addUserMessage(buildPrompt(incidentText))
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

    private String buildIncidentText(List<Incident> incidents, LocalDateTime from, LocalDateTime to) {
        StringBuilder sb = new StringBuilder();
        sb.append("Звітний період: з ")
          .append(from.format(NOCZvit.DATE_TIME_FORMATTER))
          .append(" по ")
          .append(to.format(NOCZvit.DATE_TIME_FORMATTER))
          .append("\n\nІнциденти:\n");

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
        return sb.toString();
    }

    private String buildPrompt(String incidentText) {
        return """
                Ти — досвідчений інженер NOC (Network Operations Center). Нижче наведено технічний список інцидентів мережі за зміну.

                Твоє завдання: написати КОРОТКЕ (3-5 речень) резюме зміни людською мовою українською, призначене для керівництва або чергової зміни, що приходить. Резюме має:
                - Вказати загальну кількість інцидентів та їх характер (пінг-падіння, обриви оптики, OSPF, живлення тощо)
                - Виділити найбільш значущі або повторювані проблеми по локаціях
                - Зазначити, чи є незакриті інциденти (статус START без відповідного END)
                - Бути написане стисло, в офіційному стилі, без технічного жаргону

                НЕ перелічуй всі інциденти по одному. Дай загальну картину зміни.

                %s""".formatted(incidentText);
    }

    private String buildHtml(String summary) {
        String escaped = summary
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\n\n", "</p><p>")
                .replace("\n", "<br>");
        return "<div class=\"section\" style=\"background:#fff;padding:12px 16px;"
                + "border-left:4px solid #1976d2;margin-bottom:20px;"
                + "box-shadow:2px 2px 6px rgba(0,0,0,.1)\">\n"
                + "<h2 style=\"color:#1976d2;margin-top:0\">Резюме зміни</h2>\n"
                + "<p>" + escaped + "</p>\n"
                + "</div>\n";
    }
}
