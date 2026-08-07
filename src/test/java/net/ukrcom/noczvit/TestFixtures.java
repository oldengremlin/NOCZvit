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

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared test-only helpers for building {@link Config} and {@link Dictionary} without touching
 * real, gitignored deployment files (a developer's actual {@code noczvit.properties} or
 * {@code dictionary_pd.txt}/{@code dictionary_sdh.txt} may exist untracked on disk — tests must
 * never depend on their content).
 */
public final class TestFixtures {

    private TestFixtures() {
    }

    /**
     * Builds a {@link Config} from the synthetic {@code test-noczvit.properties} resource,
     * always referenced via {@code --config=<absolute path>} rather than the classpath-resource
     * fallback — this sidesteps any ambiguity about test-classes vs. classes ordering on the
     * classpath and guarantees the same file is read regardless of what else is present.
     *
     * @param extraArgs additional CLI-style arguments (e.g. {@code "--zabbix"},
     *                  {@code "--dictionarypd=" + path}) applied on top of the base test config
     */
    public static Config config(String... extraArgs) throws IOException {
        String configPath = resourcePath("test-noczvit.properties");
        List<String> args = new ArrayList<>();
        args.add("--config=" + configPath);
        args.addAll(List.of(extraArgs));
        return new Config(args.toArray(new String[0]));
    }

    /**
     * Builds a {@link Dictionary} from ad-hoc PD/SDH/device-word entries written to
     * {@code tempDir}, so each test declares only the entries it actually needs instead of
     * sharing one growing fixture file. Pass an empty map for dictionaries the test doesn't care
     * about — an empty dictionary file is valid and matches nothing.
     *
     * @param tempDir    a directory to write the three generated dictionary files into (typically
     *                   a JUnit {@code @TempDir})
     * @param pd         entries for {@code dictionary_pd.txt} ({@code regex -> value})
     * @param sdh        entries for {@code dictionary_sdh.txt}
     * @param deviceWord entries for {@code dictionary_device_word.txt}
     */
    public static Dictionary dictionary(Path tempDir, Map<String, String> pd, Map<String, String> sdh,
            Map<String, String> deviceWord) throws IOException {
        Path pdFile = writeDictionaryFile(tempDir.resolve("pd.txt"), pd);
        Path sdhFile = writeDictionaryFile(tempDir.resolve("sdh.txt"), sdh);
        Path deviceWordFile = writeDictionaryFile(tempDir.resolve("device-word.txt"), deviceWord);
        Config config = config(
                "--dictionarypd=" + pdFile,
                "--dictionarysdh=" + sdhFile,
                "--dictionarydeviceword=" + deviceWordFile);
        return new Dictionary(config);
    }

    /** {@link #dictionary} with only PD entries — the common case for the IMAP parser tests. */
    public static Dictionary dictionaryPd(Path tempDir, Map<String, String> pd) throws IOException {
        return dictionary(tempDir, pd, Map.of(), Map.of());
    }

    private static Path writeDictionaryFile(Path file, Map<String, String> entries) throws IOException {
        StringBuilder sb = new StringBuilder();
        // LinkedHashMap preserves caller-declared order; loadDictionary() re-sorts by regex
        // length anyway, so insertion order here is only for readability if the file is inspected.
        for (Map.Entry<String, String> e : new LinkedHashMap<>(entries).entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        return file;
    }

    private static String resourcePath(String resourceName) throws IOException {
        URL url = TestFixtures.class.getClassLoader().getResource(resourceName);
        if (url == null) {
            throw new IOException("Test resource not found on classpath: " + resourceName);
        }
        try {
            return Path.of(url.toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IOException("Malformed test resource URL: " + url, e);
        }
    }
}
