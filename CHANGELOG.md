# CHANGELOG

Всі важливі зміни цього проекту фіксуються тут.
Формат засновано на [Keep a Changelog](https://keepachangelog.com/uk/1.0.0/).
Проект дотримується [Semantic Versioning](https://semver.org/).

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

[1.4.0]: https://github.com/oldengremlin/noczvit/compare/v1.3.4...v1.4.0
[1.3.4]: https://github.com/oldengremlin/noczvit/compare/v1.3.3...v1.3.4
[1.3.3]: https://github.com/oldengremlin/noczvit/compare/v1.3.2...v1.3.3
[1.3.2]: https://github.com/oldengremlin/noczvit/compare/v1.3.1...v1.3.2
[1.3.1]: https://github.com/oldengremlin/noczvit/releases/tag/v1.3.1
