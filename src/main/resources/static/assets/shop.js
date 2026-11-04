// 쇼핑 화면 공통: 장바구니 저장, API 호출, 상단 메뉴.
const Shop = (() => {
  const CART_KEY = 'sandbox-cart';

  function esc(v) {
    return String(v ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  }

  function money(v) {
    return '$' + Number(v).toFixed(2);
  }

  /* 장바구니: 이 브라우저의 localStorage에만 저장한다. [{productId, quantity}] */
  function cart() {
    try {
      const items = JSON.parse(localStorage.getItem(CART_KEY));
      return Array.isArray(items) ? items : [];
    } catch {
      return [];
    }
  }

  function saveCart(items) {
    try {
      localStorage.setItem(CART_KEY, JSON.stringify(items.filter((i) => i.quantity > 0)));
    } catch { /* 저장소를 못 쓰면 이번 화면에서만 유지된다 */ }
    renderCartCount();
  }

  function addToCart(productId, quantity) {
    const items = cart();
    const found = items.find((i) => i.productId === productId);
    if (found) found.quantity += quantity;
    else items.push({ productId, quantity });
    saveCart(items);
  }

  function setQuantity(productId, quantity) {
    saveCart(cart().map((i) => (i.productId === productId ? { ...i, quantity } : i)));
  }

  function removeFromCart(productId) {
    saveCart(cart().filter((i) => i.productId !== productId));
  }

  function clearCart() {
    saveCart([]);
  }

  async function api(method, path, body) {
    const res = await fetch(path, {
      method,
      headers: body ? { 'Content-Type': 'application/json' } : {},
      body: body ? JSON.stringify(body) : undefined,
    });
    const text = await res.text();
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch { data = text; }
    if (!res.ok) {
      const err = new Error((data && data.detail) || res.statusText);
      err.status = res.status;
      throw err;
    }
    return data;
  }

  function renderCartCount() {
    const el = document.getElementById('cart-count');
    if (el) el.textContent = cart().reduce((n, i) => n + i.quantity, 0);
  }

  /* 상단 메뉴와 장애 주입 경고 줄 */
  async function header(active) {
    const el = document.getElementById('site-header');
    el.className = 'site-header';
    el.innerHTML = `<div class="wrap">
      <a class="brand" href="/">Sandbox Store</a>
      <nav class="nav">
        <a href="/" class="${active === 'products' ? 'active' : ''}">상품</a>
        <a href="/cart.html" class="${active === 'cart' ? 'active' : ''}">장바구니 (<span id="cart-count">0</span>)</a>
        <a href="/lab.html" class="right">실험 콘솔</a>
      </nav>
    </div>`;
    renderCartCount();
    try {
      const f = await api('GET', '/faults');
      const active = [];
      if (f.pgDelayMs > 0) active.push(`PG 응답 지연 ${f.pgDelayMs}ms`);
      if (f.pgTimeoutAfterCapture) active.push('캡처 뒤 응답 타임아웃');
      if (f.dbFailureAfterCapture) active.push('캡처 뒤 DB 반영 실패');
      if (f.pgDecline) active.push('PG 결제 거절');
      if (active.length) {
        const bar = document.createElement('div');
        bar.className = 'fault-bar';
        bar.innerHTML = `<div class="wrap">장애 주입 중: ${esc(active.join(', '))}. <a href="/lab.html">실험 콘솔에서 끄기</a></div>`;
        el.after(bar);
      }
    } catch { /* 무시 */ }
  }

  return { esc, money, cart, saveCart, addToCart, setQuantity, removeFromCart, clearCart, api, header };
})();
