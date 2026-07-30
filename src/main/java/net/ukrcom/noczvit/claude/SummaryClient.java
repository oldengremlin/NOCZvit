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
import java.sql.SQLException;
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
import net.ukrcom.noczvit.history.ResumeHistory;
import net.ukrcom.noczvit.history.ResumeRecord;
import net.ukrcom.noczvit.model.Incident;

/**
 * Формує короткий текст резюме зміни NOC за допомогою Claude API. Отримує
 * єдиний список інцидентів (IMAP + Zabbix API, вже злиті та відфільтровані).
 */
@Slf4j
public class SummaryClient {

    // Запасні конвертери на випадок, якщо модель повертає Markdown всупереч інструкціям
    private static final Pattern MD_HEADING = Pattern.compile("(?m)^#{1,6}\\s+");
    private static final Pattern MD_BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");

    private final AnthropicClient client;
    private final String model;
    private final ResumeHistory resumeHistory;

    public SummaryClient(Config config) {
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(config.getClaudeApiKey())
                .build();
        this.model = config.getClaudeModel();
        this.resumeHistory = initResumeHistory(config.getHistoryResumeUrl());
    }

    /**
     * Opens the SQLite history store at the given JDBC URL.
     *
     * @param url JDBC URL such as {@code jdbc:sqlite:/var/lib/noczvit/history.db};
     *            blank or null disables cross-shift memory
     * @return initialised {@link ResumeHistory}, or {@code null} when the URL is blank or the DB
     *         cannot be opened
     */
    private ResumeHistory initResumeHistory(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            return new ResumeHistory(url);
        } catch (SQLException e) {
            log.warn("ResumeHistory: не вдалося відкрити БД '{}': {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * Викликає Claude API для формування короткого резюме зміни українською
     * мовою.
     *
     * <p>
     * Приймає єдиний злитий список інцидентів: IMAP-інциденти + сконвертовані
     * Zabbix-події. Фільтрує за {@code from}/{@code to} так само як
     * {@link net.ukrcom.noczvit.report.IncidentSectionBuilder}.
     *
     * @param allIncidents всі інциденти зміни (IMAP + Zabbix, можуть охоплювати
     * кілька змін)
     * @param from початок звітного періоду
     * @param to кінець звітного періоду
     * @return HTML-фрагмент (ніколи не null); порожній рядок при помилці або
     * відсутності даних
     */
    public String generateSummary(List<Incident> allIncidents, LocalDateTime from, LocalDateTime to) {
        return generateSummary(allIncidents, from, to, "");
    }

    /**
     * Викликає Claude API з додатковим блоком подій обладнання датацентру.
     *
     * @param allIncidents  всі інциденти зміни
     * @param from          початок звітного періоду
     * @param to            кінець звітного періоду
     * @param trapPlainText plain-text блок подій Emerson (з маркерами ізоляції); порожній рядок
     *                      якщо трап-секція відсутня
     * @return HTML-фрагмент; порожній рядок при помилці або відсутності даних
     */
    public String generateSummary(List<Incident> allIncidents, LocalDateTime from, LocalDateTime to,
                                  String trapPlainText) {
        long ctFrom = from.atZone(ZoneId.systemDefault()).toEpochSecond();
        long ctTo = to.atZone(ZoneId.systemDefault()).toEpochSecond();

        List<Incident> incidents = allIncidents.stream()
                .filter(i -> i.messageTs() >= ctFrom && i.messageTs() <= ctTo)
                .sorted(Comparator.comparingLong(Incident::messageTs))
                .toList();

        if (incidents.isEmpty()) {
            return "";
        }

        ResumeRecord previous = null;
        if (resumeHistory != null) {
            try {
                previous = resumeHistory.findPrevious(ctFrom);
                if (previous != null) {
                    log.debug("ResumeHistory: знайдено попереднє резюме (periodTo={})", previous.periodTo());
                }
            } catch (SQLException e) {
                log.warn("ResumeHistory: помилка читання попереднього резюме: {}", e.getMessage());
            }
        }

        try {
            log.debug("Виклик Claude API ({}) для резюме зміни ({} інцидентів)", model, incidents.size());

            String prompt = buildPrompt(incidents, from, to, previous, trapPlainText);
            log.debug("Claude prompt:\n{}", prompt);

            MessageCreateParams params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(1500L)
                    .addUserMessage(prompt)
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

            if (resumeHistory != null) {
                try {
                    resumeHistory.save(ctFrom, ctTo, summary);
                } catch (SQLException e) {
                    log.warn("ResumeHistory: помилка збереження резюме: {}", e.getMessage());
                }
            }

            return buildHtml(summary, from);

        } catch (AnthropicServiceException e) {
            log.warn("Claude API помилка (HTTP {}): {}", e.statusCode(), e.getMessage());
            return "";
        } catch (Exception e) {
            log.warn("Claude резюме — помилка: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Assembles the full Claude prompt: period metadata, a numbered incident list, pre-computed
     * unclosed-incident count, an optional previous-period summary for cross-shift context, an
     * optional datacenter equipment event block, and the system instruction block.
     *
     * @param incidents     filtered incidents for the current reporting period
     * @param from          start of the reporting period
     * @param to            end of the reporting period
     * @param previous      summary from the immediately preceding period, or {@code null}
     * @param trapPlainText plain-text Emerson trap block (with isolation markers); blank to omit
     */
    private String buildPrompt(List<Incident> incidents, LocalDateTime from, LocalDateTime to,
                               ResumeRecord previous, String trapPlainText) {
        StringBuilder sb = new StringBuilder();
        long startCount = incidents.stream()
                .filter(i -> i.status() == Incident.Status.START).count();
        sb.append("Звітний період: з ")
                .append(from.format(NOCZvit.DATE_TIME_FORMATTER))
                .append(" по ")
                .append(to.format(NOCZvit.DATE_TIME_FORMATTER))
                .append("\n\nУнікальних подій: ").append(startCount)
                .append(" (рядків у таблиці: ").append(incidents.size())
                .append(" — кожна подія має START і може мати END)\n");

        sb.append("\nІнциденти:\n");
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

        if (previous != null) {
            sb.append("\nРезюме попереднього звітного періоду (для порівняння та відстеження незакритих):\n")
              .append(previous.summaryText())
              .append("\n");
        }

        if (trapPlainText != null && !trapPlainText.isBlank()) {
            sb.append("\n").append(trapPlainText).append("\n");
        }

        // Closing reminder when DC events are present — placed last so Claude reads it
        // immediately before generating the response
        String trapReminder = (trapPlainText != null && !trapPlainText.isBlank())
                ? "\nНАГАДУВАННЯ: у даних вище є блок ПОДІЇ ОБЛАДНАННЯ ДАТАЦЕНТРУ — обов'язково включи ці події у резюме.\nПОДІЇ ОБЛАДНАННЯ ДАТАЦЕНТРУ подай ОКРЕМИМ АБЗАЦЕМ.\n"
                : "";

        return """
                Ти — досвідчений інженер NOC (Network Operations Center). Нижче наведено технічний список інцидентів мережі за зміну.

                Твоє завдання: написати КОРОТКЕ (до 20 речень) резюме зміни звичайним текстом українською мовою, призначене для керівництва або чергової зміни, що приходить. Резюме має:
                - Вказати загальну кількість УНІКАЛЬНИХ подій (поле "Унікальних подій") та їх характер (пінг-падіння, обриви оптики, OSPF, живлення тощо)
                - Виділити найбільш значущі або повторювані проблеми по локаціях та конкретних пристроях
                - Використати готовий факт "Незакриті інциденти на кінець зміни" — не аналізуй пари START/END самостійно
                - Бути написане стисло, в офіційному стилі, без технічного жаргону

                ВАЖЛИВО щодо підрахунку: у списку нижче кожна подія має запис START і, якщо вирішена, запис END. "Унікальних подій" — попередньо обчислене число START-записів. Використовуй ТІЛЬКИ цю цифру. НЕ рахуй рядки самостійно.
                Наразі не згадуй про зміну станів BGP.

                Доменні знання — ОБОВ'ЯЗКОВО враховуй при написанні резюме:
                - "Routing Engine: High CPU utilization" (RE CPU) на маршрутизаторах Juniper: архітектура Juniper розділяє control plane (RE) та data plane (TFEB/PFE). Навіть при 100%% завантаженні RE — комутація пакетів, доступність мережі та обслуговування абонентів залишаються повністю незачепленими. Велика кількість таких подій за зміну НЕ є ознакою системної проблеми.
                - ЗАБОРОНЕНО для RE CPU подій: "системна проблема", "проблема з обробкою трафіку", "проблема конфігурації", "рекомендується діагностика", "причина скачків". Ці формулювання неправильні і вводять в оману.
                - Правильна формула для RE CPU: "зафіксовано підвищення навантаження на Routing Engine (інформаційна подія, трафік не постраждав)".
                - Про RE CPU згадуємо узагальнено, без акцентування, не провокуючи  панічні настрої.
                - При згадуванні BGP обов'язково вказуй назву neighbor (сусіда): "виявлено короткочасну зміну стану BGP-сусіда НАЗВА", "виявлено тривалу зміну стану BGP-сусіда НАЗВА". Ці формулювання правильні.
                - ОБОВ'ЯЗКОВО УНИКАЙ в українській русизмів. ЖОДНИХ "ы", "э", "ё", "ъ". Будь-які "события" це "події".
                - Якщо на кінець зміни залишилися НЕЗАКРИТІ інциденти: перерахувати їх. За необхідності можна збільшити кількість речень в звіті.
                - Для періоду з 20:00 до 07:59 замість "на кінець зміни" пишемо "на кінець звітного періоду". Так правильніше, оскільки в цей час спостереження ведеться в автоматизованому режимі, без людини. Людина (NOC-інженер) на роботі з 08:00 до 19:59.
                - Якщо надано резюме попереднього звітного періоду: порівняй стан, зазнач, які проблеми вирішено, а які перейшли з попередньої зміни. Не переказуй попереднє резюме дослівно.
                - Якщо надано блок "ПОДІЇ ОБЛАДНАННЯ ДАТАЦЕНТРУ": ОБОВ'ЯЗКОВО згадати значущі події — знеструмлення (Loss of Mains), перехід ДБЖ на живлення від батарей, несправності кондиціонерів. НЕ згадувати: "Перезапуск картки моніторингу" (Cold Start) — це технічна деталь, не подія. ЗАБОРОНЕНО переносити події датацентру в резюме наступної зміни — вони ізольовані в межах поточного звітного періоду.

                КРИТИЧНО ВАЖЛИВО: відповідь — лише звичайний текст.
                ЗАБОРОНЕНО будь-яке Markdown-форматування: жодних **, __, #, -, *, _ та подібних символів.
                НЕ перелічуй всі інциденти по одному. Дай загальну картину зміни.
                Розбий текст на логічні абзаци, не звалюючи все в одну строку.

                %s%s""".formatted(sb.toString(), trapReminder);
    }

    /**
     * Groups incidents by {@code (location, device)} and counts START vs END events per group.
     *
     * @return human-readable Ukrainian string listing groups where START count exceeds END count,
     *         or {@code "немає"} when all incidents are closed
     */
    private String computeUnclosed(List<Incident> incidents) {
        record GroupKey(String location, String device) {

        }

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

    /**
     * Wraps the plain-text Claude summary in an HTML card with an appropriate title.
     *
     * <p>Title selection: "Резюме зміни" for day shifts (08:00–19:59), "Резюме за звітний
     * період" for night periods (20:00–07:59) where monitoring runs unattended.
     * Light Markdown remnants (headings, bold) are converted to safe HTML before injection.
     */
    private String buildHtml(String summary, LocalDateTime from) {
        // День: 08:00–19:59; ніч: 20:00–07:59
        String title = (from.getHour() >= 8 && from.getHour() < 20)
                       ? "Резюме зміни" : "Резюме за звітний період";
        title = title.concat(" (<i>згенеровано за допомогою <b>Claude Anthropic API</b></i>)");
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

        return """
               <div class="section" style="background:#fff;padding:12px 16px;border-left:4px solid #1976d2;margin-bottom:20px;box-shadow:2px 2px 6px rgba(0,0,0,.1)">
               <h2 style="color:#1976d2;margin-top:0">""" + title + "</h2><p>" + escaped + "</p></div>\n";
    }
}
