package com.trophy.promostandards.pricing.service;

import com.trophy.promostandards.config.PromoStandardsProperties;
import com.trophy.promostandards.pricing.client.PricingClient;
import com.trophy.promostandards.pricing.model.Charge;
import com.trophy.promostandards.pricing.model.Configuration;
import com.trophy.promostandards.pricing.model.FobPoint;
import com.trophy.promostandards.pricing.model.PricingRequests.GetAvailableChargesRequest;
import com.trophy.promostandards.pricing.model.PricingRequests.GetConfigurationAndPricingRequest;
import com.trophy.promostandards.pricing.model.PricingRequests.GetFobPointsRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Application service over the {@link PricingClient}. Applies configured credentials,
 * {@code wsVersion}, and localization defaults before delegating to the client.
 */
@Service
public class PricingService {

	private static final Logger log = LoggerFactory.getLogger(PricingService.class);

	/** What the distributor pays. */
	public static final String PRICE_TYPE_NET = "Net";
	/** The supplier's suggested retail — what its public product page shows. */
	public static final String PRICE_TYPE_LIST = "List";

	private static final String DEFAULT_COUNTRY = "US";
	private static final String DEFAULT_LANGUAGE = "en";

	private final PricingClient client;
	private final PromoStandardsProperties properties;

	public PricingService(PricingClient client, PromoStandardsProperties properties) {
		this.client = client;
		this.properties = properties;
	}

	public Configuration getConfigurationAndPricing(String productId, String currency, String fobId, String priceType,
			String configurationType, String country, String language) {
		PromoStandardsProperties.Credentials creds = properties.getCredentials();
		return client.getConfigurationAndPricing(new GetConfigurationAndPricingRequest(wsVersion(), creds.getId(),
				creds.getPassword(), productId, currency, fobId, priceType, country(country), language(language),
				configurationType));
	}

	/**
	 * Net pricing with the supplier's published list price merged into each break.
	 *
	 * <p>PromoStandards returns one price per call: {@code Net} is what the distributor pays,
	 * {@code List} the supplier's suggested retail. They are the same breaks priced differently —
	 * for PaceSetter's GM668A, {@code Net} is 88.20/82.80/73.80 and {@code List} 147.00/138.00/123.00,
	 * exactly the figures on their public product page. Asking only for {@code Net} (which is what
	 * this app did) throws away the supplier's own retail price and leaves the MAP floor inoperable,
	 * forcing an invented markup in its place.
	 *
	 * <p>The list call is best-effort: a supplier that does not publish list pricing simply leaves
	 * {@code listPrice} null, and callers fall back to the markup.
	 *
	 * @return the {@code Net} configuration, with {@code listPrice} filled in per break where the
	 * supplier publishes one
	 */
	public Configuration getConfigurationAndPricingWithList(String productId, String currency, String fobId,
			String configurationType, String country, String language) {
		Configuration net = getConfigurationAndPricing(productId, currency, fobId, PRICE_TYPE_NET,
				configurationType, country, language);
		Map<String, BigDecimal> netByPartAndQuantity = new HashMap<>();
		for (Configuration.PartPrice part : net.partPrices()) {
			for (Configuration.PriceBreak priceBreak : part.priceBreaks()) {
				netByPartAndQuantity.put(key(part.partId(), priceBreak.minQuantity()), priceBreak.price());
			}
		}
		Map<String, BigDecimal> listByPartAndQuantity = new HashMap<>();
		try {
			Configuration list = getConfigurationAndPricing(productId, currency, fobId, PRICE_TYPE_LIST,
					configurationType, country, language);
			for (Configuration.PartPrice part : list.partPrices()) {
				for (Configuration.PriceBreak priceBreak : part.priceBreaks()) {
					listByPartAndQuantity.put(key(part.partId(), priceBreak.minQuantity()), priceBreak.price());
				}
			}
			// Not every service honours priceType — some answer a List request with the net prices.
			// Publishing those as retail would list the whole catalog at cost, so a "retail" price
			// that is not above the net price is treated as no retail price at all.
			listByPartAndQuantity.entrySet().removeIf(entry -> {
				BigDecimal netPrice = netByPartAndQuantity.get(entry.getKey());
				return entry.getValue() == null
						|| (netPrice != null && entry.getValue().compareTo(netPrice) <= 0);
			});
		}
		catch (RuntimeException ex) {
			// Not every supplier answers a List request. Net pricing is still perfectly usable.
			log.debug("No list pricing for {}: {}", productId, ex.getMessage());
			return net;
		}
		if (listByPartAndQuantity.isEmpty()) {
			return net;
		}

		List<Configuration.PartPrice> merged = new ArrayList<>();
		for (Configuration.PartPrice part : net.partPrices()) {
			List<Configuration.PriceBreak> breaks = new ArrayList<>();
			for (Configuration.PriceBreak priceBreak : part.priceBreaks()) {
				breaks.add(new Configuration.PriceBreak(priceBreak.minQuantity(), priceBreak.price(),
						listByPartAndQuantity.get(key(part.partId(), priceBreak.minQuantity())),
						priceBreak.priceUom()));
			}
			merged.add(new Configuration.PartPrice(part.partId(), part.description(), breaks));
		}
		return new Configuration(net.productId(), net.currency(), net.priceType(), merged, net.locations());
	}

	/** Breaks line up by part and quantity; that pair is what identifies a price point. */
	private static String key(String partId, int minQuantity) {
		return partId + "|" + minQuantity;
	}

	public List<Charge> getAvailableCharges(String productId, String country, String language) {
		PromoStandardsProperties.Credentials creds = properties.getCredentials();
		return client.getAvailableCharges(new GetAvailableChargesRequest(wsVersion(), creds.getId(),
				creds.getPassword(), productId, country(country), language(language)));
	}

	public List<FobPoint> getFobPoints(String productId, String country, String language) {
		PromoStandardsProperties.Credentials creds = properties.getCredentials();
		return client.getFobPoints(new GetFobPointsRequest(wsVersion(), creds.getId(), creds.getPassword(),
				productId, country(country), language(language)));
	}

	private String wsVersion() {
		return properties.getPricing().getWsVersion();
	}

	private static String country(String country) {
		return country == null || country.isBlank() ? DEFAULT_COUNTRY : country;
	}

	private static String language(String language) {
		return language == null || language.isBlank() ? DEFAULT_LANGUAGE : language;
	}
}
