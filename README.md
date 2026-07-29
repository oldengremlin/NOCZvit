# NOCZvit

Звіт NOC про інциденти, зареєстровані в автоматичному режимі системами Zabbix та OSM.

## Опис

NOCZvit — Java-програма, яка автоматично формує щозмінний звіт для NOC. Програма:

- Читає IMAP-папку із повідомленнями від Zabbix та OSM
- Групує та фільтрує інциденти за послугами
- Отримує показники температури обладнання через SNMP (Celsius / Ramos)
- Отримує список боржників із MSSQL (опціонально)
- Генерує AI-резюме зміни через Anthropic Claude API (опціонально)
- Надсилає готовий HTML-звіт на e-mail

## Схема роботи

### Загальний потік

```mermaid
flowchart TD
    CLI([java -jar NOCZvit.jar]) --> CFG[Config\nнастройки + словники]
    CFG --> PAR{{"Паралельна ініціалізація\nvirtual threads"}}

    PAR --> IMAP[imap.Client\nчитання IMAP]
    PAR --> ZAB[zabbix.Client\nlogin]
    PAR --> DB[(Debtors\nMSSQL)]

    IMAP --> INC[/IMAP incidents/]
    ZAB  --> ZS[/Zabbix session/]
    DB   --> DH[/HTML боржників/]

    ZS --> ZEVT["event.get history\n→ ProblemFilter\n→ ZabbixIncidentConverter"]
    INC  --> MERGE[/incidentsForTable\nIMAP + Zabbix/]
    ZEVT --> MERGE

    MERGE --> CLAUDE["claude.SummaryClient\nAI-резюме зміни\n(опціонально)"]
    MERGE --> ISB[IncidentSectionBuilder\nінциденти + Ping-графіки]
    ZS    --> ISB
    INC & ZS --> SNMP[snmp.Client\nCelsius + Ramos]

    CLAUDE --> HTML[HTML-звіт]
    ISB  --> HTML
    SNMP --> HTML
    DH   --> HTML

    HTML --> MAIL[EmailSender\nsendmail]
    MAIL --> OUT([e-mail NOC])
```

### Маршрутизація IMAP-повідомлень

```mermaid
flowchart LR
    FOLDER[IMAP-папка] --> RDR[ImapReader]
    RDR --> DEDUP[Дедуплікація adlink]
    DEDUP --> RT{imap.Client}

    RT -- ping/restarted --> PD[PdIncidentParser]
    RT -- ospfNbrStateChange --> OSPF[OspfIncidentParser]
    RT -- adlink-Fault --> ADL[AdlinkIncidentParser]
    RT -- Power/STM-N --> OSM[OsmIncidentParser]

    DICT[(Словник)] -.-> PD & OSPF & ADL & OSM
    PD & OSPF & ADL & OSM --> OUT[/List of Incident/]
```

### Структура класів

