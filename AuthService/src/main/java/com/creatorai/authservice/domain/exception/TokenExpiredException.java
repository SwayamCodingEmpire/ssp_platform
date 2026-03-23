package com.creatorai.authservice.domain.exception;

public class TokenExpiredException extends RuntimeException {
    public TokenExpiredException(String tokenType) {
        super(tokenType + " token has expired");
    }
}
