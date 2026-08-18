package com.trophy.promostandards.db;

import java.util.List;

/**
 * A server-side catalog query and its result.
 *
 * <p>Only meaningful against the mirror: without a database the catalog list is served whole and
 * filtered in the browser, which is what the console does today and what it falls back to.
 */
public final class CatalogQuery {

	private CatalogQuery() {
	}

	/** What to sort by. Sorting in SQL keeps paging stable — sorting a page is not sorting a catalog. */
	public enum Sort {
		PRODUCT_ID, TITLE, VENDOR
	}

	/** Which products to include. */
	public enum Status {
		ALL, CLOSE_OUT, NO_PRODUCT_DATA, DISCONTINUED
	}

	/**
	 * @param search   free text matched against product id, title and vendor; null or blank = all
	 * @param status   status filter; null = {@link Status#ALL}
	 * @param sort     sort key; null = {@link Sort#PRODUCT_ID}
	 * @param ascending sort direction
	 * @param page     zero-based page index
	 * @param size     page size, clamped by the store to a sane maximum
	 */
	public record Request(String search, Status status, Sort sort, boolean ascending, int page, int size) {

		public Request {
			status = status == null ? Status.ALL : status;
			sort = sort == null ? Sort.PRODUCT_ID : sort;
			page = Math.max(0, page);
			size = size <= 0 ? 50 : Math.min(size, 500);
		}
	}

	/**
	 * @param rows       the requested page
	 * @param total      total matching rows, so the console can render a pager
	 * @param page       zero-based index of this page
	 * @param size       page size used
	 */
	public record Page(List<CatalogRow> rows, long total, int page, int size) {

		public int totalPages() {
			return size <= 0 ? 1 : (int) Math.max(1, (total + size - 1) / size);
		}
	}
}
