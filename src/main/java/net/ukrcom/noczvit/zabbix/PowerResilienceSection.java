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
package net.ukrcom.noczvit.zabbix;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.ukrcom.noczvit.imap.DateUtils;
import net.ukrcom.noczvit.report.DurationFormat;
import org.apache.commons.text.StringEscapeUtils;

/**
 * Рендерить {@link PowerResilienceResult} у HTML-секцію «Аудит резервного живлення через
 * непрямий сигнал».
 *
 * <p>Свідомо показує факти, а не висновки: м'який вердикт з'являється лише на двох однозначних
 * краях ({@link PowerResilienceResult#verdict()} непорожній); в усіх інших випадках читач бачить
 * лише сирі цифри до/після, а факт про лічильник uptime — тільки в цій неоднозначній середині,
 * за явним правилом, що ця нотатка належить саме до випадку «решта», а не поруч із уже чітким
 * вердиктом.
 *
 * <p>Виняток — {@link PowerResilienceResult#restartDetectedAt()}: подія «has been restarted» сама
 * теж заснована на {@code system.uptime}, тож ізольовано не надійніша за голе зменшення
 * лічильника. Але цю подію бере в розгляд лише той аудит, що вже й так прив'язаний до
 * підтвердженого ICMP-падіння/відновлення того самого хоста — а збіг переповнення лічильника
 * саме з реальним ICMP-обривом того самого хоста вкрай малоймовірний. Тому вона показується
 * завжди, коли знайдена, незалежно від вердикту чи неоднозначності, і замінює собою слабший,
 * ізольований uptime-факт нижче.
 */
public class PowerResilienceSection {

    /**
     * @param html      повний HTML-фрагмент секції; порожній рядок, якщо нема що показати
     * @param plainText компактний текстовий блок для Claude — лише підсумкові цифри й вердикт,
     *                  без переліку окремих портів/інтерфейсів; порожній рядок, якщо нема що
     *                  показати
     */
    public record SectionResult(String html, String plainText) {

        /** {@code true}, коли немає жодного аудитованого інциденту для відображення. */
        public boolean isEmpty() {
            return html.isBlank();
        }
    }

    /** Створює білдер. Без стану — безпечно перевикористовувати між викликами. */
    public PowerResilienceSection() {
    }

    /**
     * Будує HTML секції та компактний plain-text блок за наданими результатами аудиту. Повертає
     * порожній результат, якщо список порожній — немає жодного відповідного host-down інциденту,
     * або жоден не мав даних по інтерфейсах.
     *
     * @param results результати аудиту, по одному на кожен винос; порядок довільний
     * @return {@link SectionResult}; ніколи не {@code null}
     */
    public SectionResult build(List<PowerResilienceResult> results) {
        if (results == null || results.isEmpty()) {
            return new SectionResult("", "");
        }

        // Групуємо по locations — один винос часто кладе кілька SNMP-моніторованих вузлів
        // одразу (наприклад ssks-2/ssks-4/ssks-5 на «Бандери 8 (СКС)»), і плаский перелік
        // «локація (хост)» повторював локацію стільки разів, скільки там вузлів.
        Map<String, List<PowerResilienceResult>> byLocation = results.stream()
                .sorted(Comparator.comparing(PowerResilienceResult::location)
                        .thenComparing(PowerResilienceResult::fallInstant))
                .collect(Collectors.groupingBy(PowerResilienceResult::location,
                        LinkedHashMap::new, Collectors.toList()));

        StringBuilder html = new StringBuilder();
        html.append("<div class=\"section\">\n")
                .append("<h2 class=\"resilience-title\">Аудит резервного живлення через непрямий сигнал</h2>\n");

        StringBuilder plainText = new StringBuilder();
        plainText.append("АУДИТ РЕЗЕРВНОГО ЖИВЛЕННЯ (лише підсумкові висновки, без переліку портів):\n");

        for (Map.Entry<String, List<PowerResilienceResult>> entry : byLocation.entrySet()) {
            String location = entry.getKey();
            List<PowerResilienceResult> group = entry.getValue();

            html.append("<h3 class=\"resilience-location\">")
                    .append(StringEscapeUtils.escapeHtml4(location)).append("</h3>\n")
                    .append("<table width=\"100%\" cellspacing=\"0\" cellpadding=\"0\">")
                    .append("<thead><tr>")
                    .append("<th style=\"width:30px\">№</th>")
                    .append("<th class=\"nw\">Обладнання</th>")
                    .append("<th class=\"nw\">Початок</th>")
                    .append("<th class=\"nw\">Закінчення</th>")
                    .append("<th class=\"nw\">Тривалість</th>")
                    .append("<th>Результат аудиту</th>")
                    .append("</tr></thead><tbody>\n");

            // Нумерація своя в кожній таблиці — як у секції Emerson, де кожен пристрій має
            // власну таблицю; тут «своя таблиця» на кожну локацію.
            int n = 0;
            for (PowerResilienceResult r : group) {
                html.append(buildRow(r, ++n));
                plainText.append(buildPlainTextOne(location, r)).append("\n");
            }

            html.append("</tbody></table>\n");
        }

        html.append("</div>\n");
        return new SectionResult(html.toString(), plainText.toString());
    }

