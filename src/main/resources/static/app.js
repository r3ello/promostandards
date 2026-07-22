'use strict';

/* ============================================================
   TrophyPartner · Product Sync console — front-end logic

   Catalog listing is CHEAP: GET /api/catalog/products returns just
   { productId, imported } per product (one upstream call). Each row's
   inventory/price/title/thumbnail is fetched LAZILY for the visible page
   only, via GET /api/catalog/products/{id} (the expensive aggregation),
   with a small concurrency cap — so a large catalog never blocks on
   thousands of upstream requests. Rows are paginated client-side.

   Sync (write): POST /api/sync/products/{id}[/inventory|/pricing]
   ============================================================ */

const SUPPLIERS = [{ id: 'pacesetter', name: 'PaceSetter Awards' }];

let entries = [];               // [{ productId, imported, detail?, inflight?, error? }]
let loaded = false;
const expanded = new Set();
let statusFilter = 'all';
let page = 0;
let pageSize = 25;

// --- API ---------------------------------------------------------------
async function api(url, method = 'GET', payload) {
	const opts = { method, headers: { Accept: 'application/json' } };
	if (payload !== undefined && payload !== null) {
		opts.headers['Content-Type'] = 'application/json';
		opts.body = JSON.stringify(payload);
	}
	const res = await fetch(url, opts);
	if (res.status === 401) {
		// Session missing/expired — bounce back to the login screen instead of surfacing an error.
		showLogin();
		throw new Error('Your session has expired. Please sign in again.');
	}
	const body = await res.json().catch(() => null);
	if (!res.ok) {
		const base = (body && body.message) || `${res.status} ${res.statusText}`;
		const detail = body && Array.isArray(body.serviceMessages) && body.serviceMessages.length
			? body.serviceMessages.map((m) => `[${m.code}] ${m.description}`).join(' · ') : '';
		throw new Error(detail ? `${base} — ${detail}` : base);
	}
	return body;
}

// --- auth --------------------------------------------------------------
// When security.auth is enabled the app is gated behind a login screen: the console shell loads
// but every /api/** call needs a session cookie. `/api/auth/status` tells us whether to show the
// login screen or the console on load; a 404 means the auth feature is off (nothing to gate).
let authEnabled = false;

async function fetchAuthStatus() {
	try {
		const res = await fetch('/api/auth/status', { headers: { Accept: 'application/json' } });
		if (res.status === 404) return { enabled: false, authenticated: true };
		const b = await res.json().catch(() => null);
		return { enabled: true, authenticated: !!(b && b.authenticated) };
	} catch (e) {
		return { enabled: false, authenticated: true }; // can't reach status — don't block the UI
	}
}

function showLogin() {
	el('loginScreen').hidden = false;
	el('appMain').hidden = true;
	el('topbarRight').hidden = true;
	el('signOutBtn').hidden = true;
	const u = el('loginUser');
	if (u) setTimeout(() => u.focus(), 0);
}

function showApp() {
	el('loginScreen').hidden = true;
	el('appMain').hidden = false;
	el('topbarRight').hidden = false;
	el('signOutBtn').hidden = !authEnabled;
}

async function bootstrap() {
	const status = await fetchAuthStatus();
	authEnabled = status.enabled;
	if (status.enabled && !status.authenticated) {
		showLogin();
	} else {
		showApp();
		loadCatalog();
	}
}

async function doLogin(ev) {
	ev.preventDefault();
	const btn = el('loginBtn');
	const err = el('loginError');
	const username = el('loginUser').value.trim();
	const password = el('loginPass').value;
	err.hidden = true;
	const label = btn.textContent;
	btn.disabled = true; btn.innerHTML = `<span class="spinner"></span> Signing in…`;
	try {
		const res = await fetch('/api/auth/login', {
			method: 'POST',
			headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
			body: JSON.stringify({ username, password }),
		});
		if (!res.ok) {
			const b = await res.json().catch(() => null);
			err.textContent = (b && b.message) || 'Sign in failed. Please try again.';
			err.hidden = false;
			return;
		}
		authEnabled = true;
		el('loginPass').value = '';
		showApp();
		loadCatalog();
	} catch (e) {
		err.textContent = 'Could not reach the server. Please try again.';
		err.hidden = false;
	} finally {
		btn.disabled = false; btn.innerHTML = label;
	}
}

