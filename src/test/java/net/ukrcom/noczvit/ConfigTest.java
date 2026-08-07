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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Тести {@link Config}: пріоритет CLI над properties, резолюція {@code claudeEnabled},
 * {@code isValid()}/{@code isDebtorsEnabled()}/{@code isTrapEnabled()}, безпечний парсинг чисел
 * та ключованих атрибутів, обрізання inline-коментарів.
 */
class ConfigTest {

    // ---- Пріоритет CLI-прапорців над properties ----

    @Test
    @DisplayName("--zabbix перемагає zabbix=false у properties")
    void cliFlag_overridesZabbixDisabledInProperties() throws IOException {
        Config config = TestFixtures.config("--zabbix");
        assertTrue(config.isZabbixEnabled());
    }

    @Test
    @DisplayName("--no-incidents перемагає incidents=true у properties")
    void cliFlag_overridesIncidentsEnabledInProperties() throws IOException {
        Config config = TestFixtures.config("--no-incidents");
        assertFalse(config.isIncidentsEnabled());
    }

    @Test
    @DisplayName("Без CLI-прапорців properties застосовуються як є")
    void noCliFlags_propertiesApplyAsIs() throws IOException {
        Config config = TestFixtures.config();
        assertFalse(config.isZabbixEnabled());
        assertTrue(config.isIncidentsEnabled());
    }

    // ---- claudeEnabled: пріоритет CLI > property > default ----

    @Test
    @DisplayName("claudeEnabled: CLI-прапорець --claude перемагає claude=false у properties")
    void claudeEnabled_cliFlagOverridesProperty(@TempDir Path tempDir) throws IOException {
        Path p = tempDir.resolve("custom.properties");
        Files.writeString(p, baseProperties() + "\nclaude=false\nclaude.apikey=test-api-key\n", StandardCharsets.UTF_8);

        Config config = new Config(new String[]{"--config=" + p, "--claude"});
        assertTrue(config.isClaudeEnabled());
    }

    @Test
    @DisplayName("claudeEnabled: властивість claude= перемагає дефолт (!debug)")
    void claudeEnabled_propertyOverridesDefault(@TempDir Path tempDir) throws IOException {
        // debug=true -> дефолт був би false, але claude=true в properties має перемогти
        Path p = tempDir.resolve("custom.properties");
        Files.writeString(p, baseProperties() + "\ndebug=true\nclaude=true\nclaude.apikey=test-api-key\n",
                StandardCharsets.UTF_8);

        Config config = new Config(new String[]{"--config=" + p});
        assertTrue(config.isClaudeEnabled());
    }

    @Test
    @DisplayName("claudeEnabled: без CLI і без властивості claude= використовується дефолт !debug")
    void claudeEnabled_defaultIsInverseOfDebug(@TempDir Path tempDir) throws IOException {
        Path pDebugOff = tempDir.resolve("debug-off.properties");
        Files.writeString(pDebugOff, baseProperties() + "\ndebug=false\nclaude.apikey=test-api-key\n",
                StandardCharsets.UTF_8);
        Config configDebugOff = new Config(new String[]{"--config=" + pDebugOff});
        assertTrue(configDebugOff.isClaudeEnabled());

        Path pDebugOn = tempDir.resolve("debug-on.properties");
        Files.writeString(pDebugOn, baseProperties() + "\ndebug=true\nclaude.apikey=test-api-key\n",
                StandardCharsets.UTF_8);
        Config configDebugOn = new Config(new String[]{"--config=" + pDebugOn});
        assertFalse(configDebugOn.isClaudeEnabled());
    }

    @Test
    @DisplayName("claudeEnabled: авто-вимикається, якщо claude.apikey порожній, навіть при явному --claude")
    void claudeEnabled_forcedFalseWhenApiKeyBlank() throws IOException {
        // test-noczvit.properties не містить claude.apikey
        Config config = TestFixtures.config("--claude");
        assertTrue(config.getClaudeApiKey().isBlank());
        assertFalse(config.isClaudeEnabled());
    }

    // ---- isValid() ----

    @Test
    @DisplayName("isValid(): true, коли задані emailFrom/emailReplyTo/emailTo (і community є за замовчуванням)")
    void isValid_trueWhenEmailAddressesPresent() throws IOException {
        Config config = TestFixtures.config();
        assertTrue(config.isValid());
    }

    @Test
    @DisplayName("isValid(): false, коли email.to відсутній (emailTo порожній)")
    void isValid_falseWhenEmailToMissing(@TempDir Path tempDir) throws IOException {
        Path p = tempDir.resolve("custom.properties");
        Files.writeString(p, "email.from=noc@test.invalid\nemail.replyTo=noc@test.invalid\n", StandardCharsets.UTF_8);

        Config config = new Config(new String[]{"--config=" + p});
        assertFalse(config.isValid());
    }

    // ---- isDebtorsEnabled() ----

    @Test
    @DisplayName("isDebtorsEnabled(): false, коли MSSQL-властивості відсутні (test-noczvit.properties)")
    void isDebtorsEnabled_falseByDefault() throws IOException {
        Config config = TestFixtures.config();
        assertFalse(config.isDebtorsEnabled());
    }

