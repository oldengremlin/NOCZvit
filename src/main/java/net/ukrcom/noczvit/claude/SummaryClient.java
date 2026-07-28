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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
import net.ukrcom.noczvit.zabbix.ZabbixProblem;

/**
 * Generates a human-readable Ukrainian shift summary using the Claude API.
 */
@Slf4j
public class SummaryClient {

    // Запасні конвертери на випадок, якщо модель повертає Markdown всупереч інструкціям
    private static final Pattern MD_HEADING = Pattern.compile("(?m)^#{1,6}\\s+");
    private static final Pattern MD_BOLD    = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AnthropicClient client;
    private final String model;

    public SummaryClient(Config config) {
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(config.getClaudeApiKey())
                .build();
        this.model = config.getClaudeModel();
    }

    /**
     * Викликає Claude API для формування короткого резюме зміни українською мовою.
     * Фільтрує {@code allIncidents} за {@code from}/{@code to} за часом повідомлення —
     * так само як {@link net.ukrcom.noczvit.report.IncidentSectionBuilder}.
     *
     * @param allIncidents   всі розібрані інциденти (можуть охоплювати кілька змін)
     * @param from           початок звітного періоду
     * @param to             кінець звітного періоду
     * @param zabbixProblems відфільтровані події Zabbix, що не дублюються в IMAP
     *                       (порожній список, якщо Zabbix вимкнений або не дав результатів)
     * @return HTML-фрагмент (ніколи не null); порожній рядок при помилці або відсутності даних
     */
    public String generateSummary(List<Incident> allIncidents, LocalDateTime from, LocalDateTime to,
                                  List<ZabbixProblem> zabbixProblems) {
        long ctFrom = from.atZone(ZoneId.systemDefault()).toEpochSecond();
        long ctTo   = to.atZone(ZoneId.systemDefault()).toEpochSecond();

        List<Incident> incidents = allIncidents.stream()
                .filter(i -> i.messageTs() >= ctFrom && i.messageTs() <= ctTo)
                .sorted(Comparator.comparingLong(Incident::messageTs))
                .toList();

        if (incidents.isEmpty() && zabbixProblems.isEmpty()) {
            return "";
        }

        try {
            log.debug("Виклик Claude API ({}) для резюме зміни ({} інцидентів IMAP, {} подій Zabbix)",
                    model, incidents.size(), zabbixProblems.size());

            MessageCreateParams params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(1024L)
                    .addUserMessage(buildPrompt(incidents, from, to, zabbixProblems))
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

    private String buildPrompt(List<Incident> incidents, LocalDateTime from, LocalDateTime to,
                               List<ZabbixProblem> zabbixProblems) {
        StringBuilder sb = new StringBuilder();
        sb.append("Звітний період: з ")
          .append(from.format(NOCZvit.DATE_TIME_FORMATTER))
          .append(" по ")
          .append(to.format(NOCZvit.DATE_TIME_FORMATTER));

        if (!incidents.isEmpty()) {
            sb.append("\n\nІнциденти з IMAP (").append(incidents.size()).append(" шт.):\n");
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

            // Попередньо обчислений факт — Claude не аналізує пари START/END самостійно
            sb.append("\nНезакриті інциденти на кінець зміни: ")
              .append(computeUnclosed(incidents))
              .append("\n");
        } else {
            sb.append("\n\nІнцидентів з IMAP за зміну не зареєстровано.\n");
        }

        if (!zabbixProblems.isEmpty()) {
            sb.append("\nДодаткові події Zabbix (").append(zabbixProblems.size())
              .append(" шт., не включені до основного переліку):\n");
            sb.append("УВАГА: при написанні резюме ОБОВ'ЯЗКОВО вказуй значення поля \"Пристрій\".\n");
            int n = 0;
            for (ZabbixProblem p : zabbixProblems) {
                LocalDateTime dt = Instant.ofEpochSecond(p.clock())
                        .atZone(ZoneId.systemDefault()).toLocalDateTime();
                sb.append(++n).append(". ");
                sb.append("Пристрій: ").append(p.host()).append(", ");
                sb.append("[").append(dt.format(TS_FORMAT)).append("] ");
                sb.append(p.name());
                if (p.isActive()) {
                    sb.append(" [АКТИВНА]");
                } else {
                    LocalDateTime resolved = Instant.ofEpochSecond(p.rClock())
                            .atZone(ZoneId.systemDefault()).toLocalDateTime();
                    sb.append(" [вирішена о ").append(resolved.format(TS_FORMAT)).append("]");
                }
                sb.append("\n");
            }
        }

        return """
                Ти — досвідчений інженер NOC (Network Operations Center). Нижче наведено технічний перелік інцидентів та подій мережі за зміну.

                Твоє завдання: написати КОРОТКЕ (3-5 речень) резюме зміни звичайним текстом українською мовою, призначене для керівництва або чергової зміни, що приходить. Резюме має:
                - Вказати загальну кількість інцидентів та їх характер (пінг-падіння, обриви оптики, OSPF, живлення тощо)
                - Виділити найбільш значущі або повторювані проблеми по локаціях
                - Використати готовий факт "Незакриті інциденти на кінець зміни" — не аналізуй пари START/END самостійно
                - Якщо є додаткові події Zabbix — включи їх у загальну картину, ОБОВ'ЯЗКОВО вказуючи конкретний hostname кожного пристрою (наприклад: "підвищене навантаження CPU на r234-1 та rhoh15-1")
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
