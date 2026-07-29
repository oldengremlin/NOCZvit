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
package net.ukrcom.noczvit.smtp;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.noczvit.Config;

/**
 * Sends the HTML report email by piping the serialised MIME message to the system
 * {@code sendmail} binary (or SMTP in debug mode).
 *
 * <p>Writing the message and reading from the pipe run in separate virtual threads to avoid
 * the deadlock that would occur if the pipe buffer filled before the process started reading.
 */
@Slf4j
public class EmailSender {

    private final Config config;
    private final String version;

    /**
     * Creates the sender and reads the project version from the bundled
     * {@code version.properties} resource (injected by Maven resource filtering).
     *
     * @throws IOException if {@code version.properties} is missing from the classpath
     */
    public EmailSender(Config config) throws IOException {
        this.config = config;
        Properties versionProps = new Properties();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("version.properties")) {
            if (input == null) {
                throw new IOException("version.properties not found in resources");
            }
            versionProps.load(input);
        }
        this.version = versionProps.getProperty("project.version", "unknown");
    }

    /**
     * Assembles a MIME email and delivers it via sendmail.
     *
     * <p>In debug mode the message goes to {@code emailToDebug} only. The sendmail process is
     * given 30 seconds to complete; it is killed and an exception is thrown on timeout.
     *
     * @param subject     email subject line
     * @param messageHtml complete HTML body
     * @throws MessagingException if the MIME message cannot be built or serialised
     * @throws IOException        if sendmail cannot be started, times out, or is interrupted
     */
    public void sendReport(String subject, String messageHtml) throws MessagingException, IOException {
        Properties props = new Properties();
        props.put("mail.smtp.host", config.getMailHostname());
        props.put("mail.smtp.port", "25");
        props.put("mail.smtp.auth", "true");
        props.put("mail.mime.charset", "UTF-8");

        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(config.getMailUsername(), config.getMailPassword());
            }
        });

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(config.getEmailFrom()));
        message.setReplyTo(new InternetAddress[]{new InternetAddress(config.getEmailReplyTo())});

        if (config.isDebug()) {
            message.addRecipient(Message.RecipientType.TO, new InternetAddress(config.getEmailToDebug()));
        } else {
            for (String to : config.getEmailTo()) {
                message.addRecipient(Message.RecipientType.TO, new InternetAddress(to));
            }
        }

        message.setSubject(subject, "UTF-8");

        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(messageHtml, "text/html; charset=UTF-8");
        htmlPart.setHeader("Content-Transfer-Encoding", "base64");

        Multipart multipart = new MimeMultipart();
        multipart.addBodyPart(htmlPart);

        message.setContent(multipart);
        message.setHeader("X-PoweredBy", "NOCZvit v" + version);

        log.debug("Subject: {}", subject);
        log.debug("Message size: {} bytes", messageHtml.length());

        try (var executor = Executors.newVirtualThreadPerTaskExecutor(); PipedInputStream in = new PipedInputStream(); PipedOutputStream out = new PipedOutputStream(in)) {

            Future<?> writerFuture = executor.submit(() -> {
                message.writeTo(out);
                out.close();
                return null;
            });

            ProcessBuilder pb = new ProcessBuilder(config.getSendmailPath(), "-t");
            Process process = pb.start();
            try (OutputStream processOut = process.getOutputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    processOut.write(buffer, 0, bytesRead);
                }
            }

            try {
                writerFuture.get();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof MessagingException me) {
                    throw me;
                }
                throw new IOException("Message serialization failed: " + cause.getMessage(), cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for message writer", e);
            }

            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("sendmail timed out after 30 seconds");
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.warn("sendmail exited with code {}", exitCode);
            } else {
                log.info("Report sent via {}", config.getSendmailPath());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while sending email", e);
        }
    }
}
