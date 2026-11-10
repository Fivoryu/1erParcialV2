package com.diagramador.notifications;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class PushConfiguration {
    /**
     * Without Firebase configuration every send asks for a retry, so events stay pending in the outbox and no
     * credential is invented. FCM needs diagrammer.push.fcm.project-id plus either
     * diagrammer.push.fcm.service-account-file (a path outside the repository; tokens are renewed automatically),
     * diagrammer.push.fcm.service-account-json (the key content, for secret stores), or a static diagrammer.push.fcm.access-token for quick tests.
     */
    @Bean
    PushSender pushSender(@Value("${diagrammer.push.fcm.project-id:}") String projectId,
            @Value("${diagrammer.push.fcm.service-account-file:}") String serviceAccountFile,
            @Value("${diagrammer.push.fcm.service-account-json:}") String serviceAccountJson,
            @Value("${diagrammer.push.fcm.access-token:}") String accessToken,
            @Value("${diagrammer.push.fcm.base-url:https://fcm.googleapis.com}") String baseUrl) {
        if (projectId.isBlank()) return unavailable();
        if (!serviceAccountJson.isBlank()) {
            return new FcmPushSender(baseUrl, projectId, new ServiceAccountTokenProvider(() -> serviceAccountJson, null));
        }
        if (!serviceAccountFile.isBlank()) {
            return new FcmPushSender(baseUrl, projectId, new ServiceAccountTokenProvider(Path.of(serviceAccountFile), null));
        }
        if (!accessToken.isBlank()) return new FcmPushSender(baseUrl, projectId, () -> accessToken);
        return unavailable();
    }

    private static PushSender unavailable() {
        return (token, title, body, data) -> PushSender.Result.RETRY;
    }
}
