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

// --- variant grouping --------------------------------------------------
// When on, sibling product ids the supplier split by size (linked via Common Grouping) collapse
// into one row; the group index comes from GET /api/catalog/product-groups (cached server-side).
let grouping = false;
let primaryOf = new Map();       // memberId -> primaryId (present only for grouped ids)
let membersOf = new Map();       // primaryId -> [memberIds...] (sorted, includes the primary)
let groupPollTimer = null;

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

// Concurrency-limited scheduler so lazy enrichment never floods the supplier.
//
// Row enrichment is also GENERATION-scoped: every render bumps `generation`, and queued-but-not-yet-
// started row tasks from older generations are dropped instead of run. Without this, each keystroke
// in the search box appended another page of products to a FIFO queue that only drains 5 at a time,
// so the rows you were actually looking at waited behind dozens of obsolete requests. Tasks queued
// without a generation (a user-opened detail panel) are never cancelled.
const MAX_CONCURRENT = 5;
const CANCELLED = Symbol('cancelled');
let active = 0;
let generation = 0;
const waiting = [];

function schedule(task, gen) {
	return new Promise((resolve, reject) => { waiting.push({ task, gen, resolve, reject }); drain(); });
}
function drain() {
	while (active < MAX_CONCURRENT && waiting.length) {
		const { task, gen, resolve, reject } = waiting.shift();
		if (gen !== undefined && gen !== generation) { reject(CANCELLED); continue; }
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
		entries = data.map((e) => ({ productId: e.productId, imported: e.imported, closeOut: e.closeOut }));
		loaded = true;
		page = 0;
		updateShopifyStatus();
		renderTable();
		ensureTitles();
		if (grouping) ensureGroups();
	} catch (e) {
		body.innerHTML = `<tr class="row-state"><td colspan="9"><div class="state state--err">⚠ ${esc(e.message)}</div></td></tr>`;
		meta.textContent = 'Error';
	}
}

// --- title index -------------------------------------------------------
// Names/vendors for the WHOLE catalog in one cached call, so the search box matches product names
// locally instead of only the ids (and the names of rows you happened to scroll past). Rows also
// show their real name immediately, before the expensive per-row detail arrives.
let titlePollTimer = null;

async function ensureTitles() {
	try {
		const v = await api('/api/catalog/product-titles');
		applyTitles(v);
		if (v.status === 'building') scheduleTitlePoll(); else renderTable({ enrich: false });
	} catch (e) {
		/* titles are an enrichment: on failure the search box still matches ids */
	}
}

function applyTitles(v) {
	const byId = new Map((v && v.titles || []).map((t) => [t.productId, t]));
	entries.forEach((e) => {
		const t = byId.get(e.productId);
		if (t) { e.title = t.title; e.vendor = t.vendor; }
	});
}

function scheduleTitlePoll() {
	clearTimeout(titlePollTimer);
	titlePollTimer = setTimeout(async () => {
		try {
			const v = await api('/api/catalog/product-titles');
			applyTitles(v);
			if (v.status === 'building') scheduleTitlePoll(); else renderTable({ enrich: false });
		} catch (e) { /* stop polling on error */ }
	}, 2500);
}

// --- group index -------------------------------------------------------
function setGrouping(on) {
	grouping = on;
	page = 0;
	if (on) ensureGroups(); else { clearTimeout(groupPollTimer); renderTable(); }
}

// Fetch the cached group index. The first call may return status=building (the server is doing the
// one-getProduct-per-product pass); we poll until ready, showing the flat list meanwhile.
async function ensureGroups() {
	try {
		const v = await api('/api/catalog/product-groups');
		applyGroupView(v);
		renderTable();
		if (v.status === 'building') { meta.textContent = 'Building group index…'; scheduleGroupPoll(); }
	} catch (e) {
		toast('Grouping unavailable: ' + e.message, true);
		grouping = false; const t = el('groupToggle'); if (t) t.checked = false;
		renderTable();
	}
}

function applyGroupView(v) {
	primaryOf = new Map();
	membersOf = new Map();
	(v && v.groups || []).forEach((g) => {
		membersOf.set(g.primaryId, g.memberIds);
		g.memberIds.forEach((id) => primaryOf.set(id, g.primaryId));
	});
}