    /** Один рядок компактного текстового блока для Claude — підсумкові цифри й вердикт без переліку портів. */
    private String buildPlainTextOne(String location, PowerResilienceResult r) {
        StringBuilder sb = new StringBuilder();
        String title = location.equals(r.host()) ? r.host() : location + " (" + r.host() + ")";
        sb.append(title).append(": падіння ").append(DateUtils.formatUa(r.fallInstant()))
                .append(" → відновлення ").append(DateUtils.formatUa(r.recoveryInstant()))
                .append(" (").append(DurationFormat.between(r.fallInstant(), r.recoveryInstant())).append(")");

        int totalKnown = r.totalKnown();
        if (totalKnown == 0) {
            sb.append("; даних для аналізу немає.");
            return sb.toString();
        }

        sb.append("; на момент падіння вузла ").append(r.alreadyDownAtFall())
                .append(" з ").append(totalKnown).append(" відомих портів уже впали, ")
                .append(r.stillUpAtFall()).append(" ще працювали.");

        if (!r.verdict().isEmpty()) {
            sb.append(" Висновок: ").append(r.verdict());
        } else {
            sb.append(" Однозначного висновку немає — вирішує інженер.");
        }
        if (r.restartDetectedAt().isPresent()) {
            sb.append(" Zabbix підтвердив перезавантаження обладнання о ")
                    .append(DateUtils.formatUa(r.restartDetectedAt().get())).append(".");
        }
        return sb.toString();
    }