```mermaid
classDiagram
    class NOCZvit {
        +main(String[] args)
    }
    class Config {
        +isIncidentsEnabled() bool
        +isZabbixEnabled() bool
        +isClaudeEnabled() bool
        +isDebug() bool
        +getClaudeApiKey() String
        +getClaudeModel() String
    }
    class Dictionary {
        +lookupPD(key) String
        +lookupSDH(key) String
    }
    class EmailSender {
        +sendReport(subject, body)
    }
    class Debtors {
        +toString() String
    }

    class ImapClient["imap.Client"] {
        +prepareImapFolder() List~Incident~
        -deduplicateAdlink()
    }
    class ImapReader {
        +readMessages() List~RawMessage~
    }
    class RawMessage {
        <<record>>
        +subject() String
        +body() String
        +unixDate() long
        +dateStr() String
    }
    class PdIncidentParser {
        +parse(msg) Optional~Incident~
    }
    class OsmIncidentParser {
        +parse(msg) Optional~Incident~
    }
    class OspfIncidentParser {
        +parse(msg) Optional~Incident~
    }
    class AdlinkIncidentParser {
        +parse(msg) Optional~Incident~
    }

    class Incident {
        <<record>>
        +location() String
        +device() String
        +messageTs() long
        +eventTs() long
        +source() Source
        +status() Status
        +description() String
        +reviewNames() List~String~
    }
    class Source {
        <<enumeration>>
        PD
        OSM
        ZABBIX
    }
    class Status {
        <<enumeration>>
        START
        END
        NONE
    }

    class ZabbixProblem {
        <<record>>
        +host() String
        +name() String
        +clock() long
        +rClock() long
        +isActive() bool
    }
    class ZabbixIncidentConverter {
        +convert(ZabbixProblem) List~Incident~
    }
    class ProblemFilter {
        +filter(problems, imapIncidents) List~ZabbixProblem~
    }

    class IncidentSectionBuilder {
        +build(incidents, zabbix, from, to) String
    }
    class SummaryClient["claude.SummaryClient"] {
        +generateSummary(incidentsForTable, from, to) String
    }
    class SnmpClient["snmp.Client"] {
        +getCelsius(from, to, zabbix) String
        +getRamos() String
    }
    class ZabbixClient["zabbix.Client"] {
        +login() bool
        +getProblems(from, to) List~ZabbixProblem~
        +getPingGraphRow() String
        +getGraphRow() String
    }

    NOCZvit --> Config
    NOCZvit --> ImapClient
    NOCZvit --> ZabbixClient
    NOCZvit --> SnmpClient
    NOCZvit --> IncidentSectionBuilder
    NOCZvit --> SummaryClient
    NOCZvit --> EmailSender
    NOCZvit --> Debtors

    ImapClient --> ImapReader
    ImapClient --> Dictionary
    ImapClient --> PdIncidentParser
    ImapClient --> OsmIncidentParser
    ImapClient --> OspfIncidentParser
    ImapClient --> AdlinkIncidentParser

    ImapReader ..> RawMessage : creates

    PdIncidentParser ..> RawMessage : reads
    PdIncidentParser ..> Dictionary : lookup
    PdIncidentParser ..> Incident : creates
    OsmIncidentParser ..> RawMessage : reads
    OsmIncidentParser ..> Dictionary : lookup
    OsmIncidentParser ..> Incident : creates
    OspfIncidentParser ..> RawMessage : reads
    OspfIncidentParser ..> Dictionary : lookup
    OspfIncidentParser ..> Incident : creates
    AdlinkIncidentParser ..> RawMessage : reads
    AdlinkIncidentParser ..> Dictionary : lookup
    AdlinkIncidentParser ..> Incident : creates

    Incident --> Source
    Incident --> Status

    ZabbixClient ..> ZabbixProblem : creates
    ProblemFilter ..> ZabbixProblem : filters
    ZabbixIncidentConverter ..> ZabbixProblem : reads
    ZabbixIncidentConverter ..> Dictionary : lookup
    ZabbixIncidentConverter ..> Incident : creates

    IncidentSectionBuilder ..> Incident : renders
    IncidentSectionBuilder ..> ZabbixClient : Ping-графіки

    SummaryClient ..> Incident : reads
    SummaryClient ..> Config : apiKey + model

    SnmpClient ..> ZabbixClient : температурні графіки
```

## Вимоги

- **JDK**: 21 або новіше (перевірено на JDK 21 та JDK 24+)
- **Maven**: 3.6.0 або новіше
- **Залежності** (підтягуються автоматично через `pom.xml`):
  - Jakarta Mail `com.sun.mail:jakarta.mail:2.0.2`
  - SNMP4J `org.snmp4j:snmp4j:3.9.7`
  - Apache Commons Lang `org.apache.commons:commons-lang3:3.20.0`
  - Apache Commons Text `org.apache.commons:commons-text:1.15.0`
  - Gson `com.google.code.gson:gson:2.13.2`
  - jTDS `net.sourceforge.jtds:jtds:1.3.1`
  - Lombok `org.projectlombok:lombok:1.18.46` (provided)
  - Anthropic Java SDK `com.anthropic:anthropic-java:2.34.0` (опціонально, для Claude AI)

## Збирання

```bash
mvn clean package
```

Результат — `target/NOCZvit-1.11.5.jar` (uber-JAR з усіма залежностями).

## Запуск

```bash
java -jar target/NOCZvit-1.11.5.jar [OPTIONS]
```

### Параметри командного рядка

