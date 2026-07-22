package com.trophy.promostandards.orderstatus.model;

/**
 * Mirrors {@code Status} from {@code getOrderStatusTypes}: a supported order-status code and name
 * (e.g. 60 = In Production, 80 = Complete).
 *
 * @param id   numeric status code
 * @param name status name
 */
public record OrderStatusType(int id, String name) {
}
