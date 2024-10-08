package Project.common;

import java.io.Serializable;

public class Payload implements Serializable {   //jah89 07-07-2024
    private PayloadType payloadType;
    private long clientId;
    private String message;

    public PayloadType getPayloadType() {
        return payloadType;
    }
    public static final int MUTE = 6; 
    public static final int UNMUTE = 7;

    public void setPayloadType(PayloadType payloadType) {
        this.payloadType = payloadType;
    }

    public long getClientId() {
        return clientId;
    }

    public void setClientId(long clientId) {
        this.clientId = clientId;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    @Override
    public String toString(){
        return String.format("Payload[%s] Client Id [%s] Message: [%s]", getPayloadType(), getClientId(), getMessage());
    }
}