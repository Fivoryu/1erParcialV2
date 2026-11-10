package com.diagramador.notifications;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class PushConfiguration {
    /**
     * Without Firebase configuration every send asks for a retry, so events stay pending in the outbox and no
     * credential is invented. FCM needs diagrammer.push.fcm.project-id and diagrammer.push.fcm.access-token.
     */
    @Bean
    PushSender pushSender(@Value("${diagrammer.push.fcm.project-id:}") String projectId,
            @Value("${diagrammer.push.fcm.access-token:}") String accessToken,
            @Value("${diagrammer.push.fcm.base-url:https://fcm.googleapis.com}") String baseUrl) {
        if (projectId.isBlank() || accessToken.isBlank()) {
            return (token, title, body, data) -> PushSender.Result.RETRY;
        }
        return new FcmPushSender(baseUrl, projectId, () -> accessToken);
    }
}
