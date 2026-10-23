package com.aimastery.gateway.eval;

/** Un import n'ecrase jamais un dataset : des rapports y font peut-etre reference (409). */
public class DatasetAlreadyExistsException extends RuntimeException {

    public DatasetAlreadyExistsException(String id) {
        super("Le dataset '" + id + "' existe deja : choisis un autre nom.");
    }
}