function scheduleGroupPoll() {
	clearTimeout(groupPollTimer);
	groupPollTimer = setTimeout(async () => {
		if (!grouping) return;
		try {
			const v = await api('/api/catalog/product-groups');
			applyGroupView(v);
			if (v.status === 'building') { meta.textContent = 'Building group index…'; scheduleGroupPoll(); }
			else renderTable();
		} catch (e) { /* stop polling on error */ }
	}, 2500);
}

// --- filtering + pagination -------------------------------------------
function filtered() {
	const q = searchInput.value.trim().toLowerCase();
	return entries.filter((e) => {
		// In grouping mode, sibling members fold under their primary row and drop out of the list.
		if (grouping && primaryOf.has(e.productId) && primaryOf.get(e.productId) !== e.productId) return false;
		if (q) {
			// Name/vendor come from the catalog-wide title index, so searching by name works for
			// every product — not just the rows whose detail happens to be loaded.
			const hay = `${e.productId} ${titleOf(e)} ${vendorOf(e) || ''}`.toLowerCase();
			if (!hay.includes(q)) return false;
		}
		if (statusFilter === 'imported') return e.imported === true;
		if (statusFilter === 'not-imported') return e.imported !== true;
		if (statusFilter === 'low') { const s = stockCounts(e.detail); return s && (s.low + s.out) > 0; }
		return true;
	});
}

// `enrich: false` paints the rows without queueing any fetches — used while the user is still typing
// in the search box, so filtering feels instant and the network work waits until they stop.
function renderTable({ enrich = true } = {}) {
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
	slice.forEach((e) => { if (expanded.has(e.productId)) insertExpansion(e.productId); });

	pager.hidden = false;
	el('pagerInfo').textContent = `Page ${page + 1} of ${pages} · showing ${slice.length}`;
	el('prevPage').disabled = page === 0;
	el('nextPage').disabled = page >= pages - 1;

	if (enrich) enrichVisible(slice);
}

// --- row rendering -----------------------------------------------------
// Detail (loaded lazily per row) wins; the title index covers every other row; the id is the floor.
const titleOf = (e) => (e.detail && e.detail.title) || e.title || e.productId;
const vendorOf = (e) => (e.detail && e.detail.vendor) || e.vendor || null;

function rowCells(e) {
	const d = e.detail;
	const title = esc(titleOf(e));
	const vendor = vendorOf(e) ? esc(vendorOf(e)) : (d ? '—' : '<span class="skel skel--txt"></span>');
	const variants = d ? (d.inventory ? d.inventory.length : 0) : '<span class="skel skel--num"></span>';
	const inv = d ? invCell(d) : '<span class="skel skel--num"></span>';
	const price = d ? priceRange(d) : '<span class="skel skel--num"></span>';
	const fam = grouping && membersOf.has(e.productId)
		? ` <span class="badge badge--neutral fam-badge">◧ ${membersOf.get(e.productId).length} variants</span>` : '';
	// The supplier lists ids as sellable that its Product Data service has no record for (e.g. GI840).
	// The row still shows whatever inventory/pricing/media returned — flagged, not failed.
	const noData = d && d.productDataMissing
		? ` <span class="badge badge--caution" title="Listed as sellable, but the supplier's Product Data service has no record for this id">No product data</span>` : '';
	// Close-out is the supplier's OWN discontinued/sell-off flag (getProductCloseOut), known from the
	// catalog list — unlike "No product data", which only means its Product Data service is silent.
	const closeOut = e.closeOut
		? ` <span class="badge badge--critical" title="The supplier lists this product as close-out (being discontinued / sold off)">Close-out</span>` : '';
	return `
		<td class="col-expand"><span class="chev">▸</span></td>
		<td class="col-thumb"><span class="thumb-cell ${d && d.imageUrls && d.imageUrls.length ? '' : 'is-empty'}">${d && d.imageUrls && d.imageUrls.length ? `<img src="${esc(d.imageUrls[0])}" alt="" loading="lazy" onerror="this.closest('.thumb-cell').classList.add('is-empty');this.remove()">` : ''}</span></td>
		<td><div class="prod-name">${title}${fam}${closeOut}${noData}</div><div class="prod-id">${esc(e.productId)}</div></td>
		<td class="muted">${vendor}</td>
		<td class="num">${variants}</td>
		<td>${inv}</td>
		<td>${price}</td>
		<td>${syncBadge(e.imported)}${discountBadge(e)}</td>
		<td class="col-actions">${actionsHtml(e)}</td>`;
}