async function doLogout() {
	try { await fetch('/api/auth/logout', { method: 'POST', headers: { Accept: 'application/json' } }); }
	catch (e) { /* ignore — return to login regardless */ }
	entries = []; loaded = false;
	showLogin();
}

// concurrency-limited scheduler so lazy enrichment never floods the supplier
const MAX_CONCURRENT = 5;
let active = 0;
const waiting = [];
function schedule(task) {
	return new Promise((resolve, reject) => { waiting.push({ task, resolve, reject }); drain(); });
}
function drain() {
	while (active < MAX_CONCURRENT && waiting.length) {
		const { task, resolve, reject } = waiting.shift();
		active++;
		task().then(resolve, reject).finally(() => { active--; drain(); });
	}
}

// --- DOM ---------------------------------------------------------------
const el = (id) => document.getElementById(id);
const body = el('productsBody');
const meta = el('catalogMeta');
const searchInput = el('search');
const pager = el('pager');

// --- load --------------------------------------------------------------
async function loadCatalog() {
	loaded = false;
	expanded.clear();
	pager.hidden = true;
	body.innerHTML = `<tr class="row-state"><td colspan="9"><div class="state"><span class="spinner"></span> Loading products…</div></td></tr>`;
	meta.textContent = 'Querying supplier…';
	try {
		const data = await api('/api/catalog/products') || [];
		entries = data.map((e) => ({ productId: e.productId, imported: e.imported }));
		loaded = true;
		page = 0;
		updateShopifyStatus();
		renderTable();
	} catch (e) {
		body.innerHTML = `<tr class="row-state"><td colspan="9"><div class="state state--err">⚠ ${esc(e.message)}</div></td></tr>`;
		meta.textContent = 'Error';
	}
}

// --- filtering + pagination -------------------------------------------
function filtered() {
	const q = searchInput.value.trim().toLowerCase();
	return entries.filter((e) => {
		if (q) {
			const hay = `${e.productId} ${e.detail ? e.detail.title : ''} ${e.detail ? e.detail.vendor : ''}`.toLowerCase();
			if (!hay.includes(q)) return false;
		}
		if (statusFilter === 'imported') return e.imported === true;
		if (statusFilter === 'not-imported') return e.imported !== true;
		if (statusFilter === 'low') { const s = stockCounts(e.detail); return s && (s.low + s.out) > 0; }
		return true;
	});
}

function renderTable() {
	if (!loaded) return;
	const items = filtered();
	const pages = Math.max(1, Math.ceil(items.length / pageSize));
	if (page >= pages) page = pages - 1;
	const start = page * pageSize;
	const slice = items.slice(start, start + pageSize);

	meta.textContent = `${items.length} of ${entries.length} product${entries.length === 1 ? '' : 's'}`;

	if (!items.length) {
		body.innerHTML = `<tr class="row-state"><td colspan="9"><div class="state">${entries.length ? 'No products match your filters.' : 'No products.'}</div></td></tr>`;
		pager.hidden = true;
		return;
	}

	body.innerHTML = slice.map((e) => `<tr class="prod-row ${expanded.has(e.productId) ? 'is-open' : ''}" id="row-${cssId(e.productId)}" data-pid="${esc(e.productId)}">${rowCells(e)}</tr>`).join('');
	slice.forEach((e) => { if (expanded.has(e.productId)) { const tr = el(`row-${cssId(e.productId)}`); tr.insertAdjacentHTML('afterend', detailRowHtml(e.productId)); fillDetail(e.productId); } });

	pager.hidden = false;
	el('pagerInfo').textContent = `Page ${page + 1} of ${pages} · showing ${slice.length}`;
	el('prevPage').disabled = page === 0;
	el('nextPage').disabled = page >= pages - 1;

	enrichVisible(slice);
}

