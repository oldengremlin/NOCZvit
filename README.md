# NOCZvit
Звіт NOC про інциденти, зареєстровані в автоматичному режимі системами Zabbix та OSM

## Опис
NOCZvit — це Java-програма, яка автоматично формує звіти про інциденти, отримані від систем Zabbix та OSM, а також дані про температуру обладнання через SNMP. Звіт надсилається електронною поштою у форматі HTML.

## Встановлення
1. Встановіть [Maven](https://maven.apache.org/) і [JDK 21](https://openjdk.org/projects/jdk/21/).
2. Склонуйте репозиторій або скопіюйте вихідний код.
3. Зберіть проект:
   ```bash
   mvn clean package
   ```
4. Запустіть програму:
   ```bash
   java -jar target/NOCZvit-1.2.2.jar
   ```

## Налаштування
- Основна конфігурація розташована в `src/main/resources/noczvit.properties`.
- Словники для PD та SDH повідомлень: `dictionary_pd.txt` та `dictionary_sdh.txt`.
- Версія програми автоматично береться з `pom.xml` і записується в `version.properties` під час збирання.
- Параметри командного рядка (наприклад, `--debug`, `--no-incidents`) переважають налаштування з `noczvit.properties`.

### Приклад запуску в дебаг-режимі
```bash
java -jar target/NOCZvit-1.2.2.jar --debug
```
У дебаг-режимі звіт надсилається на адресу, вказану в `email.toDebug`.

## Тестування
1. Переконайтеся, що `version.properties` створено в `src/main/resources` із вмістом:
   ```
   project.version=${project.version}
   ```
2. Після збирання перевірте, чи в `target/classes/version.properties` версія відповідає `pom.xml` (наприклад, `1.1.1`).
3. Запустіть програму в дебаг-режимі та перевірте email-заголовок `X-PoweredBy` (має бути `NOCZvit v1.1.1`).
4. Змініть `<version>` у `pom.xml` (наприклад, на `1.2.0`), повторно зберіть і переконайтеся, що заголовок оновився.

## Вимоги
- **JDK**: 21
- **Maven**: 3.6.0 або новіше
- **Залежності** (автоматично додаються через `pom.xml`):
  - Jakarta Mail (`com.sun.mail:jakarta.mail:2.0.1`)
  - SNMP4J (`org.snmp4j:snmp4j:3.9.2`)
  - Apache Commons Lang (`org.apache.commons:commons-lang3:3.17.0`)

## Структура проекту
- `src/main/java/net/ukrcom/noczvit/` — вихідний код Java.
- `src/main/resources/` — конфігураційні файли та словники.
- `pom.xml` — конфігурація Maven для збирання та залежностей.

## Використання
Програма автоматично:
1. Зчитує IMAP-повідомлення з папки, вказаної в `mail.zabbixFolder`.
2. Формує звіт про інциденти (якщо `incidents=true`).
3. Отримує дані про температуру через SNMP (якщо `temperature=true` або `ramos=true`).
4. Надсилає звіт на email-адреси, вказані в `email.to` (або `email.toDebug` у дебаг-режимі).

Детальніше про налаштування — у `src/main/resources/help.txt`.