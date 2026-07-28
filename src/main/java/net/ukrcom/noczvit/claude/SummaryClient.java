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
 * Формує короткий текст резюме зміни NOC за допомогою Claude API.
 * Отримує єдиний список інцидентів (IMAP + Zabbix API, вже злиті та відфільтровані).
 */
@Slf4j
public class SummaryClient {

    // Запасні конвертери на випадок, якщо модель повертає Markdown всупереч інструкціям
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
     * Викликає Claude API для формування короткого резюме зміни українською мовою.
     *
     * <p>Приймає єдиний злитий список інцидентів: IMAP-інциденти + сконвертовані Zabbix-події.
     * Фільтрує за {@code from}/{@code to} так само як
     * {@link net.ukrcom.noczvit.report.IncidentSectionBuilder}.
     *
     * @param allIncidents всі інциденти зміни (IMAP + Zabbix, можуть охоплювати кілька змін)
     * @param from         початок звітного періоду
     * @param to           кінець звітного періоду
     * @return HTML-фрагмент (ніколи не null); порожній рядок при помилці або відсутності даних
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
            log.debug("Виклик Claude API ({}) для резюме зміни ({} інцидентів)", model, incidents.size());

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
                log.warn("Claude повернув порожнє резюме");
                return "";
            }

            log.debug("Резюме Claude сформовано ({} символів)", summary.length());
            return buildHtml(summary);

        } catch (AnthropicServiceException e) {
            log.warn("Claude API помилка (HTTP {}): {}", e.statusCode(), e.getMessage());
            return "";
        } catch (Exception e) {
            log.warn("Claude резюме — помилка: {}", e.getMessage());
            return "";
        }
    }

    private String buildPrompt(List<Incident> incidents, LocalDateTime from, LocalDateTime to) {
        StringBuilder sb = new StringBuilder();
        long startCount = incidents.stream()
                .filter(i -> i.status() == Incident.Status.START).count();
        sb.append("Звітний період: з ")
          .append(from.format(NOCZvit.DATE_TIME_FORMATTER))
          .append(" по ")
          .append(to.format(NOCZvit.DATE_TIME_FORMATTER))
          .append("\n\nУнікальних подій: ").append(startCount)
          .append(" (рядків у таблиці: ").append(incidents.size())
          .append(" — кожна подія має START і може мати END)\n")
          .append("Інциденти:\n");

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

        return """
                Ти — досвідчений інженер NOC (Network Operations Center). Нижче наведено технічний список інцидентів мережі за зміну.

                Твоє завдання: написати КОРОТКЕ (3-5 речень) резюме зміни звичайним текстом українською мовою, призначене для керівництва або чергової зміни, що приходить. Резюме має:
                - Вказати загальну кількість УНІКАЛЬНИХ подій (поле "Унікальних подій") та їх характер (пінг-падіння, обриви оптики, OSPF, живлення тощо)
                - Виділити найбільш значущі або повторювані проблеми по локаціях та конкретних пристроях
                - Використати готовий факт "Незакриті інциденти на кінець зміни" — не аналізуй пари START/END самостійно
                - Бути написане стисло, в офіційному стилі, без технічного жаргону

                ВАЖЛИВО щодо підрахунку: у списку нижче кожна подія має запис START і, якщо вирішена, запис END. "Унікальних подій" — попередньо обчислене число START-записів. Використовуй ТІЛЬКИ цю цифру. НЕ рахуй рядки самостійно.

                Доменні знання — ОБОВ'ЯЗКОВО враховуй при написанні резюме:
                - "Routing Engine: High CPU utilization" (RE CPU) на маршрутизаторах Juniper: архітектура Juniper розділяє control plane (RE) та data plane (TFEB/PFE). Навіть при 100%% завантаженні RE — комутація пакетів, доступність мережі та обслуговування абонентів залишаються повністю незачепленими. Велика кількість таких подій за зміну НЕ є ознакою системної проблеми.
                - ЗАБОРОНЕНО для RE CPU подій: "системна проблема", "проблема з обробкою трафіку", "проблема конфігурації", "рекомендується діагностика", "причина скачків". Ці формулювання неправильні і вводять в оману.
                - Правильна формула для RE CPU: "зафіксовано підвищення навантаження на Routing Engine (інформаційна подія, трафік не постраждав)".

                КРИТИЧНО ВАЖЛИВО: відповідь — лише звичайний текст.
                ЗАБОРОНЕНО будь-яке Markdown-форматування: жодних **, __, #, -, *, _ та подібних символів.
                НЕ перелічуй всі інциденти по одному. Дай загальну картину зміни.

                %s""".formatted(sb.toString());
    }

    /**
     * Групує інциденти за (location, device) і рахує START vs END.
     * Повертає людиночитаний рядок про незакриті інциденти або "немає".
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
        // 1. Прибрати Markdown-заголовки (# Заголовок → Заголовок) — страховка
        String clean = MD_HEADING.matcher(summary).replaceAll("");

        // 2. Екранувати HTML — ДО вставки будь-яких тегів
        String escaped = clean
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");

        // 3. **жирний** → <b>жирний</b> — безпечно після екранування
        escaped = MD_BOLD.matcher(escaped).replaceAll("<b>$1</b>");

        // 4. Абзаци та переноси рядків
        escaped = escaped.replace("\n\n", "</p><p>").replace("\n", "<br>");

        return "<div class=\"section\" style=\"background:#fff;padding:12px 16px;"
                + "border-left:4px solid #1976d2;margin-bottom:20px;"
                + "box-shadow:2px 2px 6px rgba(0,0,0,.1)\">\n"
                + "<h2 style=\"color:#1976d2;margin-top:0\">Резюме зміни</h2>\n"
                + "<p>" + escaped + "</p>\n"
                + "</div>\n";
    }
}