// --- row rendering -----------------------------------------------------
function rowCells(e) {
	const d = e.detail;
	const title = d ? esc(d.title || e.productId) : esc(e.productId);
	const vendor = d ? esc(d.vendor || '—') : '<span class="skel skel--txt"></span>';
	const variants = d ? (d.inventory ? d.inventory.length : 0) : '<span class="skel skel--num"></span>';
	const inv = d ? invCell(d) : '<span class="skel skel--num"></span>';
	const price = d ? priceRange(d) : '<span class="skel skel--num"></span>';
	return `
		<td class="col-expand"><span class="chev">▸</span></td>
		<td class="col-thumb"><span class="thumb-cell ${d && d.imageUrls && d.imageUrls.length ? '' : 'is-empty'}">${d && d.imageUrls && d.imageUrls.length ? `<img src="${esc(d.imageUrls[0])}" alt="" loading="lazy" onerror="this.closest('.thumb-cell').classList.add('is-empty');this.remove()">` : ''}</span></td>
		<td><div class="prod-name">${title}</div><div class="prod-id">${esc(e.productId)}</div></td>
		<td class="muted">${vendor}</td>
		<td class="num">${variants}</td>
		<td>${inv}</td>
		<td>${price}</td>
		<td>${syncBadge(e.imported)}</td>
		<td class="col-actions">${actionsHtml(e)}</td>`;
}

function refreshRow(e) {
	const tr = el(`row-${cssId(e.productId)}`);
	if (tr) tr.innerHTML = rowCells(e);
}

function actionsHtml(e) {
	if (e.imported === true) {
		return `
			<div class="row-actions">
				<div class="menu" data-menu>
					<button class="btn btn--sm" data-act="menu">Sync ▾</button>
					<div class="menu__list">
						<button class="menu__item" data-act="inv">Sync inventory</button>
						<button class="menu__item" data-act="price">Sync price</button>
						<button class="menu__item" data-act="reimport">Re-import</button>
					</div>
				</div>
			</div>`;
	}
	return `<div class="row-actions"><button class="btn btn--primary btn--sm" data-act="add">Add to Shopify</button></div>`;
}

// --- lazy enrichment ---------------------------------------------------
function enrichVisible(slice) {
	slice.forEach((e) => {
		if (e.detail || e.inflight || e.error) return;
		e.inflight = true;
		schedule(() => api(`/api/catalog/products/${encodeURIComponent(e.productId)}`))
			.then((d) => { e.detail = d; })
			.catch((err) => { e.error = err.message; })
			.finally(() => { e.inflight = false; refreshRow(e); });
	});
}

// --- expand/collapse ---------------------------------------------------
function detailRowHtml(pid) {
	return `<tr class="detail-row" data-detail="${esc(pid)}"><td colspan="9"><div class="detail" id="detail-${cssId(pid)}"><div class="state"><span class="spinner"></span> Loading detail…</div></div></td></tr>`;
}
async function fillDetail(pid) {
	const node = el(`detail-${cssId(pid)}`);
	if (!node) return;
	const e = entries.find((x) => x.productId === pid);
	try {
		if (e && !e.detail) { e.detail = await schedule(() => api(`/api/catalog/products/${encodeURIComponent(pid)}`)); refreshRow(e); }
		node.innerHTML = detailHtml(e ? e.detail : await api(`/api/catalog/products/${encodeURIComponent(pid)}`));
	} catch (err) {
		node.innerHTML = `<div class="state state--err">⚠ ${esc(err.message)}</div>`;
	}
}
function toggleRow(pid) {
	const tr = body.querySelector(`tr.prod-row[data-pid="${cssAttr(pid)}"]`);
	if (!tr) return;
	if (expanded.has(pid)) {
		expanded.delete(pid); tr.classList.remove('is-open');
		const d = body.querySelector(`tr.detail-row[data-detail="${cssAttr(pid)}"]`);
		if (d) d.remove();
	} else {
		expanded.add(pid); tr.classList.add('is-open');
		tr.insertAdjacentHTML('afterend', detailRowHtml(pid));
		fillDetail(pid);
	}
}

