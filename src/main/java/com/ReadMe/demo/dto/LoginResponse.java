package com.ReadMe.demo.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginResponse {
    private String userId;
    private String username;
    private String accessToken;
    private String refreshToken;
}