    /**
     * Один рядок таблиці: обладнання, час падіння й відновлення, тривалість — окремими колонками
     * (ті самі назви, що і в таблиці інцидентів), а весь розбір по портах — в останній комірці.
     */
    private String buildRow(PowerResilienceResult r, int n) {
        StringBuilder html = new StringBuilder();
        html.append("<tr><td>").append(n).append(".</td>")
                .append("<td class=\"nw\">").append(StringEscapeUtils.escapeHtml4(r.host())).append("</td>")
                .append("<td class=\"nw\">").append(DateUtils.formatUa(r.fallInstant())).append("</td>")
                .append("<td class=\"nw\">").append(DateUtils.formatUa(r.recoveryInstant())).append("</td>")
                .append("<td class=\"nw\">").append(DurationFormat.between(r.fallInstant(), r.recoveryInstant()))
                .append("</td><td>\n");

        StringBuilder body = new StringBuilder();

        int totalKnown = r.totalKnown();
        if (totalKnown == 0) {
            // Дві різні причини «нічого аналізувати» — не можна писати про відсутню історію,
            // коли насправді всі порти хоста виключені як службові чи вільні.
            body.append(r.noDataAtFall() == 0 && r.ignoredPorts() > 0
                    ? "<i>Немає даних для аналізу — усі " + r.ignoredPorts()
                      + " портів хоста без опису, позначені вільними, або виключеного типу "
                      + "(налаштування).</i>\n"
                    : "<i>Немає даних для аналізу — жоден інтерфейс не мав історії "
                      + "на момент падіння вузла.</i>\n");
            return html.append(body).append("</td></tr>\n").toString();
        }

        body.append("<p>На момент падіння вузла: <b>").append(r.alreadyDownAtFall())
                .append(" з ").append(totalKnown)
                .append("</b> відомих портів уже впали, <b>").append(r.stillUpAtFall())
                .append("</b> ще працювали.</p>\n");

        if (r.stillUpAtFall() > 0) {
            body.append("<p>З тих, що ще працювали: <b>").append(r.recoveredBeforeUs())
                    .append("</b> фіксувалися як активні на момент відновлення вузла");
            if (r.stillDownAfterUs() > 0) {
                body.append(", <b>").append(r.stillDownAfterUs())
                        .append("</b> лишались недоступні й після його відновлення");
            }
            // Без цього доданку сума не сходилась зі stillUpAtFall, і читач не міг зрозуміти,
            // куди подівся залишок.
            if (r.noDataAtRecovery() > 0) {
                body.append(", для <b>").append(r.noDataAtRecovery())
                        .append("</b> немає знімка на момент відновлення");
            }
            body.append(".</p>\n");
        }

        if (!r.verdict().isEmpty()) {
            body.append("<p><b>").append(StringEscapeUtils.escapeHtml4(r.verdict())).append("</b></p>\n");
        }

        if (r.restartDetectedAt().isPresent()) {
            // Сам тригер "has been restarted" теж заснований на system.uptime — ізольовано він не
            // надійніший за голе зменшення лічильника. Показується завжди (не лише в неоднозначній
            // середині) саме тому, що ми беремо лише подію одразу після підтвердженого ICMP-
            // відновлення цього ж хоста — переповнення лічильника, що збіглося точно з реальним
            // ICMP-обривом, украй малоймовірне.
            body.append("<p><i>Zabbix зафіксував подію «")
                    .append(StringEscapeUtils.escapeHtml4(r.host())).append(" has been restarted» о ")
                    .append(DateUtils.formatUa(r.restartDetectedAt().get()))
                    .append(" — одразу після відновлення зв'язку по ICMP. Цей тригер теж спирається "
                            + "на system.uptime, але саме такий збіг у часі з реальним ICMP-обривом "
                            + "робить переповнення лічильника малоймовірним поясненням: більш "
                            + "вірогідно, що обладнання дійсно перезавантажилось.</i></p>\n");
        } else if (r.verdict().isEmpty() && r.uptimeDecreased()) {
            // Факт про uptime показуємо лише тут — у неоднозначній середині, де ні розподіл
            // портів, ні подія перезавантаження не дають чіткішої відповіді. На двох однозначних
            // краях (або за наявності підтвердженого перезавантаження вище) це був би просто шум
            // поруч із сильнішим сигналом.
            body.append("<p><i>Zabbix зафіксував зменшення лічильника uptime з ")
                    .append(r.uptimeBefore().get()).append(" на ").append(r.uptimeAfter().get())
                    .append(" с. Лічильник може переповнюватись і без реального перезавантаження — "
                            + "це довідковий факт, не висновок.</i></p>\n");
        }

        appendNames(body, "Впали раніше вузла", r.alreadyDownNames());
        appendNames(body, "Активні на момент відновлення вузла", r.recoveredNames());
        appendNames(body, "Лишались недоступні після відновлення вузла", r.stillDownNames());

        if (r.noDataAtFall() > 0) {
            body.append("<p><i>").append(r.noDataAtFall())
                    .append(" портів не враховано — не мали історії на момент падіння вузла.</i></p>\n");
        }
        if (r.ignoredPorts() > 0) {
            body.append("<p><i>").append(r.ignoredPorts())
                    .append(" портів не враховано — без опису, позначені вільними "
                            + "(<code>--free--</code>, <code>--unused--</code>), "
                            + "або виключеного типу (налаштування).</i></p>\n");
        }

        return html.append(body).append("</td></tr>\n").toString();
    }

    /** Додає до {@code html} маркований список інтерфейсів з міткою часу під заданим заголовком, якщо перелік непорожній. */
    private void appendNames(StringBuilder html, String label,
            List<PowerResilienceResult.InterfaceObservation> observations) {
        if (observations.isEmpty()) {
            return;
        }
        html.append("<p><small>").append(label).append(":</small></p>\n")
                .append("<ul class=\"resilience-list\">\n");
        for (PowerResilienceResult.InterfaceObservation obs : observations) {
            html.append("<li>").append(StringEscapeUtils.escapeHtml4(obs.name()))
                    .append(" — ").append(DateUtils.formatUa(obs.observedAt()))
                    .append("</li>\n");
        }
        html.append("</ul>\n");
    }
}
