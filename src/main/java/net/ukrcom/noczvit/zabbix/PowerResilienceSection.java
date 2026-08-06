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

import java.util.List;
import net.ukrcom.noczvit.imap.DateUtils;
import net.ukrcom.noczvit.report.DurationFormat;
import org.apache.commons.text.StringEscapeUtils;

/**
 * Renders {@link PowerResilienceResult} objects into the «Аудит резервного живлення через
 * непрямий сигнал» HTML section.
 *
 * <p>Deliberately reports facts, not conclusions: a soft verdict appears only at the two
 * unambiguous edges ({@link PowerResilienceResult#verdict()} non-empty); everywhere else the
 * reader gets the raw before/after counts and, in that ambiguous middle only, the uptime-counter
 * fact — per the explicit rule that the uptime note belongs in the "решта" case, not next to an
 * already-clear verdict.
 */
public class PowerResilienceSection {

    /**
     * @param html full HTML section fragment; empty string when there is nothing to report
     */
    public record SectionResult(String html) {

        /** {@code true} when there are no audited incidents to render. */
        public boolean isEmpty() {
            return html.isBlank();
        }
    }

    /** Creates the builder. Stateless — safe to reuse across calls. */
    public PowerResilienceSection() {
    }

    /**
     * Builds the section HTML from the given audit results. Returns an empty result when the
     * list is empty — no qualifying host-down incident, or none had interface data.
     *
     * @param results audit results, one per outage; any order
     * @return {@link SectionResult}; never null
     */
    public SectionResult build(List<PowerResilienceResult> results) {
        if (results == null || results.isEmpty()) {
            return new SectionResult("");
        }

        StringBuilder html = new StringBuilder();
        html.append("<div class=\"section\">\n")
                .append("<h2 class=\"resilience-title\">Аудит резервного живлення через непрямий сигнал</h2>\n");

        for (PowerResilienceResult r : results) {
            html.append(buildOne(r));
        }

        html.append("</div>\n");
        return new SectionResult(html.toString());
    }

    private String buildOne(PowerResilienceResult r) {
        StringBuilder html = new StringBuilder();
        String title = r.location().equals(r.host())
                ? r.host()
                : r.location() + " (" + r.host() + ")";
        html.append("<h3 class=\"resilience-host\">").append(StringEscapeUtils.escapeHtml4(title)).append("</h3>\n");

        html.append("<p>Падіння: <b>").append(DateUtils.formatUa(r.fallInstant())).append("</b>")
                .append(" → Відновлення: <b>").append(DateUtils.formatUa(r.recoveryInstant())).append("</b>")
                .append(" (тривалість: ").append(DurationFormat.between(r.fallInstant(), r.recoveryInstant()))
                .append(")</p>\n");

        int totalKnown = r.totalKnown();
        if (totalKnown == 0) {
            html.append("<p><i>Немає даних для аналізу — жоден інтерфейс не мав історії "
                    + "на момент падіння вузла.</i></p>\n");
            return html.toString();
        }

        html.append("<p>На момент падіння вузла: <b>").append(r.alreadyDownAtFall())
                .append(" з ").append(totalKnown)
                .append("</b> відомих портів уже впали, <b>").append(r.stillUpAtFall())
                .append("</b> ще працювали.</p>\n");

        if (r.stillUpAtFall() > 0) {
            html.append("<p>З тих, що ще працювали: <b>").append(r.recoveredBeforeUs())
                    .append("</b> фіксувалися як активні на момент відновлення вузла");
            if (r.stillDownAfterUs() > 0) {
                html.append(", <b>").append(r.stillDownAfterUs())
                        .append("</b> лишались недоступні й після його відновлення");
            }
            html.append(".</p>\n");
        }

        if (!r.verdict().isEmpty()) {
            html.append("<p><b>").append(StringEscapeUtils.escapeHtml4(r.verdict())).append("</b></p>\n");
        } else if (r.uptimeDecreased()) {
            // Uptime fact belongs only here — in the ambiguous middle where the port pattern
            // alone gives no verdict. At the two clear edges the pattern already speaks for
            // itself, and this would just be noise alongside it.
            html.append("<p><i>Zabbix зафіксував зменшення лічильника uptime з ")
                    .append(r.uptimeBefore().get()).append(" на ").append(r.uptimeAfter().get())
                    .append(" с. Лічильник може переповнюватись і без реального перезавантаження — "
                            + "це довідковий факт, не висновок.</i></p>\n");
        }

        appendNames(html, "Впали раніше вузла", r.alreadyDownNames());
        appendNames(html, "Активні на момент відновлення вузла", r.recoveredNames());
        appendNames(html, "Лишались недоступні після відновлення вузла", r.stillDownNames());

        if (r.noData() > 0) {
            html.append("<p><i>").append(r.noData())
                    .append(" — немає даних для аналізу (відсутня історія на потрібний момент).</i></p>\n");
        }

        return html.toString();
    }

    private void appendNames(StringBuilder html, String label, List<String> names) {
        if (names.isEmpty()) {
            return;
        }
        html.append("<p><small>").append(label).append(":</small></p>\n")
                .append("<ul class=\"resilience-list\">\n");
        for (String name : names) {
            html.append("<li>").append(StringEscapeUtils.escapeHtml4(name)).append("</li>\n");
        }
        html.append("</ul>\n");
    }
}
