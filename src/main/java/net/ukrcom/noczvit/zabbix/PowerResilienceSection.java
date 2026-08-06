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
            // Локацію не розпізнано (location == host саме для цього єдиного вузла) — вкладеність
            // лише дублювала б однакову назву двічі, тож у цьому разі рівень один, а не два.
            boolean grouped = group.size() > 1 || !location.equals(group.get(0).host());
            if (grouped) {
                html.append("<h3 class=\"resilience-location\">")
                        .append(StringEscapeUtils.escapeHtml4(location)).append("</h3>\n");
            }
            for (PowerResilienceResult r : group) {
                html.append(buildOne(r, grouped));
                plainText.append(buildPlainTextOne(location, r)).append("\n");
            }
        }

        html.append("</div>\n");
        return new SectionResult(html.toString(), plainText.toString());
    }

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
        return sb.toString();
    }

    private String buildOne(PowerResilienceResult r, boolean grouped) {
        String tag = grouped ? "h4" : "h3";
        String cssClass = grouped ? "resilience-host-sub" : "resilience-host";
        String title = grouped
                ? r.host()
                : (r.location().equals(r.host()) ? r.host() : r.location() + " (" + r.host() + ")");

        StringBuilder html = new StringBuilder();
        html.append("<").append(tag).append(" class=\"").append(cssClass).append("\">")
                .append(StringEscapeUtils.escapeHtml4(title)).append("</").append(tag).append(">\n");

        // Під згрупованим заголовком (вузол під локацією) тіло зсунуте вправо в окремому div —
        // так вкладеність видно й у самому тексті, а не лише в заголовках.
        StringBuilder body = new StringBuilder();
        body.append("<p>Падіння: <b>").append(DateUtils.formatUa(r.fallInstant())).append("</b>")
                .append(" → Відновлення: <b>").append(DateUtils.formatUa(r.recoveryInstant())).append("</b>")
                .append(" (тривалість: ").append(DurationFormat.between(r.fallInstant(), r.recoveryInstant()))
                .append(")</p>\n");

        int totalKnown = r.totalKnown();
        if (totalKnown == 0) {
            body.append("<p><i>Немає даних для аналізу — жоден інтерфейс не мав історії "
                    + "на момент падіння вузла.</i></p>\n");
            return html.append(wrapBody(body, grouped)).toString();
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
            body.append(".</p>\n");
        }

        if (!r.verdict().isEmpty()) {
            body.append("<p><b>").append(StringEscapeUtils.escapeHtml4(r.verdict())).append("</b></p>\n");
        } else if (r.uptimeDecreased()) {
            // Uptime fact belongs only here — in the ambiguous middle where the port pattern
            // alone gives no verdict. At the two clear edges the pattern already speaks for
            // itself, and this would just be noise alongside it.
            body.append("<p><i>Zabbix зафіксував зменшення лічильника uptime з ")
                    .append(r.uptimeBefore().get()).append(" на ").append(r.uptimeAfter().get())
                    .append(" с. Лічильник може переповнюватись і без реального перезавантаження — "
                            + "це довідковий факт, не висновок.</i></p>\n");
        }

        appendNames(body, "Впали раніше вузла", r.alreadyDownNames());
        appendNames(body, "Активні на момент відновлення вузла", r.recoveredNames());
        appendNames(body, "Лишались недоступні після відновлення вузла", r.stillDownNames());

        if (r.noData() > 0) {
            body.append("<p><i>").append(r.noData())
                    .append(" — немає даних для аналізу (відсутня історія на потрібний момент).</i></p>\n");
        }

        return html.append(wrapBody(body, grouped)).toString();
    }

    private String wrapBody(StringBuilder body, boolean grouped) {
        return grouped
                ? "<div class=\"resilience-host-body\">\n" + body + "</div>\n"
                : body.toString();
    }

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
