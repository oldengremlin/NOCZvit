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
package net.ukrcom.noczvit;

import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPStore;
import jakarta.mail.*;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Base64;

public class ImapClient {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern MONTH_PATTERN = Pattern.compile("\\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\b");
    private static final Map<String, String> MONTH_MAP = Map.ofEntries(
            Map.entry("Jan", "січ"), Map.entry("Feb", "лют"), Map.entry("Mar", "бер"), Map.entry("Apr", "квіт"),
            Map.entry("May", "трав"), Map.entry("Jun", "черв"), Map.entry("Jul", "лип"), Map.entry("Aug", "серп"),
            Map.entry("Sep", "вер"), Map.entry("Oct", "жовт"), Map.entry("Nov", "лист"), Map.entry("Dec", "груд")
    );

    private final Config config;
    private final Dictionary dictionary;

    public ImapClient(Config config) throws IOException {
        this.config = config;
        this.dictionary = new Dictionary(config);
    }

    public Map<String, Map<String, Map<Long, String>>> prepareImapFolder(boolean isInteractive,
            LocalDateTime prevDutyBegin,
            LocalDateTime prevDutyEnd,
            LocalDateTime currDutyBegin,
            LocalDateTime currDutyEnd) {
        Map<String, Map<String, Map<Long, String>>> msgLogGroup = new HashMap<>();
        Properties props = new Properties();
        props.put("mail.imap.ssl.enable", config.isMailSsl());
        props.put("mail.imap.host", config.getMailHostname());
        props.put("mail.imap.port", config.isMailSsl() ? "993" : "143");
        props.put("mail.imap.timeout", "5000");

        Session session = Session.getInstance(props);
        try (IMAPStore store = (IMAPStore) session.getStore(config.isMailSsl() ? "imaps" : "imap")) {
            if (config.isDebug()) {
                System.err.println("Connecting to IMAP server: " + config.getMailHostname() + ":" + (config.isMailSsl() ? "993" : "143"));
            }
            store.connect(config.getMailHostname(), config.getMailUsername(), config.getMailPassword());
            if (config.isDebug()) {
                System.err.println("Connected to IMAP server");
            }
            try (IMAPFolder folder = (IMAPFolder) store.getFolder(config.getZabbixFolder())) {
                folder.open(Folder.READ_ONLY);
                if (isInteractive && config.isDebug()) {
                    System.err.println("IMAP folders:");
                    for (Folder f : store.getDefaultFolder().list()) {
                        System.err.println("    " + f.getFullName());
                    }
                }

                if (folder.getMessageCount() > 0) {
                    if (isInteractive) {
                        System.err.println("Processing " + folder.getMessageCount() + " messages...");
                        System.err.println("Running at: " + System.currentTimeMillis());
                    }

                    long ctPrevDutyBegin = prevDutyBegin.atZone(ZoneId.systemDefault()).toEpochSecond();
                    long ctCurrDutyEnd = currDutyEnd.atZone(ZoneId.systemDefault()).toEpochSecond();

                    for (Message msg : folder.getMessages()) {
                        String dateStr = msg.getHeader("Date")[0];
                        String subject = msg.getSubject();
                        String body;
                        try {
                            body = getTextFromMessage(msg);
                        } catch (MessagingException | IOException e) {
                            if (config.isDebug()) {
                                System.err.println("Failed to get message body: " + e.getMessage());
                            }
                            continue;
                        }

                        long unixDate;
                        try {
                            SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);
                            unixDate = dateFormat.parse(dateStr).getTime() / 1000;
                        } catch (ParseException e) {
                            if (config.isDebug()) {
                                System.err.println("Failed to parse date: " + dateStr);
                            }
                            continue;
                        }

                        if (isInteractive) {
                            System.err.print("Begin of duty " + ctPrevDutyBegin + " : preceed " + unixDate + " : end duty " + ctCurrDutyEnd + "\r");
                        }

                        if (unixDate < ctPrevDutyBegin || unixDate > ctCurrDutyEnd) {
                            continue;
                        }

                        if (unixDate == 0 || subject == null || body == null) {
                            if (config.isDebug()) {
                                System.err.println("Invalid message data: date=" + unixDate + ", subject=" + subject + ", body=" + body);
                            }
                            continue;
                        }

                        if (subject.matches(".*(?:Unavailable by ICMP ping|has been restarted).*")) {
                            proceedPD(isInteractive, subject, msgLogGroup, unixDate, dateStr);
                        } else if (subject.matches(".*(?:[Pp][Oo][Ww][Ee][Rr]|STM [Ss][Tt][Mm].?[" + (config.isDebug() ? "1-9" : "2-9") + "][0-9]*).*")) {
                            proceedSDH(isInteractive, subject, body, msgLogGroup, unixDate, dateStr);
                        }
                    }

                    if (isInteractive) {
                        System.err.println("\nStop at " + System.currentTimeMillis());
                    }
                }
            }
        } catch (MessagingException e) {
            System.err.println("IMAP error: " + e.getMessage());
            System.exit(2);
        }
        return msgLogGroup;
    }

    private void proceedPD(boolean isInteractive, String subject, Map<String, Map<String, Map<Long, String>>> msgLogGroup, long ts, String dt) {
        if (subject.matches(".*(IVR|TELEVIEV|Z-SQL|UVPN|SDH-OSM|astashov|console|ramb-\\d+|[dm]: NS\\d?|: [ap][^:]+: [ap][^:]+ has).*") && !subject.contains("alca")) {
            return;
        }

        if (subject.contains(" Resolved:") && subject.contains(" been")) {
            return;
        }

        String[] parts = subject.split("\\s+");
        if (parts.length < 6) {
            return;
        }

        String from = parts[2];
        String type = parts[5];

        if (!from.endsWith(":")) {
            from = from + "-65535:";
        }

        String fromName = from;
        String fromObject = null;
        Matcher matcher = Pattern.compile("^(.*?)-\\d+:").matcher(from);
        if (matcher.find()) {
            fromName = matcher.group(1);
            fromObject = matcher.group(1);
        }

        if (fromObject != null) {
            fromObject = fromObject.replaceAll("^(?:[rsp]|ies\\d?|alca)-", "");
            from = fromObject;
        }

        if (fromName.endsWith("-65535")) {
            fromName = fromName.replace("-65535", "");
        }

        fromName = fromName.replaceAll(":$", "");

        boolean needCheck;
        String originalFrom = from;
        from = dictionary.lookupPD(from.replace(":", "")); // Remove colon before lookup
        needCheck = originalFrom.equals(from.replace(":", ""));

        String state = "Zabbix зареєстровано ";
        if (subject.contains(" Resolved:")) {
            state = "Zabbix зареєстровано кінець інциденту, ";
        } else if (subject.contains(" Problem:")) {
            state = "Zabbix зареєстровано початок інциденту, ";
        }

        type = type.replace("ICMP", "зникнення зв'язку з обладнанням на");
        type = type.replace("Unavailable", "зникнення підключення");
        type = type.replace("by", "зникнення підключення");
        type = type.replace("been", "перезавантаження обладнання");

        String msg = state + type + " " + from;
        if (needCheck) {
            msg += " (<i>потребує коригування назви</i> '<b>" + from + "</b>')";
        }

        msg = msg.replaceAll("\\s+", " ");
        dt = convertMonthNumToMnemo(dt);

        fromName = fromName != null ? fromName : from;
        msgLogGroup.computeIfAbsent(from, k -> new HashMap<>())
                .computeIfAbsent(fromName, k -> new HashMap<>())
                .put(ts, dt + " : " + msg);

        if (isInteractive && config.isDebug()) {
            System.err.printf("fromName: %s, from: %s, msg: %s, ts: %d%n", fromName, from, msg, ts);
        }
    }

    private void proceedSDH(boolean isInteractive, String subject, String body, Map<String, Map<String, Map<Long, String>>> msgLogGroup, long ts, String dt) {
        String appendix = "";
        if (subject.contains("Air Conditioning")) {
            appendix = " (кондиціонер)";
        } else if (subject.contains("Diesel Generator")) {
            appendix = " (генератор)";
        }

        String[] parts = subject.split("\\s+");
        String geo = parts.length > 3 ? parts[3] : "";
        String type = parts.length > 5 ? parts[5] : "";

        String from = geo;
        String to = "";
        boolean needCheckFrom = true;
        boolean needCheckTo = true;

        if ("STM".equals(type)) {
            String[] geoParts = geo.split("__");
            from = geoParts[0];
            to = geoParts.length > 1 ? geoParts[1] : "";
        }

        String originalFrom = from;
        from = dictionary.lookupSDH(from);
        needCheckFrom = originalFrom.equals(from);

        String originalTo = to;
        to = dictionary.lookupSDH(to);
        needCheckTo = originalTo.equals(to);

        String geoMsg;
        if ("STM".equals(type)) {
            geoMsg = to.isEmpty() ? "на " + from : "з " + from + " на " + to;
        } else {
            geoMsg = from;
        }

        type = "Power".equals(type) ? "зникнення живлення на виносі " : "втрата зв'язності ";

        String msg = subject.contains(" Resolved:") ? "OSM зареєстровано кінець інциденту, " + type + geoMsg
                : "OSM зареєстровано початок інциденту, " + type + geoMsg;

        if ("STM".equals(type)) {
            try {
                String decodedBody = new String(Base64.getDecoder().decode(body));
                String[] lines = decodedBody.split("\n");
                for (String line : lines) {
                    if (line.startsWith("Trap value:")) {
                        String[] tvParts = line.split("\\s+");
                        if (tvParts.length > 8 && tvParts[8].matches("-\\d{2}T\\d{2}:.*")) {
                            try {
                                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH);
                                long newUnixDate = dateFormat.parse(tvParts[8]).getTime() / 1000;
                                String newDateStr = new SimpleDateFormat("dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH)
                                        .format(new Date(newUnixDate * 1000));
                                ts = newUnixDate;
                                dt = newDateStr;
                                if (config.isDebug()) {
                                    System.err.println("Updated timestamp from Trap value: " + ts + ", date: " + dt);
                                }
                            } catch (ParseException e) {
                                if (config.isDebug()) {
                                    System.err.println("Failed to parse Trap value date: " + tvParts[8]);
                                }
                            }
                        }
                    }
                }
            } catch (IllegalArgumentException e) {
                if (config.isDebug()) {
                    System.err.println("Failed to decode Base64 body: " + e.getMessage());
                }
            }
        }

        msg = msg.replaceAll("\\s+", " ");

        if (needCheckFrom || (needCheckTo && !to.isEmpty())) {
            msg += " (<i>потребує коригування назви</i>";
            if (needCheckFrom) {
                msg += " '<b>" + from + "</b>'";
            }
            if (needCheckTo && !to.isEmpty()) {
                msg += (needCheckFrom ? " та" : "") + " '<b>" + to + "</b>'";
            }
            msg += ")";
        }

        dt = convertMonthNumToMnemo(dt);

        msgLogGroup.computeIfAbsent(from, k -> new HashMap<>())
                .computeIfAbsent(to, k -> new HashMap<>())
                .put(ts, dt + " : " + msg + appendix);

        if (isInteractive && config.isDebug()) {
            System.err.println("SDH");
        }
    }

    private String convertMonthNumToMnemo(String dt) {
        dt = dt.replaceAll("^\\w{3},\\s+", "").replaceAll("\\s+\\+\\d{4}$", "");
        Matcher matcher = MONTH_PATTERN.matcher(dt);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result, MONTH_MAP.getOrDefault(matcher.group(1), matcher.group(1)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String getTextFromMessage(Message message) throws MessagingException, IOException {
        Object content = message.getContent();
        switch (content) {
            case String string -> {
                return string;
            }
            case Multipart multipart -> {
                for (int i = 0; i < multipart.getCount(); i++) {
                    BodyPart bodyPart = multipart.getBodyPart(i);
                    if (bodyPart.isMimeType("text/plain")) {
                        return (String) bodyPart.getContent();
                    }
                }
            }
            default -> {
            }
        }
        return "";
    }

    public static String formatReport(Config config, LocalDateTime dutyBegin, LocalDateTime dutyEnd, Map<String, Map<String, Map<Long, String>>> msgLogGroup) {
        long ctDutyBegin = dutyBegin.atZone(ZoneId.systemDefault()).toEpochSecond();
        long ctDutyEnd = dutyEnd.atZone(ZoneId.systemDefault()).toEpochSecond();

        StringBuilder html = new StringBuilder();
        html.append("<p><ol><h1><small><small>Інциденти, <u>зареєстровані в автоматичному режимі</u> системами Zabbix та OSM,<br>що відбувалися в період з ")
                .append(dutyBegin.format(DATE_TIME_FORMATTER)).append(" по ").append(dutyEnd.format(DATE_TIME_FORMATTER))
                .append("</small></small></h1>");

        boolean prnGroup = false;
        boolean prnDevice = false;
        boolean prnNull = true;

        List<String> groups = new ArrayList<>(msgLogGroup.keySet());
        Collections.sort(groups);

        for (String group : groups) {
            List<String> devices = new ArrayList<>(msgLogGroup.get(group).keySet());
            Collections.sort(devices);

            for (String device : devices) {
                List<Long> times = new ArrayList<>(msgLogGroup.get(group).get(device).keySet());
                Collections.sort(times);

                for (Long time : times) {
                    if (time < ctDutyBegin || time > ctDutyEnd) {
                        continue;
                    }

                    if (config.isDebug()) {
                        System.err.printf("%s %s %s%n", group, device, time);
                    }

                    if (!prnGroup) {
                        html.append("<h2 style=\"margin-left: 25px;\"><small>Зареєстровані інциденти на виносі ").append(group).append("</small></h2>");
                        prnGroup = true;
                    }

                    String msg = msgLogGroup.get(group).get(device).get(time);
                    if (!msg.contains(" : OSM ")) {
                        html.append("<li style=\"margin-left: 75px;\">").append(msg).append(" [").append(device).append("]</li>");
                    } else {
                        html.append("<li style=\"margin-left: 75px;\">").append(msg).append("</li>");
                    }

                    prnDevice = true;
                    prnNull = false;
                }
                if (prnDevice) {
                    html.append("<br>");
                }
            }
            prnGroup = false;
            prnDevice = false;
        }

        if (prnNull) {
            html.append("<h2 style=\"margin-left: 50px;\"><small>Інцидентів не зареєстровано</small></h2>");
        }

        html.append("</ol><p>");
        return html.toString();
    }
}