function detailHtml(d) {
	const money = currency('USD');
	const gallery = (d.imageUrls || []).length
		? `<div class="gallery">${d.imageUrls.map((u) => `<a href="${esc(u)}" target="_blank" rel="noopener"><img src="${esc(u)}" alt="" loading="lazy" onerror="this.closest('a').classList.add('is-broken')"></a>`).join('')}</div>` : '';
	const desc = d.description ? `<p class="detail__desc">${esc(d.description)}</p>` : '';

	// Inventory variations (raw rows from the Inventory service).
	const invRows = (d.inventory || []).map((r) => {
		const st = statusOf(r.onHand);
		return `<tr>
			<td class="sku">${esc(r.partId || '—')}</td>
			<td>${colorCell(r.color)}</td>
			<td>${esc(r.size || '—')}</td>
			<td class="muted">${esc(r.description || '')}</td>
			<td class="num">${r.onHand == null ? '—' : r.onHand.toLocaleString()}</td>
			<td><span class="badge badge--${st.cls}"><span class="badge__dot"></span>${st.label}</span></td>
		</tr>`;
	}).join('');
	const inventory = `
		<div>
			<div class="sub-title">Inventory</div>
			<table class="variants">
				<thead><tr><th>Part</th><th>Color</th><th>Size</th><th>Description</th><th class="num">On hand</th><th>Status</th></tr></thead>
				<tbody>${invRows || '<tr><td colspan="6" class="muted">No inventory returned.</td></tr>'}</tbody>
			</table>
		</div>`;

	// Pricing & configuration: the full quantity price-break matrix per part.
	const priceBlocks = (d.pricing || []).map((p) => {
		const breaks = (p.breaks || []).map((b) => `<tr>
			<td class="num">${(b.minQuantity ?? 0).toLocaleString()}</td>
			<td class="num">${b.price == null ? '—' : money(b.price)}</td>
			<td class="num">${b.listPrice == null ? '—' : money(b.listPrice)}</td>
			<td class="muted">${esc(b.uom || '')}</td>
		</tr>`).join('');
		return `<div class="price-part">
			<div class="price-part__head"><span class="sku">${esc(p.partId)}</span>${p.description ? `<span class="muted"> · ${esc(p.description)}</span>` : ''}${p.retail != null ? `<span class="badge badge--neutral">Retail ${money(p.retail)}</span>` : ''}</div>
			<table class="variants">
				<thead><tr><th class="num">Min qty</th><th class="num">Unit (net)</th><th class="num">List</th><th>UOM</th></tr></thead>
				<tbody>${breaks || '<tr><td colspan="4" class="muted">No price breaks.</td></tr>'}</tbody>
			</table>
		</div>`;
	}).join('');
	const pricing = `<div><div class="sub-title">Pricing &amp; configuration</div>${priceBlocks || '<p class="muted">No pricing returned.</p>'}</div>`;

	// Charges (optional).
	const charges = (d.charges || []).length ? `<div>
		<div class="sub-title">Charges</div>
		<table class="variants">
			<thead><tr><th>Charge</th><th>Type</th><th class="num">From</th></tr></thead>
			<tbody>${d.charges.map((c) => `<tr><td>${esc(c.name || c.chargeId)}</td><td class="muted">${esc(c.type || '')}</td><td class="num">${c.firstPrice == null ? '—' : money(c.firstPrice)}</td></tr>`).join('')}</tbody>
		</table></div>` : '';

	return `
		<div class="detail__top">${gallery ? `<div><div class="sub-title">Images</div>${gallery}</div>` : ''}<div>${desc}</div></div>
		${inventory}
		${pricing}
		${charges}`;
}

// --- actions (sync) ----------------------------------------------------
async function runAction(pid, act, btn) {
	const label = btn.textContent;
	btn.disabled = true; btn.innerHTML = `<span class="spinner"></span>`;
	try {
		if (act === 'inv') {
			const r = await api(`/api/sync/products/${encodeURIComponent(pid)}/inventory`, 'POST');
			toast(`Inventory synced for ${pid}: ${r.inventoryUpdated} updated`); restore(btn, label);
		} else if (act === 'price') {
			const r = await api(`/api/sync/products/${encodeURIComponent(pid)}/pricing`, 'POST');
			toast(`Prices synced for ${pid}: ${r.pricesUpdated} updated`); restore(btn, label);
		}
	} catch (e) { toast(e.message, true); restore(btn, label); }
}
function restore(btn, label) { btn.disabled = false; btn.innerHTML = label; }

function setImported(pid, value) {
	const e = entries.find((x) => x.productId === pid);
	if (e) e.imported = value;
	refreshRow(e);
	updateShopifyStatus();
}

