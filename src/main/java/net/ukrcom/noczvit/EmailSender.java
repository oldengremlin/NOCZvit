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

import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.io.*;
import java.util.Properties;

public class EmailSender {

    private final Config config;

    public EmailSender(Config config) {
        this.config = config;
    }

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
        message.setHeader("X-PoweredBy", "NOCZvit v1.0.2");

        if (config.isDebug()) {
            System.err.println("Subject: " + subject);
            System.err.println("Message: " + messageHtml);
        }

        try (PipedInputStream in = new PipedInputStream(); PipedOutputStream out = new PipedOutputStream(in)) {
            new Thread(() -> {
                try {
                    message.writeTo(out);
                    out.close();
                } catch (MessagingException | IOException e) {
                    throw new RuntimeException(e);
                }
            }).start();

            ProcessBuilder pb = new ProcessBuilder("/usr/sbin/sendmail", "-t");
            Process process = pb.start();
            try (OutputStream processOut = process.getOutputStream()) {
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    processOut.write(buffer, 0, bytesRead);
                }
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                System.err.println("sendmail failed with exit code: " + exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while sending email", e);
        }
    }
}
