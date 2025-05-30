package net.ukrcom.noczvit;

import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPStore;
import jakarta.mail.*;
import jakarta.mail.search.SearchTerm;
import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ImapClient {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern MONTH_PATTERN = Pattern.compile("\\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\b");
    private static final Map<String, String> MONTH_MAP = Map.ofEntries(
            Map.entry("Jan", "січ"), Map.entry("Feb", "лют"), Map.entry("Mar", "бер"), Map.entry("Apr", "квіт"),
            Map.entry("May", "трав"), Map.entry("Jun", "черв"), Map.entry("Jul", "лип"), Map.entry("Aug", "серп"),
            Map.entry("Sep", "вер"), Map.entry("Oct", "жовт"), Map.entry("Nov", "лист"), Map.entry("Dec", "груд")
    );
    private static final Pattern DEVICE_PREFIX_PATTERN = Pattern.compile("^(?:[rsp]|(?:ies\\d?|alca)-)");

    private final Config config;
    private final Dictionary dictionary;

    public ImapClient(Config config) throws IOException {
        this.config = config;
        this.dictionary = new Dictionary(config);
    }

    public Map<String, Map<String, Map<Long, Map<Long, List<String>>>>> prepareImapFolder(boolean isInteractive,
            LocalDateTime prevDutyBegin,
            LocalDateTime prevDutyEnd,
            LocalDateTime currDutyBegin,
            LocalDateTime currDutyEnd) {
        Map<String, Map<String, Map<Long, Map<Long, List<String>>>>> msgLogGroup = new HashMap<>();
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
                    if (config.isDebug()) {
                        System.err.println("Filter period: ctPrevDutyBegin=" + ctPrevDutyBegin + " (" + prevDutyBegin + "), ctCurrDutyEnd=" + ctCurrDutyEnd + " (" + currDutyEnd + ")");
                    }

                    Message[] messages;
                    if (config.isDebug()) {
                        messages = folder.getMessages();
                    } else {
                        SearchTerm dateTerm = new SearchTerm() {
                            @Override
                            public boolean match(Message message) {
                                try {
                                    Date sentDate = message.getSentDate();
                                    long unixDate = sentDate.getTime() / 1000;
                                    return unixDate >= ctPrevDutyBegin && unixDate <= ctCurrDutyEnd;
                                } catch (MessagingException e) {
                                    return false;
                                }
                            }
                        };
                        if (isInteractive) {
                            System.err.println("IMAP filter: [" + dateTerm.toString() + "] (sent >= " + ctPrevDutyBegin + " && sent <= " + ctCurrDutyEnd + ")");
                        }
                        messages = folder.search(dateTerm);
                    }

                    for (Message msg : messages) {
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

                        if (config.isDebug()) {
                            System.err.println("Processing message: subject=" + subject + ", unixDate=" + unixDate);
                        }

                        if (subject.matches(".*(?:Unavailable by ICMP ping|has been restarted).*")) {
                            if (unixDate >= ctPrevDutyBegin && unixDate <= ctCurrDutyEnd) {
                                proceedPD(isInteractive, subject, null, msgLogGroup, unixDate, dateStr);
                            } else if (config.isDebug()) {
                                System.err.println("Skipping PD message due to time filter: unixDate=" + unixDate);
                            }
                        } else if (subject.matches(".*(?:[Pp][Oo][Ww][Ee][Rr]|STM [Ss][Tt][Mm].?[" + (config.isDebug() ? "1-9" : "2-9") + "][0-9]*).*")) {
                            Map<String, Map<String, Map<Long, Map<Long, List<String>>>>> tempMsgLogGroup = new HashMap<>();
                            proceedSDH(isInteractive, subject, body, tempMsgLogGroup, unixDate, dateStr);
                            filterAndMergeMessages(tempMsgLogGroup, msgLogGroup, ctPrevDutyBegin, ctCurrDutyEnd, isInteractive, config);
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

    private String getTextFromMessage(Message message) throws MessagingException, IOException {
        String result = "";
        if (message.isMimeType("text/plain")) {
            // Handle text/plain directly
            Object content = message.getContent();
            switch (content) {
                case String string ->
                    result = string;
                case InputStream inputStream ->
                    result = new String(inputStream.readAllBytes(), "UTF-8");
                default -> {
                }
            }
        } else if (message.isMimeType("multipart/*")) {
            // Handle multipart messages
            Multipart multipart = (Multipart) message.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart bodyPart = multipart.getBodyPart(i);
                if (bodyPart.isMimeType("text/plain")) {
                    result = (String) bodyPart.getContent();
                    break;
                }
            }
        }
        return result;
    }

    private void filterAndMergeMessages(Map<String, Map<String, Map<Long, Map<Long, List<String>>>>> tempMsgLogGroup,
            Map<String, Map<String, Map<Long, Map<Long, List<String>>>>> msgLogGroup,
            long ctPrevDutyBegin,
            long ctCurrDutyEnd,
            boolean isInteractive,
            Config config) {
        /*
        for (String group : tempMsgLogGroup.keySet()) {
            for (String device : tempMsgLogGroup.get(group).keySet()) {
                for (Long ts : tempMsgLogGroup.get(group).get(device).keySet()) {
                    if (ts >= ctPrevDutyBegin && ts <= ctCurrDutyEnd) {
                        Map<Long, List<String>> existingTtsMap = msgLogGroup
                                .computeIfAbsent(group, k -> new HashMap<>())
                                .computeIfAbsent(device, k -> new HashMap<>())
                                .computeIfAbsent(ts, k -> new HashMap<>());
                        // Додаємо всі tts-повідомлення
                        for (Map.Entry<Long, List<String>> ttsEntry : tempMsgLogGroup.get(group).get(device).get(ts).entrySet()) {
                            existingTtsMap.computeIfAbsent(ttsEntry.getKey(), k -> new ArrayList<>())
                                    .addAll(ttsEntry.getValue());
                        }
                        if (config.isDebug()) {
                            System.err.println("Merged messages: group=" + group + ", device=" + device + ", ts=" + ts + ", messages=" + existingTtsMap);
                        }
                    }
                }
            }
        }
         */

        // Структура:
        // - String (group): назва групи, наприклад, "Обухів, Малишка 2"
        // - Map<String, ...> (device): мапа пристроїв, наприклад, "core1"
        // - Map<Long, ...> (ts): мапа таймстемпів, наприклад, 1748549842 (Unix timestamp)
        // - Map<Long, List<String>> (tts): мапа подій (tts) із списком повідомлень, наприклад, {1748528164: ["msg1", "msg2"]}
        tempMsgLogGroup
                .entrySet().stream().flatMap(
                        groupEntry -> groupEntry.getValue()
                                .entrySet().stream().map(
                                        deviceEntry -> Map.entry(
                                                groupEntry.getKey(), deviceEntry
                                        )
                                )
                ).flatMap(
                        groupDevice -> groupDevice
                                .getValue()
                                .getValue()
                                .entrySet().stream().map(
                                        tsEntry -> Map.entry(
                                                Map.entry(
                                                        groupDevice.getKey(), groupDevice.getValue().getKey()
                                                ), tsEntry
                                        )
                                )
                ).filter(groupDeviceTs -> {
                    Long ts = groupDeviceTs.getValue().getKey();
                    return ts >= ctPrevDutyBegin && ts <= ctCurrDutyEnd;
                })
                .forEach(groupDeviceTs -> {
                    String group = groupDeviceTs.getKey().getKey();
                    String device = groupDeviceTs.getKey().getValue();
                    Long ts = groupDeviceTs.getValue().getKey();
                    Map<Long, List<String>> ttsMap = groupDeviceTs.getValue().getValue();

                    Map<Long, List<String>> existingTtsMap = msgLogGroup
                            .computeIfAbsent(group, k -> new HashMap<>())
                            .computeIfAbsent(device, k -> new HashMap<>())
                            .computeIfAbsent(ts, k -> new HashMap<>());

                    ttsMap.forEach((tts, messages)
                            -> existingTtsMap.computeIfAbsent(tts, k -> new ArrayList<>()).addAll(messages));

                    if (config.isDebug()) {
                        System.err.println("Merged messages: group=" + group + ", device=" + device
                                + ", ts=" + ts + ", messages=" + existingTtsMap);
                    }
                });

        /*
        tempMsgLogGroup
                .entrySet() // Перетворює tempMsgLogGroup у набір пар {group, Map<device, Map<ts, ttsMap>>}
                .stream() // Створює потік із цих пар
                .flatMap(groupEntry
                        -> 
                        // groupEntry: пара {group, Map<device, Map<ts, ttsMap>>}
                        // groupEntry.getKey(): String — назва групи (наприклад, "Обухів, Малишка 2")
                        // groupEntry.getValue(): Map<String, Map<Long, Map<Long, List<String>>>> — мапа пристроїв
                        groupEntry.getValue() // Беремо мапу пристроїв
                        .entrySet() // Перетворюємо мапу пристроїв у набір пар {device, Map<ts, ttsMap>}
                        .stream() // Створюємо потік із пар пристроїв
                        .map(deviceEntry
                                ->
                                // deviceEntry: пара {device, Map<ts, ttsMap>}
                                // deviceEntry.getKey(): String — назва пристрою (наприклад, "core1")
                                // deviceEntry.getValue(): Map<Long, Map<Long, List<String>>> — мапа таймстемпів
                                Map.entry(
                                groupEntry.getKey(), // String: група (наприклад, "Обухів, Малишка 2")
                                deviceEntry // Map.Entry<String, Map<Long, Map<Long, List<String>>>>: пара {device, Map<ts, ttsMap>}
                        ) // Повертаємо пару {group, deviceEntry}
                        ) // Отримуємо потік пар {group, deviceEntry}
                ) // Розгортаємо в плоский потік пар {group, deviceEntry}
                .flatMap(groupDevice
                        -> 
                        // groupDevice: пара {group, deviceEntry}
                        // groupDevice.getKey(): String — група (наприклад, "Обухів, Малишка 2")
                        // groupDevice.getValue(): Map.Entry<String, Map<Long, Map<Long, List<String>>>> — пара {device, Map<ts, ttsMap>}
                        // groupDevice.getValue().getKey(): String — пристрій (наприклад, "core1")
                        // groupDevice.getValue().getValue(): Map<Long, Map<Long, List<String>>> — мапа таймстемпів
                        groupDevice.getValue().getValue() // Беремо мапу таймстемпів (Map<ts, ttsMap>)
                        .entrySet() // Перетворюємо мапу таймстемпів у набір пар {ts, ttsMap}
                        .stream() // Створюємо потік із пар таймстемпів
                        .map(tsEntry
                                ->
                                // tsEntry: пара {ts, ttsMap}
                                // tsEntry.getKey(): Long — таймстемп (наприклад, 1748549842)
                                // tsEntry.getValue(): Map<Long, List<String>> — мапа подій (tts) із повідомленнями
                                Map.entry(
                                Map.entry(
                                        groupDevice.getKey(), // String: група (наприклад, "Обухів, Малишка 2")
                                        groupDevice.getValue().getKey() // String: пристрій (наприклад, "core1")
                                ), // Map.Entry<String, String>: пара {group, device}
                                tsEntry // Map.Entry<Long, Map<Long, List<String>>>: пара {ts, ttsMap}
                        ) // Повертаємо пару {{group, device}, tsEntry}
                        ) // Отримуємо потік пар {{group, device}, tsEntry}
                ) // Розгортаємо в плоский потік пар {{group, device}, tsEntry}
                .filter(groupDeviceTs -> {
                    // groupDeviceTs: пара {{group, device}, tsEntry}
                    // groupDeviceTs.getKey(): Map.Entry<String, String> — пара {group, device}
                    // groupDeviceTs.getValue(): Map.Entry<Long, Map<Long, List<String>>> — пара {ts, ttsMap}
                    Long ts = groupDeviceTs.getValue().getKey(); // Long: таймстемп (ts)
                    return ts >= ctPrevDutyBegin && ts <= ctCurrDutyEnd; // Фільтруємо за періодом
                }) // Залишаємо тільки пари, де ts у межах [ctPrevDutyBegin, ctCurrDutyEnd]
                .forEach(groupDeviceTs -> {
                    // groupDeviceTs: пара {{group, device}, tsEntry}
                    // groupDeviceTs.getKey(): Map.Entry<String, String> — пара {group, device}
                    // groupDeviceTs.getKey().getKey(): String — група (наприклад, "Обухів, Малишка 2")
                    // groupDeviceTs.getKey().getValue(): String — пристрій (наприклад, "core1")
                    // groupDeviceTs.getValue(): Map.Entry<Long, Map<Long, List<String>>> — пара {ts, ttsMap}
                    // groupDeviceTs.getValue().getKey(): Long — таймстемп (ts)
                    // groupDeviceTs.getValue().getValue(): Map<Long, List<String>> — мапа подій (ttsMap)
                    String group = groupDeviceTs.getKey().getKey(); // Витягуємо групу
                    String device = groupDeviceTs.getKey().getValue(); // Витягуємо пристрій
                    Long ts = groupDeviceTs.getValue().getKey(); // Витягуємо таймстемп
                    Map<Long, List<String>> ttsMap = groupDeviceTs.getValue().getValue(); // Витягуємо мапу подій

                    // Отримуємо або створюємо мапу для зберігання повідомлень у msgLogGroup
                    Map<Long, List<String>> existingTtsMap = msgLogGroup
                            .computeIfAbsent(group, k -> new HashMap<>()) // Map<String, Map<Long, Map<Long, List<String>>>> для групи
                            .computeIfAbsent(device, k -> new HashMap<>()) // Map<Long, Map<Long, List<String>>> для пристрою
                            .computeIfAbsent(ts, k -> new HashMap<>()); // Map<Long, List<String>> для таймстемпа

                    // Додаємо всі повідомлення з ttsMap до existingTtsMap
                    ttsMap.forEach((tts, messages)
                            -> // tts: Long — ідентифікатор події; messages: List<String> — список повідомлень
                            existingTtsMap.computeIfAbsent(tts, k -> new ArrayList<>()).addAll(messages)
                    );

                    // Логуємо в дебаг-режимі
                    if (config.isDebug()) {
                        System.err.println("Merged messages: group=" + group + ", device=" + device
                                + ", ts=" + ts + ", messages=" + existingTtsMap);
                    }
                }); // Обробляємо кожну відфільтровану пару
         */
    }

    public static String formatReport(Config config, LocalDateTime dutyBegin, LocalDateTime dutyEnd, Map<String, Map<String, Map<Long, Map<Long, List<String>>>>> msgLogGroup) {
        StringBuilder html = new StringBuilder();
        html.append("<p><ol><h1><small><small>Інциденти, <u>зареєстровані в автоматичному режимі</u> системами Zabbix та OSM,<br>")
                .append("що відбувалися в період з ").append(dutyBegin.format(DATE_TIME_FORMATTER))
                .append(" по ").append(dutyEnd.format(DATE_TIME_FORMATTER)).append("</small></small></h1>");

        long ctDutyBegin = dutyBegin.atZone(ZoneId.systemDefault()).toEpochSecond();
        long ctDutyEnd = dutyEnd.atZone(ZoneId.systemDefault()).toEpochSecond();

        record Incident(String group, String device, Long ts, Long tts, String message) {

            public String formattedMessage() {
                var msg = !message.contains(" : OSM ")
                        ? message + " [" + device + "]"
                        : message;
                return "<li style=\"margin-left: 75px;\">" + msg + "</li>";
            }

        }

        List<Incident> incidents = msgLogGroup.entrySet().stream()
                .flatMap((var groupEntry) -> {
                    return groupEntry.getValue().entrySet().stream()
                            .flatMap((var deviceEntry) -> {
                                return deviceEntry.getValue().entrySet().stream()
                                        .flatMap((var tsEntry) -> {
                                            return tsEntry.getValue().entrySet().stream()
                                                    .flatMap((var ttsEntry) -> {
                                                        return ttsEntry.getValue().stream()
                                                                .map((var msg) -> {
                                                                    return new Incident(
                                                                            groupEntry.getKey(),
                                                                            deviceEntry.getKey(),
                                                                            tsEntry.getKey(),
                                                                            ttsEntry.getKey(),
                                                                            msg
                                                                    );
                                                                });
                                                    });
                                        });
                            });
                })
                .filter((var incident) -> {
                    return incident.ts >= ctDutyBegin && incident.ts <= ctDutyEnd;
                })
                .sorted(Comparator.comparing(Incident::ts).thenComparing(Incident::tts))
                .toList();

        String reportContent = incidents.stream()
                .collect(Collectors.groupingBy(
                        Incident::group,
                        LinkedHashMap::new,
                        Collectors.groupingBy(
                                Incident::device,
                                LinkedHashMap::new,
                                Collectors.mapping(Incident::formattedMessage, Collectors.joining())
                        )
                ))
                .entrySet().stream()
                .map((var groupEntry) -> {
                    var groupContent = groupEntry.getValue().entrySet().stream()
                            .map((var deviceEntry) -> deviceEntry.getValue() + "<br>\n")
                            .collect(Collectors.joining());
                    return "<h2 style=\"margin-left: 25px;\"><small>Зареєстровані інциденти на виносі "
                            + groupEntry.getKey() + "</small></h2>" + groupContent;
                })
                .collect(Collectors.joining());

        html.append(reportContent);

        if (incidents.isEmpty()) {
            html.append("<h2 style=\"margin-left: 50px;\"><small>Інцидентів не зареєстровано</small></h2>");
        }

        html.append("</ol><p>");
        return html.toString();
    }

    private void proceedPD(boolean isInteractive, String subject, String body, Map<String, Map<String, Map<Long, Map<Long, List<String>>>>> msgLogGroup, long ts, String dt) {
        if (subject.contains("IVR") || subject.contains("TELEVIEV") || subject.contains("Z-SQL")
                || subject.contains("UVPN") || subject.contains("SDH-OSM") || subject.contains("astashov")
                || subject.contains("console") || subject.contains("ramb-\\d+:")
                || subject.matches(".*[dm]: NS\\d?.*")
                || (subject.matches(".*: [ap][^:]+: [ap][^:]+ has.*") && !subject.contains("alca"))) {
            return;
        }

        boolean needCheck = false;
        String[] parts = subject.split("\\s+");
        String from = parts.length > 2 ? parts[2] : "";
        String type = parts.length > 5 ? parts[5] : "";

        if (subject.contains(" Resolved:") && "been".equals(type)) {
            return;
        }

        String originalFromName = from.replaceAll(Pattern.compile(":$").pattern(), ""); // Preserve the original device name

        if (from.endsWith(":")) {
            if (!from.matches(".*-\\d+:$")) {
                from = from.replace(":", "-65535:");
            }
            String[] fromParts = from.split(":");
            String fromName = fromParts[0];
            String fromObject = fromName.matches(".*-\\d+$") ? fromName.replaceAll(DEVICE_PREFIX_PATTERN.pattern(), "") : fromName;
            from = fromObject.replace("-65535", "");
            fromName = fromName.replace("-65535", "");
            String transformedFrom = dictionary.lookupPD(fromObject); // Lookup the full name including suffix
            needCheck = fromObject.equals(transformedFrom);
            from = transformedFrom;
        }

        String state = "Zabbix зареєстровано ";
        if (!(subject.contains(" Problem:") && type.contains("been"))) {
            if (subject.contains(" Resolved:")) {
                state = "Zabbix зареєстровано кінець інциденту, ";
            } else if (subject.contains(" Problem:")) {
                state = "Zabbix зареєстровано початок інциденту, ";
            }
        }

        String msg = state + switch (type) {
            case "ICMP" ->
                "зникнення зв'язку з обладнанням на ";
            case "Unavailable", "by" ->
                "зникнення підключення ";
            case "been" ->
                "перезавантаження обладнання ";
            default ->
                type + " ";
        } + from;

        if (needCheck) {
            msg += " (<i>потребує коригування назви</i> '<b>" + from + "</b>')";
        }

        msg = msg.replaceAll("\\s+", " ");
        dt = convertMonthNumToMnemo(dt);

        msgLogGroup.computeIfAbsent(from, k -> new HashMap<>())
                .computeIfAbsent(originalFromName, k -> new HashMap<>())
                .computeIfAbsent(ts, k -> new HashMap<>())
                .computeIfAbsent(ts, k -> new ArrayList<>())
                .add(dt + " : " + msg);

        if (isInteractive && config.isDebug()) {
            System.err.println("PD stored: from=" + from + ", originalFromName=" + originalFromName + ", ts=" + ts + ", dt=" + dt + ", msg=" + msg);
        }
    }

    private void proceedSDH(boolean isInteractive, String subject, String body, Map<String, Map<String, Map<Long, Map<Long, List<String>>>>> msgLogGroup, long ts, String dt) {
        if (config.isDebug()) {
            System.err.println("Processing SDH message: subject=" + subject + ", original ts=" + ts + ", original dt=" + dt);
            System.err.println("Raw body: " + body);
        }

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

        String msg;
        if (subject.contains(" Resolved:")) {
            msg = "OSM зареєстровано кінець інциденту, ";
        } else if (subject.contains(" Problem:")) {
            msg = "OSM зареєстровано початок інциденту, ";
        } else {
            msg = "OSM зареєстровано інцидент, ";
        }
        msg += "Power".equals(type) ? "зникнення живлення на виносі " + geoMsg : "втрата зв’язності " + geoMsg;

        // Обробка Trap value для отримання точного часу інциденту
        boolean trapValueFound = false;
        // Очищаємо body від \r і розбиваємо на рядки
        String[] lines = body.replace("\r", "").split("\n");
        long tts = ts;
        String tdt = dt;
        for (String line : lines) {
            if (line.startsWith("Trap value:")) {
                if (config.isDebug()) {
                    System.err.println("Trap value line: " + line);
                }
                // Шукаємо дату у форматі yyyy-MM-dd'T'HH:mm:ss
                Pattern datePattern = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
                Matcher matcher = datePattern.matcher(line);
                if (matcher.find()) {
                    String trapDate = matcher.group();
                    try {
                        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH);
                        long newUnixDate = dateFormat.parse(trapDate).getTime() / 1000;
                        // Оновлюємо ts і dt
                        tts = newUnixDate;
                        tdt = new SimpleDateFormat("dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH)
                                .format(new Date(newUnixDate * 1000));
                        trapValueFound = true;
                        if (config.isDebug()) {
                            System.err.println("Found Trap value date: " + trapDate + ", updated ts=" + tts + ", updated dt=" + tdt);
                        }
                    } catch (ParseException e) {
                        if (config.isDebug()) {
                            System.err.println("Failed to parse Trap value date: " + trapDate + ", error: " + e.getMessage());
                        }
                    }
                } else if (config.isDebug()) {
                    System.err.println("No date found in Trap value line with regex");
                }
                break; // Обробили Trap value, виходимо
            }
        }

        if (!trapValueFound && config.isDebug()) {
            System.err.println("No Trap value date found, using original ts=" + ts + ", dt=" + dt);
        }

        msg = msg.replaceAll("\\s+", " ");
        if (ts != tts) {
            tdt = convertMonthNumToMnemo(tdt);
            msg = msg.concat(", який відбувся " + tdt);
        }

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

        // Зберігаємо повідомлення з оновленим ts
        msgLogGroup.computeIfAbsent(from, k -> new HashMap<>())
                .computeIfAbsent("", k -> new HashMap<>())
                .computeIfAbsent(ts, k -> new HashMap<>())
                .computeIfAbsent(tts, k -> new ArrayList<>())
                .add(dt + " : " + msg);

        if (isInteractive && config.isDebug()) {
            System.err.println("SDH stored: from=" + from + ", to=" + to + ", ts=" + ts + ", dt=" + dt + ", msg=" + msg);
        }
    }

    private String convertMonthNumToMnemo(String dt) {
        dt = dt.replaceAll("^\\w{3},\\s+", "").replaceAll("\\+\\d{4}$", "");
        Matcher matcher = MONTH_PATTERN.matcher(dt);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sb, MONTH_MAP.get(matcher.group()));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

}
