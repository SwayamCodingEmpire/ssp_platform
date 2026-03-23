package com.creatorai.authservice.domain.exception;

public class EmailNotVerifiedException extends RuntimeException {
    public EmailNotVerifiedException(String email) {
        super("Email not verified for account: " + email);
    }
}