// --- presentation helpers ---------------------------------------------
function stockCounts(d) {
	if (!d || !d.inventory) return null;
	let inStock = 0, low = 0, out = 0, total = 0;
	for (const r of d.inventory) {
		if (r.onHand == null) continue;
		total += r.onHand;
		if (r.onHand <= 0) out++; else if (r.onHand <= 25) low++; else inStock++;
	}
	return { inStock, low, out, total };
}
function invCell(d) {
	const s = stockCounts(d);
	if (!s || (s.inStock + s.low + s.out) === 0) return `<span class="inv-cell"><strong>${s ? s.total.toLocaleString() : 0}</strong> <span class="badge badge--neutral">No data</span></span>`;
	let badge;
	if (s.out > 0 && s.inStock === 0 && s.low === 0) badge = { cls: 'critical', label: 'Out of stock' };
	else if (s.low > 0 || s.out > 0) badge = { cls: 'caution', label: 'Low stock' };
	else badge = { cls: 'success', label: 'In stock' };
	return `<span class="inv-cell"><strong>${s.total.toLocaleString()}</strong> <span class="badge badge--${badge.cls}"><span class="badge__dot"></span>${badge.label}</span></span>`;
}
function statusOf(qty) {
	if (qty == null) return { cls: 'neutral', label: 'n/a' };
	if (qty <= 0) return { cls: 'critical', label: 'Out' };
	if (qty <= 25) return { cls: 'caution', label: 'Low' };
	return { cls: 'success', label: 'In stock' };
}
function priceRange(d) {
	const money = currency('USD');
	const prices = (d.pricing || []).map((p) => p.retail).filter((p) => p != null);
	if (!prices.length) return '<span class="muted">—</span>';
	const min = Math.min(...prices), max = Math.max(...prices);
	return min === max ? money(min) : `${money(min)} – ${money(max)}`;
}
function syncBadge(imported) {
	if (imported === true) return `<span class="badge badge--success"><span class="badge__dot"></span>Imported</span>`;
	if (imported === false) return `<span class="badge badge--neutral">Not imported</span>`;
	return `<span class="muted">—</span>`;
}
function currency(code) {
	try { const nf = new Intl.NumberFormat('en-US', { style: 'currency', currency: code || 'USD' }); return (v) => (v == null) ? '—' : nf.format(v); }
	catch (e) { return (v) => (v == null) ? '—' : `${code} ${v}`; }
}
const COLOR_HEX = {
	red: '#d6453d', blue: '#3f7fd1', navy: '#26334f', green: '#5aa55f', black: '#2a2a2a',
	white: '#f3f3f3', grey: '#9a9a9a', gray: '#9a9a9a', silver: '#c8c8c8', gold: '#caa24a',
	yellow: '#e8c24a', orange: '#df8a3c', purple: '#8a5fb0', pink: '#d877a3', brown: '#7a5638',
};
function colorCell(color) {
	if (!color) return '<span class="muted">—</span>';
	const hex = COLOR_HEX[color.trim().toLowerCase()] || '#9a9a9a';
	return `<span class="swatch" style="background:${hex}"></span>${esc(color)}`;
}

// --- shopify status ----------------------------------------------------
function updateShopifyStatus() {
	const connected = entries.some((e) => e.imported !== null && e.imported !== undefined);
	const node = el('shopifyStatus');
	node.classList.toggle('is-on', connected);
	node.classList.toggle('is-off', !connected);
	el('shopifyStatusText').textContent = connected ? 'Shopify connected' : 'Shopify not connected';
}

// --- toasts ------------------------------------------------------------
function toast(message, isError = false) {
	const t = document.createElement('div');
	t.className = `toast ${isError ? 'toast--err' : ''}`;
	t.textContent = message;
	el('toasts').appendChild(t);
	setTimeout(() => t.remove(), isError ? 6000 : 3800);
}

// --- metafield picker modal -------------------------------------------
// Clicking "Add to Shopify"/"Re-import" opens this modal: it lists the store's existing product
// metafield definitions (GET /api/sync/metafield-definitions), lets you tick some and give each a
// value (typed, mapped from a supplier field, or copied from an example product), then imports with
// those metafields. "Skip & import" imports with just the identity metafields.
let metafieldDefs = null;   // cached [{namespace,key,name,type,description}]
let mfProductId = null;     // product currently being imported

const SUPPLIER_SOURCES = [
	['title', 'Product title'], ['description', 'Description'], ['vendor', 'Vendor'],
	['productType', 'Product type'], ['productId', 'Product ID'], ['tags', 'Tags'],
	['supplierCode', 'Supplier code'],
];
const modal = el('mfModal');
const mfBody = el('mfBody');