| Параметр | Опис |
|---|---|
| `--config=<шлях>` | Шлях до зовнішнього файлу конфігурації (за замовчуванням — вбудований `noczvit.properties`) |
| `--dictionarypd=<шлях>` | Шлях до зовнішнього словника PD |
| `--dictionarysdh=<шлях>` | Шлях до зовнішнього словника SDH |
| `--incidents` / `--no-incidents` | Увімкнути/вимкнути блок інцидентів |
| `--temperature` / `--no-temperature` | Увімкнути/вимкнути блок температури (SNMP Celsius) |
| `--ramos` / `--no-ramos` | Увімкнути/вимкнути блок Ramos |
| `--zabbix` / `--no-zabbix` | Увімкнути/вимкнути вбудовування графіків температури з Zabbix |
| `--claude` / `--no-claude` | Увімкнути/вимкнути AI-резюме зміни (за замовчуванням: увімк. в нормальному режимі, вимк. в `--debug`) |
| `--debug` / `--no-debug` | Дебаг-режим: звіт надсилається на `email.toDebug` замість `email.to` |

Параметри командного рядка мають пріоритет над налаштуваннями у `noczvit.properties`.

### Приклад запуску в дебаг-режимі

```bash
java -jar target/NOCZvit-1.11.5.jar --debug --no-incidents
```

## Налаштування

Основний конфігураційний файл — `src/main/resources/noczvit.properties` (вбудовується в JAR під час збирання). За потреби можна передати зовнішній файл через `--config=`.

### Ключові параметри

```properties
# Режими
debug=false
incidents=true
temperature=true
ramos=false

# IMAP
mail.hostname=imap.example.com
mail.username=noc@example.com
mail.password=secret
mail.ssl=true
mail.zabbixFolder=Zabbix

# SNMP
snmp.community=public
snmp.community.celsius=public
snmp.community.ramos=public
snmp.hosts.suffix=example.com
snmp.hosts=host1 label:desc=1.3.6.1.4.1.2636.3.1.13.1.5.7.1.0.0;temp=1.3.6.1.4.1.2636.3.1.13.1.7.7.1.0.0

# E-mail вихідний
email.from=noc@example.com
email.replyTo=noc@example.com
email.to=shift@example.com,manager@example.com
email.toDebug=dev@example.com

# Zabbix (опціонально, для графіків температури)
zabbix=false
zabbix.api=https://zabbix.example.com/zabbix/api_jsonrpc.php
zabbix.url=https://zabbix.example.com/zabbix
zabbix.username=noczvit
zabbix.password=secret
zabbix.graphwidth=640
zabbix.graphheight=83

# MSSQL (опціонально, для списку боржників)
account-mssql-server=sqlserver
account-mssql-database=Accounting
account-mssql-user=reader
account-mssql-password=secret
accequipment-mssql-server=sqlserver
accequipment-mssql-database=Equipment
accequipment-mssql-user=reader
accequipment-mssql-password=secret

# Claude AI (опціонально, для резюме зміни)
# API-ключ генерується на https://console.anthropic.com (окремий від підписки claude.ai)
# claude.apikey=sk-ant-...
# claude.model=claude-haiku-4-5
# claude=false  ← явно вимкнути завжди; claude=true ← вмикати навіть в --debug
```

### Claude AI (резюме зміни)

Опціональна секція на початку звіту — людськомовний опис зміни (до 10 речень), сформований Anthropic Claude на основі **об'єднаного списку інцидентів** (IMAP + Zabbix), тобто тих самих даних, що відображаються в HTML-таблиці.

**Що входить до промпту:**
- Звітний період та кількість унікальних подій (START-записи)
- Повний список інцидентів (location, device, опис, статус)
- Попередньо обчислений факт: незакриті інциденти на кінець зміни

**Що генерує Claude:**
- Загальна картина зміни (кількість та характер подій)
- Ключові локації та пристрої з проблемами
- Перелік незакритих інцидентів (якщо є)
- Різний термін для ночі: «кінець звітного періоду» (20:00–07:59) vs «кінець зміни» (08:00–19:59)

**Вбудовані доменні знання в промпті:**
- `Routing Engine: High CPU utilization` на Juniper — інформаційна подія, не впливає на трафік (TFEB/PFE окремо від RE); описується нейтрально
- При згадуванні BGP обов'язково вказується ім'я сусіда (neighbor)
- Мова резюме: українська без русизмів, офіційний стиль

