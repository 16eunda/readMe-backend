package com.ReadMe.demo.dto;

import lombok.Getter;

@Getter
public class GooglePubSubMessage {

    private Message message;

    @Getter
    public static class Message {
        private String data;
        private String messageId;
        private String publishTime;
    }

}
