package com.trophy.promostandards.pricing.model;

/**
 * Mirrors {@code FobPoint} from {@code getFobPoints}: a freight-on-board shipping origin.
 *
 * @param fobId      supplier FOB point id
 * @param fobName    FOB point name
 * @param city       city
 * @param state      state/province code
 * @param country    country code
 * @param postalCode postal code
 */
public record FobPoint(String fobId, String fobName, String city, String state, String country, String postalCode) {
}
