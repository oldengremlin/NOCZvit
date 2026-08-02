# CHANGELOG

Всі важливі зміни цього проекту фіксуються тут.
Формат засновано на [Keep a Changelog](https://keepachangelog.com/uk/1.0.0/).
Проект дотримується [Semantic Versioning](https://semver.org/).

---

## [1.18.0] — 2026-08-02

### Змінено
- **Фільтрація листів перенесена на IMAP-сервер.** Обидва читачі використовували анонімний підклас `SearchTerm`, а jakarta.mail транслює в команду `SEARCH` лише **стандартні** терми — власний підклас тихо відкочується на `Folder.search()`, який завантажує **всі** повідомлення папки й викликає `getSentDate()` на кожному. Для теки трапів, що накопичувалась роками, це була найдорожча операція запуску. Замінено на `AndTerm(SentDateTerm GE, SentDateTerm LE)` у спільному `ImapReader.dateRangeTerm()`. IMAP порівнює дати з добовою гранулярністю, тому діапазон розширено на добу з кожного боку; точне відсікання по секундах уже виконують наявні пост-фільтри в `imap.Client` і `NOCZvit`
- **`Debtors` більше не вивантажує всю таблицю `Customers`.** Порядок читання інвертовано: спершу `ServicesLastState` з accequipment-БД, потім один запит `WHERE Customer_id IN (…)` до account-БД лише за потрібними ID. Раніше в пам'ять піднімалась вкладена `HashMap` по всіх клієнтах, хоча використовувались десятки записів. Те, що бази на різних серверах, цьому не заважає — навпаки, саме тому інверсія й потрібна. IN-список розбивається партіями по 1000 (ліміт MSSQL — 2100 параметрів на запит); плейсхолдери генеруються з розміру партії, значення завжди прив'язуються через `setInt`

### Видалено
- Закоментований блок «ІМПЕРАТИВНИЙ СТИЛЬ» у `Debtors` — після зміни сигнатури `buildAccountMap()` він ще й посилався на неіснуючий метод

---

## [1.17.6] — 2026-08-02

### Змінено
- **`Config` читає конфігурацію як UTF-8**: `Properties.load(InputStream)` за специфікацією декодує ISO-8859-1, тому кириличні значення (наприклад `snmp.ramos=...:name=Датацентр`, яке йде просто в HTML звіту) перетворювались на мойбейк. Обидві гілки завантаження переведено на `InputStreamReader` з явним UTF-8
- **`imap.Client` приймає `Dictionary` у конструкторі** замість того, щоб створювати власний. Раніше на процес припадало два екземпляри: обидва файли словників читалися двічі, всі patterns компілювались двічі, а парсери мали кеш, відокремлений від того, яким користується `ZabbixIncidentConverter`. Конструктор більше не кидає `IOException`

### Видалено
- **`snmp.trap.correlation.minutes`** — параметр не робив нічого: `TrapCorrelator.correlationSeconds` присвоювався в конструкторі й не читався ніде. Прибрано весь ланцюжок (поле `Config`, парсинг, гетер, аргумент конструктора, згадки в `.sample` і README). Вікно кореляції Active↔Cleared як не було обмеженим, так і лишається — поведінка не змінилась
- **`subject.contains("ramb-\\d+:")`** у `PdIncidentParser.isIgnored()` — `contains()` шукає буквальний підрядок `ramb-\d+:`, тому умова не спрацьовувала ніколи. Пристрої `ramb-*` (комутатори «Швидкої допомоги», м. Київ) потрапляли у звіт і **мають** там бути; умову прибрано, щоб її помилково не «полагодили»
- **`rowClass` у `IncidentSectionBuilder`** — завжди `""`; разом із ним прибрано чотири CSS-правила `tr.row-start` / `tr.row-end`, класи для яких у HTML не генеруються
- **`rowClass` у `RamosTrapSection`** — генерував `class="ramos-crit"` / `"ramos-warn"`, хоча відповідні CSS-правила прибрано у 1.17.0 на користь нейтрального фону. Javadoc, що обіцяв червону та бурштинову підсвітку, приведено у відповідність
- **Недосяжна перевірка** `problem.host().isBlank()` у `ProblemFilter.isDuplicateOfImap()` — виклик відбувається після фільтра `.filter(p -> !p.host().isBlank())`

---

## [1.17.5] — 2026-08-02

### Виправлено
- **`fixRussianisms` псував текст**: правила стояли від коротшої форми до довшої, тому `событиях` матчилось правилом для `…ия` і давало **`подіїх`** у резюме. Порядок перевернуто (довші форми першими); основу замінено на `соб[ыи]т[иі]`, що покриває всі три написання від моделі (`событи-`, `собити-`, `собиті-`) — раніше варіанти `собитія/собитій/собитіях` не замінювались узагалі
- **`HEX_SENSOR_RE` хибно спрацьовував**: патерн приймав будь-яке ім'я з парних hex-символів, тому датчик `AC` (кондиціонер), `DC DC`, `CA FE` декодувались у `�`. Вимагається щонайменше 4 байтові групи, а декодування виконується строгим `CharsetDecoder` з `CodingErrorAction.REPORT` — `new String(bytes, UTF_8)` мовчки підставляв U+FFFD і ніколи не кидав винятку
- **Перехід на зимовий час**: трапи RAMOS/Emerson і `Trap value` OSM повідомляють настінний час без зони, а `atZone()` у неоднозначну годину завжди обирає літнє зміщення — події з другого проходу отримували мітку на годину назад і могли випасти зі звіту або поставити Cleared перед Active. Додано `DateUtils.toInstant(local, referenceEpochSec)`, який бере зміщення із заголовка `Date:` листа як опорне для `ZonedDateTime.ofLocal()`
- **Строгий парсер `Date:` мовчки викидав листи**: формат `EEE, d MMM yyyy HH:mm:ss Z` відхиляє легальні RFC 5322 форми — коментар зони `+0300 (EEST)` (додає Postfix), подвійний пробіл перед одноцифровим днем, відсутній день тижня чи секунди. Додано фолбек на `msg.getSentDate()` (толерантний `MailDateFormat` від jakarta.mail)
- **NPE у `SearchTerm.match()`**: лист без заголовка `Date:` давав `getSentDate() == null` → NPE, який проходив повз `catch (MessagingException)` і зривав увесь звіт. Додано перевірку на `null` і `catch RuntimeException`
- **`extractText` не рекурсував у вкладений multipart**: лист структури `multipart/mixed → multipart/alternative → text/plain` (типовий для листа з вкладенням) давав **порожнє тіло** — трап або інцидент втрачався мовчки. Додано обхід дерева в глибину; `InputStream`-частини читаються як UTF-8 в обох гілках
- **Один кривий рядок `ServicesLastState` зривав весь звіт**: ловився лише `JsonSyntaxException`, тоді як `getAsJsonArray`/`getAsInt` кидають `IllegalState`/`NumberFormat`/`UnsupportedOperation`/`NPE`. Додано перевірки структури та `catch RuntimeException`
- **Zabbix**: `catch` розширено до `RuntimeException` у шести місцях — HTML-сторінка від reverse-proxy замість JSON або відсутнє поле у відповіді валили звіт; `substring(0, 8)` для короткого токена кидав `StringIndexOutOfBounds`

---

## [1.17.4] — 2026-08-02

### Виправлено
- **Мережеві таймаути — раніше не було жодного.** Один завислий сокет підвішував cron-запуск назавжди (`executor.close()` чекає `awaitTermination(1, DAYS)`), процеси накопичувались щодня, а ззовні виглядало «живим»:
  - **jTDS** (`Debtors`): додано `;loginTimeout=10;socketTimeout=60` — драйвер має дефолт `0`, тобто нескінченне очікування
  - **Zabbix** (`zabbix/Client`): `connectTimeout` 10 с на клієнті, `timeout` 30 с на JSON-RPC-запитах, 60 с на `chart2.php` (рендер PNG повільніший). `java.net.http.HttpClient` не має дефолтних таймаутів
  - **Claude** (`SummaryClient`): `timeout` 90 с і `maxRetries(1)` — дефолт SDK 10 хв на запит плюс ретраї, а виклик синхронний у головному потоці
  - **IMAP** (`ImapReader`, `ImapTrapReader`): таймаути реєструються під префіксом, що відповідає протоколу (`mail.imaps.` при SSL, `mail.imap.` інакше). Раніше `mail.imap.timeout` при `mail.ssl=true` **ігнорувався повністю**, бо `getStore("imaps")` змушує jakarta.mail читати інший префікс. Додано `connectiontimeout` (його не було взагалі)
- **`orTimeout(10 хв)`** на `allOf(...)` у `NOCZvit` — страхувальна сітка на випадок помилки в таймаутах окремих клієнтів
- **`log.error("Fatal error", e)`** замість `log.error("Fatal error: {}", e.getMessage())` — загорнутий NPE друкував `Fatal error: null` без жодної діагностики

### Змінено
- Прибрано мертву властивість `maven.compiler.release=25` з `pom.xml` — `maven-compiler-plugin` має явний `<release>21</release>`, який її перекривав; реальна ціль збірки — Java 21 (байткод major 65)

---

## [1.17.3] — 2026-08-02

### Виправлено
- **HTML-ін'єкція з SNMP-відповідей** (`snmp/Client.java`): `desc`, `temp`, `value`, `unit`, `error` — це буквальний вміст OCTET STRING від пристрою, вставлявся у звіт без екранування. Усі підстановки обгорнуто в `StringEscapeUtils.escapeHtml4`. У гілці RAMOS екранування виконується **до** вставки маркерів `<font>` для hot/cold zone, інакше самі теги були б заекрановані
- **HTML-ін'єкція `location` / `device`** (`report/IncidentSectionBuilder.java`): обидва значення походять із теми IMAP-листа (`subject.split("\\s+")[2]`) і виводились без екранування, хоча `description` і `reviewNames` у тому самому методі екранувалися. Додано `escapeHtml4`
- **`Config.@ToString` розкривав секрети**: згенерований `toString()` включав `mailPassword`, `zabbixPassword`, `claudeApiKey`, обидва MSSQL-паролі та поле `properties` (усі пари ключ-значення, тобто секрети вдруге). Додано `exclude` — перевірено, що у згенерованому байткоді згадок паролів більше немає

---

## [1.17.2] — 2026-08-02

### Змінено
- **Рефакторинг**: прибрано зайві проміжні змінні що використовувались рівно один раз у наступному рядку — `emailSender` (NOCZvit), `graphRow` (snmp/Client), `debtorsHtml` (Debtors), `rows` (IncidentSectionBuilder); результати внутрішніх методів тепер передаються безпосередньо

---

## [1.17.1] — 2026-08-02

### Виправлено
- **`RamosTrapParser.expandAbbreviations()`**: додано заміну `Room N` → «зал N» (регістронезалежно, `\broom\s*(\d+)`) — назви датчиків на кшталт `Room3 AVR 1 INPUT POWER 1` перетворюються на «зал 3 автоматичний ввід резерву 1» для коректного контексту Claude
- **`RamosTrapParser.parseBody()`**: `extractRoom()` тепер викликається до `expandAbbreviations()`, щоб групування по розділах (Room3 → Room3) лишалось коректним після перетворення назв датчиків

---

## [1.17.0] — 2026-08-02

### Додано
- **`RamosTrapParser.expandAbbreviations()`**: розгортання абревіатури `AVR` → «автоматичний ввід резерву» у назвах датчиків (регістронезалежно, `\bAVR\b`) — для коректного контексту при передаванні Claude
- **Стан `Sensor Error` у RAMOS**: додано до `REPORTABLE_STATES` → відображається в HTML-таблиці звіту (масова поява зазвичай означає перезавантаження контролера); до Claude **не передається**
- **`ZabbixIncidentConverter.resolveDeviceWord()`**: повна матриця типів пристроїв за hostname-префіксом — `adlink*` → «контролері сухих контактів», `alca*`/`ies*` → «DSLAM», `a*` → «кондиціонері», `r*` → «маршрутизаторі», `s*` → «комутаторі», `p*` → «устаткуванні безперебійного живлення»

### Змінено
- **`RamosTrapSection`**: нейтральний сірий фон рядків замість кольорового (`ramos-crit` / `ramos-warn` CSS-класи видалено); стиль відповідає іншим таблицям звіту
- **`RamosTrapSection`**: поле `sensorType` виключено з plain-text блоку для Claude (залишається в HTML-таблиці); усуває некоректний переклад «Temperature Array» → «температура скупчення»
- **Виправлено шлях `ramos.trap.folder`** у `noczvit.properties.sample`: `INBOX.Internal.SNMP Traps` (замість неіснуючого `INBOX.Internal.SNMP Traps.RAMOS`)

### Виправлено
- **`SummaryClient.fixRussianisms()`**: усі флаги `(?i)` замінено на `(?iu)` — Java `CASE_INSENSITIVE` не охоплює Кирилицю без `UNICODE_CASE`; додано заміни «конец» (усі відмінки) → «кінець», «наконец» → «врешті-решт», «смена/смени/смену/смені/сменою» → «зміна/зміни/зміну/зміні/зміною»
- **`AdlinkIncidentParser.ADLINK_PATTERN`** та **`ZabbixIncidentConverter.TRAP_CARD_PATTERN`**: додано `(?i)` — рядки `Trap card N, port N, line N` від різних версій прошивки можуть мати різний регістр
- **`ZabbixIncidentConverter`**: `s*`-хости (swіtch) правильно визначаються як «комутатор» замість «маршрутизатор»; видалено надлишкову перевірку `!host.startsWith("adlink")`

---

## [1.16.0] — 2026-07-31

### Додано
- **`claude.minsentences`** і **`claude.maxsentences`** — нові властивості конфігурації: задають діапазон кількості речень у резюме зміни (за замовчуванням 5–20); підставляються в промпт «від N до M речень»; парсяться аналогічно до `claude.tokens`
- **`SummaryClient.fixRussianisms()`**: пост-процесинг відповіді Claude — замінює слова-русизми «события/собитія» (відм. наз., род., місц.) на «події/подій/подіях» до повернення HTML-фрагменту
- **Назва моделі та кількість токенів** у підписі блоку резюме Claude: «*згенеровано за допомогою Claude Anthropic API, модель X, використано N токенів*» — дрібний правовирівняний курсивний підпис під текстом резюме (не в заголовку)
- **`log.debug`** з розбивкою токенів: `input=X, output=Y, total=Z` після кожного виклику Claude API

### Змінено
- **`.system()`** в `SummaryClient`: явна заборона слів «события», «событий», «событиях», «собитія», «собитій», «собитіях» з підказкою правильних замінників
- **`claude.tokens` дефолт**: змінено з 2000 на 4096 у `Config.java`; реальне споживання при насиченій зміні (~41 подія) становить ~5300 токенів, старий ліміт обрізав відповідь на півдорозі

---

## [1.15.0] — 2026-07-31

### Додано
- **Пейринг інцидентів Zabbix** (`IncidentSectionBuilder`): листи `[-]` (початок) та `[+]` (закінчення) одного Zabbix-тікета об'єднуються в один рядок таблиці за допомогою `In-Reply-To:` email-заголовка; замість двох рядків з колонкою «Дата та час» — один рядок з колонками «Початок», «Закінчення», «Тривалість»
- **Поле `inReplyTo` у `RawMessage`**: `ImapReader` витягує `In-Reply-To:` заголовок із кожного листа; `ImapTrapReader` передає порожній рядок (трап-листи не потребують пейрингу)
- **Поле `inReplyTo` у `Incident`**: використовується як ключ пейрингу; для IMAP-інцидентів (включно з OSM) — значення `In-Reply-To:` заголовка; для Zabbix API-інцидентів — синтетичний ключ `"zabbix:host:clock"`
- **`ZabbixIncidentConverter`**: генерує синтетичний `pairKey = "zabbix:" + host + ":" + clock` — однаковий для START і END одного `ZabbixProblem`, що дозволяє пейрингу в `IncidentSectionBuilder`
- **Дедуплікація в парах**: береться перший `[-]` (за `messageTs`) та останній `[+]`; якщо однієї сторони немає — відображається `—`
- **`formatDuration()`**: тривалість < 60 с → `< 1 хв`; далі `X хв` або `X год Y хв`
- **`IncidentRow`**: новий private record у `IncidentSectionBuilder` — зберігає пару `(start, end)`, обчислює `location()`, `sortKey()`, `device()`, `mergedReviewNames()`

### Змінено
- **Таблиця інцидентів**: заголовки змінено з `№ | Дата та час | Інцидент | Обладнання` на `№ | Початок | Закінчення | Тривалість | Інцидент | Обладнання`
- **Опис спареного рядка**: «Zabbix зареєстровано початок/кінець інциденту, ...» → «Zabbix зареєстровано інцидент, ...»; неспарені рядки зберігають оригінальний опис
- **Кольорове кодування**: усі рядки таблиці інцидентів без CSS-класів — нейтральне чергування через глобальний `tr:nth-child(even) td { background:#f5f7fa }`; `row-start`/`row-end` більше не використовуються

---

## [1.14.0] — 2026-07-31

### Додано
- **Секція RAMOS трапів** (`RamosTrapEvent`, `RamosTrapParser`, `RamosTrapSection`): нова секція звіту для подій датчиків навколишнього середовища CONTEG RAMOS Ultra/Optima — читає IMAP-папку `ramos.trap.folder` (підтримує wildcard), фільтрує стани Warning/Critical, відображає HTML-таблицю з бренд-кольором `#f38120` (RAMOS/CONTEG помаранчевий), згруповану по кімнатах (Room1–Room4, Інші); до Claude передаються тільки Critical-стани
- **Декодування hex-назв датчиків**: `RamosTrapParser` автоматично виявляє Perl hex byte dumps у назвах датчиків (баг RAMOS Perl-скрипта) та декодує їх через `HexFormat.of().parseHex()` → UTF-8; підтримує multiline hex через DOTALL regex і нормалізацію пробілів
- **`ImapTrapReader.readTrapsFromFolder()`**: новий public метод із явним параметром `folderPattern`; оригінальний `readTraps()` делегує до нього (зворотна сумісність); використовується RAMOS future в `NOCZvit`
- **`Config.isRamosTrapEnabled()`** і **`Config.getRamosTrapFolder()`**: нові методи, парсинг `ramos.trap.folder`
- **`ramos.trap.folder`** — нова властивість конфігурації у `noczvit.properties.sample` (закоментована)
- **Об'єднаний `allTrapPlainText`** для Claude: `trapResult.plainText()` + `ramosTrapResult.plainText()` передаються разом як єдиний блок
- **Секція PS для нерозпізнаних типів трапів**: `Active:Alarm:*`-трапи, що не мають відповідника в таблиці описів і не є ігнорованими, збираються в `unknownTraps` та відображаються після блоку температури окремою PS-секцією (кремовий фон #fffde7, стиль 11px) — список типів подій з часом, згрупований за пристроєм
- **`SELF_CLOSING_ACTIVE` механізм**: трапи-детектори без тривалості (наразі `Active:Alarm:Compressor Short Cycle`) автоматично самозакриваються — `clearedAt = activatedAt`; суфікс «До кінця зміни не відновлено.» не додається; подальший `Cleared` мовчки ігнорується
- **`TrapCorrelator.CorrelationResult`** — новий вкладений record, що повертає `correlate()`: `incidents()` (скорельовані події) та `unknownTraps()` (нерозпізнані сирі трапи)
- **`EmersonTrapSection.build(incidents, unknownTraps)`** — новий overload; `SectionResult` розширено третім полем `unknownHtml()`
- **`claude.tokens`** — нова властивість конфігурації для налаштування `max_tokens` у запитах до Claude API (за замовчуванням 2000); `SummaryClient` читає значення через `Config.getClaudeMaxTokens()`
- Нові CSS-класи: `h2.temp-title` (заголовок блоку температури: синя гамма #1976d2, 16px, фон #e8eaf0), `h2.trap-ps-title`, `h3.trap-ps-device`, `.trap-ps-list` (PS-секція)
- Розширена таблиця `TRAP_DESCRIPTIONS` до 30+ типів із MIB-канонічними перекладами (джерело — `LIEBERT_GP_COND-MIB`): нові типи `Compressor Low Suction Pressure`, `Compressor High Head Pressure`, `Compressor Short Cycle`, `Compressor Overload`, `Air Filter Clogged`, `Water Under Floor`, `Condensation Detected`, `Fire Alarm`, `Smoke Detected`, `Heaters Overheated`, `Humidifier Failure`, `Humidifier Problem`, `Chilled Water Low Water Flow`, `Condensate Pump High Water`, `Unit Shutdown`, `Battery Charging Inhibited`
- `IGNORE_TRAPS` доповнено прошивкою Room4: `Active:Alarm:Unit Standby` та `Cleared:Alarm:Unit Standby`
- Підтримка `Active:Alarm:System Input Power Problem` як другого кореня ланцюжка відключення PDC (прошивка r3/r4) у `CHAIN_ROOT_ACTIVE`/`CHAIN_ROOT_CLEARED`

### Змінено
- `NOCZvit`: `ramosTrapFuture` запускається паралельно з `trapFuture` та іншими future; epoch-змінні (`fromEpoch`, `toEpoch`, `trapFrom`, `trapTo`) винесені з тіла `if (config.isTrapEnabled())` до спільної видимості; порядок виводу у звіті: Emerson → RAMOS → боржники → температура SNMP → PS
- CSS в `NOCZvit`: додано `h2.ramos-title`, `h3.ramos-room`, `tr.ramos-crit`, `tr.ramos-warn` (та їх `nth-child(even)` варіанти)
- `TrapCorrelator.correlate()` тепер повертає `CorrelationResult` замість `List<TrapIncident>`
- Описи в `TRAP_DESCRIPTIONS` приведено до MIB-канонічних перекладів; правило: переклад MIB завжди пріоритетний над довільним формулюванням
- `Compressor Short Cycle`: видалено суфікс «До кінця зміни не відновлено.» — виключено з `NO_UNRESOLVED_SUFFIX`; тепер у `SELF_CLOSING_ACTIVE`
- Заголовок блоку температури переведено з `<p><h1>` на `<h2 class="temp-title">` (16px, колір #1976d2, лівий бордер) — відповідає стилю заголовків трап-секцій
- `normalizeCategory()` в `EmersonTrapParser`: категорії прошивки Room4 `Message:` та `Warning:` → канонічне `Alarm:`
- Prompt Claude: системне повідомлення тепер явно вимагає відповіді **українською**; `warnIfRussian()` виявляє символи ы/ъ/э/ё у відповіді та виводить попередження в лог
- `--debug` + `--claude` явно: результат більше не записується до `history.db` (міжзмінна пам'ять не оновлюється)
- `noczvit.properties.sample`: додано закоментований рядок `# claude.tokens=2000`

---

## [1.13.0] — 2026-07-30

### Додано
- **Нова секція звіту «SNMP-трапи Emerson»**: читає листи з SNMP-трапами від пристроїв ДБЖ та кондиціонерів Emerson/Liebert із IMAP-папок (підтримує wildcard-патерн, напр. `DC-Room*`)
- Новий пакет `trap/` із 7 класами:
  - `ImapTrapReader` — читання IMAP-папок (wildcard-роздільник IMAP через `store.getDefaultFolder().getSeparator()`)
  - `EmersonTrapParser` — парсинг тема+тіло листа → `TrapEvent` (нормалізація: зняти лапки, `": "` → `":"`)
  - `TrapDeduplicator` — дедуплікація `Cold Start` трапів у часовому вікні (за замовчуванням 30 с)
  - `TrapCorrelator` — state machine: ланцюжок PDC-відключення (root `Loss of Mains` + secondary) + самостійні пари Active/Cleared + зв'язування ADC Cold Start із відновленням живлення PDC в тій самій кімнаті
  - `EmersonTrapSection` — формування HTML-таблиці (ADC перед PDC, за алфавітом) і plain-text блоку для Claude
  - `TrapEvent` — record: `timestamp, ip, hostname, trapType, deviceClass`
  - `TrapIncident` — record: `deviceClass, hostname, ip, Severity, activatedAt, clearedAt, description, details`
- Нові властивості конфігурації: `snmp.trap.folder`, `snmp.trap.dedup.seconds`, `snmp.trap.correlation.minutes`, `snmp.trap.coldstart.link.minutes`
- `Config.isTrapEnabled()` — вмикач секції (аналогічно до `isClaudeEnabled()`)
- `SummaryClient.generateSummary()` — новий overload з параметром `trapPlainText` (plain-text блок ізольований маркерами `=== ПОДІЇ ОБЛАДНАННЯ ДАТАЦЕНТРУ ===`)
- Ланцюжки подій PDC: `Loss of Mains` + `Battery Discharging`/`MMS On Battery`/`Bypass Not Available`/`Low Battery` описуються як єдина подія «Зникнення мережевого живлення»; якщо батареї живили навантаження — зазначається
- Cold Start ADC: якщо трап з'явився у вікні `coldstart.link.minutes` після відновлення живлення PDC в тій самій кімнаті — анотується «Пов'язано з відновленням мережевого живлення»
- Ігноровані трапи (нормальні операційні переходи): `Active/Cleared:Alarm:Unit On Standby`, `Active/Cleared:Alarm:Unit On`

### Змінено
- `NOCZvit.java`: `trapFuture` запускається паралельно з IMAP/Zabbix/Debtors; trap-секція вставляється між блоком боржників і температурою
- `SummaryClient`: метод `generateSummary()` без `trapPlainText` делегує до нового overload із порожнім рядком (зворотна сумісність)
- `noczvit.properties.sample`: додано закоментований блок `snmp.trap.*`
- README: нова схема потоку даних, діаграма обробки трапів, оновлена class diagram, розділ «SNMP-трапи Emerson» з таблицею класифікації та поясненням ланцюжків подій

---

## [1.12.0] — 2026-07-30

### Додано
- **Міжзмінна пам'ять Claude**: після кожного успішного виклику API резюме зміни (чистий текст) зберігається у SQLite-файл (`history.resume=jdbc:sqlite:/шлях/history.db`). Перед наступним викликом Claude читається резюме попередньої зміни та передається до промпту — модель може зазначити, що вирішено, а що перейшло з попереднього звітного періоду
- Нові класи: `history.ResumeHistory` (DDL + findPrevious + save/upsert) та `history.ResumeRecord` (record-DTO)
- Нова властивість конфігурації: `history.resume` — JDBC URL SQLite; якщо відсутня — функція вимкнена
- Залежність: `org.xerial:sqlite-jdbc:3.47.1.0`

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

[1.14.0]: https://github.com/oldengremlin/noczvit/compare/v1.13.0...v1.14.0
[1.13.0]: https://github.com/oldengremlin/noczvit/compare/v1.12.0...v1.13.0
[1.12.0]: https://github.com/oldengremlin/noczvit/compare/v1.11.5...v1.12.0
[1.11.5]: https://github.com/oldengremlin/noczvit/compare/v1.11.4...v1.11.5
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