    @Test
    @DisplayName("isDebtorsEnabled(): true лише коли всі чотири MSSQL-властивості (server+database для обох баз) непорожні")
    void isDebtorsEnabled_trueWhenAllFourPropertiesPresent(@TempDir Path tempDir) throws IOException {
        String props = baseProperties()
                + "\naccount-mssql-server=acct-srv\naccount-mssql-database=acct-db"
                + "\naccequipment-mssql-server=eq-srv\naccequipment-mssql-database=eq-db\n";
        Path p = tempDir.resolve("custom.properties");
        Files.writeString(p, props, StandardCharsets.UTF_8);

        Config config = new Config(new String[]{"--config=" + p});
        assertTrue(config.isDebtorsEnabled());
    }

    @Test
    @DisplayName("isDebtorsEnabled(): false, коли бракує лише однієї з чотирьох властивостей")
    void isDebtorsEnabled_falseWhenOnlyThreeOfFourPropertiesPresent(@TempDir Path tempDir) throws IOException {
        String props = baseProperties()
                + "\naccount-mssql-server=acct-srv\naccount-mssql-database=acct-db"
                + "\naccequipment-mssql-server=eq-srv\n"; // accequipment-mssql-database відсутній
        Path p = tempDir.resolve("custom.properties");
        Files.writeString(p, props, StandardCharsets.UTF_8);

        Config config = new Config(new String[]{"--config=" + p});
        assertFalse(config.isDebtorsEnabled());
    }

    // ---- isTrapEnabled() / isRamosTrapEnabled() ----

    @Test
    @DisplayName("isTrapEnabled()/isRamosTrapEnabled(): true, коли відповідні folder-властивості задані (test-noczvit.properties)")
    void trapEnabled_trueWhenFoldersConfigured() throws IOException {
        Config config = TestFixtures.config();
        assertTrue(config.isTrapEnabled());
        assertTrue(config.isRamosTrapEnabled());
    }

    @Test
    @DisplayName("isTrapEnabled()/isRamosTrapEnabled(): false, коли folder-властивості відсутні")
    void trapEnabled_falseWhenFoldersAbsent(@TempDir Path tempDir) throws IOException {
        Path p = tempDir.resolve("custom.properties");
        Files.writeString(p, baseProperties(), StandardCharsets.UTF_8);

        Config config = new Config(new String[]{"--config=" + p});
        assertFalse(config.isTrapEnabled());
        assertFalse(config.isRamosTrapEnabled());
    }

    // ---- parseIntSafe (приватний, перевіряємо через zabbix.graphwidth/height) ----

    @Test
    @DisplayName("zabbix.graphwidth: некоректне число -> fallback на дефолт 640, без винятку")
    void zabbixGraphWidth_invalidNumber_fallsBackToDefault(@TempDir Path tempDir) throws IOException {
        Path p = tempDir.resolve("custom.properties");
        Files.writeString(p, baseProperties() + "\nzabbix.graphwidth=не_число\n", StandardCharsets.UTF_8);

        Config config = new Config(new String[]{"--config=" + p});
        assertEquals(640, config.getZabbixGraphWidth());
    }

    @Test
    @DisplayName("zabbix.graphheight: некоректне число -> fallback на дефолт 83, без винятку")
    void zabbixGraphHeight_invalidNumber_fallsBackToDefault(@TempDir Path tempDir) throws IOException {
        Path p = tempDir.resolve("custom.properties");
        Files.writeString(p, baseProperties() + "\nzabbix.graphheight=не_число\n", StandardCharsets.UTF_8);

        Config config = new Config(new String[]{"--config=" + p});
        assertEquals(83, config.getZabbixGraphHeight());
    }

    @Test
    @DisplayName("zabbix.graphwidth: коректне число застосовується")
    void zabbixGraphWidth_validNumber_isApplied(@TempDir Path tempDir) throws IOException {
        Path p = tempDir.resolve("custom.properties");
        Files.writeString(p, baseProperties() + "\nzabbix.graphwidth=800\n", StandardCharsets.UTF_8);

        Config config = new Config(new String[]{"--config=" + p});
        assertEquals(800, config.getZabbixGraphWidth());
    }

    // ---- parseCommaList (приватний, перевіряємо через resilienceaudit.ignoreinterfaceprefixes) ----

    @Test
    @DisplayName("resilienceaudit.ignoreinterfaceprefixes: відсутня властивість -> порожній список")
    void resilienceIgnoredInterfacePrefixes_absent_isEmpty() throws IOException {
        Config config = TestFixtures.config();
        assertTrue(config.getResilienceIgnoredInterfacePrefixes().isEmpty());
    }

