package io.nexstudios.economy.economy;

import java.math.BigDecimal;

public record NexEcoResponse(BigDecimal amount, BigDecimal balance, ResponseType responseType, String errorMessage) {


    public enum ResponseType {
        SUCCESS(1),
        FAILURE(2),
        MAX_BALANCE(3),
        NOT_NEGATIVE(4),
        NOT_ENOUGH(5),
        NOT_IMPLEMENTED(6),
        UNKNOWN(7);

        private final int id;
        ResponseType(int id) {
            this.id = id;
        }

        int getId() { return id; }
    }

    public NexEcoResponse { }

    public boolean isSuccess() { return responseType == ResponseType.SUCCESS; }
    public boolean isFailure() { return responseType == ResponseType.FAILURE; }
    public boolean isMaxBalance() { return responseType == ResponseType.MAX_BALANCE; }
    public boolean isNotNegative() { return responseType == ResponseType.NOT_NEGATIVE; }
    public boolean isNotEnough() { return responseType == ResponseType.NOT_ENOUGH; }
    public boolean isNotImplemented() { return responseType == ResponseType.NOT_IMPLEMENTED; }
    public boolean isUnknown() { return responseType == ResponseType.UNKNOWN; }

}