async function openMetafieldModal(pid) {
	mfProductId = pid;
	el('mfSub').textContent = `Product ${pid}`;
	modal.hidden = false;
	el('mfImport').disabled = true;
	if (metafieldDefs) { renderMetafieldRows(); return; }
	mfBody.innerHTML = `<div class="state"><span class="spinner"></span> Loading metafields…</div>`;
	try {
		metafieldDefs = await api('/api/sync/metafield-definitions') || [];
		renderMetafieldRows();
	} catch (e) {
		// Definitions unavailable (e.g. no Shopify creds) — still allow a plain import via Skip.
		metafieldDefs = null;
		mfBody.innerHTML = `<div class="state state--err">⚠ ${esc(e.message)}</div>
			<p class="muted" style="text-align:center">You can still import without extra metafields.</p>`;
	}
}
function closeMetafieldModal() { modal.hidden = true; mfProductId = null; }

function renderMetafieldRows() {
	el('mfImport').disabled = false;
	if (!metafieldDefs.length) {
		mfBody.innerHTML = `<div class="state">No product metafield definitions found in this store.</div>`;
		return;
	}
	mfBody.innerHTML = metafieldDefs.map((d, i) => mfRowHtml(d, i)).join('');
}

function mfRowHtml(d, i) {
	const sources = SUPPLIER_SOURCES.map(([v, label]) => `<option value="${v}">${esc(label)}</option>`).join('');
	return `<div class="mf-row" data-mf-idx="${i}">
		<label class="mf-row__head">
			<input type="checkbox" data-mf-check>
			<span>
				<span class="mf-row__name">${esc(d.name || d.key)}</span>
				<span class="mf-row__meta"><code>${esc(d.namespace)}.${esc(d.key)}</code>${d.type ? ` · ${esc(d.type)}` : ''}</span>
				${d.description ? `<span class="mf-row__desc">${esc(d.description)}</span>` : ''}
			</span>
		</label>
		<div class="mf-editor">
			<select class="select" data-mf-mode>
				<option value="value">Type a value</option>
				<option value="source">Map from supplier field</option>
				<option value="example">Copy from example</option>
			</select>
			<input type="text" data-mf-value placeholder="Value" autocomplete="off">
			<select class="select" data-mf-source hidden>${sources}</select>
			<button class="btn btn--sm" data-mf-example hidden>Find example</button>
			<select class="select" data-mf-example-list hidden></select>
		</div>
	</div>`;
}

function applyMode(row) {
	const mode = row.querySelector('[data-mf-mode]').value;
	row.querySelector('[data-mf-value]').hidden = mode !== 'value';
	row.querySelector('[data-mf-source]').hidden = mode !== 'source';
	row.querySelector('[data-mf-example]').hidden = mode !== 'example';
	const list = row.querySelector('[data-mf-example-list]');
	list.hidden = mode !== 'example' || !list.options.length;
}

async function fetchExamples(row, btn) {
	const d = metafieldDefs[+row.dataset.mfIdx];
	const label = btn.textContent;
	btn.disabled = true; btn.innerHTML = `<span class="spinner"></span>`;
	try {
		const samples = await api(`/api/sync/metafield-definitions/${encodeURIComponent(d.namespace)}/${encodeURIComponent(d.key)}/examples`) || [];
		if (!samples.length) { toast('No example values found for this metafield', true); return; }
		const list = row.querySelector('[data-mf-example-list]');
		list.innerHTML = `<option value="">Pick an example…</option>` +
			samples.map((s) => `<option value="${esc(s.value)}">${esc(truncate(s.value))}${s.productTitle ? ` — ${esc(s.productTitle)}` : ''}</option>`).join('');
		list.hidden = false;
	} catch (e) { toast(e.message, true); }
	finally { btn.disabled = false; btn.innerHTML = label; }
}

function collectSelectedMetafields() {
	const out = [];
	mfBody.querySelectorAll('.mf-row.is-on').forEach((row) => {
		const d = metafieldDefs[+row.dataset.mfIdx];
		const mode = row.querySelector('[data-mf-mode]').value;
		const base = { namespace: d.namespace, key: d.key, type: d.type };
		if (mode === 'source') {
			base.source = row.querySelector('[data-mf-source]').value;
		} else if (mode === 'example') {
			const v = row.querySelector('[data-mf-example-list]').value;
			if (!v) return;            // nothing picked — skip this row
			base.value = v;
		} else {
			const v = row.querySelector('[data-mf-value]').value.trim();
			if (!v) return;            // empty — skip this row
			base.value = v;
		}
		out.push(base);
	});
	return out;
}

