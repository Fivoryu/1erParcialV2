package com.diagramador.notifications;

import java.util.Map;

/** Delivers one notification to one device token. Implementations must never log the token. */
public interface PushSender {
    enum Result { SENT, INVALID_TOKEN, RETRY }

    Result send(String token, String title, String body, Map<String, String> data);
}
