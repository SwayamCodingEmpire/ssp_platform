package com.creatorai.authservice.domain.exception;

public class TokenNotFoundException extends RuntimeException {
    public TokenNotFoundException(String tokenType) {
        super(tokenType + " token not found or already used");
    }
}
