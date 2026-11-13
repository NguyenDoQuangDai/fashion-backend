package com.example.fashion_backend.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import java.util.Collections;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class GoogleIdTokenService {
    private final GoogleIdTokenVerifier verifier;

    public GoogleIdTokenService(@Value("${app.google.client-id:}") String clientId) {
        this.verifier = clientId == null || clientId.isBlank() ? null : new GoogleIdTokenVerifier.Builder(
                new NetHttpTransport(), GsonFactory.getDefaultInstance())
                .setAudience(Collections.singletonList(clientId))
                .build();
    }

    public GoogleIdToken.Payload verify(String credential) {
        try {
            if (verifier == null) {
                throw new IllegalStateException("GOOGLE_CLIENT_ID must be configured");
            }
            GoogleIdToken token = verifier.verify(credential);
            if (token == null) {
                throw new IllegalArgumentException("Invalid Google credential");
            }
            GoogleIdToken.Payload payload = token.getPayload();
            if (!"accounts.google.com".equals(payload.getIssuer())
                    && !"https://accounts.google.com".equals(payload.getIssuer())) {
                throw new IllegalArgumentException("Invalid Google issuer");
            }
            if (payload.getSubject() == null || payload.getEmail() == null
                    || !Boolean.TRUE.equals(payload.getEmailVerified())) {
                throw new IllegalArgumentException("Google email is not verified");
            }
            return payload;
        } catch (Exception exception) {
            if (exception instanceof IllegalArgumentException) {
                throw (IllegalArgumentException) exception;
            }
            throw new IllegalArgumentException("Invalid Google credential", exception);
        }
    }
}