function refreshRow(e) {
	const tr = el(`row-${cssId(e.productId)}`);
	if (tr) tr.innerHTML = rowCells(e);
}

function actionsHtml(e) {
	const pid = esc(e.productId);
	if (e.imported === true) {
		return `
			<div class="row-actions">
				<div class="menu" data-menu>
					<button class="btn btn--sm" data-act="menu">Sync ▾</button>
					<div class="menu__list">
						<button class="menu__item" data-act="inv" data-pid="${pid}">Sync inventory</button>
						<button class="menu__item" data-act="price" data-pid="${pid}">Sync price</button>
						<button class="menu__item" data-act="discounts" data-pid="${pid}">Sync discounts</button>
						<button class="menu__item" data-act="reimport" data-pid="${pid}">Re-import</button>
					</div>
				</div>
			</div>`;
	}
	return `<div class="row-actions"><button class="btn btn--primary btn--sm" data-act="add" data-pid="${pid}">Add to Shopify</button></div>`;
}

// --- lazy enrichment ---------------------------------------------------
// Bumping the generation first drops whatever earlier renders left queued: only the rows on screen
// right now compete for the 5 slots. Cancelled rows keep their skeleton and re-queue if they come
// back into view.
function enrichVisible(slice) {
	const gen = ++generation;
	slice.forEach((e) => {
		if (e.detail || e.inflight || e.error) return;
		e.inflight = true;
		schedule(() => api(`/api/catalog/products/${encodeURIComponent(e.productId)}`), gen)
			.then((d) => { e.detail = d; refreshRow(e); })
			.catch((err) => { if (err !== CANCELLED) { e.error = err.message; refreshRow(e); } })
			.finally(() => { e.inflight = false; });
	});
}

// --- expand/collapse ---------------------------------------------------
function detailRowHtml(pid) {
	return `<tr class="detail-row" data-detail="${esc(pid)}"><td colspan="9"><div class="detail" id="detail-${cssId(pid)}"><div class="state"><span class="spinner"></span> Loading detail…</div></div></td></tr>`;
}
// A group primary expands into the grouped-variants panel; every other row into its own detail.
function insertExpansion(pid) {
	const tr = body.querySelector(`tr.prod-row[data-pid="${cssAttr(pid)}"]`);
	if (!tr) return;
	tr.insertAdjacentHTML('afterend', detailRowHtml(pid));
	if (grouping && membersOf.has(pid)) fillGroupPanel(pid); else fillDetail(pid);
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
		insertExpansion(pid);
	}
}

// --- grouped-variants panel -------------------------------------------
// Shown when a group primary row is expanded: each sibling product id's full detail, stacked, so
// the "one product split across ids" reads as a single grouped product.
async function fillGroupPanel(pid) {
	const node = el(`detail-${cssId(pid)}`);
	if (!node) return;
	const members = membersOf.get(pid) || [pid];
	node.innerHTML = `<div class="sub-title">Grouped variants · ${members.length} product ids form one product</div>
		<div class="group-members">${members.map((id) => `<div class="group-member" id="gm-${cssId(id)}"><div class="state"><span class="spinner"></span> ${esc(id)}…</div></div>`).join('')}</div>`;
	members.forEach((id) => enrichMember(id));
}

async function enrichMember(id) {
	const node = el(`gm-${cssId(id)}`);
	if (!node) return;
	let e = entries.find((x) => x.productId === id);
	if (!e) { e = { productId: id }; }
	try {
		if (!e.detail) e.detail = await schedule(() => api(`/api/catalog/products/${encodeURIComponent(id)}`));
		node.innerHTML = memberBlockHtml(e);
	} catch (err) {
		node.innerHTML = `<div class="state state--err">⚠ ${esc(id)}: ${esc(err.message)}</div>`;
	}
}

