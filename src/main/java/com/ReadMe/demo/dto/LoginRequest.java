package com.ReadMe.demo.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginRequest {
    private String username;
    private String password;
    private String deviceId;
    private String accessToken;
    private String refreshToken;
}
