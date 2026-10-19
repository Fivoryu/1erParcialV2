package com.diagramador.mobile.chat;
import java.io.*;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import org.springframework.dao.DataIntegrityViolationException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@ControllerAdvice(assignableTypes=MobileAgentChatController.class)
public class MobileAgentChatBodyAdvice extends RequestBodyAdviceAdapter {
    @Override public boolean supports(MethodParameter parameter, Type type, Class<? extends HttpMessageConverter<?>> converter) { return true; }
    @Override public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter, Type type,
            Class<? extends HttpMessageConverter<?>> converter) throws IOException {
        if (input.getHeaders().getContentLength() > 65536) throw new TooLarge();
        byte[] bytes = input.getBody().readNBytes(65537);
        if (bytes.length > 65536) throw new TooLarge();
        return new HttpInputMessage() {
            public HttpHeaders getHeaders() { return input.getHeaders(); }
            public InputStream getBody() { return new ByteArrayInputStream(bytes); }
        };
    }
    @ExceptionHandler(TooLarge.class)
    ResponseEntity<java.util.Map<String,String>> oversized() {
        return ResponseEntity.status(413).body(java.util.Map.of("code", "CHAT_BODY_TOO_LARGE"));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<java.util.Map<String,String>> conflictingIdentifier() {
        return ResponseEntity.status(409).body(java.util.Map.of("code", "CHAT_ID_CONFLICT"));
    }
    static class TooLarge extends RuntimeException {}
}
