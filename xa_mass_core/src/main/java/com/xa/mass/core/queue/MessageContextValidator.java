package com.xa.mass.core.queue;


import com.xa.mass.core.model.message.MassMessage;
import com.xa.mass.core.model.message.MessageContext;
import org.springframework.stereotype.Component;

@Component
public class MessageContextValidator {

    public boolean isValid(MassMessage<?> msg) {
        if (msg == null) return false;
        MessageContext ctx = msg.getContext();
        return ctx != null &&
                ctx.getDeviceId() != null &&
                ctx.getConnRole() != null;
    }
}