async function doImport(metafields) {
	const pid = mfProductId;
	const btn = el('mfImport'), skip = el('mfSkip');
	const orig = btn.innerHTML;
	btn.disabled = true; skip.disabled = true; btn.innerHTML = `<span class="spinner"></span>`;
	try {
		const body = metafields.length ? { metafields } : null;
		const r = await api(`/api/sync/products/${encodeURIComponent(pid)}`, 'POST', body);
		const mfNote = metafields.length ? `, ${metafields.length} metafield${metafields.length === 1 ? '' : 's'}` : '';
		toast(`${r.updated ? 'Updated' : 'Added'} ${pid}: ${r.variantCount} variants, ${r.inventoryUpdated} inventory${mfNote}`);
		setImported(pid, true);
		closeMetafieldModal();
	} catch (e) { toast(e.message, true); }
	finally { btn.disabled = false; skip.disabled = false; btn.innerHTML = orig; }
}

mfBody.addEventListener('change', (e) => {
	const row = e.target.closest('.mf-row'); if (!row) return;
	if (e.target.matches('[data-mf-check]')) row.classList.toggle('is-on', e.target.checked);
	else if (e.target.matches('[data-mf-mode]')) applyMode(row);
});
mfBody.addEventListener('click', (e) => {
	const exBtn = e.target.closest('[data-mf-example]');
	if (exBtn) fetchExamples(e.target.closest('.mf-row'), exBtn);
});
el('mfImport').addEventListener('click', () => doImport(collectSelectedMetafields()));
el('mfSkip').addEventListener('click', () => doImport([]));
modal.querySelectorAll('[data-mf-close]').forEach((n) => n.addEventListener('click', closeMetafieldModal));
document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && !modal.hidden) closeMetafieldModal(); });

function truncate(s, n = 40) { s = String(s ?? ''); return s.length > n ? `${s.slice(0, n - 1)}…` : s; }

// --- escaping / ids ----------------------------------------------------
function esc(s) {
	return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
const cssId = (s) => String(s).replace(/[^a-zA-Z0-9_-]/g, '_');
const cssAttr = (s) => String(s).replace(/"/g, '\\"');

// --- wiring ------------------------------------------------------------
el('supplier').innerHTML = SUPPLIERS.map((s) => `<option value="${s.id}">${esc(s.name)}</option>`).join('');

body.addEventListener('click', (e) => {
	const actBtn = e.target.closest('[data-act]');
	if (actBtn) {
		e.stopPropagation();
		const pid = actBtn.closest('tr').dataset.pid;
		const act = actBtn.dataset.act;
		if (act === 'menu') { toggleMenu(actBtn.closest('[data-menu]')); return; }
		closeMenus();
		if (act === 'add' || act === 'reimport') { openMetafieldModal(pid); return; }
		runAction(pid, act, actBtn); return;
	}
	const row = e.target.closest('tr.prod-row');
	if (row) toggleRow(row.dataset.pid);
});

function toggleMenu(menu) { const open = menu.classList.contains('is-open'); closeMenus(); if (!open) menu.classList.add('is-open'); }
function closeMenus() { document.querySelectorAll('.menu.is-open').forEach((m) => m.classList.remove('is-open')); }
document.addEventListener('click', (e) => { if (!e.target.closest('[data-menu]')) closeMenus(); });

searchInput.addEventListener('input', () => { page = 0; renderTable(); });
el('reloadBtn').addEventListener('click', loadCatalog);
el('statusFilter').addEventListener('click', (e) => {
	const seg = e.target.closest('.seg'); if (!seg) return;
	statusFilter = seg.dataset.filter; page = 0;
	el('statusFilter').querySelectorAll('.seg').forEach((s) => s.classList.toggle('is-active', s === seg));
	renderTable();
});
el('prevPage').addEventListener('click', () => { if (page > 0) { page--; renderTable(); } });
el('nextPage').addEventListener('click', () => { page++; renderTable(); });
el('pageSize').addEventListener('change', (e) => { pageSize = parseInt(e.target.value, 10) || 25; page = 0; renderTable(); });

el('loginForm').addEventListener('submit', doLogin);
el('signOutBtn').addEventListener('click', doLogout);

bootstrap();
