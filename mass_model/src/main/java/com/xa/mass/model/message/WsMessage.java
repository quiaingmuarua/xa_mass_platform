package com.xa.mass.model.message;

import com.google.gson.JsonObject;
import lombok.Data;


@Data
public class WsMessage {
    private String type;
    private JsonObject data;

    // Getter / Setter


}