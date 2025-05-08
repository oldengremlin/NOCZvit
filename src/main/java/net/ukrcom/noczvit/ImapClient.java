package net.ukrcom.noczvit;

import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPStore;
import jakarta.mail.*;
import jakarta.mail.internet.MimeMultipart;
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

    private String getTextFromMessage(Message message) throws MessagingException, IOException {
        String result = "";
        if (message.isMimeType("text/plain")) {
            // Handle text/plain directly
            Object content = message.getContent();
            if (content instanceof String) {
                result = (String) content;
            } else if (content instanceof InputStream) {
                result = new String(((InputStream) content).readAllBytes(), "UTF-8");
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

    private void proceedSDH(boolean isInteractive, String subject, String body, Map<String, Map<String, Map<Long, String>>> msgLogGroup, long ts, String dt) {
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
                        ts = newUnixDate;
                        dt = new SimpleDateFormat("dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH)
                                .format(new Date(newUnixDate * 1000));
                        trapValueFound = true;
                        if (config.isDebug()) {
                            System.err.println("Found Trap value date: " + trapDate + ", updated ts=" + ts + ", updated dt=" + dt);
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
                .computeIfAbsent(to, k -> new HashMap<>())
                .put(ts, dt + " : " + msg + appendix);

        if (isInteractive && config.isDebug()) {
            System.err.println("SDH stored: from=" + from + ", to=" + to + ", ts=" + ts + ", dt=" + dt + ", msg=" + msg);
        }
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
                    if (config.isDebug()) {
                        System.err.println("Filter period: ctPrevDutyBegin=" + ctPrevDutyBegin + " (" + prevDutyBegin + "), ctCurrDutyEnd=" + ctCurrDutyEnd + " (" + currDutyEnd + ")");
                    }

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

                        if (config.isDebug()) {
                            System.err.println("Processing message: subject=" + subject + ", unixDate=" + unixDate);
                        }

                        if (subject.matches(".*(?:Unavailable by ICMP ping|has been restarted).*")) {
                            if (unixDate >= ctPrevDutyBegin && unixDate <= ctCurrDutyEnd) {
                                proceedPD(isInteractive, subject, msgLogGroup, unixDate, dateStr);
                            } else if (config.isDebug()) {
                                System.err.println("Skipping PD message due to time filter: unixDate=" + unixDate);
                            }
                            continue;
                        } else if (subject.matches(".*(?:[Pp][Oo][Ww][Ee][Rr]|STM [Ss][Tt][Mm].?[" + (config.isDebug() ? "1-9" : "2-9") + "][0-9]*).*")) {
                            Map<String, Map<String, Map<Long, String>>> tempMsgLogGroup = new HashMap<>();
                            proceedSDH(isInteractive, subject, body, tempMsgLogGroup, unixDate, dateStr);

                            for (String group : tempMsgLogGroup.keySet()) {
                                for (String device : tempMsgLogGroup.get(group).keySet()) {
                                    for (Long ts : tempMsgLogGroup.get(group).get(device).keySet()) {
                                        if (ts >= ctPrevDutyBegin && ts <= ctCurrDutyEnd) {
                                            String message = tempMsgLogGroup.get(group).get(device).get(ts);
                                            msgLogGroup.computeIfAbsent(group, k -> new HashMap<>())
                                                    .computeIfAbsent(device, k -> new HashMap<>())
                                                    .put(ts, message);
                                            if (config.isDebug()) {
                                                System.err.println("Added SDH message to report: group=" + group + ", device=" + device + ", ts=" + ts + ", message=" + message);
                                            }
                                        } else if (config.isDebug()) {
                                            System.err.println("Skipping SDH message due to time filter: group=" + group + ", device=" + device + ", ts=" + ts);
                                        }
                                    }
                                }
                            }
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

        if (from.endsWith(":")) {
            if (!from.matches(".*-\\d+:$")) {
                from = from.replace(":", "-65535:");
            }
            String[] fromParts = from.split(":");
            String fromName = fromParts[0];
            String fromObject = fromName.matches(".*-\\d+$") ? fromName.replaceAll("^(?:[rsp]|(?:ies\\d?|alca)-)", "") : fromName;
            from = fromObject.replace("-65535", "");
            fromName = fromName.replace("-65535", "");
            String originalFrom = from;
            from = dictionary.lookupPD(from);
            needCheck = originalFrom.equals(from);
        }

        String state = "Zabbix зареєстровано ";
        if (subject.contains(" Resolved:")) {
            state = "Zabbix зареєстровано кінець інциденту, ";
        } else if (subject.contains(" Problem:")) {
            state = "Zabbix зареєстровано початок інциденту, ";
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

        String fromName = from;
        msgLogGroup.computeIfAbsent(from, k -> new HashMap<>())
                .computeIfAbsent(fromName, k -> new HashMap<>())
                .put(ts, dt + " : " + msg);

        if (isInteractive && config.isDebug()) {
            System.err.println("PD stored: from=" + from + ", ts=" + ts + ", dt=" + dt + ", msg=" + msg);
        }
    }

    private String convertMonthNumToMnemo(String dt) {
        Matcher matcher = MONTH_PATTERN.matcher(dt);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sb, MONTH_MAP.get(matcher.group()));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    public static String formatReport(Config config, LocalDateTime dutyBegin, LocalDateTime dutyEnd, Map<String, Map<String, Map<Long, String>>> msgLogGroup) {
        StringBuilder html = new StringBuilder();
        html.append("<p><ol><h1><small><small>Інциденти, <u>зареєстровані в автоматичному режимі</u> системами Zabbix та OSM,<br>")
                .append("що відбувалися в період з ").append(dutyBegin.format(DATE_TIME_FORMATTER))
                .append(" по ").append(dutyEnd.format(DATE_TIME_FORMATTER)).append("</small></small></h1>");

        boolean prnGroup = false;
        boolean prnDevice = false;
        boolean prnNull = true;

        List<String> groups = new ArrayList<>(msgLogGroup.keySet());
        Collections.sort(groups);

        long ctDutyBegin = dutyBegin.atZone(ZoneId.systemDefault()).toEpochSecond();
        long ctDutyEnd = dutyEnd.atZone(ZoneId.systemDefault()).toEpochSecond();

        for (String group : groups) {
            List<String> devices = new ArrayList<>(msgLogGroup.get(group).keySet());
            Collections.sort(devices);
            for (String device : devices) {
                List<Long> times = new ArrayList<>(msgLogGroup.get(group).get(device).keySet());
                Collections.sort(times);
                for (Long time : times) {
                    if (time >= ctDutyBegin && time <= ctDutyEnd) {
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