    @Test
    @DisplayName("resilienceaudit.ignoreinterfaceprefixes: розбирається на trim+lowercase, порожні елементи (подвійна кома) відкидаються")
    void resilienceIgnoredInterfacePrefixes_parsedTrimmedLowercasedNoBlanks(@TempDir Path tempDir) throws IOException {
        Path p = tempDir.resolve("custom.properties");
        Files.writeString(p, baseProperties()
                + "\nresilienceaudit.ignoreinterfaceprefixes= WireGuard , sstp ,, L2TP\n", StandardCharsets.UTF_8);

        Config config = new Config(new String[]{"--config=" + p});
        assertEquals(List.of("wireguard", "sstp", "l2tp"), config.getResilienceIgnoredInterfacePrefixes());
    }

    // ---- stripInlineComment ----

    @Test
    @DisplayName("stripInlineComment: claude.model з хвостовим коментарем обрізається до значення без пробілів")
    void stripInlineComment_trimsTrailingCommentFromClaudeModel(@TempDir Path tempDir) throws IOException {
        Path p = tempDir.resolve("custom.properties");
        Files.writeString(p, baseProperties() + "\nclaude.model=my-model # коментар\n", StandardCharsets.UTF_8);

        Config config = new Config(new String[]{"--config=" + p});
        assertEquals("my-model", config.getClaudeModel());
    }

    @Test
    @DisplayName("stripInlineComment: без коментаря значення застосовується як є (лише trim)")
    void stripInlineComment_noCommentPresent_valueUnchanged(@TempDir Path tempDir) throws IOException {
        Path p = tempDir.resolve("custom.properties");
        Files.writeString(p, baseProperties() + "\nclaude.model=plain-model\n", StandardCharsets.UTF_8);

        Config config = new Config(new String[]{"--config=" + p});
        assertEquals("plain-model", config.getClaudeModel());
    }

    // ---- parseKeyedAttributes (hostsProperties / snmp.hosts) ----

    @Test
    @DisplayName("snmp.hosts: коректний парсинг key:attr=val;attr=val,key2:attr=val у мапу")
    void hostsProperties_parsesKeyedAttributesCorrectly(@TempDir Path tempDir) throws IOException {
        Path p = tempDir.resolve("custom.properties");
        Files.writeString(p, baseProperties() + "\nsnmp.hosts=host1:desc=1.2.3;temp=1.2.4,host2:desc=5.6.7\n",
                StandardCharsets.UTF_8);

        Config config = new Config(new String[]{"--config=" + p});
        Map<String, Map<String, String>> hosts = config.getHosts();

        assertEquals(2, hosts.size());
        assertEquals("1.2.3", hosts.get("host1").get("desc"));
        assertEquals("1.2.4", hosts.get("host1").get("temp"));
        assertEquals("5.6.7", hosts.get("host2").get("desc"));
    }

    @Test
    @DisplayName("snmp.hosts: записи без ':' та атрибути без '=' пропускаються, а не кидають виняток")
    void hostsProperties_malformedEntriesAreSkipped(@TempDir Path tempDir) throws IOException {
        Path p = tempDir.resolve("custom.properties");
        // "malformed" без ':' -> пропущено цілком; "host1:badattr" (без '=') -> сам ключ лишається з порожньою мапою атрибутів
        Files.writeString(p, baseProperties() + "\nsnmp.hosts=malformed,host1:badattr,host2:desc=ok\n",
                StandardCharsets.UTF_8);

        Config config = new Config(new String[]{"--config=" + p});
        Map<String, Map<String, String>> hosts = config.getHosts();

        assertFalse(hosts.containsKey("malformed"));
        assertTrue(hosts.containsKey("host1"));
        assertTrue(hosts.get("host1").isEmpty());
        assertEquals("ok", hosts.get("host2").get("desc"));
    }

    @Test
    @DisplayName("snmp.ramos: той самий формат парситься так само, як snmp.hosts")
    void ramosProperties_parsesKeyedAttributesCorrectly(@TempDir Path tempDir) throws IOException {
        Path p = tempDir.resolve("custom.properties");
        Files.writeString(p, baseProperties() + "\nsnmp.ramos=10.0.0.1:name=ДЦ1\n", StandardCharsets.UTF_8);

        Config config = new Config(new String[]{"--config=" + p});
        assertEquals("ДЦ1", config.getRamos().get("10.0.0.1").get("name"));
    }

    // ---- Регресія: --dictionarydeviceword= не має вбивати процес ----

    @Test
    @DisplayName("Регресія: --dictionarydeviceword=<шлях> НЕ трактується як невідомий аргумент")
    void dictionaryDeviceWordFlag_isRecognizedAndDoesNotAbort() throws IOException {
        Config config = TestFixtures.config("--dictionarydeviceword=/будь/який/шлях");
        assertNotNull(config);
        assertEquals("/будь/який/шлях", config.getDictionaryDeviceWordPath());
    }

    // ---- Спільний мінімальний набір властивостей для custom-properties тестів ----

    /**
     * Мінімальний набір властивостей, достатній для успішного проходження конструктора
     * {@link Config}: валідні email-адреси, щоб {@code isValid()} мав змістовну базову лінію.
     */
    private static String baseProperties() {
        return "email.from=noc@test.invalid\n"
                + "email.replyTo=noc@test.invalid\n"
                + "email.to=shift@test.invalid\n";
    }
}