**Отримання API-ключа:**
1. Зареєструватися на [console.anthropic.com](https://console.anthropic.com) *(окремий акаунт від claude.ai)*
2. Додати платіжний метод
3. Згенерувати ключ (`sk-ant-...`) та вказати його в `claude.apikey`

**Поведінка за замовчуванням** (без явного `claude=` у конфігурації):

| Режим запуску | Claude |
|---|---|
| Звичайний (без `--debug`) | **увімкнений** (якщо є `claude.apikey`) |
| `--debug` | **вимкнений** |
| `--claude` (явно) | увімкнений незалежно від режиму |
| `--no-claude` (явно) | вимкнений незалежно від режиму |

**Орієнтовна вартість** на моделі `claude-haiku-4-5`: ~$0.01–0.02/день (2 виклики × ~3 000–5 000 вхідних + ~300 вихідних токенів залежно від кількості інцидентів за зміну). Бюджету $5 вистачить приблизно на **1–2 роки**.

### Словники

- `dictionary_pd.txt` — фрази для розпізнавання PD-інцидентів
- `dictionary_sdh.txt` — фрази для розпізнавання SDH-інцидентів

## Структура проекту

```
NOCZvit/
├── src/main/java/net/ukrcom/noczvit/
│   ├── NOCZvit.java               — точка входу
│   ├── Config.java                — зчитування та валідація конфігурації (Lombok)
│   ├── Dictionary.java            — словники PD/SDH (regex-lookup з кешем; нормалізація hostname: prefix ^[rsp]/ies/alca- + суфікс -N)
│   ├── Debtors.java               — список боржників із MSSQL
│   ├── imap/
│   │   ├── Client.java            — оркестратор: читання IMAP → парсинг → List<Incident>
│   │   ├── ImapReader.java        — I/O: читання сирих повідомлень з IMAP-папки
│   │   ├── RawMessage.java        — record: незмінний DTO (subject, body, unixDate, dateStr)
│   │   ├── PdIncidentParser.java  — Zabbix ICMP ping / restarted
│   │   ├── OsmIncidentParser.java — OSM/SDH (Power, STM-N); Trap value → точний час події
│   │   ├── OspfIncidentParser.java — Zabbix ospfNbrStateChange
│   │   ├── AdlinkIncidentParser.java — сухі контакти adlink (card/port/line → словник)
│   │   └── DateUtils.java         — конвертація місяців у локалізований рядок
│   ├── model/
│   │   └── Incident.java          — record: доменна модель інциденту (Source, Status, reviewNames)
│   ├── report/
│   │   └── IncidentSectionBuilder.java — HTML-секція інцидентів (групування, Ping-графіки)
│   ├── claude/
│   │   └── SummaryClient.java     — Claude API: генерація короткого резюме зміни (опціонально)
│   ├── snmp/
│   │   └── Client.java            — SNMP-опитування (virtual threads, паралельно)
│   └── zabbix/
│       ├── Client.java            — Zabbix API: login, event.get history, host/graph lookup, chart2.php PNG
│       ├── ZabbixProblem.java     — record: host, name, clock, rClock; isActive()
│       ├── ZabbixIncidentConverter.java — ZabbixProblem → List<Incident> з Dictionary lookup
│       └── ProblemFilter.java     — фільтрація: порожній host, SDH-OSM, No SNMP, OSPF, дублікати IMAP
├── src/main/resources/
│   ├── noczvit.properties         — конфігурація за замовчуванням
│   ├── logback.xml                — конфігурація логування (Logback)
│   ├── dictionary_pd.txt          — словник PD/OSPF/adlink (regex → назва / опис)
│   ├── dictionary_sdh.txt         — словник SDH/OSM (regex → назва виносу)
│   ├── help.txt
│   └── version.properties
└── pom.xml
```

## Версіонування

Проект дотримується [Semantic Versioning](https://semver.org/):

- **MAJOR** — несумісні зміни API або конфігурації
- **MINOR** — нова функціональність зі зворотною сумісністю
- **PATCH** — виправлення помилок

Версія автоматично підставляється в `version.properties` під час збирання і відображається в e-mail заголовку `X-PoweredBy`.
