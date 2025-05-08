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

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class Config {

    private final Properties properties;
    private final Map<String, Map<String, String>> hosts;
    private final Map<String, Map<String, String>> ramos;
    private boolean debugEnabled;
    private boolean incidentsEnabled;
    private boolean temperatureEnabled;
    private boolean ramosEnabled;
    private String configPath;
    private String dictionaryPdPath;
    private String dictionarySdhPath;

    public Config(String[] args) throws IOException {
        properties = new Properties();
        hosts = new HashMap<>();
        ramos = new HashMap<>();
        // Initialize paths to default resource values
        configPath = null; // Will load from resources by default
        dictionaryPdPath = null; // Will load from resources by default
        dictionarySdhPath = null; // Will load from resources by default
        // Parse args to override paths and settings
        parseArgs(args);
        loadProperties();
        // Initialize settings from properties
        debugEnabled = Boolean.parseBoolean(properties.getProperty("debug", "false"));
        incidentsEnabled = Boolean.parseBoolean(properties.getProperty("incidents", "true"));
        temperatureEnabled = Boolean.parseBoolean(properties.getProperty("temperature", "true"));
        ramosEnabled = Boolean.parseBoolean(properties.getProperty("ramos", "false"));
        // Parse args again to override settings (ensures args take precedence)
        parseArgs(args);
        parseHosts();
        parseRamos();
    }

    private void loadProperties() throws IOException {
        if (configPath != null) {
            // Load from specified file path
            try (InputStream input = new FileInputStream(configPath)) {
                properties.load(input);
            } catch (IOException e) {
                throw new IOException("Failed to load configuration file: " + configPath, e);
            }
        } else {
            // Load from default resource
            try (InputStream input = getClass().getClassLoader().getResourceAsStream("noczvit.properties")) {
                if (input == null) {
                    throw new IOException("Default noczvit.properties not found in resources");
                }
                properties.load(input);
            }
        }
    }

    private void parseArgs(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--config=")) {
                configPath = arg.substring("--config=".length()).trim();
            } else if (arg.startsWith("--dictionarypd=")) {
                dictionaryPdPath = arg.substring("--dictionarypd=".length()).trim();
            } else if (arg.startsWith("--dictionarysdh=")) {
                dictionarySdhPath = arg.substring("--dictionarysdh=".length()).trim();
            } else {
                switch (arg) {
                    case "--incidents" ->
                        incidentsEnabled = true;
                    case "--no-incidents" ->
                        incidentsEnabled = false;
                    case "--temperature" ->
                        temperatureEnabled = true;
                    case "--no-temperature" ->
                        temperatureEnabled = false;
                    case "--ramos" ->
                        ramosEnabled = true;
                    case "--no-ramos" ->
                        ramosEnabled = false;
                    case "--debug" ->
                        debugEnabled = true;
                    case "--no-debug" ->
                        debugEnabled = false;
                    default -> {
                        printHelp();
                        System.err.println("Error: Unknown argument: " + arg);
                        System.exit(1);
                    }
                }
            }
        }
    }

    private void printHelp() {
        System.err.println("Usage: java -jar NOCZvit.jar [options]");
        System.err.println("Options:");
        System.err.println("  --config=<path>         Specify path to configuration file (default: noczvit.properties)");
        System.err.println("  --dictionarypd=<path>   Specify path to PD dictionary file (default: dictionary_pd.txt)");
        System.err.println("  --dictionarysdh=<path>  Specify path to SDH dictionary file (default: dictionary_sdh.txt)");
        System.err.println("  --debug                 Enable debug mode (send report to email.toDebug)");
        System.err.println("  --no-debug              Disable debug mode (send report to email.to)");
        System.err.println("  --incidents             Include incidents section in the report");
        System.err.println("  --no-incidents          Exclude incidents section from the report");
        System.err.println("  --temperature           Include temperature section in the report");
        System.err.println("  --no-temperature        Exclude temperature section from the report");
        System.err.println("  --ramos                 Include Ramos temperature section in the report");
        System.err.println("  --no-ramos              Exclude Ramos temperature section from the report");
        System.err.println();
        System.err.println("Configuration and dictionaries are loaded from resources by default. Command-line arguments override property values.");
        System.err.println();
        System.err.println("Configuration File Structure (noczvit.properties):");
        System.err.println("  # General settings");
        System.err.println("  debug=false             Enable/disable debug mode (true/false, default: false)");
        System.err.println("  incidents=true          Enable/disable incidents section (true/false, default: true)");
        System.err.println("  temperature=true        Enable/disable temperature section (true/false, default: true)");
        System.err.println("  ramos=false             Enable/disable Ramos section (true/false, default: false)");
        System.err.println();
        System.err.println("  # Mail settings");
        System.err.println("  mail.hostname=smtp.example.com  SMTP/IMAP server hostname");
        System.err.println("  mail.username=user      Username for SMTP/IMAP authentication");
        System.err.println("  mail.password=pass      Password for SMTP/IMAP authentication");
        System.err.println("  mail.ssl=false          Enable/disable SSL for IMAP (true/false, default: false)");
        System.err.println("  mail.zabbixFolder=INBOX.Zabbix  IMAP folder for Zabbix messages");
        System.err.println();
        System.err.println("  # Email settings");
        System.err.println("  email.from=noczvit@example.com     Sender email address");
        System.err.println("  email.replyTo=support@example.com  Reply-to email address");
        System.err.println("  email.to=recipient1@example.com,recipient2@example.com  Comma-separated list of recipient emails");
        System.err.println("  email.toDebug=debug@example.com    Debug recipient email");
        System.err.println();
        System.err.println("  # SNMP settings");
        System.err.println("  snmp.community=public         SNMP community string (default for all devices)");
        System.err.println("  snmp.community.celsius=public SNMP community string for temperature devices (falls back to snmp.community)");
        System.err.println("  snmp.community.ramos=public   SNMP community string for Ramos devices (falls back to snmp.community)");
        System.err.println("  snmp.hosts.suffix=example.com Domain suffix for SNMP hostnames");
        System.err.println("  snmp.jnxOperatingDescr=.1.3.6.1.4.1.2636.3.1.13.1.5  SNMP OID for equipment description");
        System.err.println("  snmp.jnxOperatingTemp=.1.3.6.1.4.1.2636.3.1.13.1.7   SNMP OID for equipment temperature");
        System.err.println("  snmp.hosts=host1:desc=.1.3.6.1.4.1.2636.3.1.13.1.5;temp=.1.3.6.1.4.1.2636.3.1.13.1.7,host2:desc=...  Comma-separated list of hosts with OIDs (format: hostname:desc=OID;temp=OID)");
        System.err.println("  snmp.ramos=ip1:name=Site1;temperatureSensorIndex=.1.3.6.1.4.1.318.1.1.10.4.2.3.1.3;...,ip2:...       Comma-separated list of Ramos devices (format: ip:name=Name;temperatureSensorIndex=OID;...)");
        System.err.println();
        System.err.println("Example noczvit.properties:");
        System.err.println("  debug=false");
        System.err.println("  incidents=true");
        System.err.println("  temperature=true");
        System.err.println("  ramos=false");
        System.err.println("  mail.hostname=smtp.example.com");
        System.err.println("  mail.username=noczvit");
        System.err.println("  mail.password=secret");
        System.err.println("  mail.ssl=false");
        System.err.println("  mail.zabbixFolder=INBOX.Zabbix");
        System.err.println("  email.from=noczvit@example.com");
        System.err.println("  email.replyTo=support@example.com");
        System.err.println("  email.to=duty-report@example.com,support@example.com");
        System.err.println("  email.toDebug=root@example.com");
        System.err.println("  snmp.community=public");
        System.err.println("  snmp.jnxOperatingDescr=.1.3.6.1.4.1.2636.3.1.13.1.5");
        System.err.println("  snmp.jnxOperatingTemp=.1.3.6.1.4.1.2636.3.1.13.1.7");
        System.err.println("  snmp.hosts=switch1:desc=.1.3.6.1.4.1.2636.3.1.13.1.5;temp=.1.3.6.1.4.1.2636.3.1.13.1.7");
        System.err.println("  snmp.ramos=192.168.1.100:name=Site1;temperatureSensorIndex=.1.3.6.1.4.1.318.1.1.10.4.2.3.1.3;temperatureSensorDescription=.1.3.6.1.4.1.318.1.1.10.4.2.3.1.4");
    }

    private void parseHosts() {
        String hostsStr = properties.getProperty("snmp.hosts");
        if (hostsStr != null) {
            for (String hostEntry : hostsStr.split(",")) {
                String[] parts = hostEntry.split(":");
                String hostName = parts[0].trim();
                Map<String, String> hostData = new HashMap<>();
                for (String attr : parts[1].split(";")) {
                    String[] kv = attr.split("=");
                    hostData.put(kv[0].trim(), kv[1].trim());
                }
                hosts.put(hostName, hostData);
            }
        }
    }

    private void parseRamos() {
        String ramosStr = properties.getProperty("snmp.ramos");
        if (ramosStr != null) {
            for (String ramosEntry : ramosStr.split(",")) {
                String[] parts = ramosEntry.split(":");
                String ip = parts[0].trim();
                Map<String, String> ramosData = new HashMap<>();
                for (String attr : parts[1].split(";")) {
                    String[] kv = attr.split("=");
                    ramosData.put(kv[0].trim(), kv[1].trim());
                }
                ramos.put(ip, ramosData);
            }
        }
    }

    public boolean isDebug() {
        return debugEnabled;
    }

    public boolean isIncidentsEnabled() {
        return incidentsEnabled;
    }

    public boolean isTemperatureEnabled() {
        return temperatureEnabled;
    }

    public boolean isRamosEnabled() {
        return ramosEnabled;
    }

    public String getDictionaryPdPath() {
        return dictionaryPdPath;
    }

    public String getDictionarySdhPath() {
        return dictionarySdhPath;
    }

    public String getMailHostname() {
        return properties.getProperty("mail.hostname");
    }

    public String getMailUsername() {
        return properties.getProperty("mail.username");
    }

    public String getMailPassword() {
        return properties.getProperty("mail.password");
    }

    public boolean isMailSsl() {
        return Boolean.parseBoolean(properties.getProperty("mail.ssl", "false"));
    }

    public String getZabbixFolder() {
        return properties.getProperty("mail.zabbixFolder");
    }

    public String getJnxOperatingDescr() {
        return properties.getProperty("snmp.jnxOperatingDescr");
    }

    public String getJnxOperatingTemp() {
        return properties.getProperty("snmp.jnxOperatingTemp");
    }

    public String getSnmpCommunity() {
        return properties.getProperty("snmp.community", "public");
    }

    public String getSnmpCommunityCelsius() {
        return properties.getProperty("snmp.community.celsius", getSnmpCommunity());
    }

    public String getSnmpCommunityRamos() {
        return properties.getProperty("snmp.community.ramos", getSnmpCommunity());
    }

    public String getSnmpHostsSuffix() {
        return properties.getProperty("snmp.hosts.suffix", "");
    }

    public Map<String, Map<String, String>> getHosts() {
        return hosts;
    }

    public Map<String, Map<String, String>> getRamos() {
        return ramos;
    }

    public String getEmailFrom() {
        return properties.getProperty("email.from");
    }

    public String getEmailReplyTo() {
        return properties.getProperty("email.replyTo");
    }

    public List<String> getEmailTo() {
        List<String> toList = new ArrayList<>();
        String toStr = properties.getProperty("email.to");
        if (toStr != null) {
            for (String email : toStr.split(",")) {
                toList.add(email.trim());
            }
        }
        return toList;
    }

    public String getEmailToDebug() {
        return properties.getProperty("email.toDebug");
    }

    public boolean isValid() {
        boolean isEmailValid = getEmailFrom() != null && getEmailReplyTo() != null && !getEmailTo().isEmpty();
        boolean isSnmpValid = true;
        if (isTemperatureEnabled() || isRamosEnabled()) {
            isSnmpValid = getSnmpCommunity() != null || getSnmpCommunityCelsius() != null || getSnmpCommunityRamos() != null;
        }
        
        return isEmailValid && isSnmpValid;
    }
}
