package com.trophy.promostandards.sync;

import com.trophy.promostandards.db.CatalogQuery;
import com.trophy.promostandards.db.CatalogRow;
import com.trophy.promostandards.db.CatalogStore;
import com.trophy.promostandards.sync.model.ProductGroup;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Test doubles for the persistence port. The real store needs Postgres, which this machine has no
 * way to run (no Docker, so no Testcontainers), so the logic that decides <em>whether</em> to use the
 * mirror — the part that can actually break the console — is exercised against an in-memory fake.
 * The SQL itself is covered separately by {@code JdbcCatalogStoreIT}, which only runs where a real
 * database is configured.
 */
final class CatalogTestSupport {

    private CatalogTestSupport() {
    }

    /** An {@link ObjectProvider} over a fixed value; {@code null} models "no such bean". */
    static <T> ObjectProvider<T> providerOf(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                if (value == null) {
                    throw new IllegalStateException("no bean available");
                }
                return value;
            }

            @Override
            public T getObject(Object... args) {
                return getObject();
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }
        };
    }

    /** In-memory {@link CatalogStore}; {@code failing} makes every read throw, modelling an outage. */
    static final class FakeCatalogStore implements CatalogStore {

        private final Map<String, CatalogRow> rows = new LinkedHashMap<>();
        private List<ProductGroup> groups = List.of();
        private Instant lastScanAt;
        boolean failing;

        @Override
        public int saveCatalog(List<CatalogRow> incoming) {
            if (incoming.isEmpty()) {
                return 0;
            }
            for (CatalogRow row : incoming) {
                rows.put(row.productId().toUpperCase(Locale.ROOT), row);
            }
            lastScanAt = Instant.now();
            return incoming.size();
        }

        @Override
        public List<CatalogRow> findAll() {
            failIfDown();
            return new ArrayList<>(rows.values());
        }

        @Override
        public CatalogQuery.Page search(CatalogQuery.Request request) {
            failIfDown();
            List<CatalogRow> matching = rows.values().stream()
                    .filter(row -> request.search() == null || request.search().isBlank()
                            || (row.productId() + " " + row.title()).toLowerCase(Locale.ROOT)
                                    .contains(request.search().toLowerCase(Locale.ROOT)))
                    .toList();
            int from = Math.min(request.page() * request.size(), matching.size());
            int to = Math.min(from + request.size(), matching.size());
            return new CatalogQuery.Page(matching.subList(from, to), matching.size(),
                    request.page(), request.size());
        }

        @Override
        public Optional<Instant> lastScanAt() {
            failIfDown();
            return Optional.ofNullable(lastScanAt);
        }

        @Override
        public void saveGroups(List<ProductGroup> incoming) {
            this.groups = List.copyOf(incoming);
        }

        @Override
        public List<ProductGroup> findGroups() {
            failIfDown();
            return groups;
        }

        List<CatalogRow> saved() {
            return new ArrayList<>(rows.values());
        }

        List<ProductGroup> savedGroups() {
            return groups;
        }

        private void failIfDown() {
            if (failing) {
                throw new IllegalStateException("connection refused");
            }
        }
    }
}
