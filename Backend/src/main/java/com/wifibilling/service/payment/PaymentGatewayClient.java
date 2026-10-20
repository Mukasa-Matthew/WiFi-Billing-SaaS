package com.wifibilling.service.payment;

import com.wifibilling.model.Transaction;

public interface PaymentGatewayClient {

    /**
     * Dispatches a collection prompt (STK / USSD push) to the user's mobile phone.
     *
     * @param transaction the pending transaction
     * @return provider reference or tracking ID
     */
    String requestPayment(Transaction transaction);

    /**
     * Provider identification (e.g., MOCK, MTN_MOMO, AIRTEL_MONEY).
     */
    String getProviderName();
}