function memberBlockHtml(e) {
	const d = e.detail;
	return `<div class="group-member__head">
			<div class="group-member__id"><span class="prod-name">${esc(d.title || e.productId)}</span><span class="prod-id">${esc(e.productId)}</span></div>
			<div class="group-member__figs">${invCell(d)}<span class="price-fig">${priceRange(d)}</span>${syncBadge(e.imported)}</div>
			${actionsHtml(e)}
		</div>
		${detailHtml(d)}`;
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
			<td class="num"><strong>${b.retail == null ? '—' : money(b.retail)}</strong></td>
			<td class="num">${b.discount == null ? '—' : `−${money(b.discount)}`}</td>
			<td class="muted">${esc(b.uom || '')}</td>
		</tr>`).join('');
		return `<div class="price-part">
			<div class="price-part__head"><span class="sku">${esc(p.partId)}</span>${p.description ? `<span class="muted"> · ${esc(p.description)}</span>` : ''}${p.retail != null ? `<span class="badge badge--neutral">Retail ${money(p.retail)}</span>` : ''}</div>
			<table class="variants">
				<thead><tr><th class="num">Min qty</th><th class="num">Unit (net)</th><th class="num">List</th><th class="num">Sale price</th><th class="num">Discount</th><th>UOM</th></tr></thead>
				<tbody>${breaks || '<tr><td colspan="6" class="muted">No price breaks.</td></tr>'}</tbody>
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

	const decorations = decorationsHtml(d);

	// Which services came up empty, so a partial answer never reads as "the supplier has nothing".
	const warnings = (d.warnings || []).length
		? `<div class="state state--warn">⚠ Partial data — ${d.warnings.map(esc).join(' · ')}</div>` : '';

	return `
		${warnings}
		<div class="detail__top">${gallery ? `<div><div class="sub-title">Images</div>${gallery}</div>` : ''}<div>${desc}</div></div>
		${inventory}
		${decorations}
		${pricing}
		${discountsHtml(d)}
		${charges}`;
}

// --- decoration areas --------------------------------------------------
// The supplier's imprint locations (Pricing & Configuration's LocationArray): where the product can
// be decorated, by which method, and how big the area is. Drawn to scale, because "3 x 4.5 in" reads
// as a number while a shape reads as an imprint area — the thing people otherwise open a PDF for.
function decorationsHtml(d) {
	const locations = d.decorationLocations || [];
	if (!locations.length) return '';

	// Scale shapes only against others measured in the SAME unit: drawing inches and stitches on one
	// scale would be a lie. Each unit gets its own reference maximum.
	const maxByUom = new Map();
	locations.forEach((l) => (l.decorations || []).forEach((x) => {
		const biggest = Math.max(x.height || 0, x.width || 0, x.diameter || 0);
		if (biggest > 0) maxByUom.set(x.uom || '', Math.max(maxByUom.get(x.uom || '') || 0, biggest));
	}));

	// PaceSetter publishes locations and methods but leaves every dimension null (geometry "Other").
	// With nothing to draw, shape boxes and a per-item "Area not specified" are just noise — fall
	// back to a compact list and say once, plainly, that the supplier omits the sizes.
	const hasAnyDimensions = maxByUom.size > 0;

	const cards = locations.map((l) => {
		const limits = [
			l.included ? `${l.included} included` : null,
			l.maxDecoration ? `max ${l.maxDecoration}` : null,
		].filter(Boolean).join(' · ');
		const head = `<div class="deco-card__head">
				<span class="deco-card__name">${esc(l.name || `Location ${l.locationId}`)}</span>
				${l.isDefault ? '<span class="badge badge--success"><span class="badge__dot"></span>Default</span>' : ''}
				${limits ? `<span class="muted">${esc(limits)}</span>` : ''}
			</div>`;
		const methods = l.decorations || [];
		const items = hasAnyDimensions
			? methods.map((x) => {
				const shape = areaShape(x, maxByUom.get(x.uom || '') || 0);
				return `<div class="deco-item">
					${shape ? `<div class="deco-shape">${shape}</div>` : ''}
					<div class="deco-item__text">
						<div class="deco-item__name">${methodName(x)}</div>
						<div class="deco-item__dims">${areaText(x)}</div>
					</div>
				</div>`;
			}).join('')
			: `<div class="deco-methods">${methods.map((x) => `<span class="deco-chip">${methodName(x)}</span>`).join('')}</div>`;
		return `<div class="deco-card">${head}
			<div class="deco-items">${methods.length ? items : '<span class="muted">No decoration methods listed.</span>'}</div>
		</div>`;
	}).join('');

	const note = hasAnyDimensions ? ''
		: `<p class="muted deco-note">The supplier lists these locations and methods but publishes no imprint dimensions for them.</p>`;
	return `<div><div class="sub-title">Decoration areas</div>${note}<div class="deco-grid">${cards}</div></div>`;
}

