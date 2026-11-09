package com.diagramador.notifications;

import com.diagramador.api.ApiException;
import com.diagramador.identity.CurrentUserContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PushDeviceController {
    private final CurrentUserContext currentUser;
    private final JdbcTemplate jdbc;

    public PushDeviceController(CurrentUserContext currentUser, JdbcTemplate jdbc) {
        this.currentUser = currentUser;
        this.jdbc = jdbc;
    }

    public record Registration(@NotBlank @Size(max = 4096) String token) {}

    @PostMapping("/push/devices")
    public ResponseEntity<Void> register(@Valid @RequestBody Registration request) {
        var user = currentUser.require();
        int changed = jdbc.update("""
                INSERT INTO diagrammer.push_device (user_id, token, token_hash) VALUES (?, ?, ?)
                ON CONFLICT (token_hash) DO UPDATE SET registered_at = now()
                WHERE diagrammer.push_device.user_id = EXCLUDED.user_id
                  AND diagrammer.push_device.token = EXCLUDED.token
                """, user.userId(), request.token(), tokenHash(request.token()));
        if (changed == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "DEVICE_ALREADY_REGISTERED",
                    "The device is already registered.", List.of(), null);
        }
        return ResponseEntity.noContent().build();
    }

    private static String tokenHash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
