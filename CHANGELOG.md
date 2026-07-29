# CHANGELOG

Всі важливі зміни цього проекту фіксуються тут.
Формат засновано на [Keep a Changelog](https://keepachangelog.com/uk/1.0.0/).
Проект дотримується [Semantic Versioning](https://semver.org/).

---

## [1.11.5] — 2026-07-29

### Документація
- Повний прохід JavaDoc по всіх 21 Java-класах: додано документацію до класів, публічних і приватних методів, enum-констант та record-компонентів
- Виправлено застарілий коментар у `NOCZvit.java`: тепер коректно відображає, що `incidentsForTable` (IMAP + Zabbix) передається як до HTML-таблиці, так і до Claude

---

## [1.11.4] — 2026-07-29

### Змінено
- `SummaryClient`: заголовок секції резюме тепер містить дисклеймер — `(згенеровано за допомогою Claude Anthropic API)` — щоб читач чітко розумів: текст написано AI, а не інженером NOC

---

## [1.11.3] — 2026-07-29

### Змінено
- `SummaryClient.buildHtml()`: заголовок секції резюме тепер залежить від часу початку зміни — «Резюме зміни» для денної (08:00–19:59) та «Резюме за звітний період» для нічної (20:00–07:59)

---

## [1.11.2] — 2026-07-29

### Виправлено
- `OspfIncidentParser`: колонка «Обладнання» в таблиці звіту була порожньою для OSPF-інцидентів — `device` передавався як `""` замість `originalRouter` (raw hostname до підстановки зі словника)

---

## [1.11.1] — 2026-07-29

### Змінено (уточнення промпту Claude)
- Ліміт речень: «3-5» → «до 10» — резюме може бути розгорнутішим при складній зміні
- Якщо є незакриті інциденти на кінець зміни — перерахувати їх явно
- Ніч (20:00–07:59): «на кінець зміни» → «на кінець звітного періоду» (моніторинг без людини; NOC-інженер — з 08:00)
- RE CPU: згадувати узагальнено, без акцентування, без провокування тривоги
- BGP: при згадуванні обов'язково вказувати ім'я сусіда (neighbor): «зміна стану BGP-сусіда НАЗВА»
- «УНИКАЙ в українській русизмів» — стилістична вимога до мови резюме

---

## [1.11.0] — 2026-07-28

### Додано
- **Zabbix event history у таблиці звіту** — `zabbix.Client.getProblems()` переключено з `problem.get` (очищається housekeeping) на `event.get` (постійна таблиця); відображає всі PROBLEM-події зміни, включно з уже вирішеними
  - Двоетапний запит: `event.get` з `value=[1]` для PROBLEM-подій → другий `event.get` для `rClock` через `r_eventid`
  - Fallback для template-based тригерів з порожнім `hosts`: `trigger.get` за `objectid`
- **`ZabbixProblem`** — record: `host, name, clock, rClock`; `isActive()` → `rClock == 0`
- **`ZabbixIncidentConverter`** — конвертує `ZabbixProblem` → `List<Incident>` (START + опціональний END); location через `Dictionary.lookupPD()`; опис містить назву локації, а не raw hostname
- **`ProblemFilter`** — ланцюжок фільтрів перед конвертацією: порожній host, `SDH-OSM`, `No SNMP`, `OSPF`, перезавантаження, дедублікація з IMAP-інцидентами (±5 хвилин)
- Zabbix-інциденти злиті з IMAP у `incidentsForTable` — відображаються в HTML-таблиці поряд з IMAP-інцидентами (групування по локаціях, один Ping-графік на унікальний пристрій)

### Змінено
- **`Dictionary.lookupPD()`** — нормалізація hostname перенесена всередину методу: знімає prefix `^(?:[rsp]|(?:ies\d?|alca)-)` і (лише при збігу prefix) суфікс `-\d+$`; fallback на оригінальний ключ; захищає типи `adlink-hoh15-1` від зайвого зрізання суфікса
- **`SummaryClient.generateSummary()`** приймає `incidentsForTable` (IMAP + Zabbix) замість лише IMAP — Claude отримує повну картину зміни
- **Claude prompt** — блок «Доменні знання»: `Routing Engine: High CPU utilization` на Juniper не впливає на комутацію (TFEB/PFE окремо від RE); явна заборона формулювань «системна проблема», «обробка трафіку», «рекомендується діагностика»; рекомендована нейтральна формула

### Виправлено
- `SummaryClient.buildPrompt()`: `100%` у text block з `.formatted()` → `UnknownFormatConversionException: Conversion = ' '`; виправлено на `100%%`

---

## [1.10.0] — 2026-07-28

### Додано
- **Claude AI резюме зміни** (`claude.SummaryClient`) — опціональна секція на початку звіту з коротким людськомовним описом зміни українською мовою, сформованим через Anthropic API (`anthropic-java 2.34.0`)
  - Вмикається параметром `claude=true` + `claude.apikey=sk-ant-...` у конфігурації або прапором `--claude` у командному рядку
  - Модель за замовчуванням: `claude-haiku-4-5`; перевизначається через `claude.model=...`
  - При відсутності ключа або помилці API — секція мовчки пропускається (без збою звіту)
  - Промпт: кількість та характер інцидентів, ключові локації, незакриті аварії — 3-5 речень, офіційний стиль, без технічного жаргону
- Нова залежність: `com.anthropic:anthropic-java:2.34.0`

---

## [1.9.0] — 2026-07-28

### Архітектура
- Розділення коду за відповідальністю: **Infrastructure → Domain → Presentation**
  - `imap.ImapReader` — лише I/O (читання IMAP, формування `RawMessage`)
  - `record RawMessage` — незмінний DTO з полями subject, body, unixDate, dateStr
  - `record Incident` — доменна модель інциденту з enum `Source` (PD/OSM) та `Status` (START/END/NONE)
  - `imap.Client` — оркестратор: делегує I/O до `ImapReader`, бізнес-логіку до парсерів
  - `imap.*IncidentParser` — по одному парсеру на тип повідомлення
  - `report.IncidentSectionBuilder` — формування HTML-секції інцидентів (відокремлено від бізнес-логіки)
- `net.ukrcom.noczvit.zabbix.ZabbixClient` → `net.ukrcom.noczvit.zabbix.Client` (однакова конвенція з `imap.Client` та `snmp.Client`)

### Додано
- `OspfIncidentParser` — підтримка повідомлень Zabbix `ospfNbrStateChange`; формат: «Zabbix зареєстровано початок/кінець інциденту, падіння каналу на \<router> по каналу \<channel>»; обидві назви з словника PD з `reviewNames`-маркером при відсутності
- `AdlinkIncidentParser` — підтримка сухих контактів (dry-contact monitor); парсить `card N, port N, line N - Fault`; ключ `device:card:port:line` у словнику PD визначає опис події; ключ `device` визначає назву виносу для групування; fallback — «спрацювання сухого контакту, лінія N» + `reviewNames`
- Дедублікація adlink-повідомлень у `imap.Client`: Zabbix надсилає кожне сповіщення двічі; пара з однаковим subject у 60-секундному вікні зводиться до першого; дедуплікація виконується до фільтрації за зміною, тому граничні дублікати (напр. 07:59:59 + 08:00:03) коректно залишаються в ранішній зміні
- `OsmIncidentParser`: розрізнення між `Power` на кондиціонери і на виніс — за ознакою `Air Condition` у темі листа; опис: «зникнення живлення на \<location> до кондиціонерів» (без «виносі»)
- `imap.DateUtils` — утиліта конвертації місяців (українська локаль), перенесена з `ImapClient`

### Змінено
- Словник `dictionary_pd.txt` тепер підтримує складені ключі для adlink: `^adlink-dev:card:port:line=опис події`
- `reviewNames` — список `List<String>` у `Incident`; рендериться в `IncidentSectionBuilder` як «(потребує коригування назви '\<name>')»
- Колонка «Дата та час» у звіті завжди показує час **отримання листа** (messageTs); час події з Trap value OSM відображається в описі через суфікс «який відбувся \<eventTime>»

---

## [1.8.0] — 2026-07-28

### Додано
- SLF4J 2.0 + Logback 1.5 — повноцінне структуроване логування замість `System.err.println` скрізь по коду
- `logback.xml` у ресурсах: рівень INFO за замовчуванням, вивід у `System.err` з форматом `HH:mm:ss LEVEL [Class] message`
- `@Slf4j` (Lombok) на всіх 8 класах — `private static final Logger log` більше не пишеться вручну
- `--debug` тепер перемикає кореневий рівень Logback на DEBUG до ініціалізації `Config`, тому всі повідомлення про завантаження конфігурації теж потрапляють до виводу

### Змінено
- `System.err.println(...)` → `log.debug/info/warn/error(...)` з `{}`-плейсхолдерами (без зайвої конкатенації рядків при вимкненому рівні)
- `if (config.isDebug()) { System.err.println(...) }` прибрано скрізь — рівень логування контролює Logback, а не ручний прапор; `config.isDebug()` залишився тільки для двох функціональних цілей: вибір отримувачів email та режим "завантажити всі листи" в IMAP
- `log.warn(...)` для відновлюваних проблем (SNMP таймаут, граф не знайдено, sendmail error-code)
- `log.error(...)` для фатальних помилок (IMAP, SQL, невалідна конфігурація)
- Повідомлення Ping-chart2.php тепер мають `log.isDebugEnabled()` guard перед побудовою великого preview-рядка

---

## [1.7.0] — 2026-07-28

### Виправлено (критичне)
- `Dictionary`: `HashMap` → `LinkedHashMap` для `pdDictionary` та `sdhDictionary` — сортування за довжиною regex більше не губиться при вставці в `HashMap` (де `Pattern` використовує identity hashCode)
- `EmailSender`: виняток у потоці-записнику більше не губиться беззвучно — замість анонімного `Thread` використовується `Future` через `Executors.newVirtualThreadPerTaskExecutor()`; `MessagingException`/`IOException` з `message.writeTo()` тепер коректно піднімаються до `sendReport`
- `SnmpClient` (Ramos): `Double.parseDouble(null)` → NPE; тепер є `null`-перевірка перед парсингом усіх 7 значень датчика

### Додано
- `Dictionary`: кеш повторних пошуків (`ConcurrentHashMap`) — повторний lookup по тому ж ключу не перебирає весь словник
- `Config`: новий параметр `email.sendmail` (шлях до sendmail, за замовчуванням `/usr/sbin/sendmail`)

### Паралелізм
- `NOCZvit.main()`: IMAP, авторизація Zabbix та отримання боржників запускаються паралельно через `CompletableFuture` + virtual threads — найбільший виграш у часі запуску
- `ImapClient.formatReport`: Ping-графіки для всіх пристроїв у виносі завантажуються паралельно (`CompletableFuture`) зі збереженням порядку відображення
- `SnmpClient` (Ramos): 7 окремих SNMP GET на датчик замінено одним multi-OID GET — 7× менше round-trip'ів на кожен сенсор

### Змінено
- `Config`: `Integer.parseInt` для `zabbix.graphwidth`/`zabbix.graphheight` захищено — некоректне значення у конфігурації → fallback на 640/83 замість `NumberFormatException`
- `Config.getHosts()` / `Config.getRamos()`: повертають `Collections.unmodifiableMap` — захист від випадкової мутації
- `EmailSender`: шлях до sendmail конфігурується через `email.sendmail`; буфер 1024→8192 байт; `process.waitFor(30, TimeUnit.SECONDS)` з таймаутом і `destroyForcibly()` при перевищенні
- `ZabbixClient.apiCall()`: перевірка HTTP-статусу — не-200 кидає `IOException` замість того щоб парсити HTML як JSON
- `ZabbixClient`: `result.size() > 0` → `!result.isEmpty()`
- `Debtors`: SQL-помилки логуються завжди (раніше — лише в debug-режимі)
- `ImapClient.PATTERN_ORIGINALFROMNAME`: `private final ... Pattern.compile(":$").pattern()` → `private static final String ":$"` (видалено зайвий `Pattern.compile`)
- `ImapClient.convertMonthNumToMnemo`: `StringBuffer` → `StringBuilder`
- `ImapClient.filterAndMergeMessages`: зовнішні цикли `keySet()` + `get(key)` → `entrySet()`
- `ImapClient.prepareImapFolder`: `System.exit(2)` у catch-блоці замінено на `throw new RuntimeException` — VM більше не зупиняється примусово

---

## [1.6.0] — 2026-07-28

### Додано
- Графіки Ping для обладнання у розділах інцидентів: після рядків таблиці кожного виносу додаються `<tr>` з embedded PNG-графіком `<hostname>: Ping` за звітний період (лише для Zabbix-інцидентів, не OSM; унікальні пристрої, порядок першої появи)
- `ZabbixClient.getPingGraphRow(hostname, from, to)` — новий публічний метод для завантаження Ping-графіків
- `ZabbixClient.resolveGraphId` тепер приймає повну назву для пошуку (замість суфікса `: Temperature`); кешування працює для будь-якої назви графіка
- `ZabbixClient` тепер створюється один раз при старті (`isZabbixEnabled()`) і використовується і для інцидентів, і для температури

### Змінено
- `ImapClient.formatReport` отримав параметр `ZabbixClient zabbix` (може бути `null` — Ping-графіки пропускаються)
- `NOCZvit`: `ZabbixClient` ініціалізується до формування звіту, умова ввімкнення — `isZabbixEnabled()` (раніше вимагало `isTemperatureEnabled()`)

---

## [1.5.0] — 2026-07-28

### Додано
- `ZabbixClient`: авторизація через `user.login` API (токен використовується як `zbx_sessionid` cookie), пошук хоста (`host.get`) та графіка (`graph.get` з пошуком `"<desc>: Temperature"`), завантаження `chart2.php` та вбудовування PNG як `data:image/png;base64,...` у звіт
- Після кожного рядка температури у таблиці додається рядок з графіком за звітний період (якщо Zabbix увімкнено і граф знайдено)
- Новий параметр CLI `--zabbix` / `--no-zabbix`; нові конфіг-параметри `zabbix`, `zabbix.api`, `zabbix.url`, `zabbix.username`, `zabbix.password`, `zabbix.graphwidth`, `zabbix.graphheight`
- `getCelsius(from, to, zabbix)` тепер отримує звітний часовий діапазон замість `LocalDateTime.now()`

### Змінено
- `NOCZvit`: визначення зміни (`nightShift`) винесено в окрему змінну; `reportFrom`/`reportTo` передаються в `getCelsius`
- Graceful degradation: будь-яка помилка Zabbix (мережа, авторизація, граф не знайдено) → просто пропускається графік, рядок з температурою залишається

---

## [1.4.6] — 2026-07-23

### Виправлено
- Зебра-чергування тепер працює й на рядках зі статусом: парні `row-end` → `#e2f5e2` (темніший зелений), парні `row-start`/`row-critical` → `#f5e2e2` (темніший червоний)
- `tr:hover td` отримав `!important` щоб hover перекривав парні статусні рядки з вищою специфічністю

---

## [1.4.5] — 2026-07-23

### Виправлено
- Відкат `border-collapse:separate` → `border-collapse:collapse`: `overflow:hidden` на `<table>` не обрізає дочірні `<td>` у Gecko, тому закруглення не з'являлося, а подвійний 2px border між клітинками залишався як побічний ефект

---

## [1.4.4] — 2026-07-23

### Змінено
- Закруглені кути таблиць (`border-radius:5px`) — перехід на `border-collapse:separate; border-spacing:0; overflow:hidden`; Outlook ігнорує без наслідків

---

## [1.4.3] — 2026-07-23

### Змінено
- Підсвітка рядка при наведенні мишки (`tr:hover td{background:#e8ecf5}`) — прогресивне покращення для Thunderbird та інших Gecko-клієнтів; Outlook та The Bat ігнорують без наслідків

---

## [1.4.2] — 2026-07-23

### Змінено
- Таблиці інцидентів, температури та боржників: зебра-підсвітка парних рядків (`#f5f7fa`) для легшого читання довгих таблиць
- Кольори статусів (`row-start`, `row-end`, `row-critical`) мають пріоритет над зеброю завдяки порядку CSS-правил
- Зебра підтримується в Thunderbird та сучасних клієнтах; Outlook ігнорує `:nth-child` без втрати читабельності

---

## [1.4.1] — 2026-07-23

### Змінено
- `Debtors`: список заблокованих абонентів тепер відображається таблицею (колонки №, Абонент) замість нумерованого списку
- Таблиця боржників виділена тонким червоним обрамленням (`box-shadow: 0 0 0 2px #ef9a9a`) — помітно, але без агресії
- CSS клас `.table-debtors` додано до глобальних стилів у `NOCZvit.java`
- Коментовані приклади (IMPERATIVE / АНТИПАТЕРН) оновлено під новий табличний формат

---

## [1.4.0] — 2026-07-23

### Додано
- Паралельне SNMP-опитування хостів у `SnmpClient` через `Executors.newVirtualThreadPerTaskExecutor()` + `Semaphore(10)` — замість послідовного обходу; усуває "затик" при таймаутах

### Змінено
- Версія переведена на нумерацію `1.4.x` для відображення нової функціональності
- README.md оновлено: актуальні версії залежностей, опис параметрів CLI, структура проекту, опис версіонування
- CHANGELOG.md — запроваджено

---

## [1.3.4] — 2026-04-26

### Змінено
- `ImapClient`: рефакторинг — `record MessageHeader` тепер без поля `toBeContinue`; метод `parseMessageHeader` повертає `Optional<MessageHeader>` замість `null` + булевого прапора
- Перейменовано константи патернів у `UPPER_SNAKE_CASE`

### Виправлено
- Порядок ініціалізації у `Config`: `parsePathArgs` тепер викликається до `loadProperties()`, `parseFlagArgs` — після `generalProperties()`, щоб параметри CLI мали пріоритет над файлом конфігурації
- Параметр `--config=` тепер коректно перехоплюється до завантаження `noczvit.properties`

---

## [1.3.3] — 2026-04-25

### Додано
- Lombok (`org.projectlombok:lombok:1.18.46`) інтегровано в `Config`: `@Getter`, `@ToString`, `@EqualsAndHashCode`, `@NonNull`; усі ручні геттери видалено
- `annotationProcessorPaths` у `maven-compiler-plugin` для надійного виявлення Lombok на всіх середовищах збирання

### Виправлено
- Lombok 1.18.36 → 1.18.46: усунуто `TypeTag::UNKNOWN` на JDK 24+

---

## [1.3.2] — 2026-04-22

### Додано
- Модуль `Debtors`: отримання списку заблокованих абонентів із двох MSSQL-баз (Accounting + Equipment)
- Перехід з `mssql-jdbc` на `jTDS` + резолвер псевдонімів FreeTDS
- Заміна ручного парсингу JSON на бібліотеку Gson у `parseServicesLastState`
- Maven Shade Plugin: виключення підписаних JAR-маніфестів (`META-INF/*.SF`, `*.DSA`, `*.RSA`) для усунення `SecurityException`

---

## [1.3.1] — 2025-05-12

### Додано
- Перший публічний реліз у репозиторії
- IMAP-клієнт для читання повідомлень Zabbix та OSM
- SNMP-клієнт: `getCelsius()` (температура обладнання) та `getRamos()` (датчики Ramos)
- Формування HTML-звіту та відправка через Jakarta Mail
- Конфігурація через `noczvit.properties` з підтримкою параметрів CLI
- Словники `dictionary_pd.txt` / `dictionary_sdh.txt` для класифікації інцидентів
- Версія автоматично підставляється в `version.properties` через Maven filtering

---

[1.11.4]: https://github.com/oldengremlin/noczvit/compare/v1.11.3...v1.11.4
[1.11.3]: https://github.com/oldengremlin/noczvit/compare/v1.11.2...v1.11.3
[1.11.2]: https://github.com/oldengremlin/noczvit/compare/v1.11.1...v1.11.2
[1.11.1]: https://github.com/oldengremlin/noczvit/compare/v1.11.0...v1.11.1
[1.11.0]: https://github.com/oldengremlin/noczvit/compare/v1.10.0...v1.11.0
[1.9.0]: https://github.com/oldengremlin/noczvit/compare/v1.8.0...v1.9.0
[1.8.0]: https://github.com/oldengremlin/noczvit/compare/v1.7.0...v1.8.0
[1.7.0]: https://github.com/oldengremlin/noczvit/compare/v1.6.0...v1.7.0
[1.6.0]: https://github.com/oldengremlin/noczvit/compare/v1.5.0...v1.6.0
[1.5.0]: https://github.com/oldengremlin/noczvit/compare/v1.4.6...v1.5.0
[1.4.6]: https://github.com/oldengremlin/noczvit/compare/v1.4.5...v1.4.6
[1.4.5]: https://github.com/oldengremlin/noczvit/compare/v1.4.4...v1.4.5
[1.4.4]: https://github.com/oldengremlin/noczvit/compare/v1.4.3...v1.4.4
[1.4.3]: https://github.com/oldengremlin/noczvit/compare/v1.4.2...v1.4.3
[1.4.2]: https://github.com/oldengremlin/noczvit/compare/v1.4.1...v1.4.2
[1.4.1]: https://github.com/oldengremlin/noczvit/compare/v1.4.0...v1.4.1
[1.4.0]: https://github.com/oldengremlin/noczvit/compare/v1.3.4...v1.4.0
[1.3.4]: https://github.com/oldengremlin/noczvit/compare/v1.3.3...v1.3.4
[1.3.3]: https://github.com/oldengremlin/noczvit/compare/v1.3.2...v1.3.3
[1.3.2]: https://github.com/oldengremlin/noczvit/compare/v1.3.1...v1.3.2
[1.3.1]: https://github.com/oldengremlin/noczvit/releases/tag/v1.3.1