const methodName = (x) => `${esc(x.name || `#${x.decorationId}`)}${x.isDefault ? ' <span class="badge badge--neutral">Default</span>' : ''}`;

/** "3 × 4.5 in" for rectangles, "⌀ 2 in" for circles, "—" when the supplier omits the dimensions. */
function areaText(x) {
	const uom = ({ Inches: 'in', SquareInches: 'sq in', Centimeters: 'cm' })[x.uom] || (x.uom || '');
	const num = (v) => String(parseFloat(v));
	if (x.diameter) return `⌀ ${num(x.diameter)} ${esc(uom)}`.trim();
	if (x.height && x.width) return `${num(x.height)} × ${num(x.width)} ${esc(uom)}`.trim();
	if (x.height || x.width) return `${num(x.height || x.width)} ${esc(uom)}`.trim();
	return `<span class="muted">Area not specified${uom ? ` (${esc(uom)})` : ''}</span>`;
}

/** Inline SVG of the imprint area, scaled against the largest area in the same unit. */
function areaShape(x, maxDim) {
	const BOX = 58;
	if (!maxDim) return '';
	const px = (v) => Math.max(6, Math.round((v / maxDim) * (BOX - 6)));
	const svg = (inner) => `<svg viewBox="0 0 ${BOX} ${BOX}" width="${BOX}" height="${BOX}" aria-hidden="true">${inner}</svg>`;
	if (x.diameter) {
		const r = px(x.diameter) / 2;
		return svg(`<circle cx="${BOX / 2}" cy="${BOX / 2}" r="${r}" class="deco-shape__fig"/>`);
	}
	if (x.height && x.width) {
		const w = px(x.width), h = px(x.height);
		return svg(`<rect x="${(BOX - w) / 2}" y="${(BOX - h) / 2}" width="${w}" height="${h}" rx="1" class="deco-shape__fig"/>`);
	}
	return '';
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
		} else if (act === 'discounts') {
			const r = await api(`/api/discounts/products/${encodeURIComponent(pid)}`, 'POST');
			toast(discountMessage(pid, r), discountFailed(r));
			applyDiscountResult(pid, r);
			restore(btn, label);
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
		// A service the supplier could not answer for: the import went through, but a part of it did
		// not, and "Added X" alone would read as a complete sync.
		for (const w of r.warnings || []) toast(`${pid}: ${w}`, true);
		// The import publishes the discounts too, server-side, and reports what happened to them.
		if (r.discountError) toast(`Discounts for ${pid}: ${r.discountError}`, true);
		else if (r.discounts) {
			if (r.discounts.outcome !== 'NO_DISCOUNTS') toast(discountMessage(pid, r.discounts), discountFailed(r.discounts));
			applyDiscountResult(pid, r.discounts);
		}
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
		// Buttons carry their own data-pid (needed inside the group panel, whose wrapping row is the
		// primary's detail row); fall back to the row's pid for normal product rows.
		const pid = actBtn.dataset.pid || (actBtn.closest('tr.prod-row') || {}).dataset?.pid;
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

// Typing filters instantly but defers the fetching: each keystroke cancels the queued enrichment
// (generation bump) and repaints; only ~300ms after the last keystroke do the surviving rows load.
// Otherwise "GI840" queued five pages' worth of detail requests to show one product.
const SEARCH_DEBOUNCE_MS = 300;
let searchTimer = null;
searchInput.addEventListener('input', () => {
	page = 0;
	generation++;
	clearTimeout(searchTimer);
	renderTable({ enrich: false });
	searchTimer = setTimeout(() => renderTable(), SEARCH_DEBOUNCE_MS);
});
el('reloadBtn').addEventListener('click', loadCatalog);
el('groupToggle').addEventListener('change', (e) => setGrouping(e.target.checked));
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

// --- quantity discounts -------------------------------------------------
// A published discount is one JSON metafield on the product and on each covered variant, so there is
// nothing to authenticate and nothing to reconcile: publishing is a write that can simply be
// repeated. The ladder shown here is the one that gets written — read straight out of the detail the
// row already loaded (each price break carries its published price and the amount off), so opening a
// row costs nothing extra. Only "View payload" goes back to the server, and only when clicked.
const discountMoney = currency('USD');

// Only PUBLISHED means something was written; the other outcomes are why nothing was.
function discountFailed(r) { return r && r.outcome !== 'PUBLISHED' && r.outcome !== 'NO_DISCOUNTS'; }

function discountMessage(pid, r) {
	if (r.outcome === 'SKIPPED') return `Discounts for ${pid} skipped: ${r.reason || 'disabled'}`;
	if (r.outcome === 'NOT_WRITTEN') return `Discounts for ${pid} not written: ${r.reason || 'nowhere to write them'}`;
	if (r.outcome === 'NO_DISCOUNTS') return `${pid} has no quantity discounts to publish`;
	const tiers = (r.tiers || []).length;
	const where = r.productWide ? 'the product and its variants' : `${(r.variants || []).length} variant group(s)`;
	return `Published ${tiers} discount tier${tiers === 1 ? '' : 's'} for ${pid} from ${discountMoney(r.basePrice)} on ${where}`;
}

function discountBadge(e) {
	if (!e.discountsPublished) return '';
	return ` <span class="badge badge--success" title="Quantity ladder published in the discount metafield">% Discounts</span>`;
}

function discountLadder(d) {
	// The ladder published for this id: the part the supplier priced under it, else the first part it
	// did price. A grouped product whose parts differ publishes one value per part, per variant.
	const parts = d.pricing || [];
	if (!parts.length) return null;
	const part = parts.find((p) => (p.partId || '').toUpperCase() === (d.productId || '').toUpperCase()) || parts[0];
	const breaks = (part.breaks || []).slice().sort((a, b) => (a.minQuantity || 0) - (b.minQuantity || 0));
	if (!breaks.length) return null;
	return { part, breaks, tiers: breaks.filter((b) => b.discount != null && Number(b.discount) > 0) };
}

function discountsHtml(d) {
	const pid = d.productId;
	const entry = entries.find((x) => x.productId === pid);
	const published = !!(entry && entry.discountsPublished);
	const money = currency('USD');
	const ladder = discountLadder(d);

	const status = published
		? `<span class="badge badge--success">Published</span>`
		: `<span class="badge badge--neutral">Not published</span>`;

	if (!ladder || !ladder.tiers.length) {
		return `<div class="disc-block" id="disc-block-${cssId(pid)}"><div class="sub-title">Quantity discounts ${status}</div>
			<p class="muted">The supplier gives no cheaper price at higher quantities, so there is no
			quantity ladder to publish for this product.</p></div>`;
	}

	const base = ladder.breaks[0];
	const minQty = base.minQuantity || 1;
	// Each tier runs until the next one starts — the range the shopper actually sees on the page.
	const rows = ladder.breaks.map((b, i) => {
		const next = ladder.breaks[i + 1];
		const from = b.minQuantity || 1;
		const range = next ? `${from} – ${(next.minQuantity || 1) - 1}` : `${from}+`;
		return `<tr>
			<td>Order ${range} pieces</td>
			<td class="num"><strong>${b.retail == null ? '—' : money(b.retail)}</strong> each</td>
			<td class="num">${b.discount == null || Number(b.discount) <= 0 ? '—' : `−${money(b.discount)}`}</td>
		</tr>`;
	}).join('');

	const notes = [];
	if (minQty > 1) notes.push(`The supplier's table starts at ${minQty}, so this product has a minimum order of ${minQty} — the ladder says so, but enforcing it is the storefront's job.`);
	if ((d.pricing || []).length > 1) notes.push('This product has several priced parts; parts priced differently get their own value on their own variants.');
	const noteHtml = notes.length ? `<p class="muted disc-note">⚠ ${notes.map(esc).join(' · ')}</p>` : '';

	return `<div class="disc-block" id="disc-block-${cssId(pid)}">
		<div class="sub-title">Quantity discounts ${status}</div>
		<table class="variants disc-table">
			<thead><tr><th>Quantity</th><th class="num">Price each</th><th class="num">Discount / unit</th></tr></thead>
			<tbody>${rows}</tbody>
		</table>
		${noteHtml}
		<div class="disc-actions">
			<button class="btn btn--primary btn--sm" data-disc="publish" data-pid="${esc(pid)}">${published ? 'Update discounts' : 'Publish discounts'}</button>
			<button class="btn btn--sm" data-disc="payload" data-pid="${esc(pid)}">View payload</button>
		</div>
		<pre class="disc-payload" id="disc-payload-${cssId(pid)}" hidden></pre>
	</div>`;
}

// A published ladder shows up in three places — the row badge, the expanded block's status, and the
// button's own label — and all three read the catalog entry, which is loaded once per page. So the
// result is written back to it and both views repainted; otherwise the block keeps saying "Not
// published" about a ladder that is already live in Shopify, which is exactly what it did.
function applyDiscountResult(pid, r) {
	const published = !!(r && r.outcome === 'PUBLISHED');
	// A grouped product publishes one value per group of ids (EP2 at $345.99, EP2PK at $634.99), so
	// every supplier id it covers gets its own row updated — not just the one clicked.
	const touched = new Set([pid]);
	for (const v of (r && r.variants) || []) {
		for (const id of v.supplierIds || []) {
			const e = entries.find((x) => x.productId === id);
			if (e && published) { e.discountsPublished = true; touched.add(id); }
		}
	}
	const entry = entries.find((x) => x.productId === pid);
	if (entry && published) entry.discountsPublished = true;
	for (const id of touched) {
		const e = entries.find((x) => x.productId === id);
		if (e) refreshRow(e);
		refreshDiscountBlock(id);
	}
}

function refreshDiscountBlock(pid) {
	const block = el(`disc-block-${cssId(pid)}`);
	const entry = entries.find((x) => x.productId === pid);
	if (block && entry && entry.detail) block.outerHTML = discountsHtml(entry.detail);
}

document.addEventListener('click', async (e) => {
	const btn = e.target.closest('[data-disc]');
	if (!btn) return;
	const pid = btn.getAttribute('data-pid');
	const act = btn.getAttribute('data-disc');

	if (act === 'payload') {
		const pre = el(`disc-payload-${cssId(pid)}`);
		if (!pre) return;
		if (!pre.hidden) { pre.hidden = true; return; }
		pre.hidden = false;
		pre.textContent = 'Loading…';
		try {
			const preview = await api(`/api/discounts/preview/${encodeURIComponent(pid)}`);
			pre.textContent = `${preview.metafield} (${preview.metafieldType})\n${JSON.stringify(preview.payload, null, 2)}`;
		} catch (err) { pre.textContent = err.message; }
		return;
	}

	const label = btn.textContent;
	btn.disabled = true; btn.innerHTML = `<span class="spinner"></span>`;
	try {
		const r = await api(`/api/discounts/products/${encodeURIComponent(pid)}`, 'POST');
		toast(discountMessage(pid, r), discountFailed(r));
		applyDiscountResult(pid, r);
	} catch (err) { toast(err.message, true); }
	finally { btn.disabled = false; btn.innerHTML = label; }
});
