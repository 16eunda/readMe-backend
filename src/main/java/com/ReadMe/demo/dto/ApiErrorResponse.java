package com.ReadMe.demo.dto;

import lombok.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
public class ApiErrorResponse {

    private String code;

    private String message;

    private Object data;

    public static ApiErrorResponse of(String code, String message, Object data) {
        return new ApiErrorResponse(code, message, data);
    }
}