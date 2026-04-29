package io.g3tech.axetrader.brokers.capital;

public enum Constants {
    CAP_APP_KEY("X-CAP-API-KEY"),
    CLIENT_SSO_TOKEN_NAME("CST"),
    ACCOUNT_SSO_TOKEN_NAME("X-SECURITY-TOKEN"),

    API("/api"),
    V_1("/v1"),
    API_V1(API.value.concat(V_1.value)),

    SESSION("/session"),
    ACCOUNTS("/accounts"),
    MARKET_NAVIGATION("/marketnavigation"),
    MARKETS("/markets"),
    POSITIONS("/positions"),
    ORDERS("/workingorders"),
    CONNECT("/connect"),

    API_V1_SESSION(API_V1.value.concat(SESSION.value)),
    API_V1_ACCOUNTS(API_V1.value.concat(ACCOUNTS.value)),
    API_V1_MARKET_NAVIGATION(API_V1.value.concat(MARKET_NAVIGATION.value)),
    API_V1_MARKETS(API_V1.value.concat(MARKETS.value)),
    API_V1_POSITIONS(API_V1.value.concat(POSITIONS.value)),
    API_V1_ORDERS(API_V1.value.concat(ORDERS.value)),
    API_V1_CONFIRMS(API_V1.value.concat("/confirms")),
    API_V1_TIME(API_V1.value.concat("/time")),
    API_V1_CLIENTSENTIMENT(API_V1.value.concat("/clientsentiment")),
    API_V1_PRICES(API_V1.value.concat("/prices")),
    API_V1_WATCHLISTS(API_V1.value.concat("/watchlists")),
    API_V1_HISTORY(API_V1.value.concat("/history")),

    API_V1_SESSION_ENCRYPTION_KEY(API_V1_SESSION.value.concat("/encryptionKey")),
    API_V1_ACCOUNTS_PREFERENCES(API_V1_ACCOUNTS.value.concat("/preferences")),
    API_V1_HISTORY_TRANSACTIONS(API_V1_HISTORY.value.concat("/transactions"));

    private final String value;

    Constants(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
